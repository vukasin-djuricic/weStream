package app;

import java.net.SocketException;

import cli.CLIParser;
import core.kademlia.Contact;
import core.kademlia.KademliaService;
import core.kademlia.UdpTransport;

/**
 * Describes the procedure for starting a single Kademlia node.
 *
 * <p>Node 0 is the <em>seed</em>: it just starts listening. Every other node
 * bootstraps to the seed's UDP endpoint to join the network. (This replaces the
 * legacy Chord boot path — listener + initializer + bootstrap server — which
 * still compiles but is no longer started.)
 *
 * @author bmilojkovic
 */
public class ServentMain {

	/**
	 * Command line arguments are:
	 * 0 - path to servent list file
	 * 1 - this servent's id
	 */
	public static void main(String[] args) {
		if (args.length != 2) {
			AppConfig.timestampedErrorPrint("Please provide servent list file and id of this servent.");
		}

		int serventId = -1;

		String serventListFile = args[0];

		try {
			serventId = Integer.parseInt(args[1]);
		} catch (NumberFormatException e) {
			AppConfig.timestampedErrorPrint("Second argument should be an int. Exiting...");
			System.exit(0);
		}

		AppConfig.readConfig(serventListFile, serventId);

		if (AppConfig.myPort < 1000 || AppConfig.myPort > 2000) {
			AppConfig.timestampedErrorPrint("Port number should be in range 1000-2000. Exiting...");
			System.exit(0);
		}

		// Build the transport + service exactly as the regression suite does
		// (UdpTransport -> KademliaService -> start).
		KademliaService kademlia = null;
		try {
			UdpTransport transport = new UdpTransport(AppConfig.myPort);
			kademlia = new KademliaService("127.0.0.1", AppConfig.myPort, transport);
			kademlia.start();

			AppConfig.transport = transport;
			AppConfig.kademliaService = kademlia;
		} catch (SocketException e) {
			AppConfig.timestampedErrorPrint("Couldn't bind UDP port " + AppConfig.myPort + ". Exiting...");
			System.exit(0);
		}

		AppConfig.timestampedStandardPrint("Starting Kademlia node " + kademlia.self());

		// The CLI thread reads commands (stdin, redirected to a per-node input file by the starter).
		Thread cliThread = new Thread(new CLIParser());
		cliThread.start();

		// Join the network. Bootstrap is a BLOCKING call (it waits on RPC futures
		// completed by the UDP receive thread), so it must run on its own
		// application thread — never inline here, never on the receive thread.
		// Node 0 is the seed: it has nobody to bootstrap to, it just listens.
		if (!AppConfig.isSeedNode) {
			Contact seed = new Contact(AppConfig.SEED_HOST, AppConfig.SEED_PORT);
			new Thread(() -> {
				AppConfig.timestampedStandardPrint("Bootstrapping via seed " + seed);
				AppConfig.kademliaService.bootstrap(seed);
				AppConfig.timestampedStandardPrint("Bootstrap complete, routing table size "
						+ AppConfig.kademliaService.routingTable().size());
			}, "kad-bootstrap").start();
		}
	}
}
