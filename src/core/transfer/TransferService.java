package core.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import core.kademlia.KademliaService;
import core.kademlia.NodeId;

/**
 * Ties the transfer layer to the Kademlia DHT and the app. {@code share} splits a
 * file, seeds it on its own ephemeral TCP server, and announces
 * {@code infohash -> (seed endpoint + metadata)} in the DHT; {@code download}
 * resolves an infohash via {@code findValue} and pulls the file from the seed.
 *
 * <p>Skeleton scope (single-seed): the DHT holds one value per infohash carrying
 * one seed's endpoint plus the small torrent metadata, so a downloader gets
 * everything it needs from a single {@code findValue}. Each shared file runs on
 * its own ephemeral port (the port travels in the DHT value), so no port
 * bookkeeping or routing-by-infohash is needed. Multi-peer swarming is deferred.
 */
public final class TransferService implements Closeable {

	private static final int DOWNLOAD_TIMEOUT_MS = 30_000;
	private static final int MAX_IN_FLIGHT = 16;
	/** Sliding-window size for streaming downloads (the player's playhead drives the window). */
	private static final int STREAM_WINDOW = 16;
	/** Defensive cap when parsing a DHT announce (guards against a hostile value). */
	private static final int MAX_PIECES = 1 << 20;

	private final KademliaService dht;
	private final String host;
	private final NodeId selfId;
	/** Ephemeral per-node download cache (under the app folder); wiped on startup + shutdown. */
	private final Path cacheDir;

	private final List<TcpPeerServer> servers = new CopyOnWriteArrayList<>();
	private final List<SeedSession> seeds = new CopyOnWriteArrayList<>();
	private final List<PieceStore> stores = new CopyOnWriteArrayList<>();
	/** Live, non-blocking downloads keyed by infohash (the Phase-5 UI polls these via progress()). */
	private final Map<NodeId, DownloadSession> active = new ConcurrentHashMap<>();
	/** Display names (file names) for shares and downloads, for the Library list. */
	private final Map<NodeId, String> sharedNames = new ConcurrentHashMap<>();
	private final Map<NodeId, String> downloadNames = new ConcurrentHashMap<>();

	/**
	 * One row of the Library list (a file this node seeds or is downloading). Pure
	 * data for the {@code /api/transfers} endpoint; {@code have}/{@code total}/
	 * {@code peers}/{@code complete} are a live snapshot for downloads (a seed reads
	 * have==total, complete==true).
	 */
	public record Transfer(String infohash, String name, long totalLength, int pieceCount,
			boolean seeding, int have, int total, int peers, boolean complete) {
	}

	public TransferService(KademliaService dht) {
		// Downloads are an ephemeral cache in the app folder (user.dir), one subdir per node so
		// multiple local nodes (Electron windows) don't wipe each other's files.
		this(dht, Path.of(System.getProperty("user.dir"), "cache", "node-" + dht.self().getPort()));
	}

	/** Test/embedding seam: inject the download cache directory (so checks never touch the real one). */
	public TransferService(KademliaService dht, Path cacheDir) {
		this.dht = dht;
		this.host = dht.self().getHost();
		this.selfId = dht.self().getId();
		this.cacheDir = cacheDir;
	}

	/** The per-node ephemeral download cache directory (default output location for downloads). */
	public Path cacheDir() {
		return cacheDir;
	}

