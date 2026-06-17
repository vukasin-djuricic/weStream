package core.transfer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import core.kademlia.NodeId;

/**
 * A persistent TCP connection to one peer for one torrent: a long-lived socket
 * carrying a stream of framed {@link TransferMessage}s (mirrors {@code
 * UdpTransport}'s daemon-thread lifecycle). One dedicated reader thread decodes
 * inbound frames and fires {@link Listener} callbacks; all sends are framed and
 * mutually exclusive so the orchestrator and any reply path never interleave
 * bytes. Keeping the socket open lets the downloader pipeline many REQUESTs
 * before the PIECEs come back.
 */
public final class PeerConnection implements Closeable {

	/** Callbacks fired on the reader thread; keep them non-blocking. */
	public interface Listener {
		void onHandshake(PeerConnection c);
		void onBitfield(PeerConnection c);
		void onHave(PeerConnection c, int index);
		void onRequest(PeerConnection c, int index);
		void onPiece(PeerConnection c, int index, byte[] block);
		void onClosed(PeerConnection c);
	}

	private final Socket socket;
	private final DataInputStream in;
	private final DataOutputStream out;
	private final NodeId localId;
	private final NodeId infohash;
	private final int pieceCount;
	private final TransferCodec codec = new TransferCodec();

	/**
	 * Optional, node-wide upload throttle (shared across every connection). Null
	 * unless {@code WS_THROTTLE_KBPS}/{@code westream.throttleKbps} is set, so the
	 * hot path pays nothing in normal runs — see {@link RateLimiter}. Read once at
	 * class load (the env/property is fixed for the JVM's lifetime).
	 */
	private static final RateLimiter UPLOAD_THROTTLE = RateLimiter.fromEnv();

	private volatile Listener listener;
	private volatile NodeId remoteId;
	private volatile Bitfield remoteBitfield; // learned from BITFIELD, mutated by HAVE
	private final AtomicBoolean closeAnnounced = new AtomicBoolean(false);
	private volatile boolean running = true;
	private Thread reader;

	public PeerConnection(Socket socket, NodeId localId, NodeId infohash, int pieceCount) throws IOException {
		this.socket = socket;
		this.localId = localId;
		this.infohash = infohash;
		this.pieceCount = pieceCount;
		this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
		this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
	}

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	public void start() {
		reader = new Thread(this::readLoop, "transfer-recv-" + socket.getRemoteSocketAddress());
		reader.setDaemon(true);
		reader.start();
	}

	public NodeId remoteId() {
		return remoteId;
	}

	/** The peer's piece availability as learned so far, or null before its BITFIELD arrives. */
	public Bitfield remoteBitfield() {
		return remoteBitfield;
	}

	// -------------------------------------------------------------- sends

	public void sendHandshake() throws IOException {
		send(TransferMessage.handshake(infohash, localId));
	}

	public void sendBitfield(Bitfield bf) throws IOException {
		send(TransferMessage.bitfield(bf.toBytes()));
	}

	public void sendHave(int index) throws IOException {
		send(TransferMessage.have(index));
	}

	public void sendRequest(int index) throws IOException {
		send(TransferMessage.request(index));
	}

	public void sendPiece(int index, byte[] block) throws IOException {
		// Pace the bulk PIECE bytes when a throttle is configured (demo/congestion
		// simulation). PIECE carries ~all the traffic; REQUEST/HAVE/BITFIELD are tiny,
		// so this single hook is enough to cap a node's effective upload rate.
		if (UPLOAD_THROTTLE != null) {
			try {
				UPLOAD_THROTTLE.acquire(block.length);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("upload throttle interrupted", e);
			}
		}
		send(TransferMessage.piece(index, block));
	}

	private synchronized void send(TransferMessage m) throws IOException {
		codec.writeFrame(out, m); // writeFrame flushes
	}

	// -------------------------------------------------------------- receive

	private void readLoop() {
		try {
			while (running) {
				TransferMessage m = codec.readFrame(in);
				dispatch(m);
			}
		} catch (Exception e) {
			// EOF, reset, malformed frame, or close() — drop the connection cleanly.
		} finally {
			announceClosed();
		}
	}

	private void dispatch(TransferMessage m) {
		Listener l = listener;
		switch (m.type) {
			case HANDSHAKE:
				remoteId = m.senderId;
				if (l != null) {
					l.onHandshake(this);
				}
				break;
			case BITFIELD:
				// Update availability BEFORE the callback so the orchestrator sees it.
				remoteBitfield = new Bitfield(pieceCount, m.bitfield);
				if (l != null) {
					l.onBitfield(this);
				}
				break;
			case HAVE:
				Bitfield rb = remoteBitfield;
				if (rb == null) {
					rb = new Bitfield(pieceCount);
					remoteBitfield = rb;
				}
				if (m.index >= 0 && m.index < pieceCount) {
					rb.set(m.index);
				}
				if (l != null) {
					l.onHave(this, m.index);
				}
				break;
			case REQUEST:
				if (l != null) {
					l.onRequest(this, m.index);
				}
				break;
			case PIECE:
				if (l != null) {
					l.onPiece(this, m.index, m.block);
				}
				break;
			default:
				// unknown type already rejected by the codec
		}
	}

	private void announceClosed() {
		if (closeAnnounced.compareAndSet(false, true)) {
			Listener l = listener;
			if (l != null) {
				l.onClosed(this);
			}
		}
	}

	@Override
	public void close() {
		running = false;
		try {
			socket.close(); // unblocks readFrame
		} catch (IOException ignored) {
			// best effort
		}
	}
}
