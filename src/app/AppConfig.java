package app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import core.ServentInfo;
import core.chord.ChordState;
import core.kademlia.KademliaService;
import core.kademlia.UdpTransport;

/**
 * This class contains all the global application configuration stuff.
 * @author bmilojkovic
 *
 */
public class AppConfig {

	/**
	 * Convenience access for this servent's information.
	 * <p>Chord-era field, kept for the dormant Chord classes; the live Kademlia
	 * path does not use it (a node's id is derived from host:port inside the engine).
	 */
	public static ServentInfo myServentInfo;

	/** The live Kademlia node for this servent. Set once at startup by {@link #readConfig}/ServentMain. */
	public static KademliaService kademliaService;
	/** The UDP transport backing {@link #kademliaService}, kept so {@code stop} can close it. */
	public static UdpTransport transport;
	/** This node's UDP listener port. */
	public static int myPort;
	/** True for node 0, the seed every other node bootstraps to. */
	public static boolean isSeedNode;
	/** Host of the seed node (node 0). */
	public static String SEED_HOST;
	/** UDP port of the seed node (node 0) = {@code servent0.port}. */
	public static int SEED_PORT;

	/**
	 * Print a message to stdout with a timestamp
	 * @param message message to print
	 */
	public static void timestampedStandardPrint(String message) {
		DateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
		Date now = new Date();
		
		System.out.println(timeFormat.format(now) + " - " + message);
	}
	
	/**
	 * Print a message to stderr with a timestamp
	 * @param message message to print
	 */
	public static void timestampedErrorPrint(String message) {
		DateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
		Date now = new Date();
		
		System.err.println(timeFormat.format(now) + " - " + message);
	}
	
	public static boolean INITIALIZED = false;
	public static int BOOTSTRAP_PORT;
	public static int SERVENT_COUNT;

	public static ChordState chordState;

	/**
	 * Reads a config file. Should be called once at start of app.
	 * The config file should be of the following format:
	 * <br/>
	 * <code><br/>
	 * servent_count=3 			- number of servents in the system <br/>
	 * servent0.port=1100 		- listener ports for each servent (node 0 is the Kademlia seed) <br/>
	 * servent1.port=1200 <br/>
	 * servent2.port=1300 <br/>
	 *
	 * </code>
	 * <br/>
	 * So in this case, we would have three servents, listening on UDP ports
	 * 1100, 1200, and 1300, with node 0 (port 1100) acting as the seed that the
	 * others bootstrap to.<br/>
	 *
	 * <p>The Kademlia path derives each node's 160-bit id from its host:port (SHA-1)
	 * inside the engine, so the old Chord keys ({@code chord_size}, {@code bs.port})
	 * are not read here; leaving them in the file is harmless.
	 *
	 * @param configName name of configuration file
	 * @param serventId id of the servent, as used in the configuration file
	 */
	public static void readConfig(String configName, int serventId){
		Properties properties = new Properties();
		try {
			properties.load(new FileInputStream(new File(configName)));

		} catch (IOException e) {
			timestampedErrorPrint("Couldn't open properties file. Exiting...");
			System.exit(0);
		}

		try {
			SERVENT_COUNT = Integer.parseInt(properties.getProperty("servent_count"));
		} catch (NumberFormatException e) {
			timestampedErrorPrint("Problem reading servent_count. Exiting...");
			System.exit(0);
		}

		String portProperty = "servent"+serventId+".port";

		try {
			myPort = Integer.parseInt(properties.getProperty(portProperty));
		} catch (NumberFormatException e) {
			timestampedErrorPrint("Problem reading " + portProperty + ". Exiting...");
			System.exit(0);
		}

		try {
			SEED_PORT = Integer.parseInt(properties.getProperty("servent0.port"));
		} catch (NumberFormatException e) {
			timestampedErrorPrint("Problem reading servent0.port (the seed). Exiting...");
			System.exit(0);
		}

		SEED_HOST = "127.0.0.1";
		isSeedNode = (serventId == 0);
	}

}
