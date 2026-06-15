package app;

import java.io.IOException;

import cli.CLIParser;

/**
 * Describes the procedure for starting a single Kademlia node, <em>headless</em>
 * (CLI front-end). Node 0 is the <em>seed</em>: it just starts listening. Every
 * other node bootstraps to the seed's UDP endpoint to join the network. (This
 * replaces the legacy Chord boot path — listener + initializer + bootstrap
 * server — which still compiles but is no longer started.)
 *
 * <p>The node stack itself lives in {@link NodeRuntime}; this entry point only
 * adds config parsing and the stdin CLI loop. The Phase-5 JavaFX window is the
 * other front-end over the same {@link NodeRuntime}, so the two never duplicate
 * boot logic and the headless path stays pure-JDK for {@code ./check.sh}.
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

		// Build the node stack (UdpTransport -> KademliaService -> TransferService),
		// exactly as the regression suite does, via the shared NodeRuntime.
		NodeRuntime runtime = null;
		try {
			runtime = new NodeRuntime("127.0.0.1", AppConfig.myPort,
					AppConfig.isSeedNode, AppConfig.SEED_HOST, AppConfig.SEED_PORT);
		} catch (IOException e) {
			AppConfig.timestampedErrorPrint("Couldn't bind node ports (UDP " + AppConfig.myPort
					+ " / API) : " + e.getMessage() + ". Exiting...");
			System.exit(0);
		}

		// Publish the runtime's parts on AppConfig so the CLI commands (and the
		// legacy static-singleton idiom) can reach them.
		AppConfig.transport = runtime.transport();
		AppConfig.kademliaService = runtime.kademlia();
		AppConfig.transferService = runtime.transferService();

		AppConfig.timestampedStandardPrint("Starting Kademlia node " + runtime.kademlia().self());

		// The CLI thread reads commands (stdin, redirected to a per-node input file by the starter).
		Thread cliThread = new Thread(new CLIParser());
		cliThread.start();

		// Join the network (off-thread; no-op for the seed). See NodeRuntime#joinNetwork.
		runtime.joinNetwork();
	}
}
