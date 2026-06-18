package core.transfer;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import core.kademlia.NodeId;

/**
 * Drives a download: connects to peers, exchanges bitfields, asks a
 * {@link PiecePicker} what to fetch next, pipelines REQUESTs up to a fixed
 * in-flight ceiling, verifies arriving PIECEs (via {@link PieceStore}, the
 * integrity gate), announces HAVE, and completes a {@link CountDownLatch} when
 * the file is whole. Event-driven — every bitfield/have/piece refills the
 * pipeline — so no thread ever busy-waits (blueprint rule #4).
 */
public final class DownloadSession implements PeerConnection.Listener, Closeable {

	private final TorrentMetadata meta;
	private final PieceStore store;
	private final NodeId localId;
	private final PiecePicker picker;
	private final int maxInFlight;     // global ceiling across ALL peers (keeps the stream window ahead)
	private final int perPeerInFlight; // per-socket ceiling, so no single peer hogs the whole budget

	/** Pieces requested anywhere, for dedup + progress (a piece is never requested from two peers). */
	private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
	/** Per-peer requested set: which pieces are outstanding TO each peer (bounds each socket, releases on close). */
	private final Map<PeerConnection, Set<Integer>> requestedTo = new ConcurrentHashMap<>();
	/** Per-peer delivered count: how many verified pieces each peer actually gave us (diagnostics/fair-share proof). */
	private final Map<PeerConnection, Integer> deliveredBy = new ConcurrentHashMap<>();
	private final List<PeerConnection> peers = new CopyOnWriteArrayList<>();
	private final CountDownLatch done = new CountDownLatch(1);

	/** Fired once, when the first piece is verified — lets the owner join the swarm as a partial seed. */
	private final AtomicBoolean firstPieceFired = new AtomicBoolean(false);
	private volatile Runnable firstPieceCallback;

	public DownloadSession(TorrentMetadata meta, Path outPath, NodeId localId,
			PiecePicker picker, int maxInFlight, int perPeerInFlight) throws IOException {
		this.meta = meta;
		this.localId = localId;
		this.picker = picker;
		this.maxInFlight = Math.max(1, maxInFlight);
		this.perPeerInFlight = Math.max(1, perPeerInFlight);
		this.store = new PieceStore(outPath, meta);
		if (store.isComplete()) {
			done.countDown();
		}
	}

	/** Open a connection to a peer's transfer socket and begin the handshake/bitfield exchange. */
	public void addPeer(Socket socket) throws IOException {
		PeerConnection c = new PeerConnection(socket, localId, meta.infohash(), meta.pieceCount());
		c.setListener(this);
		peers.add(c);
		c.start();
		c.sendHandshake();
		c.sendBitfield(store.bitfield());
	}

