package core.transfer;

import java.util.Set;

/**
 * Decides which piece to request next. Two strategies implement it:
 * {@link SlidingWindowPicker} (streaming — near the playhead first) and
 * {@link RarestFirstPicker} (download — least-available first). The download
 * mode picks the implementation.
 *
 * <p>Implementations must be thread-safe: availability callbacks fire from
 * multiple {@code PeerConnection} reader threads while the orchestrator calls
 * {@link #pick}.
 */
public interface PiecePicker {

	/**
	 * Next piece index to request that {@code available} has, {@code have} lacks,
	 * and is not already {@code inFlight}; or {@code -1} if nothing is pickable.
	 */
	int pick(Bitfield have, Bitfield available, Set<Integer> inFlight);

	/** A peer advertised its full availability — update bookkeeping. */
	void onPeerBitfield(Bitfield peerBits);

	/** A peer announced one new piece — update bookkeeping. */
	void onHave(int index);
}
