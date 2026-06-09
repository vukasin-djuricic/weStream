package core.kademlia;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-dependency regression checks for the Kademlia engine — run via {@code ./check.sh}.
 *
 * <p>Lives in {@code test/} (kept out of {@code src/} so it never ships in the app)
 * but in package {@code core.kademlia} so it can reach package-internal types.
 * Exits non-zero if any check fails, so it plugs straight into a git hook or CI.
 *
 * <p>Two groups: pure unit checks (identity, routing, codec) and an end-to-end
 * group that spins up real {@link UdpTransport} nodes on localhost.
 */
public class KademliaCheck {

	private static int passed = 0;
	private static final List<String> failures = new ArrayList<>();

	public static void main(String[] args) throws Exception {
		unitChecks();
		rpcChecks();

		System.out.println();
		System.out.println(passed + " passed, " + failures.size() + " failed");
		if (!failures.isEmpty()) {
			failures.forEach(f -> System.out.println("  FAILED: " + f));
			System.exit(1);
		}
		System.out.println("OK");
	}

	// -------- identity / routing / codec (fast, no sockets) --------

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
		check("toBytes is 20 bytes", a.toBytes().length == 20);
		check("toBytes round-trips", NodeId.fromValueBytes(a.toBytes()).equals(a));

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
		boolean sorted = true;
		for (int i = 1; i < closest.size(); i++) {
			if (closest.get(i - 1).getId().distance(b)
					.compareTo(closest.get(i).getId().distance(b)) > 0) {
				sorted = false;
			}
		}
		check("findClosest sorted by distance", sorted);

		KBucket bucket = new KBucket(2);
		Contact c1 = new Contact("localhost", 1200);
		Contact c2 = new Contact("localhost", 1300);
		Contact c3 = new Contact("localhost", 1400);
		check("bucket add c1", bucket.update(c1) == null);
		check("bucket add c2", bucket.update(c2) == null);
		Contact candidate = bucket.update(c3);
		check("full bucket returns LRS candidate", candidate != null && candidate.getId().equals(c1.getId()));
		bucket.update(c1); // refresh c1 -> c2 becomes LRS
		Contact candidate2 = bucket.update(c3);
		check("refresh moves LRS to c2", candidate2 != null && candidate2.getId().equals(c2.getId()));

		MessageCodec codec = new MessageCodec();
		Contact self = new Contact("127.0.0.1", 1234);
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

	// -------- end-to-end over real UDP sockets --------

	private static void rpcChecks() throws Exception {
		int base = 5100;
		int n = 6;
		List<KademliaService> nodes = new ArrayList<>();
		List<UdpTransport> transports = new ArrayList<>();
		try {
			for (int i = 0; i < n; i++) {
				UdpTransport t = new UdpTransport(base + i);
				KademliaService s = new KademliaService("127.0.0.1", base + i, t);
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
			check("ping dead node fails", !nodes.get(2).ping(new Contact("127.0.0.1", 5999)));

			NodeId key = NodeId.fromBytes("infohash".getBytes(StandardCharsets.UTF_8));
			nodes.get(1).storeValue(key, "chunk".getBytes(StandardCharsets.UTF_8));
			byte[] got = nodes.get(4).findValue(key);
			check("findValue across network", got != null && new String(got, StandardCharsets.UTF_8).equals("chunk"));
			check("missing key returns null",
					nodes.get(4).findValue(NodeId.fromBytes("nope".getBytes(StandardCharsets.UTF_8))) == null);
		} finally {
			for (UdpTransport t : transports) {
				t.close();
			}
		}
	}

	private static void check(String name, boolean cond) {
		if (cond) {
			passed++;
			System.out.println("ok: " + name);
		} else {
			failures.add(name);
			System.out.println("FAIL: " + name);
		}
	}
}
