package core.kademlia;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A live Kademlia node: wires a {@link RoutingTable} to a {@link Transport},
 * answers incoming RPCs, and drives outgoing RPCs / iterative lookups.
 *
 * <h2>Threading</h2>
 * Inbound messages arrive on the transport's receive thread ({@link #onRaw}).
 * Request handlers there are non-blocking (a table query + a reply send), and
 * responses simply complete a pending {@link CompletableFuture}. The blocking
 * operations — {@link #nodeLookup}, {@link #ping}, {@link #findValue} — MUST be
 * called from an application thread, never from the receive thread: they wait
 * on futures that are only completed by that very thread, so calling them on it
 * would deadlock. RPC timeouts are enforced off-thread by a scheduler, so no
 * thread ever busy-waits (blueprint rule #4).
 */
public class KademliaService {

	/** Lookup concurrency: how many nodes we query in parallel per round. */
	public static final int ALPHA = 3;
	public static final long RPC_TIMEOUT_MS = 2000;
	/** Cap on locally stored keys, to bound memory against STORE flooding. */
	public static final int MAX_STORE_KEYS = 4096;

	private final Contact self;
	private final RoutingTable routingTable;
	private final Transport transport;
	private final MessageCodec codec = new MessageCodec();

	private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();
	private final AtomicLong txCounter = new AtomicLong();
	private final ScheduledExecutorService scheduler =
			Executors.newSingleThreadScheduledExecutor(daemon("kad-rpc-timeout"));

	/** Local key/value store backing STORE / FIND_VALUE. */
	private final Map<NodeId, byte[]> store = new ConcurrentHashMap<>();

	public KademliaService(String host, int port, Transport transport) {
		this.self = new Contact(host, port);
		this.routingTable = new RoutingTable(self.getId());
		this.transport = transport;
		this.transport.setReceiveHandler(this::onRaw);
	}

	public void start() {
		transport.start();
	}

	public Contact self() {
		return self;
	}

	public RoutingTable routingTable() {
		return routingTable;
	}

	// ----------------------------------------------------------------- inbound

	private void onRaw(Contact wireSource, byte[] data) {
		Message msg;
		try {
			msg = codec.decode(data);
		} catch (RuntimeException malformed) {
			return; // drop garbage rather than crash the receive loop
		}

		// Learn the address we ACTUALLY heard from (the wire source), never the
		// self-reported endpoint in the payload — a peer could spoof that to poison
		// our table or make itself unreachable. (Assumes the transport's source
		// address is the peer's reachable address; a NAT transport must guarantee this.)
		routingTable.update(wireSource);

		if (msg.type.isResponse()) {
			CompletableFuture<Message> waiter = pending.remove(msg.txId);
			if (waiter != null) {
				waiter.complete(msg);
			}
		} else {
			handleRequest(wireSource, msg);
		}
	}

	private void handleRequest(Contact replyTo, Message req) {
		switch (req.type) {
			case PING -> reply(replyTo, Message.pong(self, req.txId));
			case FIND_NODE -> reply(replyTo,
					Message.nodes(self, req.txId, routingTable.findClosest(req.target, RoutingTable.K)));
			case STORE -> {
				if (store.size() < MAX_STORE_KEYS || store.containsKey(req.target)) {
					store.put(req.target, req.value);
				}
				reply(replyTo, Message.stored(self, req.txId));
			}
			case FIND_VALUE -> {
				byte[] held = store.get(req.target);
				if (held != null) {
					reply(replyTo, Message.value(self, req.txId, held));
				} else {
					reply(replyTo,
							Message.nodes(self, req.txId, routingTable.findClosest(req.target, RoutingTable.K)));
				}
			}
			default -> { /* responses are handled in onRaw; nothing else to do */ }
		}
	}

	private void reply(Contact to, Message msg) {
		transport.send(to, codec.encode(msg));
	}

	// ---------------------------------------------------------------- outbound

	private CompletableFuture<Message> rpc(Contact to, Message request) {
		CompletableFuture<Message> future = new CompletableFuture<>();
		pending.put(request.txId, future);
		scheduler.schedule(() -> {
			CompletableFuture<Message> stale = pending.remove(request.txId);
			if (stale != null) {
				stale.completeExceptionally(new TimeoutException("RPC " + request.type + " to " + to));
			}
		}, RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		transport.send(to, codec.encode(request));
		return future;
	}

	private Message await(CompletableFuture<Message> future) throws Exception {
		// Generous get-timeout; the scheduler above is the real deadline.
		return future.get(RPC_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
	}

	private long nextTx() {
		return txCounter.incrementAndGet();
	}

	/** Liveness probe used for k-bucket eviction. Returns true if the node replied. */
	public boolean ping(Contact to) {
		try {
			await(rpc(to, Message.ping(self, nextTx())));
			return true;
		} catch (Exception dead) {
			return false;
		}
	}

	/** Join the network through a known peer, then self-lookup to fill our buckets. */
	public void bootstrap(Contact knownPeer) {
		routingTable.update(knownPeer);
		nodeLookup(self.getId());
	}

	/**
	 * Iterative node lookup: find the k closest nodes to {@code target}. Each
	 * round fires FIND_NODE at the {@code ALPHA} closest not-yet-queried nodes,
	 * merges their returned contacts, and repeats until the k closest known
	 * nodes have all been queried. Must run on an application thread.
	 */
	public List<Contact> nodeLookup(NodeId target) {
		Comparator<Contact> byDistance = Comparator.comparing(c -> c.getId().distance(target));

		// Insertion-order map doubles as a de-dup set of everyone we've heard of.
		Map<NodeId, Contact> known = new LinkedHashMap<>();
		for (Contact c : routingTable.findClosest(target, RoutingTable.K)) {
			known.put(c.getId(), c);
		}
		Set<NodeId> queried = new HashSet<>();

		while (true) {
			List<Contact> shortlist = new ArrayList<>(known.values());
			shortlist.sort(byDistance);
			if (shortlist.size() > RoutingTable.K) {
				shortlist = shortlist.subList(0, RoutingTable.K);
			}

			List<Contact> batch = new ArrayList<>();
			for (Contact c : shortlist) {
				if (!queried.contains(c.getId())) {
					batch.add(c);
					if (batch.size() == ALPHA) {
						break;
					}
				}
			}
			if (batch.isEmpty()) {
				break; // the k closest have all been queried — converged
			}

			List<CompletableFuture<Message>> inflight = new ArrayList<>();
			for (Contact c : batch) {
				queried.add(c.getId());
				inflight.add(rpc(c, Message.findNode(self, nextTx(), target)));
			}
			for (CompletableFuture<Message> f : inflight) {
				try {
					Message resp = await(f);
					if (resp.contacts != null) {
						for (Contact c : resp.contacts) {
							if (!c.getId().equals(self.getId())) {
								known.putIfAbsent(c.getId(), c);
							}
						}
					}
				} catch (Exception timedOutOrDead) {
					// Leave it marked queried; a dead node simply contributes nothing.
				}
			}
		}

		List<Contact> result = new ArrayList<>(known.values());
		result.sort(byDistance);
		return result.size() > RoutingTable.K
				? new ArrayList<>(result.subList(0, RoutingTable.K))
				: result;
	}

	/** Store a value at the k nodes closest to its key. Must run on an application thread. */
	public void storeValue(NodeId key, byte[] value) {
		// TODO(phase4-hardening): this local put bypasses the MAX_STORE_KEYS cap
		// (that cap only guards the inbound STORE handler), so a heavy writer grows
		// `store` unbounded. Route all writes through a bounded store (LRU + TTL) and
		// add republish. See CLAUDE.md "Known engine gaps".
		store.put(key, value); // keep a copy locally too
		for (Contact c : nodeLookup(key)) {
			try {
				await(rpc(c, Message.store(self, nextTx(), key, value)));
			} catch (Exception ignored) {
				// best-effort replication; a missed replica is not fatal
			}
		}
	}

	/**
	 * Retrieve a value by key: check locally, then ask the k closest nodes.
	 * Returns the value bytes, or {@code null} if not found. Application thread.
	 */
	public byte[] findValue(NodeId key) {
		byte[] local = store.get(key);
		if (local != null) {
			return local;
		}
		for (Contact c : nodeLookup(key)) {
			try {
				Message resp = await(rpc(c, Message.findValue(self, nextTx(), key)));
				if (resp.type == MessageType.VALUE) {
					// TODO(phase4-hardening): optional cache-on-read here (store.put(key, resp.value))
					// so reads survive the holder dying — but ONLY via a bounded store (LRU + TTL),
					// else heavy readers grow memory unbounded. See CLAUDE.md "Known engine gaps".
					return resp.value;
				}
			} catch (Exception ignored) {
				// try the next closest node
			}
		}
		return null;
	}

	private static ThreadFactory daemon(String name) {
		return r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		};
	}
}
