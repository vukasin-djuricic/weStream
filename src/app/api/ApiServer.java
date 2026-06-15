package app.api;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import core.kademlia.Contact;
import core.kademlia.KademliaService;
import core.kademlia.RoutingTable;

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

	private final HttpServer server;
	private final ExecutorService executor;
	private final KademliaService kademlia;
	private final int udpPort;
	private final LongSupplier uptimeMillis;

	/**
	 * Create (but do not start) the API server.
	 *
	 * @param bindHost     loopback address to bind ({@code 127.0.0.1} in production)
	 * @param bindPort     TCP port to bind; pass {@code 0} for an ephemeral port (tests)
	 * @param kademlia     the live node whose state the endpoints read
	 * @param udpPort      this node's UDP listener port (reported by {@code /api/status})
	 * @param uptimeMillis supplies milliseconds since the node booted
	 */
	public ApiServer(String bindHost, int bindPort, KademliaService kademlia,
			int udpPort, LongSupplier uptimeMillis) throws IOException {
		this.kademlia = kademlia;
		this.udpPort = udpPort;
		this.uptimeMillis = uptimeMillis;
		this.server = HttpServer.create(new InetSocketAddress(bindHost, bindPort), 0);
		this.executor = Executors.newFixedThreadPool(HANDLER_THREADS, daemon("api-http"));
		this.server.setExecutor(executor);
		this.server.createContext("/api/status", this::handleStatus);
		this.server.createContext("/api/routing", this::handleRouting);
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

	// ------------------------------------------------------------------ helpers

	private boolean requireGet(HttpExchange ex) throws IOException {
		if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
			sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
			return false;
		}
		return true;
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
