package core.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

	private final List<TcpPeerServer> servers = new CopyOnWriteArrayList<>();
	private final List<SeedSession> seeds = new CopyOnWriteArrayList<>();
	private final List<PieceStore> stores = new CopyOnWriteArrayList<>();
	/** Live, non-blocking downloads keyed by infohash (the Phase-5 UI polls these via progress()). */
	private final Map<NodeId, DownloadSession> active = new ConcurrentHashMap<>();

	public TransferService(KademliaService dht) {
		this.dht = dht;
		this.host = dht.self().getHost();
		this.selfId = dht.self().getId();
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
		DownloadSession dl = new DownloadSession(
				a.meta, out, selfId, new SlidingWindowPicker(a.meta.pieceCount(), STREAM_WINDOW), MAX_IN_FLIGHT);
		dl.addPeer(new Socket(a.host, a.port));
		active.put(infohash, dl);
		return dl;
	}

	/** The live download for {@code infohash}, or {@code null} if none is active. */
	public DownloadSession session(NodeId infohash) {
		return active.get(infohash);
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
	}
}
