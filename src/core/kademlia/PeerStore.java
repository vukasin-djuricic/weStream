package core.kademlia;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BEP 5-style swarm membership: a bounded, TTL'd <em>set</em> of peer endpoints
 * per key, accumulated by UNION (not overwrite) so many seeds can advertise the
 * same file. This is what makes real multi-peer swarming possible — the generic
 * {@code KademliaService.store} is one-value-per-key (latest wins), which can
 * only ever name a single seed.
 *
 * <p>Kept separate from the generic store and reached through the existing
 * STORE / FIND_VALUE RPCs (the announce/set bytes carry a one-byte tag so the
 * handler can tell a peer announce from a plain value). Every entry has an
 * independent expiry, so dead peers age out on their own — there is no
 * read-modify-write race (the union happens server-side under this store's lock).
 *
 * <p>All mutation is {@code synchronized} (blueprint rule #3); the value codecs
 * are hand-rolled and length-bounded like {@link MessageCodec} (no OOM on a
 * hostile announce). Pure JDK.
 */
public final class PeerStore {

	/** How long a single announce keeps a peer in the swarm before it must refresh. */
	public static final long PEER_TTL_MS = 30 * 60 * 1000L; // 30 min
	/** Cap on peers held per key (bounds a flooding announcer; freshest are kept). */
	public static final int MAX_PEERS_PER_KEY = 64;
	/** Cap on distinct swarm keys (mirrors {@code KademliaService.MAX_STORE_KEYS}). */
	public static final int MAX_PEER_KEYS = 1024;

	/** A single peer endpoint. Equality is by (host, port) — the record default. */
	public record PeerEntry(String host, int port) {
	}

	/** Tag byte prefixing a single-peer announce (STORE value). */
	private static final byte TAG_ANNOUNCE = 0x01;
	/** Tag byte prefixing a serialized peer set (FIND_VALUE / VALUE payload). */
	private static final byte TAG_SET = 0x02;

	/** key -> (peer -> expiry epoch-ms). */
	private final Map<NodeId, Map<PeerEntry, Long>> swarms = new HashMap<>();

	/**
	 * Add {@code peer} to {@code key}'s swarm (or refresh its TTL if already
	 * present). Prunes expired entries first; if the key is new and we are at the
	 * key cap, the announce is dropped; if the swarm is full of <em>other</em>
	 * peers, the soonest-to-expire is evicted to make room for the fresher one.
	 */
	public synchronized void announce(NodeId key, PeerEntry peer, long nowMs) {
		Map<PeerEntry, Long> swarm = swarms.get(key);
		if (swarm == null) {
			if (swarms.size() >= MAX_PEER_KEYS) {
				return; // at the key cap and this is a new key — drop
			}
			swarm = new HashMap<>();
			swarms.put(key, swarm);
		}
		swarm.values().removeIf(expiry -> expiry <= nowMs); // prune dead first
		if (!swarm.containsKey(peer) && swarm.size() >= MAX_PEERS_PER_KEY) {
			evictSoonestToExpire(swarm);
		}
		swarm.put(peer, nowMs + PEER_TTL_MS); // insert or refresh
	}

	/** Live (non-expired) peers for {@code key}; prunes expired entries as a side effect. */
	public synchronized List<PeerEntry> peers(NodeId key, long nowMs) {
		Map<PeerEntry, Long> swarm = swarms.get(key);
		if (swarm == null) {
			return List.of();
		}
		swarm.values().removeIf(expiry -> expiry <= nowMs);
		if (swarm.isEmpty()) {
			swarms.remove(key);
			return List.of();
		}
		return new ArrayList<>(swarm.keySet());
	}

	/** Number of keys with at least one (possibly stale) peer — diagnostics/UI. */
	public synchronized int swarmCount() {
		return swarms.size();
	}

	private static void evictSoonestToExpire(Map<PeerEntry, Long> swarm) {
		PeerEntry soonest = null;
		long min = Long.MAX_VALUE;
		for (Map.Entry<PeerEntry, Long> e : swarm.entrySet()) {
			if (e.getValue() < min) {
				min = e.getValue();
				soonest = e.getKey();
			}
		}
		if (soonest != null) {
			swarm.remove(soonest);
		}
	}

	// --------------------------------------------------------------- value codec

	/** Tagged STORE value announcing a single peer: {@code [TAG_ANNOUNCE][utf host][i32 port]}. */
	public static byte[] tagAnnounce(String host, int port) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(TAG_ANNOUNCE);
			out.writeUTF(host);
			out.writeInt(port);
		} catch (IOException e) {
			throw new UncheckedIOException(e); // never on a byte array
		}
		return bytes.toByteArray();
	}

	/** True if {@code value} is a peer announce (carries {@link #TAG_ANNOUNCE}). */
	public static boolean isPeerAnnounce(byte[] value) {
		return value != null && value.length >= 1 && value[0] == TAG_ANNOUNCE;
	}

	/** Decode a {@link #tagAnnounce} value; throws on malformed/hostile bytes. */
	public static PeerEntry decodeAnnounce(byte[] value) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(value))) {
			byte tag = in.readByte();
			if (tag != TAG_ANNOUNCE) {
				throw new IOException("not a peer announce");
			}
			String host = in.readUTF();
			int port = in.readInt();
			if (port < 0 || port > 0xFFFF) {
				throw new IOException("bad port " + port);
			}
			return new PeerEntry(host, port);
		} catch (IOException e) {
			throw new UncheckedIOException("malformed peer announce", e);
		}
	}

	/** Tagged FIND_VALUE payload for a whole swarm: {@code [TAG_SET][i32 n][n×(utf host,i32 port)]}. */
	public static byte[] tagSet(List<PeerEntry> peers) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(TAG_SET);
			int n = Math.min(peers.size(), MAX_PEERS_PER_KEY);
			out.writeInt(n);
			for (int i = 0; i < n; i++) {
				PeerEntry p = peers.get(i);
				out.writeUTF(p.host());
				out.writeInt(p.port());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e); // never on a byte array
		}
		return bytes.toByteArray();
	}

	/** True if {@code value} is a serialized peer set (carries {@link #TAG_SET}). */
	public static boolean isPeerSet(byte[] value) {
		return value != null && value.length >= 1 && value[0] == TAG_SET;
	}

	/** Decode a {@link #tagSet} payload; bounds {@code n} so a hostile set cannot OOM. */
	public static List<PeerEntry> decodeSet(byte[] value) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(value))) {
			byte tag = in.readByte();
			if (tag != TAG_SET) {
				throw new IOException("not a peer set");
			}
			int n = in.readInt();
			if (n < 0 || n > MAX_PEERS_PER_KEY) {
				throw new IOException("bad peer count " + n);
			}
			List<PeerEntry> out = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				String host = in.readUTF();
				int port = in.readInt();
				out.add(new PeerEntry(host, port));
			}
			return out;
		} catch (IOException e) {
			throw new UncheckedIOException("malformed peer set", e);
		}
	}
}
