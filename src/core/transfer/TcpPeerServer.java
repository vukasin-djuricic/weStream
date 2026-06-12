package core.transfer;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * A TCP {@link ServerSocket} accept loop on a daemon thread, mirroring
 * {@code UdpTransport}'s lifecycle ({@link #start()}/{@link #close()}, a named
 * daemon thread, a clean {@code SocketException}-on-close exit). Each accepted
 * connection is handed to the registered handler, which wraps it in a
 * {@code PeerConnection}.
 *
 * <p>This is the bulk-transfer channel — TCP, separate from the engine's UDP
 * RPC socket — so piece bytes never share the datagram path.
 */
public final class TcpPeerServer implements Closeable {

	private final ServerSocket serverSocket;
	private volatile boolean running = true;
	private volatile Consumer<Socket> handler;
	private Thread acceptor;

	public TcpPeerServer(int port) throws IOException {
		this.serverSocket = new ServerSocket(port);
	}

	/** The actual bound port — useful when constructed with port 0 (ephemeral). */
	public int getLocalPort() {
		return serverSocket.getLocalPort();
	}

	public void setConnectionHandler(Consumer<Socket> handler) {
		this.handler = handler;
	}

	public void start() {
		acceptor = new Thread(this::acceptLoop, "transfer-accept-" + getLocalPort());
		acceptor.setDaemon(true);
		acceptor.start();
	}

	private void acceptLoop() {
		while (running) {
			try {
				Socket socket = serverSocket.accept();
				Consumer<Socket> h = handler;
				if (h != null) {
					try {
						h.accept(socket);
					} catch (RuntimeException handlerError) {
						System.err.println("transfer accept handler error on " + getLocalPort()
								+ ": " + handlerError);
						closeQuietly(socket);
					}
				} else {
					closeQuietly(socket);
				}
			} catch (IOException e) {
				if (running) {
					System.err.println("transfer accept error on " + getLocalPort() + ": " + e.getMessage());
				}
			}
		}
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		} catch (IOException ignored) {
			// best effort
		}
	}

	@Override
	public void close() {
		running = false;
		try {
			serverSocket.close(); // unblocks accept() with a SocketException
		} catch (IOException ignored) {
			// best effort
		}
	}
}
