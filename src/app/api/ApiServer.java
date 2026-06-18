package app.api;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import core.kademlia.Contact;
import core.kademlia.KademliaService;
import core.kademlia.NodeId;
import core.kademlia.RoutingTable;
import core.kademlia.RpcLog;
import core.transfer.Bitfield;
import core.transfer.DownloadSession;
import core.transfer.PieceStore;
import core.transfer.SeedSession;
import core.transfer.TorrentMetadata;
import core.transfer.TransferService;

/**
 * The local HTTP control API for a single weStream node — the seam the Phase-5
 * Electron/React front-end polls (replacing the JavaFX direct-call seam). One
 * server per node, bound to loopback only.
 *
 * <p><b>Dependency policy:</b> this lives in {@code app.api}, NOT in
 * {@code core.kademlia}/{@code core.transfer}, so the engine's import-level
 * zero-dependency rule is untouched. It uses only {@code com.sun.net.httpserver}
 * (JDK module {@code jdk.httpserver}) + {@code java.*}, so {@code ./check.sh}'s
 * plain {@code javac src test} compiles it with no extra classpath.
 *
 * <p><b>Threading:</b> handlers run on this server's own pool (a cached pool, so a
 * long-lived {@code /stream} response cannot starve short polling requests like
 * {@code /api/progress}), never on the Kademlia UDP receive thread — so the
 * blocking RPC endpoints ({@code findValue}/{@code download}) cannot deadlock
 * (see {@link KademliaService} threading notes).
 *
 * <p>Increment 1 exposes two read-only endpoints: {@code GET /api/status} and
 * {@code GET /api/routing}. They only read existing engine seam methods and
 * change no engine state.
 */
public final class ApiServer implements Closeable {

	/** Cap on a request body we will read (the DHT put body is tiny; reject anything large). */
	private static final int MAX_BODY_BYTES = 64 * 1024;
	/** How long the streamer waits for one not-yet-downloaded piece before giving up. */
	private static final long STREAM_PIECE_TIMEOUT_MS = 30_000;

	private final HttpServer server;
	private final ExecutorService executor;
	private final KademliaService kademlia;
	private final TransferService transfer;
	private final int udpPort;
	private final LongSupplier uptimeMillis;

	/**
	 * Create (but do not start) the API server.
	 *
	 * @param bindHost     loopback address to bind ({@code 127.0.0.1} in production)
	 * @param bindPort     TCP port to bind; pass {@code 0} for an ephemeral port (tests)
	 * @param kademlia     the live node whose state the endpoints read
	 * @param transfer     the transfer service backing share/download/progress
	 * @param udpPort      this node's UDP listener port (reported by {@code /api/status})
	 * @param uptimeMillis supplies milliseconds since the node booted
	 */
	public ApiServer(String bindHost, int bindPort, KademliaService kademlia,
			TransferService transfer, int udpPort, LongSupplier uptimeMillis) throws IOException {
		this.kademlia = kademlia;
		this.transfer = transfer;
		this.udpPort = udpPort;
		this.uptimeMillis = uptimeMillis;
		this.server = HttpServer.create(new InetSocketAddress(bindHost, bindPort), 0);
		this.executor = Executors.newCachedThreadPool(daemon("api-http"));
		this.server.setExecutor(executor);
		this.server.createContext("/api/status", this::handleStatus);
		this.server.createContext("/api/routing", this::handleRouting);
		this.server.createContext("/api/dht/put", this::handleDhtPut);
		this.server.createContext("/api/dht/get", this::handleDhtGet);
		this.server.createContext("/api/dht/keys", this::handleDhtKeys);
		this.server.createContext("/api/share", this::handleShare);
		this.server.createContext("/api/peers", this::handlePeers);
		this.server.createContext("/api/download", this::handleDownload);
		this.server.createContext("/api/progress", this::handleProgress);
		this.server.createContext("/api/transfers", this::handleTransfers);
		this.server.createContext("/api/rpclog", this::handleRpcLog);
		this.server.createContext("/stream", this::handleStream);
	}

