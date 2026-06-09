package core.kademlia;

import java.util.function.BiConsumer;

/**
 * Abstracts "get these bytes from here to a {@link Contact}". The Kademlia
 * engine talks only to this interface and never touches sockets directly, so
 * the connectivity strategy can evolve independently of the routing logic:
 * a plain {@link UdpTransport} works on a LAN (or with port-forwarding) today,
 * and a future NAT-traversing transport (UPnP / ICE-WebRTC / relay) can be
 * dropped in later without changing a line of Kademlia.
 *
 * <p>The interface itself stays pure JDK; a concrete NAT-traversal
 * implementation living outside {@code core.kademlia} is free to use libraries.
 */
public interface Transport {

	/** Send one datagram-sized message to {@code to}. Fire-and-forget. */
	void send(Contact to, byte[] message);

	/**
	 * Register the callback invoked for every inbound message, with the wire
	 * source address as the first argument. Called on the transport's receive
	 * thread, so the handler must not block on that thread.
	 */
	void setReceiveHandler(BiConsumer<Contact, byte[]> handler);

	/** Begin receiving. */
	void start();

	/** Stop receiving and release resources. */
	void close();
}
