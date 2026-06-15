package app.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import core.kademlia.Contact;
import core.kademlia.KademliaService;
import core.kademlia.NodeId;
import core.kademlia.RoutingTable;
import core.kademlia.UdpTransport;

/**
 * Zero-dependency regression checks for the local HTTP API ({@link ApiServer}) —
 * run via {@code ./check.sh} alongside {@code KademliaCheck} / {@code TransferCheck}.
 *
 * <p>Same shape as those: lives in {@code test/} but in package {@code app.api} so
 * it can reach the package-private {@link Json} writer, groups run in isolation,
 * and the process exits non-zero on any failure so it plugs into the git hook / CI.
 *
 * <p>Hermetic: the {@link UdpTransport} binds an ephemeral UDP port ({@code 0})
 * and the {@link ApiServer} an ephemeral TCP port ({@code 0}), so the checks never
 * collide with a running node or with each other.
 */
public class ApiCheck {

	private static int passed = 0;
	private static final List<String> failures = new ArrayList<>();

	public static void main(String[] args) {
		runGroup("json writer (unit)", ApiCheck::jsonChecks);
		runGroup("json reader (unit)", ApiCheck::jsonReaderChecks);
		runGroup("api endpoints (real localhost HTTP)", ApiCheck::endpointChecks);
		runGroup("dht endpoints (real localhost HTTP)", ApiCheck::dhtChecks);

		System.out.println();
		System.out.println(passed + " passed, " + failures.size() + " failed");
		if (!failures.isEmpty()) {
			failures.forEach(f -> System.out.println("  FAILED: " + f));
			System.exit(1);
		}
		System.out.println("OK");
	}

	// ---------------------------------------------------------------- groups

	private static void jsonChecks() {
		check("escapes control + quote + backslash",
				Json.escape("\n\t\"\\").equals("\\n\\t\\\"\\\\"));
		check("quote wraps + escapes", Json.quote("a\"b").equals("\"a\\\"b\""));
		check("flat object", new Json().str("a", "x").num("n", 1).bool("b", true).end()
				.equals("{\"a\":\"x\",\"n\":1,\"b\":true}"));
		check("int array", new Json().intArray("xs", new int[] {1, 2, 3}).end()
				.equals("{\"xs\":[1,2,3]}"));
		check("array of pre-encoded elements",
				Json.array(List.of("1", "{\"k\":2}")).equals("[1,{\"k\":2}]"));
		check("null value", new Json().str("a", null).end().equals("{\"a\":null}"));
	}

	private static void jsonReaderChecks() {
		Map<String, String> two = Json.parseFlatObject("{\"key\":\"hello\",\"value\":\"world\"}");
		check("reader parses two fields",
				"hello".equals(two.get("key")) && "world".equals(two.get("value")));
		check("reader parses empty object", Json.parseFlatObject("{}").isEmpty());
		check("reader tolerates whitespace",
				"v".equals(Json.parseFlatObject("  {  \"k\" : \"v\" }  ").get("k")));
		Map<String, String> esc = Json.parseFlatObject("{\"k\":\"a\\\"b\\n\\u0041\"}");
		check("reader decodes escapes", "a\"b\nA".equals(esc.get("k")));
		// write-then-read round trip survives a value full of special chars
		String tricky = "q\"u\\o\te\n";
		String encoded = new Json().str("value", tricky).end();
		check("writer/reader round-trip",
				tricky.equals(Json.parseFlatObject(encoded).get("value")));
		check("reader rejects malformed", throwsOnParse("{\"k\":") );
		check("reader rejects non-object", throwsOnParse("\"bare\""));
	}

	private static boolean throwsOnParse(String json) {
		try {
			Json.parseFlatObject(json);
			return false;
		} catch (RuntimeException expected) {
			return true;
		}
	}

	private static void endpointChecks() throws Exception {
		UdpTransport transport = new UdpTransport(0);
		int udpPort = transport.getLocalPort();
		KademliaService kad = new KademliaService("127.0.0.1", udpPort, transport);
		kad.start();
		// Seed one known peer so /api/routing has a contact and peerCount == 1.
		kad.routingTable().update(new Contact("127.0.0.1", 1200));

		ApiServer api = new ApiServer("127.0.0.1", 0, kad, udpPort, () -> 4242L);
		api.start();
		int p = api.boundPort();
		try {
			// --- GET /api/status
			Response status = http("GET", p, "/api/status");
			check("status -> 200", status.code == 200);
			check("status reports udpPort", status.body.contains("\"udpPort\":" + udpPort));
			check("status reports apiPort", status.body.contains("\"apiPort\":" + p));
			check("status reports uptimeMs from supplier", status.body.contains("\"uptimeMs\":4242"));
			check("status reports peerCount", status.body.contains("\"peerCount\":1"));
			check("status reports 40-hex nodeId",
					status.body.contains("\"nodeId\":\"" + kad.self().getId() + "\""));

			// --- GET /api/routing
			Response routing = http("GET", p, "/api/routing");
			check("routing -> 200", routing.code == 200);
			check("routing lists seeded contact", routing.body.contains("\"port\":1200"));
			check("routing bucketSizes has ID_BITS entries",
					bucketSizesLength(routing.body) == NodeId.ID_BITS);

			// --- method enforcement
			Response post = http("POST", p, "/api/status");
			check("non-GET -> 405", post.code == 405);
		} finally {
			api.close();
			transport.close();
		}
	}