	public void start() {
		server.start();
	}

	/** The actual bound TCP port — meaningful when constructed with port 0 (ephemeral). */
	public int boundPort() {
		return server.getAddress().getPort();
	}

	// ----------------------------------------------------------------- handlers

	private void handleStatus(HttpExchange ex) throws IOException {
		if (!requireGet(ex)) {
			return;
		}
		Contact self = kademlia.self();
		String body = new Json()
				.str("nodeId", self.getId().toString())
				.str("host", self.getHost())
				.num("udpPort", udpPort)
				.num("apiPort", boundPort())
				.num("uptimeMs", uptimeMillis.getAsLong())
				.num("peerCount", kademlia.routingTable().size())
				.num("upBytes", transfer.uploadedBytes())
				.num("downBytes", transfer.downloadedBytes())
				.end();
		sendJson(ex, 200, body);
	}

	private void handleRouting(HttpExchange ex) throws IOException {
		if (!requireGet(ex)) {
			return;
		}
		Contact self = kademlia.self();
		RoutingTable rt = kademlia.routingTable();
		List<String> contacts = new ArrayList<>();
		for (Contact c : rt.findClosest(self.getId(), RoutingTable.K)) {
			contacts.add(new Json()
					.str("id", c.getId().toString())
					.str("host", c.getHost())
					.num("port", c.getPort())
					.end());
		}
		String body = new Json()
				.str("selfId", self.getId().toString())
				.intArray("bucketSizes", rt.bucketSizes())
				.raw("contacts", Json.array(contacts))
				.end();
		sendJson(ex, 200, body);
	}

	/**
	 * {@code POST /api/dht/put} with body {@code {"key":"...","value":"..."}} —
	 * stores the value in the DHT under {@code SHA-1(key)}, the SAME derivation the
	 * CLI {@code dht_put} uses (so CLI and API are interoperable). The blocking
	 * {@code storeValue} runs on this HTTP pool thread, never the UDP receive
	 * thread, so it cannot deadlock the engine.
	 */
	private void handleDhtPut(HttpExchange ex) throws IOException {
		if (!requireMethod(ex, "POST")) {
			return;
		}
		Map<String, String> body;
		try {
			body = Json.parseFlatObject(readBody(ex, MAX_BODY_BYTES));
		} catch (RuntimeException badRequest) {
			sendJson(ex, 400, "{\"error\":\"invalid JSON body\"}");
			return;
		}
		String key = body.get("key");
		String value = body.get("value");
		if (key == null || value == null) {
			sendJson(ex, 400, "{\"error\":\"missing key or value\"}");
			return;
		}
		NodeId keyId = NodeId.fromBytes(key.getBytes(StandardCharsets.UTF_8));
		kademlia.storeValue(keyId, value.getBytes(StandardCharsets.UTF_8));
		sendJson(ex, 200, new Json()
				.bool("stored", true)
				.str("key", key)
				.str("keyId", keyId.toString())
				.end());
	}

	/**
	 * {@code GET /api/dht/get?key=...} — resolves {@code SHA-1(key)} via
	 * {@code findValue} (local store first, otherwise the k closest nodes). Blocking,
	 * but on the HTTP pool thread (safe). Returns {@code found:false} when absent.
	 */
	private void handleDhtGet(HttpExchange ex) throws IOException {
		if (!requireGet(ex)) {
			return;
		}
		String key = queryParam(ex, "key");
		if (key == null || key.isEmpty()) {
			sendJson(ex, 400, "{\"error\":\"missing key\"}");
			return;
		}
		NodeId keyId = NodeId.fromBytes(key.getBytes(StandardCharsets.UTF_8));
		byte[] value = kademlia.findValue(keyId);
		Json j = new Json()
				.str("key", key)
				.str("keyId", keyId.toString())
				.bool("found", value != null);
		if (value != null) {
			j.str("value", new String(value, StandardCharsets.UTF_8));
		}
		sendJson(ex, 200, j.end());
	}

