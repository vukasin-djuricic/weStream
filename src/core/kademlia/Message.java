package core.kademlia;

import java.util.List;

/**
 * An immutable Kademlia RPC message. Every message carries a common header
 * (type, transaction id, and the sender's identity/endpoint) plus the fields
 * relevant to its {@link MessageType}; unused fields are {@code null}.
 *
 * <p>The {@code txId} correlates a response to its request — see
 * {@code KademliaService}'s pending-request map. Instances are built through
 * the static factories so the header is always filled consistently from the
 * sending node's own {@link Contact}.
 */
public final class Message {

	public final MessageType type;
	public final long txId;

	public final NodeId senderId;
	public final String senderHost;
	public final int senderPort;

	/** Target id for FIND_NODE / FIND_VALUE, or the key for STORE. */
	public final NodeId target;
	/** Payload for STORE / VALUE. */
	public final byte[] value;
	/** Returned contacts for NODES. */
	public final List<Contact> contacts;

	/** Full constructor, used by the codec when reconstructing from the wire. */
	Message(MessageType type, long txId, NodeId senderId, String senderHost, int senderPort,
			NodeId target, byte[] value, List<Contact> contacts) {
		this.type = type;
		this.txId = txId;
		this.senderId = senderId;
		this.senderHost = senderHost;
		this.senderPort = senderPort;
		this.target = target;
		this.value = value;
		this.contacts = contacts;
	}

	private Message(MessageType type, long txId, Contact self,
			NodeId target, byte[] value, List<Contact> contacts) {
		this(type, txId, self.getId(), self.getHost(), self.getPort(), target, value, contacts);
	}

	public static Message ping(Contact self, long txId) {
		return new Message(MessageType.PING, txId, self, null, null, null);
	}

	public static Message pong(Contact self, long txId) {
		return new Message(MessageType.PONG, txId, self, null, null, null);
	}

	public static Message findNode(Contact self, long txId, NodeId target) {
		return new Message(MessageType.FIND_NODE, txId, self, target, null, null);
	}

	public static Message nodes(Contact self, long txId, List<Contact> contacts) {
		return new Message(MessageType.NODES, txId, self, null, null, contacts);
	}

	public static Message store(Contact self, long txId, NodeId key, byte[] value) {
		return new Message(MessageType.STORE, txId, self, key, value, null);
	}

	public static Message stored(Contact self, long txId) {
		return new Message(MessageType.STORED, txId, self, null, null, null);
	}

	public static Message findValue(Contact self, long txId, NodeId key) {
		return new Message(MessageType.FIND_VALUE, txId, self, key, null, null);
	}

	public static Message value(Contact self, long txId, byte[] value) {
		return new Message(MessageType.VALUE, txId, self, null, value, null);
	}
}
