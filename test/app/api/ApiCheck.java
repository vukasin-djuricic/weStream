package app.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import core.kademlia.Contact;
import core.kademlia.KademliaService;
import core.kademlia.NodeId;
import core.kademlia.RoutingTable;
import core.kademlia.UdpTransport;
import core.transfer.TransferService;

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
		runGroup("transfer endpoints (share/download/progress, real UDP+TCP+HTTP)", ApiCheck::transferChecks);

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

		TransferService transfer = new TransferService(kad);
		ApiServer api = new ApiServer("127.0.0.1", 0, kad, transfer, udpPort, () -> 4242L);
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
			transfer.close();
			transport.close();
		}
	}

	private static void dhtChecks() throws Exception {
		UdpTransport transport = new UdpTransport(0);
		int udpPort = transport.getLocalPort();
		KademliaService kad = new KademliaService("127.0.0.1", udpPort, transport);
		kad.start();
		TransferService transfer = new TransferService(kad);
		ApiServer api = new ApiServer("127.0.0.1", 0, kad, transfer, udpPort, () -> 0L);
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
			transfer.close();
			transport.close();
		}
	}

	/**
	 * End-to-end through HTTP: node 0 shares a file via {@code /api/share}, node 2
	 * downloads it via {@code /api/download}, and we poll {@code /api/progress}
	 * until complete — then assert the bytes are identical. Mirrors
	 * {@code TransferCheck.dhtDiscoveryChecks} but driven entirely over the API.
	 */
	private static void transferChecks() throws Exception {
		int n = 3;
		List<UdpTransport> transports = new ArrayList<>();
		List<KademliaService> nodes = new ArrayList<>();
		TransferService seederSvc = null;
		TransferService leecherSvc = null;
		ApiServer seederApi = null;
		ApiServer leecherApi = null;
		Path src = null;
		Path out = null;
		try {
			for (int i = 0; i < n; i++) {
				UdpTransport t = new UdpTransport(0);
				KademliaService s = new KademliaService("127.0.0.1", t.getLocalPort(), t);
				s.start();
				transports.add(t);
				nodes.add(s);
			}
			for (int i = 1; i < n; i++) {
				nodes.get(i).bootstrap(nodes.get(0).self());
			}

			seederSvc = new TransferService(nodes.get(0));
			leecherSvc = new TransferService(nodes.get(2));
			seederApi = new ApiServer("127.0.0.1", 0, nodes.get(0), seederSvc,
					nodes.get(0).self().getPort(), () -> 0L);
			leecherApi = new ApiServer("127.0.0.1", 0, nodes.get(2), leecherSvc,
					nodes.get(2).self().getPort(), () -> 0L);
			seederApi.start();
			leecherApi.start();
			int seedPort = seederApi.boundPort();
			int leechPort = leecherApi.boundPort();

			// ~525 KB so the default 256 KB piece size yields 3 pieces (real progress to watch).
			byte[] content = deterministicBytes(262144 * 2 + 1000, 7);
			src = Files.createTempFile("westream-api-src", ".bin");
			Files.write(src, content);
			out = Files.createTempFile("westream-api-out", ".bin");

			// --- share on node 0
			Response share = http("POST", seedPort, "/api/share",
					new Json().str("path", src.toString()).end());
			check("share -> 200", share.code == 200);
			check("share reports 3 pieces", share.body.contains("\"pieceCount\":3"));
			String infohash = extract(share.body, "infohash");
			check("share returns 40-hex infohash", infohash != null && infohash.length() == 40);
			check("infohash parses via NodeId.fromHex",
					NodeId.fromHex(infohash).toString().equals(infohash));

			// --- download on node 2 (non-blocking)
			Response dl = http("POST", leechPort, "/api/download",
					new Json().str("infohash", infohash).str("out", out.toString()).end());
			check("download -> 200 started", dl.code == 200 && dl.body.contains("\"started\":true"));

			// --- poll progress until complete (or time out)
			boolean complete = false;
			String last = "";
			for (int i = 0; i < 100 && !complete; i++) {
				last = http("GET", leechPort, "/api/progress?infohash=" + infohash).body;
				complete = last.contains("\"complete\":true");
				if (!complete) {
					Thread.sleep(100);
				}
			}
			check("progress reports active with total 3", last.contains("\"total\":3"));
			check("progress reaches complete", complete);
			check("downloaded file byte-identical", Arrays.equals(Files.readAllBytes(out), content));

			// unknown infohash -> 404
			String bogus = NodeId.fromBytes("never-shared".getBytes(StandardCharsets.UTF_8)).toString();
			check("download of unknown infohash -> 404",
					http("POST", leechPort, "/api/download",
							new Json().str("infohash", bogus).end()).code == 404);
			check("progress of unknown infohash -> active:false",
					http("GET", leechPort, "/api/progress?infohash=" + bogus).body.contains("\"active\":false"));
		} finally {
			if (seederApi != null) {
				seederApi.close();
			}
			if (leecherApi != null) {
				leecherApi.close();
			}
			if (seederSvc != null) {
				seederSvc.close();
			}
			if (leecherSvc != null) {
				leecherSvc.close();
			}
			transports.forEach(UdpTransport::close);
			if (src != null) {
				Files.deleteIfExists(src);
			}
			if (out != null) {
				Files.deleteIfExists(out);
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	/** Pull the string value of a top-level {@code "name":"..."} field out of a JSON body. */
	private static String extract(String body, String name) {
		String key = "\"" + name + "\":\"";
		int i = body.indexOf(key);
		if (i < 0) {
			return null;
		}
		int start = i + key.length();
		int end = body.indexOf('"', start);
		return end < 0 ? null : body.substring(start, end);
	}

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

	private static byte[] deterministicBytes(int n, int seed) {
		byte[] data = new byte[n];
		for (int i = 0; i < n; i++) {
			data[i] = (byte) ((i * 31 + 7 + seed) & 0xff);
		}
		return data;
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
