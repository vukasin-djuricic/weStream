package cli.command;

import java.nio.charset.StandardCharsets;

import app.AppConfig;
import core.kademlia.NodeId;

/**
 * <code>dht_put &lt;key&gt; &lt;value&gt;</code> — stores a string value under a
 * string key. The key is hashed to a 160-bit {@link NodeId} (SHA-1) and the value
 * is replicated to the k nodes closest to that id via {@code storeValue}.
 */
public class DHTPutCommand implements CLICommand {

	@Override
	public String commandName() {
		return "dht_put";
	}

	@Override
	public void execute(String args) {
		int spacePos = (args == null) ? -1 : args.indexOf(' ');
		if (spacePos == -1) {
			AppConfig.timestampedErrorPrint("Usage: dht_put <key> <value>");
			return;
		}

		String key = args.substring(0, spacePos);
		String value = args.substring(spacePos + 1);

		NodeId keyId = NodeId.fromBytes(key.getBytes(StandardCharsets.UTF_8));
		// Runs on the CLI thread, so this blocking lookup-and-store is safe.
		AppConfig.kademliaService.storeValue(keyId, value.getBytes(StandardCharsets.UTF_8));

		AppConfig.timestampedStandardPrint("Stored '" + key + "' = '" + value + "'");
	}

}
