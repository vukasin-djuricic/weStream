package core.kademlia;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 * Test-only {@link Transport} that delivers messages between nodes in-process
 * through a shared {@code Map<port, transport>} registry — no real sockets.
 *
 * <p>Delivery happens on a shared executor (not the caller's thread), which is
 * essential: it mirrors the real async network so a blocking {@code nodeLookup}
 * on the test thread is unblocked by responses arriving on a delivery thread,
 * exactly as with {@code UdpTransport}. Sending to a port that isn't registered
 * is silently dropped, which lets tests simulate loss / unreachable nodes.
 */
public class InMemoryTransport implements Transport {

	private final String host;
	private final int port;
	private final Map<Integer, InMemoryTransport> network;
	private final ExecutorService delivery;
	private volatile BiConsumer<Contact, byte[]> handler;

	public InMemoryTransport(String host, int port,
			Map<Integer, InMemoryTransport> network, ExecutorService delivery) {
		this.host = host;
		this.port = port;
		this.network = network;
		this.delivery = delivery;
	}

	@Override
	public void setReceiveHandler(BiConsumer<Contact, byte[]> handler) {
		this.handler = handler;
	}

	@Override
	public void start() {
		network.put(port, this);
	}

	@Override
	public void close() {
		network.remove(port);
	}

	@Override
	public void send(Contact to, byte[] message) {
		InMemoryTransport target = network.get(to.getPort());
		if (target == null) {
			return; // unreachable / dropped
		}
		byte[] copy = message.clone();
		Contact wireSource = new Contact(host, port);
		delivery.execute(() -> {
			BiConsumer<Contact, byte[]> h = target.handler;
			if (h != null) {
				h.accept(wireSource, copy);
			}
		});
	}
}
