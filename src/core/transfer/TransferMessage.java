package core.transfer;

import java.util.Arrays;
import java.util.Objects;

import core.kademlia.NodeId;

/**
 * An immutable transfer-protocol message. One class with nullable per-type
 * fields and static factories, mirroring {@code core.kademlia.Message}. Byte
 * arrays are defensively copied in and out so instances never alias caller state
 * (blueprint rule #2).
 */
public final class TransferMessage {

	public final TransferMessageType type;
	public final NodeId infohash; // HANDSHAKE
	public final NodeId senderId; // HANDSHAKE
	public final byte[] bitfield; // BITFIELD (packed)
	public final int index;       // HAVE / REQUEST / PIECE
	public final byte[] block;    // PIECE

	private TransferMessage(TransferMessageType type, NodeId infohash, NodeId senderId,
			byte[] bitfield, int index, byte[] block) {
		this.type = type;
		this.infohash = infohash;
		this.senderId = senderId;
		this.bitfield = bitfield;
		this.index = index;
		this.block = block;
	}

	public static TransferMessage handshake(NodeId infohash, NodeId senderId) {
		return new TransferMessage(TransferMessageType.HANDSHAKE, infohash, senderId, null, 0, null);
	}

	public static TransferMessage bitfield(byte[] packed) {
		return new TransferMessage(TransferMessageType.BITFIELD, null, null, packed.clone(), 0, null);
	}

	public static TransferMessage have(int index) {
		return new TransferMessage(TransferMessageType.HAVE, null, null, null, index, null);
	}

	public static TransferMessage request(int index) {
		return new TransferMessage(TransferMessageType.REQUEST, null, null, null, index, null);
	}

	public static TransferMessage piece(int index, byte[] block) {
		return new TransferMessage(TransferMessageType.PIECE, null, null, null, index, block.clone());
	}

	/** Defensive copy of the packed bitfield (BITFIELD only). */
	public byte[] bitfield() {
		return bitfield == null ? null : bitfield.clone();
	}

	/** Defensive copy of the piece bytes (PIECE only). */
	public byte[] block() {
		return block == null ? null : block.clone();
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof TransferMessage)) {
			return false;
		}
		TransferMessage o = (TransferMessage) obj;
		return type == o.type
				&& index == o.index
				&& Objects.equals(infohash, o.infohash)
				&& Objects.equals(senderId, o.senderId)
				&& Arrays.equals(bitfield, o.bitfield)
				&& Arrays.equals(block, o.block);
	}

	@Override
	public int hashCode() {
		int h = Objects.hash(type, infohash, senderId, index);
		h = 31 * h + Arrays.hashCode(bitfield);
		h = 31 * h + Arrays.hashCode(block);
		return h;
	}

	@Override
	public String toString() {
		switch (type) {
			case HANDSHAKE:
				return "HANDSHAKE[infohash=" + infohash + ", sender=" + senderId + "]";
			case BITFIELD:
				return "BITFIELD[" + bitfield.length + "B]";
			case HAVE:
				return "HAVE[" + index + "]";
			case REQUEST:
				return "REQUEST[" + index + "]";
			case PIECE:
				return "PIECE[" + index + ", " + block.length + "B]";
			default:
				return type.toString();
		}
	}
}
