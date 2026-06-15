package app.api;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import core.transfer.DownloadSession;
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
 * <p><b>Threading:</b> handlers run on this server's own bounded pool, never on
 * the Kademlia UDP receive thread — so once later increments add endpoints that
 * call blocking RPCs ({@code findValue}/{@code download}), they will not deadlock
 * (see {@link KademliaService} threading notes).
 *
 * <p>Increment 1 exposes two read-only endpoints: {@code GET /api/status} and
 * {@code GET /api/routing}. They only read existing engine seam methods and
 * change no engine state.
 */
public final class ApiServer implements Closeable {

	private static final int HANDLER_THREADS = 4;
	/** Cap on a request body we will read (the DHT put body is tiny; reject anything large). */
	private static final int MAX_BODY_BYTES = 64 * 1024;

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
		this.executor = Executors.newFixedThreadPool(HANDLER_THREADS, daemon("api-http"));
		this.server.setExecutor(executor);
		this.server.createContext("/api/status", this::handleStatus);
		this.server.createContext("/api/routing", this::handleRouting);
		this.server.createContext("/api/dht/put", this::handleDhtPut);
		this.server.createContext("/api/dht/get", this::handleDhtGet);
		this.server.createContext("/api/share", this::handleShare);
		this.server.createContext("/api/download", this::handleDownload);
		this.server.createContext("/api/progress", this::handleProgress);
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
		String outStr = body.get("out");
		Path out = (outStr != null && !outStr.isEmpty())
				? Path.of(outStr)
				: Path.of(System.getProperty("java.io.tmpdir"), "westream-" + infohash + ".bin");

		DownloadSession dl = transfer.startDownload(infohash, out);
		if (dl == null) {
			sendJson(ex, 404, "{\"error\":\"infohash not announced in the DHT\"}");
			return;
		}
		sendJson(ex, 200, new Json()
				.bool("started", true)
				.str("infohash", infohash.toString())
				.str("out", out.toString())
				.end());
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
			sendJson(ex, 200, "{\"active\":false}");
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
				.byteArray("pieceStates", p.pieceStates())
				.end());
	}

	// ------------------------------------------------------------------ helpers

	private boolean requireGet(HttpExchange ex) throws IOException {
		return requireMethod(ex, "GET");
	}

	private boolean requireMethod(HttpExchange ex, String method) throws IOException {
		if (!method.equalsIgnoreCase(ex.getRequestMethod())) {
			sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
			return false;
		}
		return true;
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
