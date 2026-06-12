package core.transfer;

import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Download piece picker: requests the piece held by the fewest connected peers
 * first ("rarest first"), which spreads scarce pieces through the swarm and
 * maximizes overall throughput. Ties break toward the lower index.
 *
 * <p>Availability is an {@link AtomicIntegerArray} of per-piece counts, bumped
 * lock-free from every {@code PeerConnection} reader thread as bitfields/haves
 * arrive.
 */
public final class RarestFirstPicker implements PiecePicker {

	private final int pieceCount;
	private final AtomicIntegerArray availability;

	public RarestFirstPicker(int pieceCount) {
		this.pieceCount = pieceCount;
		this.availability = new AtomicIntegerArray(pieceCount);
	}

	@Override
	public void onPeerBitfield(Bitfield peerBits) {
		for (int i = 0; i < pieceCount; i++) {
			if (peerBits.get(i)) {
				availability.incrementAndGet(i);
			}
		}
	}

	@Override
	public void onHave(int index) {
		availability.incrementAndGet(index);
	}

	@Override
	public int pick(Bitfield have, Bitfield available, Set<Integer> inFlight) {
		int best = -1;
		int bestCount = Integer.MAX_VALUE;
		for (int i = 0; i < pieceCount; i++) {
			if (!available.get(i) || have.get(i) || inFlight.contains(i)) {
				continue;
			}
			int count = availability.get(i);
			if (count < bestCount) {
				bestCount = count;
				best = i;
			}
		}
		return best;
	}

	int availabilityOf(int index) {
		return availability.get(index);
	}
}