	/** Best-effort wipe of the download cache (startup safety net + shutdown cleanup). */
	public void cleanCache() {
		if (!Files.exists(cacheDir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(cacheDir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// a locked/in-use file; the next startup wipe will get it
				}
			});
		} catch (IOException ignored) {
			// cache dir vanished or unreadable — nothing to clean
		}
	}

	/** Share {@code file} with the default piece size. */
	public TorrentMetadata share(Path file) throws IOException {
		return share(file, TorrentMetadata.DEFAULT_PIECE_SIZE);
	}

	/** Split + hash {@code file}, seed it on an ephemeral TCP port, and announce it in the DHT. */
	public TorrentMetadata share(Path file, int pieceSize) throws IOException {
		TorrentMetadata meta = PieceHasher.fromFile(file, pieceSize);
		PieceStore store = new PieceStore(file, meta);
		store.verifyAndMarkExisting();

		SeedSession seed = new SeedSession(store, selfId);
		TcpPeerServer server = new TcpPeerServer(0);
		server.setConnectionHandler(seed::handle);
		server.start();

		stores.add(store);
		seeds.add(seed);
		servers.add(server);
		Path name = file.getFileName();
		sharedNames.put(meta.infohash(), name != null ? name.toString() : meta.infohash().toString());

		dht.storeValue(meta.infohash(), encodeAnnounce(meta, host, server.getLocalPort()));
		return meta;
	}

	/**
	 * Resolve {@code infohash} via the DHT and download the file to {@code out}.
	 * Returns false (quickly) if no one has announced the infohash, or if the
	 * download does not complete before the timeout.
	 */
	public boolean download(NodeId infohash, Path out) throws Exception {
		byte[] announce = dht.findValue(infohash);
		if (announce == null) {
			return false;
		}
		Announce a = decodeAnnounce(announce);
		// Download mode uses rarest-first; the sliding-window picker is the streaming path (Phase 5).
		DownloadSession dl = new DownloadSession(
				a.meta, out, selfId, new RarestFirstPicker(a.meta.pieceCount()), MAX_IN_FLIGHT);
		try {
			dl.addPeer(new Socket(a.host, a.port));
			return dl.awaitCompletion(DOWNLOAD_TIMEOUT_MS);
		} finally {
			dl.close();
		}
	}

	/**
	 * Start a download <em>without blocking</em> and return its live
	 * {@link DownloadSession} (or {@code null} if the infohash is not announced in
	 * the DHT). Unlike {@link #download}, this does not wait for completion: the
	 * session runs event-driven on its peer-reader threads, and a caller (the
	 * Phase-5 UI / HTTP {@code /api/progress}) polls {@link DownloadSession#progress()}.
	 *
	 * <p>Uses the {@link SlidingWindowPicker} (the streaming path, blueprint rule
	 * #6) rather than rarest-first, so a player can later drive the window via the
	 * picker's playhead. The session is tracked in {@link #active} and reachable via
	 * {@link #session(NodeId)}; {@link #close()} shuts any still running.
	 *
	 * <p>The brief {@code findValue} below blocks on the DHT — fine on an HTTP/app
	 * thread, never the UDP receive thread.
	 */
	public DownloadSession startDownload(NodeId infohash, Path out) throws IOException {
		DownloadSession existing = active.get(infohash);
		if (existing != null) {
			return existing; // already downloading this file
		}
		byte[] announce = dht.findValue(infohash);
		if (announce == null) {
			return null;
		}
		Announce a = decodeAnnounce(announce);
		Path parent = out.getParent();
		if (parent != null) {
			Files.createDirectories(parent); // create the cache dir only once a download truly starts
		}
		DownloadSession dl = new DownloadSession(
				a.meta, out, selfId, new SlidingWindowPicker(a.meta.pieceCount(), STREAM_WINDOW), MAX_IN_FLIGHT);
		dl.addPeer(new Socket(a.host, a.port));
		active.put(infohash, dl);
		Path name = out.getFileName();
		downloadNames.put(infohash, name != null ? name.toString() : infohash.toString());
		return dl;
	}

	/** The live download for {@code infohash}, or {@code null} if none is active. */
	public DownloadSession session(NodeId infohash) {
		return active.get(infohash);
	}

	/**
	 * The local seed store for {@code infohash} (a file this node fully shares), or
	 * {@code null}. Lets the streaming endpoint serve a seed's complete file
	 * directly (vs. an in-progress {@link #session}).
	 */
	public PieceStore seedStore(NodeId infohash) {
		for (PieceStore s : stores) {
			if (s.metadata().infohash().equals(infohash)) {
				return s;
			}
		}
		return null;
	}

	/**
	 * Snapshot of every file this node seeds (from {@code share}) or is downloading
	 * (from {@code startDownload}) — the live Library list. Seeds report a full
	 * bitfield; downloads carry a live {@link DownloadSession#progress()} snapshot.
	 */
	public List<Transfer> transfers() {
		List<Transfer> out = new ArrayList<>();
		for (PieceStore s : stores) {
			TorrentMetadata m = s.metadata();
			NodeId ih = m.infohash();
			out.add(new Transfer(ih.toString(), sharedNames.getOrDefault(ih, ih.toString()),
					m.totalLength(), m.pieceCount(), true,
					m.pieceCount(), m.pieceCount(), 0, true));
		}
		for (Map.Entry<NodeId, DownloadSession> e : active.entrySet()) {
			NodeId ih = e.getKey();
			DownloadSession dl = e.getValue();
			TorrentMetadata m = dl.store().metadata();
			DownloadSession.Progress p = dl.progress();
			out.add(new Transfer(ih.toString(), downloadNames.getOrDefault(ih, ih.toString()),
					m.totalLength(), m.pieceCount(), false,
					p.have(), p.total(), p.peers(), dl.isComplete()));
		}
		return out;
	}

	// ----------------------------------------------------------- announce codec

	private static byte[] encodeAnnounce(TorrentMetadata meta, String host, int port) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeUTF(host);
			out.writeInt(port);
			out.writeInt(meta.pieceSize());
			out.writeLong(meta.totalLength());
			out.writeInt(meta.pieceCount());
			for (int i = 0; i < meta.pieceCount(); i++) {
				out.write(meta.pieceHash(i));
			}
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e); // never happens on a byte array
		}
		return bytes.toByteArray();
	}

	private static Announce decodeAnnounce(byte[] data) throws IOException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			String host = in.readUTF();
			int port = in.readInt();
			int pieceSize = in.readInt();
			long totalLength = in.readLong();
			int pieceCount = in.readInt();
			if (pieceCount < 0 || pieceCount > MAX_PIECES) {
				throw new IOException("bad piece count in announce: " + pieceCount);
			}
			List<byte[]> hashes = new ArrayList<>(pieceCount);
			for (int i = 0; i < pieceCount; i++) {
				byte[] h = new byte[20];
				in.readFully(h);
				hashes.add(h);
			}
			return new Announce(new TorrentMetadata(pieceSize, totalLength, hashes), host, port);
		}
	}

	private static final class Announce {
		final TorrentMetadata meta;
		final String host;
		final int port;

		Announce(TorrentMetadata meta, String host, int port) {
			this.meta = meta;
			this.host = host;
			this.port = port;
		}
	}

	@Override
	public void close() {
		for (DownloadSession dl : active.values()) {
			try {
				dl.close();
			} catch (IOException ignored) {
				// best effort
			}
		}
		for (TcpPeerServer s : servers) {
			s.close();
		}
		for (SeedSession s : seeds) {
			s.close();
		}
		for (PieceStore s : stores) {
			try {
				s.close();
			} catch (IOException ignored) {
				// best effort
			}
		}
		cleanCache(); // downloads are ephemeral — drop them on shutdown
	}
}
