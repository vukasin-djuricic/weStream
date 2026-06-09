package core.kademlia;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * A plain UDP {@link Transport}: one {@link DatagramSocket} bound to the node's
 * port, used for both sending and receiving. Because a single socket is reused,
 * the UDP source port of our outgoing packets equals our listening port, so a
 * receiver learns a reachable address for us "for free".
 *
 * <p>UDP (not TCP) is the right fit for Kademlia's small, frequent RPCs: no
 * handshake per query, and UDP hole-punching is more reliable than TCP's when
 * NAT traversal is added later. Bulk piece/chunk transfer will use TCP instead.
 */
public class UdpTransport implements Transport {

	/** Max UDP payload we will read; FIND_NODE responses with k contacts stay well under this. */
	private static final int MAX_PACKET = 64 * 1024;

	private final int port;
	private final DatagramSocket socket;
	private volatile boolean running = true;
	private volatile BiConsumer<Contact, byte[]> handler;
	private Thread receiver;

	public UdpTransport(int port) throws SocketException {
		this.port = port;
		this.socket = new DatagramSocket(port);
	}

	@Override
	public void setReceiveHandler(BiConsumer<Contact, byte[]> handler) {
		this.handler = handler;
	}

	/** The actual bound port — useful when constructed with port 0 (ephemeral). */
	public int getLocalPort() {
		return socket.getLocalPort();
	}

	@Override
	public void start() {
		receiver = new Thread(this::receiveLoop, "kad-udp-recv-" + port);
		receiver.setDaemon(true);
		receiver.start();
	}

	private void receiveLoop() {
		byte[] buffer = new byte[MAX_PACKET];
		while (running) {
			try {
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				socket.receive(packet);
				byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
				BiConsumer<Contact, byte[]> h = handler;
				if (h != null) {
					Contact from = new Contact(packet.getAddress().getHostAddress(), packet.getPort());
					try {
						h.accept(from, data);
					} catch (RuntimeException handlerError) {
						// A bad packet must never kill the receive loop.
						System.err.println("handler error on " + port + ": " + handlerError);
					}
				}
			} catch (IOException e) {
				if (running) {
					System.err.println("UDP receive error on " + port + ": " + e.getMessage());
				}
			}
		}
	}

	@Override
	public void send(Contact to, byte[] message) {
		try {
			DatagramPacket packet = new DatagramPacket(
					message, message.length, InetAddress.getByName(to.getHost()), to.getPort());
			socket.send(packet);
		} catch (IOException e) {
			System.err.println("UDP send error to " + to + ": " + e.getMessage());
		}
	}

	@Override
	public void close() {
		running = false;
		socket.close();
	}
}
