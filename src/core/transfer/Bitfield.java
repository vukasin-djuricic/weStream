package core.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread-safe, bit-packed set of piece indices over {@code [0, pieceCount)} —
 * "which pieces a peer holds". Bits are packed MSB-first within each byte
 * (BitTorrent convention: piece {@code i} is bit {@code 7-(i&7)} of byte {@code i>>3}).
 *
 * <p>Read/written from multiple {@code PeerConnection} reader threads and the
 * download orchestrator, so all bit access is {@code synchronized} (mirroring
 * {@code KBucket}'s simple full-synchronization choice); {@code setCount} is an
 * {@link AtomicInteger} so completeness can be checked lock-free.
 */
public final class Bitfield {

	private final int pieceCount;
	private final byte[] bits;
	private final AtomicInteger setCount = new AtomicInteger(0);

	public Bitfield(int pieceCount) {
		if (pieceCount < 0) {
			throw new IllegalArgumentException("pieceCount must be non-negative: " + pieceCount);
		}
		this.pieceCount = pieceCount;
		this.bits = new byte[byteLength(pieceCount)];
	}

	/** Reconstruct from the packed wire form; validates the byte length. */
	public Bitfield(int pieceCount, byte[] packed) {
		this(pieceCount);
		if (packed.length != bits.length) {
			throw new IllegalArgumentException(
					"bitfield length " + packed.length + " != expected " + bits.length);
		}
		synchronized (this) {
			System.arraycopy(packed, 0, bits, 0, bits.length);
			int count = 0;
			for (int i = 0; i < pieceCount; i++) {
				if (rawGet(i)) {
					count++;
				}
			}
			setCount.set(count);
		}
	}

	private static int byteLength(int pieceCount) {
		return (pieceCount + 7) / 8;
	}

	/** Set the bit for {@code index}; returns true iff it was newly set. */
	public synchronized boolean set(int index) {
		checkIndex(index);
		if (rawGet(index)) {
			return false;
		}
		bits[index >> 3] |= (byte) (1 << (7 - (index & 7)));
		setCount.incrementAndGet();
		return true;
	}

	public synchronized boolean get(int index) {
		checkIndex(index);
		return rawGet(index);
	}

	private boolean rawGet(int index) {
		return (bits[index >> 3] & (1 << (7 - (index & 7)))) != 0;
	}

	public int pieceCount() {
		return pieceCount;
	}

	/** Number of set pieces (lock-free). */
	public int cardinality() {
		return setCount.get();
	}

	public boolean isComplete() {
		return setCount.get() == pieceCount;
	}

	/** Packed copy for the wire. */
	public synchronized byte[] toBytes() {
		return bits.clone();
	}

	/** Indices not yet set, in ascending order. */
	public synchronized List<Integer> missing() {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < pieceCount; i++) {
			if (!rawGet(i)) {
				out.add(i);
			}
		}
		return out;
	}

	private void checkIndex(int index) {
		if (index < 0 || index >= pieceCount) {
			throw new IndexOutOfBoundsException("piece index " + index + " of " + pieceCount);
		}
	}
}
