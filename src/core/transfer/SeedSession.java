package core.transfer;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import core.kademlia.NodeId;

/**
 * The upload side: serves a complete file's pieces to any peer that connects.
 * Wired behind a {@link TcpPeerServer} ({@code server.setConnectionHandler(seed::handle)}).
 * For each accepted socket it sends HANDSHAKE + a full BITFIELD, then answers
 * each REQUEST with the corresponding PIECE.
 */
public final class SeedSession implements Closeable {

	private final PieceStore store;
	private final TorrentMetadata meta;
	private final NodeId localId;
	private final List<PeerConnection> connections = new CopyOnWriteArrayList<>();

	public SeedSession(PieceStore store, NodeId localId) {
		this.store = store;
		this.meta = store.metadata();
		this.localId = localId;
	}

	/** Handle one accepted connection. Safe to pass as a {@code Consumer<Socket>}. */
	public void handle(Socket socket) {
		try {
			PeerConnection c = new PeerConnection(socket, localId, meta.infohash(), meta.pieceCount());
			c.setListener(new Responder());
			connections.add(c);
			c.start();
			c.sendHandshake();
			c.sendBitfield(store.bitfield());
		} catch (IOException e) {
			try {
				socket.close();
			} catch (IOException ignored) {
				// best effort
			}
		}
	}

	private final class Responder implements PeerConnection.Listener {
		@Override
		public void onRequest(PeerConnection c, int index) {
			if (index >= 0 && index < meta.pieceCount() && store.bitfield().get(index)) {
				try {
					c.sendPiece(index, store.readPiece(index));
				} catch (IOException e) {
					c.close();
				}
			}
		}

		@Override
		public void onClosed(PeerConnection c) {
			connections.remove(c);
		}

		// The seed only uploads; it ignores the rest.
		@Override public void onHandshake(PeerConnection c) { }
		@Override public void onBitfield(PeerConnection c) { }
		@Override public void onHave(PeerConnection c, int index) { }
		@Override public void onPiece(PeerConnection c, int index, byte[] block) { }
	}

	@Override
	public void close() {
		for (PeerConnection c : connections) {
			c.close();
		}
	}
}
