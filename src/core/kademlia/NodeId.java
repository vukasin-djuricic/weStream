package core.kademlia;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A 160-bit Kademlia node/key identifier.
 *
 * The value is held as a non-negative {@link BigInteger} in the range
 * <code>[0, 2^160)</code>. Identifiers are produced by hashing with SHA-1
 * (which is exactly 160 bits), so {@link #ID_BITS} matches the digest size.
 *
 * The Kademlia distance metric is the symmetric XOR: <code>d(x, y) = x ^ y</code>.
 * Nodes are sorted into k-buckets by the index of the highest set bit of that
 * distance (see {@link #bucketIndex(NodeId)}).
 */
public final class NodeId implements Comparable<NodeId> {

	/** Size of the id space in bits. SHA-1 produces a 160-bit digest. */
	public static final int ID_BITS = 160;

	private final BigInteger value;

	private NodeId(BigInteger value) {
		this.value = value;
	}

	/**
	 * Deterministic id for a servent, derived from its listener port via
	 * <code>SHA-1("localhost:" + port)</code>. Deterministic on purpose: the same
	 * port always maps to the same id, which keeps simulation runs reproducible.
	 */
	public static NodeId fromPort(int port) {
		return fromBytes(("localhost:" + port).getBytes(StandardCharsets.UTF_8));
	}

	/** SHA-1 of the given bytes, interpreted as a non-negative 160-bit id. */
	public static NodeId fromBytes(byte[] data) {
		try {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			byte[] digest = sha1.digest(data); // 20 bytes == 160 bits
			return new NodeId(new BigInteger(1, digest)); // 1 == positive sign
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 is required but not available", e);
		}
	}

	/** XOR distance to {@code other}, as a non-negative raw value. */
	public BigInteger distance(NodeId other) {
		return this.value.xor(other.value);
	}

	/**
	 * Index of the k-bucket that {@code other} belongs to, relative to this id:
	 * the position (0-based) of the highest set bit of the XOR distance.
	 *
	 * @return a value in <code>[0, ID_BITS)</code>, or <code>-1</code> when
	 *         {@code other} equals this id (distance 0 has no set bits).
	 */
	public int bucketIndex(NodeId other) {
		return distance(other).bitLength() - 1;
	}

	public BigInteger value() {
		return value;
	}

	@Override
	public int compareTo(NodeId o) {
		return this.value.compareTo(o.value);
	}

	@Override
	public boolean equals(Object obj) {
		return (obj instanceof NodeId) && value.equals(((NodeId) obj).value);
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}

	/** Zero-padded 40-character hex representation of the 160-bit id. */
	@Override
	public String toString() {
		return String.format("%040x", value);
	}
}
