package core.kademlia;

/**
 * The Kademlia RPC message types. Four request/response pairs:
 * <ul>
 *   <li>{@link #PING} → {@link #PONG} — liveness check (bucket eviction).</li>
 *   <li>{@link #FIND_NODE} → {@link #NODES} — return the k closest contacts to a target id.</li>
 *   <li>{@link #STORE} → {@link #STORED} — store a key/value on this node.</li>
 *   <li>{@link #FIND_VALUE} → {@link #VALUE} if held, otherwise {@link #NODES}.</li>
 * </ul>
 *
 * <p>Ordinals are used on the wire (see {@code MessageCodec}); do not reorder.
 */
public enum MessageType {

	PING,
	PONG,
	FIND_NODE,
	NODES,
	STORE,
	STORED,
	FIND_VALUE,
	VALUE;

	/** True if this is a reply that should complete a pending request future. */
	public boolean isResponse() {
		return this == PONG || this == NODES || this == STORED || this == VALUE;
	}
}
