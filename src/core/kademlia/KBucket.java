package core.kademlia;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * A single Kademlia k-bucket: an ordered list of at most {@code capacity}
 * contacts, kept from least-recently-seen (head) to most-recently-seen (tail).
 *
 * <p>Kademlia's eviction policy favours long-lived nodes: when a new contact
 * arrives and the bucket is full, the least-recently-seen contact is offered as
 * an eviction <em>candidate</em> rather than dropped outright. The caller is
 * expected to PING that candidate and only {@link #replaceLeastRecentlySeen}
 * it if it fails to respond. This is what gives Kademlia its churn resilience.
 *
 * <p>All operations are {@code synchronized} because the routing table is read
 * and written by multiple handler threads concurrently (blueprint rule #3).
 */
public class KBucket {

	private final int capacity;
	/** Head = least-recently-seen, tail = most-recently-seen. */
	private final LinkedList<Contact> contacts = new LinkedList<>();

	public KBucket(int capacity) {
		this.capacity = capacity;
	}

	/**
	 * Record that {@code contact} was just seen.
	 * <ul>
	 *   <li>Already present: move it to the tail (most-recently-seen).</li>
	 *   <li>Absent with room: append it at the tail.</li>
	 *   <li>Absent and full: leave the bucket unchanged and return the
	 *       least-recently-seen contact as an eviction candidate.</li>
	 * </ul>
	 *
	 * @return {@code null} if the contact was inserted or refreshed; otherwise
	 *         the least-recently-seen contact the caller should probe.
	 */
	public synchronized Contact update(Contact contact) {
		int idx = indexOf(contact);
		if (idx >= 0) {
			Contact existing = contacts.remove(idx);
			existing.touch();
			contacts.addLast(existing);
			return null;
		}
		if (contacts.size() < capacity) {
			contacts.addLast(contact);
			return null;
		}
		return contacts.peekFirst();
	}

	/**
	 * Drop the least-recently-seen contact (head) and append {@code replacement}.
	 * Call this only after the head has been confirmed unresponsive.
	 */
	public synchronized void replaceLeastRecentlySeen(Contact replacement) {
		if (!contacts.isEmpty()) {
			contacts.removeFirst();
		}
		contacts.addLast(replacement);
	}

	public synchronized void remove(Contact contact) {
		int idx = indexOf(contact);
		if (idx >= 0) {
			contacts.remove(idx);
		}
	}

	/** Snapshot copy of the contacts, head-first (least-recently-seen first). */
	public synchronized List<Contact> contacts() {
		return new ArrayList<>(contacts);
	}

	public synchronized int size() {
		return contacts.size();
	}

	private int indexOf(Contact c) {
		for (int i = 0; i < contacts.size(); i++) {
			if (contacts.get(i).getId().equals(c.getId())) {
				return i;
			}
		}
		return -1;
	}
}