	/**
	 * {@code GET /api/dht/keys} — the local DHT store snapshot for the inspector's
	 * "Stored keys" panel: how many key/value pairs and swarm peer-sets this node
	 * holds, plus the key ids. (Values aren't returned — they're raw bytes, and
	 * peer-sets live in a separate store; the panel shows the keys + counts.)
	 */
	private void handleDhtKeys(HttpExchange ex) throws IOException {
		if (!requireGet(ex)) {
			return;
		}
		List<String> keys = new ArrayList<>();
		for (NodeId k : kademlia.storedKeys()) {
			keys.add(new Json().str("key", k.toString()).end());
		}
		sendJson(ex, 200, new Json()
				.num("storedCount", kademlia.storedKeyCount())
				.num("swarmCount", kademlia.swarmCount())
				.raw("keys", Json.array(keys))
				.end());
	}

	/**
	 * {@code POST /api/share} with body {@code {"path":"..."}} — split + hash the
	 * file, seed it, announce it in the DHT, and return its metadata + infohash.
	 */
	private void handleShare(HttpExchange ex) throws IOException {
		if (!requireMethod(ex, "POST")) {
			return;
		}
		String pathStr;
		try {
			pathStr = Json.parseFlatObject(readBody(ex, MAX_BODY_BYTES)).get("path");
		} catch (RuntimeException badRequest) {
			sendJson(ex, 400, "{\"error\":\"invalid JSON body\"}");
			return;
		}
		if (pathStr == null || pathStr.isEmpty()) {
			sendJson(ex, 400, "{\"error\":\"missing path\"}");
			return;
		}
		Path path = Path.of(pathStr);
		if (!Files.isRegularFile(path)) {
			sendJson(ex, 400, "{\"error\":\"not a readable file: " + Json.escape(pathStr) + "\"}");
			return;
		}
		TorrentMetadata meta = transfer.share(path);
		sendJson(ex, 200, new Json()
				.str("infohash", meta.infohash().toString())
				.str("name", nameOr(meta.infohash()))
				.num("pieceSize", meta.pieceSize())
				.num("totalLength", meta.totalLength())
				.num("pieceCount", meta.pieceCount())
				.end());
	}

	/**
	 * {@code POST /api/download} with body {@code {"infohash":"<40-hex>"[,"out":"..."]}} —
	 * start a non-blocking, sliding-window download. Returns {@code 404} if the
	 * infohash is not announced in the DHT; otherwise {@code started:true} and the
	 * resolved output path (poll {@code /api/progress} to watch it).
	 */
	/**
	 * {@code GET /api/peers?infohash=<40-hex>} — a lightweight DHT peek that resolves
	 * the file's metadata and counts its live swarm WITHOUT starting a download, so
	 * the Add-Stream screen can show "N peers hold this file" before the user
	 * commits. {@code found:false} when the infohash is not announced. The {@code peers}
	 * count is the whole swarm; the DHT peer set carries no seeder-vs-leecher flag
	 * (that split is only knowable once a transfer connects and reads bitfields).
	 */
	private void handlePeers(HttpExchange ex) throws IOException {
		if (!requireGet(ex)) {
			return;
		}
		NodeId infohash;
		try {
			infohash = NodeId.fromHex(queryParam(ex, "infohash"));
		} catch (RuntimeException badHash) {
			sendJson(ex, 400, "{\"error\":\"missing or malformed infohash\"}");
			return;
		}
		TransferService.Peek peek = transfer.peek(infohash);
		if (peek == null) {
			sendJson(ex, 200, "{\"found\":false}");
			return;
		}
		sendJson(ex, 200, new Json()
				.bool("found", true)
				.str("infohash", infohash.toString())
				.str("name", peek.name() == null ? "" : peek.name())   // sharer's advisory file name
				.num("peers", peek.peers())
				.num("pieceSize", peek.pieceSize())
				.num("totalLength", peek.totalLength())
				.num("pieceCount", peek.pieceCount())
				.end());
	}

