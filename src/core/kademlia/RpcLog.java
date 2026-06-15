package core.kademlia;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A small bounded, thread-safe ring of recent RPC events — the data behind the
 * Phase-5 DHT inspector's "RPC activity" feed. Pure JDK. Both the receive thread
 * (inbound messages) and application threads (outbound requests/replies) append
 * concurrently, so every method is {@code synchronized}; the oldest event is
 * dropped once {@code capacity} is reached.
 */
public final class RpcLog {

	/**
	 * One logged RPC message. {@code outbound} is true for messages this node sent,
	 * false for ones it received; {@code peer} is the short {@code id@host} of the
	 * other end; {@code detail} is a tiny hint ({@code "N contacts"}, byte count, …)
	 * or empty.
	 */
	public record Event(long timeMillis, boolean outbound, String type, String peer, String detail) {
	}

	private final int capacity;
	private final ArrayDeque<Event> events;

	public RpcLog(int capacity) {
		this.capacity = capacity;
		this.events = new ArrayDeque<>(capacity);
	}

	public synchronized void add(Event e) {
		if (events.size() >= capacity) {
			events.pollFirst();
		}
		events.addLast(e);
	}

	/** Snapshot, oldest → newest. */
	public synchronized List<Event> snapshot() {
		return new ArrayList<>(events);
	}
}
