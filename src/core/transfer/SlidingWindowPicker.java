package core.transfer;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streaming piece picker (blueprint rule #6): keeps a window {@code [playhead,
 * playhead+W)} and requests the piece nearest the playhead first, so playback
 * can start before the whole file is downloaded. If every piece in the window is
 * already had / in-flight, it falls back to the first pickable piece anywhere
 * (so the download still completes once the window is satisfied).
 *
 * <p>The {@code playhead} is a settable cursor — in Phase 4 a test advances it;
 * in Phase 5 the media player will drive it from real playback position. This is
 * the seam between the transfer engine and the player.
 */
public final class SlidingWindowPicker implements PiecePicker {

	private final int pieceCount;
	private final int windowSize;
	private final AtomicInteger playhead = new AtomicInteger(0);

	public SlidingWindowPicker(int pieceCount, int windowSize) {
		if (windowSize <= 0) {
			throw new IllegalArgumentException("windowSize must be positive: " + windowSize);
		}
		this.pieceCount = pieceCount;
		this.windowSize = windowSize;
	}

	/** Move the window so it starts at {@code index} (the current playback position). */
	public void setPlayhead(int index) {
		playhead.set(Math.max(0, Math.min(index, pieceCount)));
	}

	public int playhead() {
		return playhead.get();
	}

	@Override
	public int pick(Bitfield have, Bitfield available, Set<Integer> inFlight) {
		int start = playhead.get();
		// High-priority: nearest the playhead, within the window.
		int windowEnd = Math.min(pieceCount, start + windowSize);
		for (int i = start; i < windowEnd; i++) {
			if (isPickable(i, have, available, inFlight)) {
				return i;
			}
		}
		// Fallback: anything else still missing (keeps a pure download progressing).
		for (int i = 0; i < pieceCount; i++) {
			if (isPickable(i, have, available, inFlight)) {
				return i;
			}
		}
		return -1;
	}

	private boolean isPickable(int i, Bitfield have, Bitfield available, Set<Integer> inFlight) {
		return available.get(i) && !have.get(i) && !inFlight.contains(i);
	}

	@Override
	public void onPeerBitfield(Bitfield peerBits) {
		// Window picking does not need availability counts.
	}

	@Override
	public void onHave(int index) {
		// no-op
	}
}