	private void handleDownload(HttpExchange ex) throws IOException {
		if (!requireMethod(ex, "POST")) {
			return;
		}
		Map<String, String> body;
		try {
			body = Json.parseFlatObject(readBody(ex, MAX_BODY_BYTES));
		} catch (RuntimeException badRequest) {
			sendJson(ex, 400, "{\"error\":\"invalid JSON body\"}");
			return;
		}
		NodeId infohash;
		try {
			infohash = NodeId.fromHex(body.get("infohash"));
		} catch (RuntimeException badHash) {
			sendJson(ex, 400, "{\"error\":\"missing or malformed infohash\"}");
			return;
		}
		// Already seeding this file? There's nothing to download — the stream endpoint
		// serves it straight from the local seed store. Short-circuit so "Watch now" on
		// your own share doesn't kick off a pointless self-download (and 404 when you're
		// the only seeder, because connectToSwarm skips self).
		PieceStore seeded = transfer.seedStore(infohash);
		if (seeded != null) {
			TorrentMetadata meta = seeded.metadata();
			sendJson(ex, 200, new Json()
					.bool("started", true)
					.bool("alreadyLocal", true)
					.str("infohash", infohash.toString())
					.str("name", nameOr(infohash))
					.num("pieceSize", meta.pieceSize())
					.num("totalLength", meta.totalLength())
					.num("pieceCount", meta.pieceCount())
					.end());
			return;
		}
		// Default: the node's ephemeral cache (wiped on shutdown). Created lazily by startDownload.
		// A caller-supplied out is CONFINED to the cache dir — otherwise a loopback request
		// could write attacker-chosen content to an arbitrary path (Security M1).
		String outStr = body.get("out");
		Path cacheDir = transfer.cacheDir().toAbsolutePath().normalize();
		Path out;
		if (outStr != null && !outStr.isEmpty()) {
			Path candidate = Path.of(outStr).toAbsolutePath().normalize(); // collapses ".."
			if (!candidate.startsWith(cacheDir)) {
				sendJson(ex, 400, "{\"error\":\"out must be inside the download cache\"}");
				return;
			}
			out = candidate;
		} else {
			out = cacheDir.resolve(infohash + ".bin");
		}

		DownloadSession dl = transfer.startDownload(infohash, out);
		if (dl == null) {
			sendJson(ex, 404, "{\"error\":\"infohash not announced in the DHT\"}");
			return;
		}
		// Echo the resolved metadata so the UI's "resolved" card renders real values.
		TorrentMetadata meta = dl.store().metadata();
		sendJson(ex, 200, new Json()
				.bool("started", true)
				.str("infohash", infohash.toString())
				.str("name", nameOr(infohash))   // original sharer's name, resolved from the DHT
				.str("out", out.toString())
				.num("pieceSize", meta.pieceSize())
				.num("totalLength", meta.totalLength())
				.num("pieceCount", meta.pieceCount())
				.end());
	}

	/** The display name for {@code infohash}, or "" — never null (keeps the JSON tidy). */
	private String nameOr(NodeId infohash) {
		String n = transfer.nameFor(infohash);
		return n == null ? "" : n;
	}