	/** Block until the file is complete or the timeout elapses. */
	public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
		return done.await(timeoutMs, TimeUnit.MILLISECONDS);
	}

	public boolean isComplete() {
		return store.isComplete();
	}

	/** Number of connected peers (diagnostics/UI). */
	public int peerCount() {
		return peers.size();
	}

	/** The backing piece store — lets the streaming endpoint read pieces as they arrive. */
	public PieceStore store() {
		return store;
	}

	/**
	 * Move the streaming window to {@code pieceIndex} (a player seek), if this
	 * session uses a {@link SlidingWindowPicker}. No-op for other pickers — the
	 * playhead is the player↔transfer seam (blueprint rule #6).
	 */
	public void setPlayhead(int pieceIndex) {
		if (picker instanceof SlidingWindowPicker sw) {
			sw.setPlayhead(pieceIndex);
		}
	}

	/**
	 * Register a callback fired exactly once, the first time a piece is verified.
	 * The owner uses this to announce itself as a (partial) seed so other leechers
	 * can pull from it. Runs on a peer reader thread — keep it non-blocking (e.g.
	 * schedule the actual announce elsewhere), or it stalls piece reception.
	 */
	public void onFirstPiece(Runnable callback) {
		this.firstPieceCallback = callback;
	}

	/**
	 * A point-in-time download snapshot for the Phase-5 UI (swarm rail, scrubber,
	 * sliding-window strip). Pure data — reading it never touches the network.
	 */
	public Progress progress() {
		int total = meta.pieceCount();
		Bitfield bits = store.bitfield();
		byte[] pieceStates = new byte[total];
		int inFlightCount = 0;
		for (int i = 0; i < total; i++) {
			if (bits.get(i)) {
				pieceStates[i] = HAVE;
			} else if (inFlight.contains(i)) {
				pieceStates[i] = IN_FLIGHT;
				inFlightCount++;
			} else {
				pieceStates[i] = MISSING;
			}
		}
		// Real seeder/leecher split among the peers we pull FROM: a seeder has the
		// whole file (a complete bitfield); the rest are partial leechers. Peers whose
		// BITFIELD hasn't arrived yet (null) count as leechers, not seeders — honest.
		int seeders = 0;
		for (PeerConnection c : peers) {
			Bitfield rb = c.remoteBitfield();
			if (rb != null && rb.cardinality() == total) {
				seeders++;
			}
		}
		return new Progress(bits.cardinality(), inFlightCount, total, peers.size(), seeders, pieceStates);
	}

	/** Per-piece state byte in {@link Progress#pieceStates}: not yet requested. */
	public static final byte MISSING = 0;
	/** Per-piece state byte: requested, awaiting the PIECE reply. */
	public static final byte IN_FLIGHT = 1;
	/** Per-piece state byte: held and verified. */
	public static final byte HAVE = 2;

	/**
	 * Immutable download snapshot. {@code pieceStates[i]} is one of
	 * {@link #MISSING}/{@link #IN_FLIGHT}/{@link #HAVE} — the exact input the
	 * sliding-window strip renders.
	 */
	public record Progress(int have, int inFlight, int total, int peers, int seeders, byte[] pieceStates) {
		public double fraction() {
			return total == 0 ? 1.0 : (double) have / total;
		}

		/** Connected peers that are NOT complete seeders — partial leechers we also pull from. */
		public int leechers() {
			return peers - seeders;
		}
	}

	// -------------------------------------------------------- Listener callbacks

	@Override
	public void onHandshake(PeerConnection c) {
		// nothing to do; we already sent our side in addPeer
	}

	@Override
	public void onBitfield(PeerConnection c) {
		picker.onPeerBitfield(c.remoteBitfield());
		pump();
	}

	@Override
	public void onHave(PeerConnection c, int index) {
		picker.onHave(index);
		pump();
	}

	@Override
	public void onRequest(PeerConnection c, int index) {
		// We serve pieces we already hold, so a downloader doubles as a partial seed.
		if (index >= 0 && index < meta.pieceCount() && store.bitfield().get(index)) {
			try {
				c.sendPiece(index, store.readPiece(index));
			} catch (IOException ignored) {
				// peer will time out and re-request elsewhere
			}
		}
	}

	@Override
	public void onPiece(PeerConnection c, int index, byte[] block) {
		boolean accepted = (index >= 0 && index < meta.pieceCount()) && store.writePiece(index, block);
		releaseRequest(c, index); // free the slot (global + this peer); re-pickable if rejected
		if (accepted) {
			deliveredBy.merge(c, 1, Integer::sum); // who actually fed us — for fair-share diagnostics
			if (firstPieceFired.compareAndSet(false, true)) {
				Runnable cb = firstPieceCallback;
				if (cb != null) {
					cb.run(); // must be non-blocking (see onFirstPiece)
				}
			}
			for (PeerConnection p : peers) {
				try {
					p.sendHave(index);
				} catch (IOException ignored) {
					// best-effort gossip
				}
			}
			if (store.isComplete()) {
				done.countDown();
			}
		}
		pump(); // top EVERY peer back up — not just c — so an idle seeder gets work
	}

	@Override
	public synchronized void onClosed(PeerConnection c) {
		peers.remove(c);
		// Release the dead peer's outstanding requests so the rest of the swarm can
		// re-pick them (otherwise those pieces leak in inFlight and the download stalls).
		// Synchronized so this can't interleave with pump() mutating the same sets.
		Set<Integer> orphaned = requestedTo.remove(c);
		if (orphaned != null) {
			inFlight.removeAll(orphaned);
		}
		pump();
	}

	/** Free a delivered/rejected piece's slot, globally and on the peer it was requested from. */
	private synchronized void releaseRequest(PeerConnection c, int index) {
		inFlight.remove(index);
		Set<Integer> mine = requestedTo.get(c);
		if (mine != null) {
			mine.remove(index);
		}
	}

	/**
	 * Top EVERY connected peer up to its per-peer in-flight ceiling, subject to the
	 * global ceiling, picking pieces that peer actually has. Because the {@code inFlight}
	 * set is shared, no two peers are sent the same piece, so the work spreads across
	 * the swarm instead of one peer (whose bitfield happened to arrive first) hogging
	 * the whole global budget. Synchronized so concurrent reader threads keep the
	 * shared/per-peer sets and the picker consistent.
	 */
	private synchronized void pump() {
		for (PeerConnection c : peers) {
			Bitfield available = c.remoteBitfield();
			if (available == null) {
				continue; // its bitfield hasn't arrived yet — nothing to ask for
			}
			Set<Integer> mine = requestedTo.computeIfAbsent(c, k -> new HashSet<>());
			while (mine.size() < perPeerInFlight && inFlight.size() < maxInFlight) {
				int index = picker.pick(store.bitfield(), available, inFlight);
				if (index < 0) {
					break; // this peer has nothing new to offer right now
				}
				if (!inFlight.add(index)) {
					continue; // already claimed (defensive — picker excludes inFlight)
				}
				try {
					c.sendRequest(index);
					mine.add(index);
				} catch (IOException e) {
					inFlight.remove(index);
					break;
				}
			}
		}
	}

	/** Distinct peers that have delivered at least one verified piece (fair-share diagnostics/tests). */
	public int contributingPeerCount() {
		return deliveredBy.size();
	}

	/** Current streaming playhead (piece index), or -1 if this download isn't sliding-window. */
	public int playhead() {
		return (picker instanceof SlidingWindowPicker sw) ? sw.playhead() : -1;
	}

	/** Streaming window length in pieces, or 0 if this download isn't sliding-window. */
	public int streamWindow() {
		return (picker instanceof SlidingWindowPicker sw) ? sw.windowSize() : 0;
	}

	@Override
	public void close() throws IOException {
		for (PeerConnection c : peers) {
			c.close();
		}
		store.close();
	}
}
