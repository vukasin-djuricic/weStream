package core.kademlia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The local DHT key/value store, bounded by an LRU cap so it cannot grow without
 * limit. Every write — inbound STORE <em>and</em> the originator's local copy in
 * {@code storeValue} — flows through here, closing the gap where the originator
 * path bypassed the cap (CLAUDE.md "Unbounded local store" / Security H1).
 *
 * <p>Access-order {@link LinkedHashMap}: a get or put moves a key to the most-
 * recently-used end, and the least-recently-used entry is evicted once the cap is
 * exceeded. All access is {@code synchronized} (the store is read on the receive
 * thread and written from app threads — blueprint rule #3).
 *
 * <p>No TTL: the DHT has no republish yet, so expiring entries would silently
 * drop still-wanted values. The LRU cap alone bounds memory, which is the actual
 * DoS this closes; TTL + republish stay deferred (see CLAUDE.md known gaps).
 */
final class BoundedStore {

	private final int maxKeys;
	private final LinkedHashMap<NodeId, byte[]> map;

	BoundedStore(int maxKeys) {
		this.maxKeys = maxKeys;
		this.map = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<NodeId, byte[]> eldest) {
				return size() > BoundedStore.this.maxKeys;
			}
		};
	}

	synchronized void put(NodeId key, byte[] value) {
		map.put(key, value);
	}

	synchronized byte[] get(NodeId key) {
		return map.get(key);
	}

	synchronized boolean containsKey(NodeId key) {
		return map.containsKey(key);
	}

	synchronized int size() {
		return map.size();
	}

	synchronized List<NodeId> keys() {
		return new ArrayList<>(map.keySet());
	}
}