	/**
	 * {@code GET /api/progress?infohash=<40-hex>} — the live download snapshot that
	 * drives the sliding-window strip / scrubber: per-piece state bytes
	 * (0=missing, 1=in-flight, 2=have) plus counts. {@code active:false} when no
	 * download is running for that infohash.
	 */
	private void handleProgress(HttpExchange ex) throws IOException {
		if (!requireGet(ex)) {
			return;
		}
		NodeId infohash;
		try {
			infohash = NodeId.fromHex(queryParam(ex, "infohash"));
		} catch (RuntimeException badHash) {
			sendJson(ex, 400, "{\"error\":\"missing or malformed infohash\"}");
			return;
		}
		DownloadSession dl = transfer.session(infohash);
		if (dl == null) {
			// Not an active download. We may still be SEEDING this file (complete on
			// disk) — report a real snapshot from the seed store so the Player shows
			// the true piece map (a full strip) instead of falling back to mock.
			PieceStore seed = transfer.seedStore(infohash);
			if (seed == null) {
				sendJson(ex, 200, "{\"active\":false}");
				return;
			}
			int total = seed.metadata().pieceCount();
			Bitfield bits = seed.bitfield();
			byte[] states = new byte[total];
			for (int i = 0; i < total; i++) {
				states[i] = bits.get(i) ? DownloadSession.HAVE : DownloadSession.MISSING;
			}
			// Real leecher list: who is pulling from us + how much each one already has
			// (from the BITFIELD/HAVE messages they send us — see SeedSession.leechers()).
			List<SeedSession.Leecher> leechers = transfer.leechers(infohash);
			List<String> leecherJson = new ArrayList<>();
			for (SeedSession.Leecher l : leechers) {
				leecherJson.add(new Json()
						.str("id", l.id())
						.str("endpoint", l.endpoint())
						.num("have", l.have())
						.num("total", l.total())
						.end());
			}
			sendJson(ex, 200, new Json()
					.bool("active", false)
					.bool("seeding", true)
					.bool("complete", seed.isComplete())
					.num("have", bits.cardinality())
					.num("inFlight", 0)
					.num("total", total)
					.num("peers", leechers.size())
					.raw("leechers", Json.array(leecherJson))
					.byteArray("pieceStates", states)
					.end());
			return;
		}
		DownloadSession.Progress p = dl.progress();
		sendJson(ex, 200, new Json()
				.bool("active", true)
				.bool("complete", dl.isComplete())
				.num("have", p.have())
				.num("inFlight", p.inFlight())
				.num("total", p.total())
				.num("peers", p.peers())
				.num("seeders", p.seeders())
				.num("leechers", p.leechers())
				.num("playhead", dl.playhead())   // streaming window anchor (a seek moves it)
				.num("window", dl.streamWindow())
				.byteArray("pieceStates", p.pieceStates())
				.end());
	}

	/**
	 * {@code GET /api/transfers} — the live Library list: every file this node seeds
	 * or is downloading, with a progress snapshot for downloads.
	 */
	private void handleTransfers(HttpExchange ex) throws IOException {
		if (!requireGet(ex)) {
			return;
		}
		List<String> items = new ArrayList<>();
		for (TransferService.Transfer t : transfer.transfers()) {
			items.add(new Json()
					.str("infohash", t.infohash())
					.str("name", t.name())
					.num("totalLength", t.totalLength())
					.num("pieceCount", t.pieceCount())
					.bool("seeding", t.seeding())
					.num("have", t.have())
					.num("total", t.total())
					.num("peers", t.peers())
					.bool("complete", t.complete())
					.end());
		}
		sendJson(ex, 200, new Json().raw("transfers", Json.array(items)).end());
	}

	/**
	 * {@code GET /api/rpclog} — recent RPC activity (newest first, capped), for the
	 * DHT inspector's live log. {@code dir} is "→" (sent) / "←" (received).
	 */
	private void handleRpcLog(HttpExchange ex) throws IOException {
		if (!requireGet(ex)) {
			return;
		}
		SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss");
		List<RpcLog.Event> events = kademlia.rpcEvents();
		List<String> items = new ArrayList<>();
		for (int i = events.size() - 1; i >= 0 && items.size() < 40; i--) { // newest first, cap 40
			RpcLog.Event e = events.get(i);
			items.add(new Json()
					.str("time", hms.format(new Date(e.timeMillis())))
					.str("dir", e.outbound() ? "→" : "←")
					.str("type", e.type())
					.str("peer", e.peer())
					.str("detail", e.detail())
					.end());
		}
		sendJson(ex, 200, new Json().raw("events", Json.array(items)).end());
	}