	private static void dhtChecks() throws Exception {
		UdpTransport transport = new UdpTransport(0);
		int udpPort = transport.getLocalPort();
		KademliaService kad = new KademliaService("127.0.0.1", udpPort, transport);
		kad.start();
		ApiServer api = new ApiServer("127.0.0.1", 0, kad, udpPort, () -> 0L);
		api.start();
		int p = api.boundPort();
		try {
			// put -> 200, stored
			Response put = http("POST", p, "/api/dht/put", "{\"key\":\"hello\",\"value\":\"world\"}");
			check("put -> 200", put.code == 200);
			check("put reports stored", put.body.contains("\"stored\":true"));

			// get the same key -> found + value (single node: served from the local store)
			Response get = http("GET", p, "/api/dht/get?key=hello");
			check("get -> 200", get.code == 200);
			check("get found", get.body.contains("\"found\":true"));
			check("get returns value", get.body.contains("\"value\":\"world\""));

			// CLI/API interop: SHA-1(key) keyId must match the engine's derivation
			NodeId expectedId = NodeId.fromBytes("hello".getBytes(StandardCharsets.UTF_8));
			check("keyId is SHA-1(key)", get.body.contains("\"keyId\":\"" + expectedId + "\""));

			// special-character value survives the write/parse/store/read round trip
			String tricky = "a\"b\\c";
			http("POST", p, "/api/dht/put", new Json().str("key", "weird").str("value", tricky).end());
			Response got = http("GET", p, "/api/dht/get?key=" + URLEncoder.encode("weird", StandardCharsets.UTF_8));
			check("tricky value round-trips",
					got.body.contains("\"value\":" + Json.quote(tricky)));

			// missing key -> found:false (not an error)
			Response miss = http("GET", p, "/api/dht/get?key=nope");
			check("absent key -> 200 found:false",
					miss.code == 200 && miss.body.contains("\"found\":false"));

			// bad inputs
			check("bad JSON body -> 400", http("POST", p, "/api/dht/put", "{not json").code == 400);
			check("missing value -> 400",
					http("POST", p, "/api/dht/put", "{\"key\":\"k\"}").code == 400);
			check("get without key -> 400", http("GET", p, "/api/dht/get").code == 400);
			check("wrong method on put -> 405", http("GET", p, "/api/dht/put").code == 405);
		} finally {
			api.close();
			transport.close();
		}
	}

	// ---------------------------------------------------------------- helpers

	/** Count the entries in the {@code "bucketSizes":[...]} array of a routing body. */
	private static int bucketSizesLength(String body) {
		int open = body.indexOf("\"bucketSizes\":[");
		if (open < 0) {
			return -1;
		}
		int start = open + "\"bucketSizes\":[".length();
		int end = body.indexOf(']', start);
		String inner = body.substring(start, end).trim();
		if (inner.isEmpty()) {
			return 0;
		}
		return inner.split(",").length;
	}

	private static Response http(String method, int port, String path) throws IOException {
		return http(method, port, path, null);
	}

	private static Response http(String method, int port, String path, String requestBody) throws IOException {
		HttpURLConnection con = (HttpURLConnection)
				URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
		con.setRequestMethod(method);
		if (requestBody != null) {
			con.setDoOutput(true);
			con.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
		}
		int code = con.getResponseCode();
		InputStream is = (code >= 400) ? con.getErrorStream() : con.getInputStream();
		String body = (is == null) ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
		con.disconnect();
		return new Response(code, body);
	}

	private record Response(int code, String body) {
	}

	// ---------------------------------------------------- tiny check harness

	private interface Group {
		void run() throws Exception;
	}

	private static void runGroup(String name, Group group) {
		System.out.println("-- " + name);
		try {
			group.run();
		} catch (Exception e) {
			failures.add(name + " threw " + e);
			System.out.println("  THREW: " + e);
		}
	}

	private static void check(String label, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  ok: " + label);
		} else {
			failures.add(label);
			System.out.println("  FAIL: " + label);
		}
	}
}
