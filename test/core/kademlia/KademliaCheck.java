package core.kademlia;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zero-dependency regression checks for the Kademlia engine — run via {@code ./check.sh}.
 *
 * <p>Lives in {@code test/} (kept out of {@code src/} so it never ships in the app)
 * but in package {@code core.kademlia} so it can reach package-internal types.
 * Exits non-zero if any check fails, so it plugs straight into a git hook or CI.
 *
 * <p>Groups run in isolation via {@link #runGroup}: a thrown exception is reported
 * as a failure instead of crashing the whole run. The socket-free groups (unit,
 * codec-fuzz, eviction, multi-hop, timeout) are fast and deterministic; the last
 * group exercises the real {@link UdpTransport} over loopback.
 */
public class KademliaCheck {

	private static int passed = 0;
	private static final List<String> failures = new ArrayList<>();

	public static void main(String[] args) {
		runGroup("unit (identity / routing / codec)", KademliaCheck::unitChecks);
		runGroup("codec fuzz (hostile packets)", KademliaCheck::codecFuzzChecks);
		runGroup("k-bucket eviction", KademliaCheck::evictionChecks);
		runGroup("multi-hop lookup (in-memory)", KademliaCheck::multiHopChecks);
		runGroup("RPC timeout (drop transport)", KademliaCheck::timeoutChecks);
		runGroup("end-to-end (real UDP)", KademliaCheck::rpcChecks);

		System.out.println();
		System.out.println(passed + " passed, " + failures.size() + " failed");
		if (!failures.isEmpty()) {
			failures.forEach(f -> System.out.println("  FAILED: " + f));
			System.exit(1);
		}
		System.out.println("OK");
	}

	// ---------------------------------------------------------------- groups

	/** Identity, routing-table, and codec round-trip — pure, fast, no sockets. */
	private static void unitChecks() {
		NodeId a = NodeId.fromPort(1100);
		NodeId b = NodeId.fromPort(1200);
		check("id deterministic", a.equals(NodeId.fromPort(1100)));
		check("ids distinct", !a.equals(b));
		check("id is 40 hex chars", a.toString().length() == 40);
		check("xor symmetric", a.distance(b).equals(b.distance(a)));
		check("self distance is zero", a.distance(a).signum() == 0);
		check("self bucketIndex is -1", a.bucketIndex(a) == -1);
		check("bucketIndex in range", a.bucketIndex(b) >= 0 && a.bucketIndex(b) < NodeId.ID_BITS);

		// bucketIndex extremes via hand-built ids
		byte[] zero = new byte[20];
		byte[] lowBit = new byte[20];
		lowBit[19] = 1; // differs from zero only in the lowest bit
		byte[] highBit = new byte[20];
		highBit[0] = (byte) 0x80; // differs only in the highest (159th) bit
		NodeId idZero = NodeId.fromValueBytes(zero);
		check("bucketIndex lowest bit == 0", idZero.bucketIndex(NodeId.fromValueBytes(lowBit)) == 0);
		check("bucketIndex highest bit == 159", idZero.bucketIndex(NodeId.fromValueBytes(highBit)) == 159);

		check("toBytes is 20 bytes", a.toBytes().length == 20);
		check("toBytes round-trips", NodeId.fromValueBytes(a.toBytes()).equals(a));
		NodeId small = NodeId.fromValueBytes(lowBit);
		check("toBytes left-pads small ids", small.toBytes().length == 20
				&& NodeId.fromValueBytes(small.toBytes()).equals(small));

		RoutingTable rt = new RoutingTable(a);
		rt.update(new Contact("localhost", 1100)); // == self, must be ignored
		check("self not stored", rt.size() == 0);
		int[] ports = {1200, 1300, 1400, 1600, 1700, 1800, 1900};
		for (int p : ports) {
			rt.update(new Contact("localhost", p));
		}
		check("contacts stored", rt.size() == ports.length);

		List<Contact> closest = rt.findClosest(b, 3);
		check("findClosest bounded", closest.size() == 3);
		check("findClosest nearest first", closest.get(0).getId().equals(b));
		check("findClosest sorted by distance", isSortedByDistanceTo(closest, b));
		check("findClosest count>size returns all", rt.findClosest(b, 999).size() == ports.length);
		check("findClosest count 0 returns empty", rt.findClosest(b, 0).isEmpty());
		check("findClosest on empty table", new RoutingTable(a).findClosest(b, 5).isEmpty());

		MessageCodec codec = new MessageCodec();
		Contact self = new Contact("127.0.0.1", 1234);
		Contact c1 = new Contact("localhost", 1200);
		Contact c2 = new Contact("localhost", 1300);
		Message dec = codec.decode(codec.encode(Message.findNode(self, 42, b)));
		check("codec preserves type", dec.type == MessageType.FIND_NODE);
		check("codec preserves txId", dec.txId == 42);
		check("codec preserves target", dec.target.equals(b));
		check("codec preserves sender", dec.senderPort == 1234 && dec.senderHost.equals("127.0.0.1"));
		Message dn = codec.decode(codec.encode(Message.nodes(self, 7, List.of(c1, c2))));
		check("codec preserves contacts", dn.contacts.size() == 2);
		Message dv = codec.decode(codec.encode(Message.value(self, 9, "hi".getBytes(StandardCharsets.UTF_8))));
		check("codec preserves value bytes", new String(dv.value, StandardCharsets.UTF_8).equals("hi"));
	}

	/** A malformed/hostile packet must throw (and be caught), never OOM or crash. */
	private static void codecFuzzChecks() throws IOException {
		MessageCodec codec = new MessageCodec();
		checkThrows("empty packet", () -> codec.decode(new byte[0]));
		checkThrows("unknown type ordinal", () -> codec.decode(new byte[] {(byte) 99}));

		byte[] valid = codec.encode(Message.findNode(new Contact("127.0.0.1", 1), 1, NodeId.fromPort(2)));
		byte[] truncated = new byte[5];
		System.arraycopy(valid, 0, truncated, 0, 5);
		checkThrows("truncated header", () -> codec.decode(truncated));

		byte[] hugeValue = craftPacket(MessageType.VALUE, Integer.MAX_VALUE, false);
		checkThrows("huge value length (no OOM)", () -> codec.decode(hugeValue));
		byte[] negativeValue = craftPacket(MessageType.VALUE, -1, false);
		checkThrows("negative value length", () -> codec.decode(negativeValue));
		byte[] hugeContacts = craftPacket(MessageType.NODES, Integer.MAX_VALUE, true);
		checkThrows("huge contact count (no OOM)", () -> codec.decode(hugeContacts));
	}

	/** k-bucket least-recently-seen eviction-candidate, replace, and remove. */
	private static void evictionChecks() {
		KBucket bucket = new KBucket(2);
		Contact c1 = new Contact("localhost", 1200);
		Contact c2 = new Contact("localhost", 1300);
		Contact c3 = new Contact("localhost", 1400);
		check("add c1", bucket.update(c1) == null);
		check("add c2", bucket.update(c2) == null);
		Contact candidate = bucket.update(c3);
		check("full bucket returns LRS candidate", candidate != null && candidate.getId().equals(c1.getId()));

		bucket.replaceLeastRecentlySeen(c3); // c1 (head) out, c3 appended
		List<Contact> after = bucket.contacts();
		check("size unchanged after replace", after.size() == 2);
		check("LRS (c1) evicted", after.stream().noneMatch(c -> c.getId().equals(c1.getId())));
		check("new contact (c3) present", after.stream().anyMatch(c -> c.getId().equals(c3.getId())));
		check("survivor (c2) now head", after.get(0).getId().equals(c2.getId()));

		bucket.remove(c2);
		check("remove shrinks bucket", bucket.size() == 1);
	}

	/**
	 * Deterministic multi-hop lookup. Wires a hand-built chain — each node knows
	 * only the node one step closer to the target — so a lookup from the farthest
	 * node must traverse several hops to reach the owner. This exercises the
	 * iterative loop (multiple rounds, shortlist merge, convergence) that the
	 * dense n=6 end-to-end group can never reach.
	 */
	private static void multiHopChecks() {
		Map<Integer, InMemoryTransport> network = new ConcurrentHashMap<>();
		ExecutorService delivery = Executors.newCachedThreadPool(daemonFactory());
		List<KademliaService> nodes = new ArrayList<>();
		try {
			int n = 8;
			for (int i = 1; i <= n; i++) {
				InMemoryTransport t = new InMemoryTransport("mem", i, network, delivery);
				KademliaService s = new KademliaService("mem", i, t);
				s.start();
				nodes.add(s);
			}

			NodeId target = nodes.get(0).self().getId(); // node "1" owns the target id
			List<KademliaService> byDistance = new ArrayList<>(nodes);
			byDistance.sort(Comparator.comparing(s -> s.self().getId().distance(target)));
			// chain: byDistance[i] knows only byDistance[i-1] (one step closer)
			for (int i = 1; i < byDistance.size(); i++) {
				byDistance.get(i).routingTable().update(byDistance.get(i - 1).self());
			}

			KademliaService farthest = byDistance.get(byDistance.size() - 1);
			List<Contact> result = farthest.nodeLookup(target);
			check("multi-hop lookup reaches owner",
					!result.isEmpty() && result.get(0).getId().equals(target));
			check("multi-hop result sorted", isSortedByDistanceTo(result, target));

			// A value stored at the owner is retrievable across the chain.
			byte[] payload = "across-the-chain".getBytes(StandardCharsets.UTF_8);
			nodes.get(0).storeValue(target, payload);
			byte[] got = farthest.findValue(target);
			check("multi-hop findValue retrieves stored bytes",
					got != null && new String(got, StandardCharsets.UTF_8).equals("across-the-chain"));
		} finally {
			nodes.forEach(s -> { });
			network.values().forEach(InMemoryTransport::close);
			delivery.shutdownNow();
		}
	}

	/** The RPC timeout path, isolated from OS networking via a silent (drop) transport. */
	private static void timeoutChecks() {
		Map<Integer, InMemoryTransport> network = new ConcurrentHashMap<>();
		ExecutorService delivery = Executors.newCachedThreadPool(daemonFactory());
		try {
			InMemoryTransport t = new InMemoryTransport("mem", 1, network, delivery);
			KademliaService s = new KademliaService("mem", 1, t);
			s.start();

			long start = System.currentTimeMillis();
			boolean alive = s.ping(new Contact("mem", 999)); // 999 not registered -> dropped
			long elapsed = System.currentTimeMillis() - start;

			check("ping to silent node fails", !alive);
			check("ping fails near the RPC timeout",
					elapsed >= KademliaService.RPC_TIMEOUT_MS && elapsed < KademliaService.RPC_TIMEOUT_MS + 1500);
		} finally {
			network.values().forEach(InMemoryTransport::close);
			delivery.shutdownNow();
		}
	}

	/** End-to-end over real UDP sockets (ephemeral ports to avoid bind clashes). */
	private static void rpcChecks() throws Exception {
		int n = 6;
		List<KademliaService> nodes = new ArrayList<>();
		List<UdpTransport> transports = new ArrayList<>();
		try {
			for (int i = 0; i < n; i++) {
				UdpTransport t = new UdpTransport(0); // 0 == OS-assigned ephemeral port
				KademliaService s = new KademliaService("127.0.0.1", t.getLocalPort(), t);
				s.start();
				transports.add(t);
				nodes.add(s);
			}
			Contact bootstrap = nodes.get(0).self();
			for (int i = 1; i < n; i++) {
				nodes.get(i).bootstrap(bootstrap);
			}

			check("join populates routing table", nodes.get(3).routingTable().size() > 0);

			NodeId target = nodes.get(5).self().getId();
			List<Contact> closest = nodes.get(3).nodeLookup(target);
			check("nodeLookup nearest is target", !closest.isEmpty() && closest.get(0).getId().equals(target));

			check("ping live node", nodes.get(2).ping(nodes.get(4).self()));

			NodeId key = NodeId.fromBytes("infohash".getBytes(StandardCharsets.UTF_8));
			nodes.get(1).storeValue(key, "chunk".getBytes(StandardCharsets.UTF_8));
			byte[] got = nodes.get(4).findValue(key);
			check("findValue across network", got != null && new String(got, StandardCharsets.UTF_8).equals("chunk"));
			check("missing key returns null",
					nodes.get(4).findValue(NodeId.fromBytes("nope".getBytes(StandardCharsets.UTF_8))) == null);
		} finally {
			transports.forEach(UdpTransport::close);
		}
	}

	// ---------------------------------------------------------------- helpers

	private interface Group {
		void run() throws Exception;
	}

	private static void runGroup(String name, Group group) {
		System.out.println("-- " + name + " --");
		try {
			group.run();
		} catch (Throwable t) {
			failures.add(name + " group crashed: " + t);
			System.out.println("GROUP CRASH: " + t);
		}
	}

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("ok: " + name);
		} else {
			failures.add(name);
			System.out.println("FAIL: " + name);
		}
	}

	/** Passes iff {@code action} throws — used to assert hostile input is rejected. */
	private static void checkThrows(String name, Runnable action) {
		try {
			action.run();
			failures.add(name + " (did not throw)");
			System.out.println("FAIL: " + name + " (did not throw)");
		} catch (Throwable expected) {
			passed++;
			System.out.println("ok: " + name + " (threw " + expected.getClass().getSimpleName() + ")");
		}
	}

	private static boolean isSortedByDistanceTo(List<Contact> contacts, NodeId target) {
		for (int i = 1; i < contacts.size(); i++) {
			if (contacts.get(i - 1).getId().distance(target)
					.compareTo(contacts.get(i).getId().distance(target)) > 0) {
				return false;
			}
		}
		return true;
	}

	/** Header + a single declared length/count field, with no payload following it. */
	private static byte[] craftPacket(MessageType type, int declared, boolean isContactCount) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(type.ordinal());
			out.writeLong(1L);
			out.write(new byte[20]); // senderId
			out.writeUTF("127.0.0.1");
			out.writeInt(1234); // senderPort
			out.writeInt(declared); // value length or contact count
		}
		return bytes.toByteArray();
	}

	private static java.util.concurrent.ThreadFactory daemonFactory() {
		return r -> {
			Thread t = new Thread(r, "kad-test-delivery");
			t.setDaemon(true);
			return t;
		};
	}
}