	/**
	 * {@code GET /stream/<40-hex infohash>} — serve the file's bytes for an HTML5
	 * {@code <video>}, honouring HTTP {@code Range} (206 Partial Content) so the
	 * player can seek. The {@code Content-Length} is known from the metadata up
	 * front, so the body can be filled <em>as pieces arrive</em>: each not-yet-held
	 * piece is awaited (no busy-wait) — this is watch-while-download. A seek (Range
	 * start) moves the sliding-window playhead so the pieces we are about to read
	 * are fetched first.
	 */
	private void handleStream(HttpExchange ex) throws IOException {
		if (!requireGet(ex)) {
			return;
		}
		String path = ex.getRequestURI().getPath(); // /stream/<hex>
		String hex = path.substring(path.lastIndexOf('/') + 1);
		NodeId infohash;
		try {
			infohash = NodeId.fromHex(hex);
		} catch (RuntimeException bad) {
			sendJson(ex, 400, "{\"error\":\"malformed infohash in path\"}");
			return;
		}

		// Prefer an in-progress download (partial, growing); fall back to a local seed (complete).
		DownloadSession dl = transfer.session(infohash);
		PieceStore store = (dl != null) ? dl.store() : transfer.seedStore(infohash);
		if (store == null) {
			sendJson(ex, 404, "{\"error\":\"infohash not available locally\"}");
			return;
		}
		TorrentMetadata meta = store.metadata();
		long total = meta.totalLength();

		ex.getResponseHeaders().add("Accept-Ranges", "bytes");
		ex.getResponseHeaders().add("Content-Type", "video/mp4");

		if (total <= 0) {
			ex.sendResponseHeaders(200, -1);
			ex.close();
			return;
		}

		long start = 0;
		long end = total - 1;
		int status = 200;
		String rangeHeader = ex.getRequestHeaders().getFirst("Range");
		if (rangeHeader != null) {
			long[] range = parseRange(rangeHeader, total);
			if (range == null) {
				ex.getResponseHeaders().add("Content-Range", "bytes */" + total);
				ex.sendResponseHeaders(416, -1);
				ex.close();
				return;
			}
			start = range[0];
			end = range[1];
			status = 206;
			ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + total);
		}

		// A seek moves the streaming window so the next pieces fetched are the ones being read.
		if (dl != null) {
			dl.setPlayhead((int) (start / meta.pieceSize()));
		}

