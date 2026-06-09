package core.kademlia;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled binary wire format for {@link Message}. Deliberately NOT Java
 * native serialization: {@code ObjectInputStream.readObject()} on bytes from
 * arbitrary peers is a remote-code-execution vector. This explicit,
 * length-prefixed format only ever produces the fields it knows about.
 *
 * <p>Layout:
 * <pre>
 *   u8   type (MessageType ordinal)
 *   i64  txId
 *   20B  senderId
 *   utf  senderHost
 *   i32  senderPort
 *   [20B target]        if type has a target (FIND_NODE, FIND_VALUE, STORE)
 *   [i32 len, bytes]    if type has a value  (STORE, VALUE)
 *   [i32 n, n×(utf host, i32 port)]  if type has contacts (NODES)
 * </pre>
 */
public class MessageCodec {

	public byte[] encode(Message m) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(m.type.ordinal());
			out.writeLong(m.txId);
			out.write(m.senderId.toBytes());
			out.writeUTF(m.senderHost);
			out.writeInt(m.senderPort);
			if (hasTarget(m.type)) {
				out.write(m.target.toBytes());
			}
			if (hasValue(m.type)) {
				out.writeInt(m.value.length);
				out.write(m.value);
			}
			if (hasContacts(m.type)) {
				out.writeInt(m.contacts.size());
				for (Contact c : m.contacts) {
					out.writeUTF(c.getHost());
					out.writeInt(c.getPort());
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("encode failed", e); // never happens on a byte array
		}
		return bytes.toByteArray();
	}

	public Message decode(byte[] data) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			MessageType type = MessageType.values()[in.readUnsignedByte()];
			long txId = in.readLong();
			byte[] idBytes = new byte[20];
			in.readFully(idBytes);
			NodeId senderId = NodeId.fromValueBytes(idBytes);
			String senderHost = in.readUTF();
			int senderPort = in.readInt();

			NodeId target = null;
			byte[] value = null;
			List<Contact> contacts = null;
			if (hasTarget(type)) {
				byte[] t = new byte[20];
				in.readFully(t);
				target = NodeId.fromValueBytes(t);
			}
			if (hasValue(type)) {
				value = new byte[in.readInt()];
				in.readFully(value);
			}
			if (hasContacts(type)) {
				int n = in.readInt();
				contacts = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					String host = in.readUTF();
					int port = in.readInt();
					contacts.add(new Contact(host, port));
				}
			}
			return new Message(type, txId, senderId, senderHost, senderPort, target, value, contacts);
		} catch (IOException e) {
			throw new UncheckedIOException("decode failed (malformed packet)", e);
		}
	}

	private static boolean hasTarget(MessageType t) {
		return t == MessageType.FIND_NODE || t == MessageType.FIND_VALUE || t == MessageType.STORE;
	}

	private static boolean hasValue(MessageType t) {
		return t == MessageType.STORE || t == MessageType.VALUE;
	}

	private static boolean hasContacts(MessageType t) {
		return t == MessageType.NODES;
	}
}
