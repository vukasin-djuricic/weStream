package core.transfer;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
	private final int maxInFlight;

	private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
	private final List<PeerConnection> peers = new CopyOnWriteArrayList<>();
	private final CountDownLatch done = new CountDownLatch(1);

	public DownloadSession(TorrentMetadata meta, Path outPath, NodeId localId,
			PiecePicker picker, int maxInFlight) throws IOException {
		this.meta = meta;
		this.localId = localId;
		this.picker = picker;
		this.maxInFlight = Math.max(1, maxInFlight);
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
		return new Progress(bits.cardinality(), inFlightCount, total, peers.size(), pieceStates);
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
	public record Progress(int have, int inFlight, int total, int peers, byte[] pieceStates) {
		public double fraction() {
			return total == 0 ? 1.0 : (double) have / total;
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
		fillPipeline(c);
	}

	@Override
	public void onHave(PeerConnection c, int index) {
		picker.onHave(index);
		fillPipeline(c);
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
		inFlight.remove(index); // re-pickable if rejected
		if (accepted) {
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
		fillPipeline(c);
	}

	@Override
	public void onClosed(PeerConnection c) {
		peers.remove(c);
	}

	/**
	 * Request as many pickable pieces from {@code c} as the in-flight ceiling
	 * allows. Synchronized so concurrent reader threads keep {@code inFlight} and
	 * the picker consistent.
	 */
	private synchronized void fillPipeline(PeerConnection c) {
		Bitfield available = c.remoteBitfield();
		if (available == null) {
			return;
		}
		while (inFlight.size() < maxInFlight) {
			int index = picker.pick(store.bitfield(), available, inFlight);
			if (index < 0) {
				break;
			}
			if (!inFlight.add(index)) {
				continue;
			}
			try {
				c.sendRequest(index);
			} catch (IOException e) {
				inFlight.remove(index);
				break;
			}
		}
	}

	@Override
	public void close() throws IOException {
		for (PeerConnection c : peers) {
			c.close();
		}
		store.close();
	}
}