		ex.sendResponseHeaders(status, end - start + 1);
		try (OutputStream os = ex.getResponseBody()) {
			streamRange(os, store, meta, start, end);
		} catch (IOException clientGone) {
			// the player closed the connection (seek / stop) — normal, nothing to do
		} finally {
			ex.close();
		}
	}

	/** Write file bytes {@code [start, end]} to {@code os}, awaiting each covering piece as needed. */
	private static void streamRange(OutputStream os, PieceStore store, TorrentMetadata meta,
			long start, long end) throws IOException {
		int pieceSize = meta.pieceSize();
		int firstPiece = (int) (start / pieceSize);
		int lastPiece = (int) (end / pieceSize);
		for (int i = firstPiece; i <= lastPiece; i++) {
			try {
				if (!store.awaitPiece(i, STREAM_PIECE_TIMEOUT_MS)) {
					return; // piece never arrived — stop (client sees a short read)
				}
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return;
			}
			byte[] piece = store.readPiece(i);
			long pieceStart = (long) i * pieceSize;
			int from = (int) Math.max(0, start - pieceStart);
			int to = (int) Math.min(piece.length - 1, end - pieceStart);
			os.write(piece, from, to - from + 1);
			os.flush();
		}
	}

	/**
	 * Parse a single HTTP byte range ({@code bytes=start-end}, {@code bytes=start-},
	 * or suffix {@code bytes=-n}) against {@code total}. Returns {@code [start,end]}
	 * (inclusive, clamped), or {@code null} if malformed or unsatisfiable (→ 416).
	 */
	private static long[] parseRange(String header, long total) {
		if (header == null || !header.startsWith("bytes=")) {
			return null;
		}
		String spec = header.substring("bytes=".length()).trim();
		int dash = spec.indexOf('-');
		if (dash < 0) {
			return null;
		}
		String s = spec.substring(0, dash).trim();
		String e = spec.substring(dash + 1).trim();
		try {
			long start;
			long end;
			if (s.isEmpty()) {
				long suffix = Long.parseLong(e); // last N bytes
				if (suffix <= 0) {
					return null;
				}
				start = Math.max(0, total - suffix);
				end = total - 1;
			} else {
				start = Long.parseLong(s);
				end = e.isEmpty() ? total - 1 : Long.parseLong(e);
			}
			if (start < 0 || end < start || start >= total) {
				return null;
			}
			return new long[] { start, Math.min(end, total - 1) };
		} catch (NumberFormatException nfe) {
			return null;
		}
	}

	// ------------------------------------------------------------------ helpers

	private boolean requireGet(HttpExchange ex) throws IOException {
		return requireMethod(ex, "GET");
	}

	private boolean requireMethod(HttpExchange ex, String method) throws IOException {
		// CSRF guard: every handler enters here, so reject any cross-origin request from a
		// non-local web origin BEFORE it can act (a remote page could otherwise drive
		// /api/share, /api/download, /api/dht/put via a simple POST — Security H2/M1/L3).
		// Requests with no Origin (native clients, the <video> tag, same-origin) and the
		// renderer's own localhost / file:// origins are allowed, so the UI is unaffected.
		if (!originAllowed(ex)) {
			ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
			sendJson(ex, 403, "{\"error\":\"cross-origin request rejected\"}");
			return false;
		}
		// Permissive CORS: this is a loopback-only companion API for the Electron/React
		// renderer (a different origin: the Vite dev server, file://, or the <video> tag).
		// Every handler enters through here, so this is the single CORS chokepoint.
		ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
		if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
			ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
			ex.sendResponseHeaders(204, -1);
			ex.close();
			return false; // preflight handled — the handler must stop
		}
		if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
			sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
			return false;
		}
		return true;
	}

	/**
	 * Whether the request's {@code Origin} is allowed to drive the API. Absent
	 * Origin (native clients, the {@code <video>} tag, same-origin) and the
	 * renderer's localhost / {@code file://} origins pass; any other web origin is
	 * a cross-site caller and is rejected.
	 */
	private static boolean originAllowed(HttpExchange ex) {
		String origin = ex.getRequestHeaders().getFirst("Origin");
		if (origin == null || origin.isEmpty() || origin.equals("null")) {
			return true; // no browser origin (curl / media element / same-origin) or file://
		}
		try {
			java.net.URI u = java.net.URI.create(origin);
			if ("file".equalsIgnoreCase(u.getScheme())) {
				return true; // packaged Electron renderer
			}
			String host = u.getHost();
			return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
		} catch (RuntimeException malformed) {
			return false;
		}
	}

	/** Read up to {@code max} bytes of the request body as UTF-8; reject anything larger. */
	private static String readBody(HttpExchange ex, int max) throws IOException {
		byte[] data = ex.getRequestBody().readNBytes(max + 1);
		if (data.length > max) {
			throw new IllegalArgumentException("request body too large");
		}
		return new String(data, StandardCharsets.UTF_8);
	}

	/** Value of a single query-string parameter (URL-decoded), or {@code null} if absent. */
	private static String queryParam(HttpExchange ex, String name) {
		String raw = ex.getRequestURI().getRawQuery();
		if (raw == null) {
			return null;
		}
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			String k = (eq < 0) ? pair : pair.substring(0, eq);
			if (k.equals(name)) {
				String v = (eq < 0) ? "" : pair.substring(eq + 1);
				return URLDecoder.decode(v, StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static ThreadFactory daemon(String prefix) {
		AtomicInteger n = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}
}
