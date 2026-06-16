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
import java.util.concurrent.ExecutorService;
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
	/** Tries per single-target RPC (1 retry) to ride out transient UDP packet loss. */
	public static final int RPC_ATTEMPTS = 2;
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
	/** Off-thread eviction probes: PING a full bucket's LRS candidate without blocking the receive thread. */
	private final ExecutorService evictionProbes =
			Executors.newSingleThreadExecutor(daemon("kad-eviction"));

	/** Local key/value store backing STORE / FIND_VALUE (LRU-bounded; see {@link BoundedStore}). */
	private final BoundedStore store = new BoundedStore(MAX_STORE_KEYS);

	/** BEP 5-style swarm membership (peer sets, UNION + TTL), keyed by {@link #peerSetKey}. */
	private final PeerStore peerStore = new PeerStore();

	/** Recent RPC activity (inbound + outbound), for the Phase-5 DHT inspector. */
	private final RpcLog rpcLog = new RpcLog(256);

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

	/** Number of key/value pairs currently held in the local DHT store (diagnostics/UI). */
	public int storedKeyCount() {
		return store.size();
	}

	/** Snapshot of the keys currently stored locally (diagnostics/UI; drives the DHT inspector). */
	public List<NodeId> storedKeys() {
		return store.keys();
	}

	/** Snapshot of recent RPC activity (oldest → newest), for the DHT inspector's live log. */
	public List<RpcLog.Event> rpcEvents() {
		return rpcLog.snapshot();
	}

	// ----------------------------------------------------------------- inbound

	private void onRaw(Contact wireSource, byte[] data) {
		Message msg;
		try {
			msg = codec.decode(data);
		} catch (RuntimeException malformed) {
			return; // drop garbage rather than crash the receive loop
		}

		rpcLog.add(new RpcLog.Event(System.currentTimeMillis(), false, msg.type.name(),
				peerLabel(wireSource), detailOf(msg)));

		// Learn the address we ACTUALLY heard from (the wire source), never the
		// self-reported endpoint in the payload — a peer could spoof that to poison
		// our table or make itself unreachable. (Assumes the transport's source
		// address is the peer's reachable address; a NAT transport must guarantee this.)
		Contact evictionCandidate = routingTable.update(wireSource);
		if (evictionCandidate != null) {
			// Bucket is full: Kademlia probes the least-recently-seen contact and only
			// replaces it if it's dead (favouring long-lived nodes). ping() blocks, so
			// it must NOT run on this receive thread — hand it to the eviction executor.
			evictionProbes.execute(() -> {
				if (!ping(evictionCandidate)) {
					routingTable.replaceInBucketFor(evictionCandidate.getId(), wireSource);
				}
				// alive → candidate keeps its slot (its PONG refreshes it), wireSource dropped
			});
		}

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
				if (PeerStore.isPeerAnnounce(req.value)) {
					try {
						// Anti-spoof: record the host we ACTUALLY heard from (wire source),
						// not the self-reported one, so a peer can only announce itself.
						PeerStore.PeerEntry e = PeerStore.decodeAnnounce(req.value);
						peerStore.announce(req.target,
								new PeerStore.PeerEntry(replyTo.getHost(), e.port()), now());
					} catch (RuntimeException malformed) {
						// drop a malformed announce; still ack below so the sender's await completes
					}
				} else {
					store.put(req.target, req.value); // BoundedStore self-caps (LRU)
				}
				reply(replyTo, Message.stored(self, req.txId));
			}
			case FIND_VALUE -> {
				byte[] held = store.get(req.target);
				List<PeerStore.PeerEntry> swarm = peerStore.peers(req.target, now());
				if (held != null) {
					reply(replyTo, Message.value(self, req.txId, held));
				} else if (!swarm.isEmpty()) {
					reply(replyTo, Message.value(self, req.txId, PeerStore.tagSet(swarm)));
				} else {
					reply(replyTo,
							Message.nodes(self, req.txId, routingTable.findClosest(req.target, RoutingTable.K)));
				}
			}
			default -> { /* responses are handled in onRaw; nothing else to do */ }
		}
	}

	private void reply(Contact to, Message msg) {
		rpcLog.add(new RpcLog.Event(System.currentTimeMillis(), true, msg.type.name(),
				peerLabel(to), detailOf(msg)));
		transport.send(to, codec.encode(msg));
	}

	/** Short {@code id@host} label for the RPC log (first 8 hex of the id). */
	private static String peerLabel(Contact c) {
		return c.getId().toString().substring(0, 8) + "@" + c.getHost();
	}

	/** Tiny human hint for the RPC log's result column. */
	private static String detailOf(Message m) {
		if (m.contacts != null) {
			return m.contacts.size() + " contacts";
		}
		if (m.value != null) {
			return m.value.length + " B";
		}
		return "";
	}

	// ---------------------------------------------------------------- outbound

	private CompletableFuture<Message> rpc(Contact to, Message request) {
		rpcLog.add(new RpcLog.Event(System.currentTimeMillis(), true, request.type.name(),
				peerLabel(to), detailOf(request)));
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

	/**
	 * Send a single RPC to {@code to} with up to {@link #RPC_ATTEMPTS} tries,
	 * recovering from transient UDP packet loss. Each attempt uses a FRESH txId
	 * (the {@code builder} takes it) — reusing one would let a prior attempt's
	 * scheduled timeout cancel a later attempt's pending future. Used for
	 * single-target RPCs (ping, replication); iterative lookups get their
	 * redundancy from querying ALPHA nodes instead.
	 */
	private Message awaitRetrying(Contact to, java.util.function.LongFunction<Message> builder) throws Exception {
		Exception last = null;
		for (int attempt = 0; attempt < RPC_ATTEMPTS; attempt++) {
			try {
				return await(rpc(to, builder.apply(nextTx())));
			} catch (Exception lossOrTimeout) {
				last = lossOrTimeout; // retry with a fresh txId
			}
		}
		throw last;
	}

	private long nextTx() {
		return txCounter.incrementAndGet();
	}

	/** Liveness probe used for k-bucket eviction. Returns true if the node replied. */
	public boolean ping(Contact to) {
		try {
			awaitRetrying(to, tx -> Message.ping(self, tx));
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
		store.put(key, value); // local copy — LRU-bounded like the inbound path (no longer unbounded)
		for (Contact c : nodeLookup(key)) {
			try {
				awaitRetrying(c, tx -> Message.store(self, tx, key, value));
			} catch (Exception ignored) {
				// best-effort replication; a missed replica is not fatal
			}
		}
	}

	/**
	 * Retrieve a value by key with a proper <em>iterative</em> FIND_VALUE: check
	 * locally, then walk toward the key sending FIND_VALUE (not FIND_NODE) and
	 * short-circuit on the first holder. A node without the value replies with its
	 * closest contacts (NODES), which are merged into the shortlist so the search
	 * hops closer each round — unlike delegating to {@link #nodeLookup} (FIND_NODE
	 * only), this reaches a holder even when it is not among the originator's k
	 * closest nodes. Returns the value bytes, or {@code null} if not found.
	 * Application thread only.
	 */
	public byte[] findValue(NodeId key) {
		byte[] local = store.get(key);
		if (local != null) {
			return local;
		}

		Comparator<Contact> byDistance = Comparator.comparing(c -> c.getId().distance(key));
		Map<NodeId, Contact> known = new LinkedHashMap<>();
		for (Contact c : routingTable.findClosest(key, RoutingTable.K)) {
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
				return null; // the k closest have all been queried, no holder found
			}

			List<CompletableFuture<Message>> inflight = new ArrayList<>();
			for (Contact c : batch) {
				queried.add(c.getId());
				inflight.add(rpc(c, Message.findValue(self, nextTx(), key)));
			}
			for (CompletableFuture<Message> f : inflight) {
				try {
					Message resp = await(f);
					if (resp.type == MessageType.VALUE) {
						// TODO(phase4-hardening): optional cache-on-read here (store.put(key, resp.value))
						// so reads survive the holder dying — but ONLY via a bounded store (LRU + TTL),
						// else heavy readers grow memory unbounded. See CLAUDE.md "Known engine gaps".
						return resp.value; // first holder wins
					}
					if (resp.contacts != null) {
						for (Contact c : resp.contacts) {
							if (!c.getId().equals(self.getId())) {
								known.putIfAbsent(c.getId(), c);
							}
						}
					}
				} catch (Exception timedOutOrDead) {
					// a dead node contributes nothing; keep it marked queried
				}
			}
		}
	}

	// --------------------------------------------------------- swarm membership

	/**
	 * The DHT key under which a file's <em>peer set</em> lives — a derived id,
	 * {@code SHA-1(infohash ‖ "peers")}, kept distinct from the {@code infohash}
	 * key that holds the (content-identical) metadata. Splitting the two lets
	 * metadata stay overwrite-safe while peers accumulate by union.
	 */
	public static NodeId peerSetKey(NodeId infohash) {
		byte[] id = infohash.toBytes();
		byte[] tag = "peers".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] both = new byte[id.length + tag.length];
		System.arraycopy(id, 0, both, 0, id.length);
		System.arraycopy(tag, 0, both, id.length, tag.length);
		return NodeId.fromBytes(both);
	}

	/**
	 * Join {@code infohash}'s swarm: announce {@code (host, port)} (this node's
	 * transfer endpoint) at the k nodes closest to its peer-set key, and keep a
	 * local copy. Idempotent and TTL-refreshing — call periodically to stay in the
	 * swarm. Application thread only (drives a blocking lookup).
	 */
	public void announcePeer(NodeId infohash, String host, int port) {
		NodeId key = peerSetKey(infohash);
		byte[] value = PeerStore.tagAnnounce(host, port);
		peerStore.announce(key, new PeerStore.PeerEntry(host, port), now());
		for (Contact c : nodeLookup(key)) {
			try {
				awaitRetrying(c, tx -> Message.store(self, tx, key, value));
			} catch (Exception ignored) {
				// best-effort; a missed announce is refreshed on the next period
			}
		}
	}

	/**
	 * All live peers seeding {@code infohash}, UNION-merged across the k closest
	 * holders plus this node's local view. Unlike a single FIND_VALUE, merging
	 * every holder's set means a peer that announced to a different subset still
	 * shows up. Application thread only.
	 */
	public List<PeerStore.PeerEntry> getPeers(NodeId infohash) {
		NodeId key = peerSetKey(infohash);
		Set<PeerStore.PeerEntry> merged = new java.util.LinkedHashSet<>(peerStore.peers(key, now()));
		for (Contact c : nodeLookup(key)) {
			try {
				Message resp = await(rpc(c, Message.findValue(self, nextTx(), key)));
				if (resp.type == MessageType.VALUE && PeerStore.isPeerSet(resp.value)) {
					merged.addAll(PeerStore.decodeSet(resp.value));
				}
			} catch (Exception ignored) {
				// skip a dead/garbage holder; the others still contribute
			}
		}
		return new ArrayList<>(merged);
	}

	/** Number of swarm keys this node currently tracks (diagnostics/UI). */
	public int swarmCount() {
		return peerStore.swarmCount();
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	private static ThreadFactory daemon(String name) {
		return r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		};
	}
}
