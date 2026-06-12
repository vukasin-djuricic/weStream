package core.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import core.kademlia.NodeId;

/**
 * Hand-rolled binary wire format for {@link TransferMessage}, mirroring
 * {@code core.kademlia.MessageCodec}: explicit and length-prefixed, never Java
 * native serialization (an RCE vector on peer input).
 *
 * <p>Unlike the UDP codec, this runs over a TCP <em>byte stream</em>, which has
 * no message boundaries — so each message is wrapped in a frame: a 4-byte
 * big-endian body length, then exactly that many body bytes. {@link #readFrame}
 * rejects absurd lengths before allocating (no OOM on a hostile/garbage stream)
 * and a truncated stream surfaces as {@link java.io.EOFException}.
 *
 * <p>Body layout:
 * <pre>
 *   u8   type (TransferMessageType ordinal)
 *   HANDSHAKE: 20B infohash, 20B senderId
 *   BITFIELD : i32 len, bytes
 *   HAVE     : i32 index
 *   REQUEST  : i32 index
 *   PIECE    : i32 index, i32 len, bytes
 * </pre>
 */
public final class TransferCodec {

	/** Upper bound on a single frame: a default 256 KB piece plus headroom. */
	public static final int MAX_FRAME = 2 * 1024 * 1024;

	private static final TransferMessageType[] TYPES = TransferMessageType.values();

	public byte[] encodeBody(TransferMessage m) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(m.type.ordinal());
			switch (m.type) {
				case HANDSHAKE:
					out.write(m.infohash.toBytes());
					out.write(m.senderId.toBytes());
					break;
				case BITFIELD:
					out.writeInt(m.bitfield.length);
					out.write(m.bitfield);
					break;
				case HAVE:
				case REQUEST:
					out.writeInt(m.index);
					break;
				case PIECE:
					out.writeInt(m.index);
					out.writeInt(m.block.length);
					out.write(m.block);
					break;
				default:
					throw new IOException("unencodable type " + m.type);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("encode failed", e); // never happens on a byte array
		}
		return bytes.toByteArray();
	}

	public TransferMessage decodeBody(byte[] body) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
			int ordinal = in.readUnsignedByte();
			if (ordinal >= TYPES.length) {
				throw new IOException("unknown transfer type ordinal " + ordinal);
			}
			TransferMessageType type = TYPES[ordinal];
			switch (type) {
				case HANDSHAKE: {
					byte[] infohash = new byte[20];
					byte[] senderId = new byte[20];
					in.readFully(infohash);
					in.readFully(senderId);
					return TransferMessage.handshake(
							NodeId.fromValueBytes(infohash), NodeId.fromValueBytes(senderId));
				}
				case BITFIELD: {
					byte[] packed = readLengthPrefixed(in, body.length);
					return TransferMessage.bitfield(packed);
				}
				case HAVE:
					return TransferMessage.have(in.readInt());
				case REQUEST:
					return TransferMessage.request(in.readInt());
				case PIECE: {
					int index = in.readInt();
					byte[] block = readLengthPrefixed(in, body.length);
					return TransferMessage.piece(index, block);
				}
				default:
					throw new IOException("undecodable type " + type);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("decode failed (malformed message)", e);
		}
	}

	private static byte[] readLengthPrefixed(DataInputStream in, int bodyLength) throws IOException {
		int len = in.readInt();
		if (len < 0 || len > bodyLength) {
			throw new IOException("bad field length " + len);
		}
		byte[] out = new byte[len];
		in.readFully(out);
		return out;
	}

	/** Frame {@code m} onto a TCP stream: 4-byte body length, then the body. */
	public void writeFrame(DataOutputStream out, TransferMessage m) throws IOException {
		byte[] body = encodeBody(m);
		out.writeInt(body.length);
		out.write(body);
		out.flush();
	}

	/** Read one framed message from a TCP stream; rejects absurd lengths before allocating. */
	public TransferMessage readFrame(DataInputStream in) throws IOException {
		int len = in.readInt();
		if (len < 0 || len > MAX_FRAME) {
			throw new IOException("frame length out of range: " + len);
		}
		byte[] body = new byte[len];
		in.readFully(body);
		return decodeBody(body);
	}
}
