package app;

import java.io.Closeable;
import java.net.SocketException;

import core.kademlia.Contact;
import core.kademlia.KademliaService;
import core.kademlia.UdpTransport;
import core.transfer.TransferService;

/**
 * The headless boot of a single weStream node, extracted from {@link ServentMain}
 * so it can be reused by both the CLI process (the test harness / scripted
 * scenarios) and the Phase-5 JavaFX window — one node, one runtime, two possible
 * front-ends.
 *
 * <p>It wires the exact same stack the regression suite builds:
 * {@link UdpTransport} → {@link KademliaService} (started) → {@link TransferService}.
 * {@link #joinNetwork()} bootstraps to the seed on its own thread (bootstrap is a
 * BLOCKING RPC call, so it must never run on the UDP receive thread — see
 * {@link KademliaService}). Node 0 is the seed and has nobody to bootstrap to.
 *
 * <p><b>Pure JDK by design.</b> This class lives in {@code app} and must NOT
 * import JavaFX or any UI type — the GUI layer depends on {@code NodeRuntime},
 * never the other way around, so {@code ./check.sh}'s plain {@code javac} keeps
 * compiling {@code src/} with no UI/JavaFX on the classpath.
 */
public final class NodeRuntime implements Closeable {

	private final UdpTransport transport;
	private final KademliaService kademlia;
	private final TransferService transferService;
	private final int port;
	private final boolean seed;
	private final String seedHost;
	private final int seedPort;
	private final long startedAtMillis = System.currentTimeMillis();

	/**
	 * Bind the UDP port and start the Kademlia + transfer stack (but do not join
	 * the network yet — call {@link #joinNetwork()} once a front-end is ready).
	 *
	 * @throws SocketException if {@code port} cannot be bound
	 */
	public NodeRuntime(String host, int port, boolean seed, String seedHost, int seedPort)
			throws SocketException {
		this.port = port;
		this.seed = seed;
		this.seedHost = seedHost;
		this.seedPort = seedPort;
		this.transport = new UdpTransport(port);
		this.kademlia = new KademliaService(host, port, transport);
		this.kademlia.start();
		this.transferService = new TransferService(kademlia);
	}

	/**
	 * Join the network through the seed, off-thread. No-op for the seed node.
	 * Safe to call once; the bootstrap runs on a dedicated {@code kad-bootstrap}
	 * thread because it blocks on RPC futures completed by the receive thread.
	 */
	public void joinNetwork() {
		if (seed) {
			return;
		}
		Contact seedContact = new Contact(seedHost, seedPort);
		new Thread(() -> {
			AppConfig.timestampedStandardPrint("Bootstrapping via seed " + seedContact);
			kademlia.bootstrap(seedContact);
			AppConfig.timestampedStandardPrint("Bootstrap complete, routing table size "
					+ kademlia.routingTable().size());
		}, "kad-bootstrap").start();
	}

	public KademliaService kademlia() {
		return kademlia;
	}

	public TransferService transferService() {
		return transferService;
	}

	public UdpTransport transport() {
		return transport;
	}

	public int port() {
		return port;
	}

	public boolean isSeed() {
		return seed;
	}

	/** Milliseconds since this runtime was constructed (drives the inspector "uptime"). */
	public long uptimeMillis() {
		return System.currentTimeMillis() - startedAtMillis;
	}

	/** Closes the transfer service (shutting its TCP servers) and the UDP transport. */
	@Override
	public void close() {
		transferService.close();
		transport.close();
	}
}
