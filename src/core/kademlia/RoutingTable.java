package core.kademlia;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A Kademlia routing table: one {@link KBucket} per bit of the id space,
 * indexed by XOR-distance bucket index relative to this node's own id.
 *
 * <p>Bucket {@code i} holds contacts whose highest differing bit from
 * {@code selfId} is at position {@code i}. Far-away nodes (high bucket index)
 * share fewer prefix bits with us, near nodes (low index) share more — so the
 * table naturally keeps detailed knowledge of the id space near us and coarse
 * knowledge far away, which is what bounds lookups to O(log n) hops.
 */
public class RoutingTable {

	/** Default bucket size (Kademlia's "k" replication parameter). */
	public static final int K = 20;

	private final NodeId selfId;
	private final KBucket[] buckets;

	public RoutingTable(NodeId selfId) {
		this(selfId, K);
	}

	public RoutingTable(NodeId selfId, int k) {
		this.selfId = selfId;
		this.buckets = new KBucket[NodeId.ID_BITS];
		for (int i = 0; i < buckets.length; i++) {
			buckets[i] = new KBucket(k);
		}
	}

	/**
	 * Record a sighting of {@code contact}. Delegates to the relevant bucket's
	 * least-recently-seen policy.
	 *
	 * @return {@code null} if inserted/refreshed, or the eviction candidate the
	 *         caller should PING (see {@link KBucket#update}). Self is ignored.
	 */
	public Contact update(Contact contact) {
		if (contact.getId().equals(selfId)) {
			return null; // never store ourselves
		}
		int idx = selfId.bucketIndex(contact.getId());
		if (idx < 0) {
			return null; // distance 0 == self, already handled above
		}
		return buckets[idx].update(contact);
	}

	public void replaceInBucketFor(NodeId deadId, Contact replacement) {
		int idx = selfId.bucketIndex(deadId);
		if (idx >= 0) {
			buckets[idx].replaceLeastRecentlySeen(replacement);
		}
	}

	/**
	 * The up-to-{@code count} known contacts closest to {@code target} by XOR
	 * distance. This is the core query behind FIND_NODE / FIND_VALUE and the
	 * iterative lookup driver.
	 */
	public List<Contact> findClosest(NodeId target, int count) {
		List<Contact> all = allContacts();
		all.sort(Comparator.comparing((Contact c) -> c.getId().distance(target)));
		return all.size() > count ? new ArrayList<>(all.subList(0, count)) : all;
	}

	/**
	 * Per-bucket occupancy snapshot, indexed by bucket index
	 * {@code 0..NodeId.ID_BITS-1}. Read-only diagnostics view (drives the Phase-5
	 * DHT-inspector k-bucket bars); does not expose or mutate the buckets.
	 */
	public int[] bucketSizes() {
		int[] sizes = new int[buckets.length];
		for (int i = 0; i < buckets.length; i++) {
			sizes[i] = buckets[i].size();
		}
		return sizes;
	}

	/** Flat snapshot of every contact currently in the table. */
	public List<Contact> allContacts() {
		List<Contact> all = new ArrayList<>();
		for (KBucket bucket : buckets) {
			all.addAll(bucket.contacts());
		}
		return all;
	}

	public NodeId getSelfId() {
		return selfId;
	}

	public int size() {
		int total = 0;
		for (KBucket bucket : buckets) {
			total += bucket.size();
		}
		return total;
	}
}
