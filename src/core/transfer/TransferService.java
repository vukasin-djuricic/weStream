package core.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import core.kademlia.KademliaService;
import core.kademlia.NodeId;
import core.kademlia.PeerStore;

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
	/**
	 * Sliding-window size for streaming downloads. Kept strictly larger than
	 * {@link #MAX_IN_FLIGHT} so the high-priority window always has unrequested
	 * slack ahead of the playhead — otherwise the in-flight ceiling fills with
	 * fallback (far) pieces and neutralizes the window's near-playhead priority.
	 */
	private static final int STREAM_WINDOW = 32;
	/**
	 * Upper bound on pieces for a shared file, so the metadata announce (every
	 * piece's 20-byte hash) fits one UDP datagram. 2048 pieces → ~40 KB of hashes,
	 * comfortably under {@link core.kademlia.UdpTransport#MAX_PACKET} (64 KB) with
	 * room for the STORE framing/endpoint overhead. Drives {@link #pieceSizeFor}.
	 */
	private static final int MAX_ANNOUNCE_PIECES = 2048;
	/** Defensive cap when parsing DHT metadata (guards against a hostile value). */
	private static final int MAX_PIECES = 1 << 20;
	/** Max simultaneous peer connections per download (bounds fan-out). */
	private static final int MAX_PEERS = 8;
	/** Refresh our swarm announce well before the TTL lapses. */
	private static final long REANNOUNCE_PERIOD_MS = PeerStore.PEER_TTL_MS / 3;

	private final KademliaService dht;
	private final String host;
	private final NodeId selfId;
	/** Ephemeral per-node download cache (under the app folder); wiped on startup + shutdown. */
	private final Path cacheDir;

	/** Periodic swarm re-announce (TTL refresh); runs off the network/reader threads. */
	private final ScheduledExecutorService reannounce =
			Executors.newSingleThreadScheduledExecutor(daemon("ws-reannounce"));
	/** TCP ports this node serves (seed + download servers) — used to skip self when swarming. */
	private final Set<Integer> myPorts = ConcurrentHashMap.newKeySet();

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

	/**
	 * Share {@code file}, auto-scaling the piece size so the announce fits the wire.
	 *
	 * <p>The full torrent metadata (every 20-byte piece hash) rides in a single DHT
	 * value sent over one UDP datagram ({@link core.kademlia.UdpTransport#MAX_PACKET},
	 * 64 KB). With the fixed {@link TorrentMetadata#DEFAULT_PIECE_SIZE} (256 KB) a
	 * file over ~800 MB produces more hashes than fit in a datagram and the STORE
	 * fails. So we grow the piece size for large files to keep the hash count (and
	 * thus the announce) bounded — see {@link #pieceSizeFor}.
	 */
	public TorrentMetadata share(Path file) throws IOException {
		return share(file, pieceSizeFor(Files.size(file)));
	}

	/** Smallest power-of-two piece size that keeps the piece count (and the DHT
	 *  announce it produces) within {@link #MAX_ANNOUNCE_PIECES}, starting from the
	 *  256 KB default. Capped at 64 MB pieces (only relevant for &gt;128 GB files,
	 *  far past this app's scope). Package-visible for the regression check. */
	static int pieceSizeFor(long fileSize) {
		int pieceSize = TorrentMetadata.DEFAULT_PIECE_SIZE;
		while (pieceSize < (1 << 26)
				&& (fileSize + pieceSize - 1) / pieceSize > MAX_ANNOUNCE_PIECES) {
			pieceSize <<= 1;
		}
		return pieceSize;
	}

	/** Split + hash {@code file}, seed it on an ephemeral TCP port, and announce it in the DHT. */
	public TorrentMetadata share(Path file, int pieceSize) throws IOException {
		TorrentMetadata meta = PieceHasher.fromFile(file, pieceSize);
		// Seed read-only: the source file is served, never resized or written
		// (works on read-only files and never mutates the user's original).
		PieceStore store = PieceStore.forSeeding(file, meta);
		store.verifyAndMarkExisting();

		SeedSession seed = new SeedSession(store, selfId);
		TcpPeerServer server = new TcpPeerServer(0);
		server.setConnectionHandler(seed::handle);
		server.start();

		stores.add(store);
		seeds.add(seed);
		servers.add(server);
		int port = server.getLocalPort();
		myPorts.add(port);
		Path name = file.getFileName();
		sharedNames.put(meta.infohash(), name != null ? name.toString() : meta.infohash().toString());

		// BEP 5 split: metadata under the infohash key (content-identical across
		// sharers, so latest-wins is safe), our endpoint into the file's peer set.
		dht.storeValue(meta.infohash(), encodeMetadata(meta));
		dht.announcePeer(meta.infohash(), host, port);            // synchronous initial join
		scheduleReannounce(meta.infohash(), port, REANNOUNCE_PERIOD_MS); // then refresh
		return meta;
	}

	/**
	 * Resolve {@code infohash} via the DHT and download the file to {@code out}.
	 * Returns false (quickly) if no one has announced the infohash, or if the
	 * download does not complete before the timeout.
	 */
	public boolean download(NodeId infohash, Path out) throws Exception {
		byte[] metaBytes = dht.findValue(infohash);
		if (metaBytes == null) {
			return false;
		}
		TorrentMetadata meta = decodeMetadata(metaBytes);
		List<PeerStore.PeerEntry> swarm = dht.getPeers(infohash);
		if (swarm.isEmpty()) {
			return false; // metadata known but no live seed
		}
		// Download mode uses rarest-first; the sliding-window picker is the streaming path (Phase 5).
		DownloadSession dl = new DownloadSession(
				meta, out, selfId, new RarestFirstPicker(meta.pieceCount()), MAX_IN_FLIGHT);
		try {
			if (connectToSwarm(dl, swarm) == 0) {
				return false; // every announced peer was unreachable
			}
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
		byte[] metaBytes = dht.findValue(infohash);
		if (metaBytes == null) {
			return null;
		}
		TorrentMetadata meta = decodeMetadata(metaBytes);
		List<PeerStore.PeerEntry> swarm = dht.getPeers(infohash);
		if (swarm.isEmpty()) {
			return null; // metadata known but no live seed
		}
		Path parent = out.getParent();
		if (parent != null) {
			Files.createDirectories(parent); // create the cache dir only once a download truly starts
		}
		DownloadSession dl = new DownloadSession(
				meta, out, selfId, new SlidingWindowPicker(meta.pieceCount(), STREAM_WINDOW), MAX_IN_FLIGHT);

		// Become a findable partial seed: an inbound server serves whatever pieces
		// this download already holds, and we join the swarm on the first verified
		// piece (registered BEFORE connecting so the one-shot can't be missed).
		SeedSession seed = new SeedSession(dl.store(), selfId);
		TcpPeerServer server = new TcpPeerServer(0);
		server.setConnectionHandler(seed::handle);
		server.start();
		int port = server.getLocalPort();
		myPorts.add(port);
		dl.onFirstPiece(() -> scheduleReannounce(infohash, port, 0)); // async join + refresh

		if (connectToSwarm(dl, swarm) == 0) {
			dl.close();
			server.close();
			seed.close();
			myPorts.remove(port);
			return null; // every announced peer was unreachable
		}
		servers.add(server);
		seeds.add(seed);
		active.put(infohash, dl);
		Path name = out.getFileName();
		downloadNames.put(infohash, name != null ? name.toString() : infohash.toString());
		return dl;
	}

	/**
	 * Open a connection to each distinct, non-self peer in {@code swarm} (capped at
	 * {@link #MAX_PEERS}); a peer that refuses is skipped. Returns the number of
	 * peers actually connected — the {@link DownloadSession} swarms across all of
	 * them (one shared in-flight set, so no piece is requested twice).
	 */
	private int connectToSwarm(DownloadSession dl, List<PeerStore.PeerEntry> swarm) {
		int connected = 0;
		Set<String> seen = new HashSet<>();
		for (PeerStore.PeerEntry p : swarm) {
			if (connected >= MAX_PEERS) {
				break;
			}
			if (isSelf(p) || !seen.add(p.host() + ":" + p.port())) {
				continue; // don't connect to ourselves or the same endpoint twice
			}
			try {
				dl.addPeer(new Socket(p.host(), p.port()));
				connected++;
			} catch (IOException dead) {
				// peer gone; the rest of the swarm still covers the file
			}
		}
		return connected;
	}

	private boolean isSelf(PeerStore.PeerEntry p) {
		return p.host().equals(host) && myPorts.contains(p.port());
	}

	/** Schedule (and immediately-or-soon start) periodic announce of our endpoint for {@code infohash}. */
	private void scheduleReannounce(NodeId infohash, int port, long initialDelayMs) {
		reannounce.scheduleAtFixedRate(() -> {
			try {
				dht.announcePeer(infohash, host, port);
			} catch (Exception ignored) {
				// best-effort; the next period retries
			}
		}, initialDelayMs, REANNOUNCE_PERIOD_MS, TimeUnit.MILLISECONDS);
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
			// A finished download is a full seed: its SeedSession server serves every
			// piece and it re-announces to the swarm, so report seeding once complete
			// (an in-progress download is still seeding:false).
			boolean complete = dl.isComplete();
			out.add(new Transfer(ih.toString(), downloadNames.getOrDefault(ih, ih.toString()),
					m.totalLength(), m.pieceCount(), complete,
					p.have(), p.total(), p.peers(), complete));
		}
		return out;
	}

	// ----------------------------------------------------------- metadata codec

	/**
	 * Encode the torrent metadata (the DHT value under the {@code infohash} key).
	 * Carries NO endpoint — peers live in the separate peer-set key — so the bytes
	 * are content-identical across every sharer, making latest-wins overwrite safe.
	 */
	private static byte[] encodeMetadata(TorrentMetadata meta) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(meta.pieceSize());
			out.writeLong(meta.totalLength());
			out.writeInt(meta.pieceCount());
			for (int i = 0; i < meta.pieceCount(); i++) {
				out.write(meta.pieceHash(i));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e); // never happens on a byte array
		}
		return bytes.toByteArray();
	}

	private static TorrentMetadata decodeMetadata(byte[] data) throws IOException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			int pieceSize = in.readInt();
			long totalLength = in.readLong();
			int pieceCount = in.readInt();
			if (pieceCount < 0 || pieceCount > MAX_PIECES) {
				throw new IOException("bad piece count in metadata: " + pieceCount);
			}
			List<byte[]> hashes = new ArrayList<>(pieceCount);
			for (int i = 0; i < pieceCount; i++) {
				byte[] h = new byte[20];
				in.readFully(h);
				hashes.add(h);
			}
			// The TorrentMetadata constructor is the hostile-input gate (C1): it
			// rejects an inconsistent pieceSize/totalLength/pieceCount.
			return new TorrentMetadata(pieceSize, totalLength, hashes);
		}
	}

	private static ThreadFactory daemon(String name) {
		return r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		};
	}

	@Override
	public void close() {
		reannounce.shutdownNow();
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
