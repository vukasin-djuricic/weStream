package cli.command;

import java.nio.charset.StandardCharsets;

import app.AppConfig;
import core.kademlia.NodeId;

/**
 * <code>dht_get &lt;key&gt;</code> — retrieves the value stored under a string key.
 * The key is hashed to a 160-bit {@link NodeId} (SHA-1) and resolved via
 * {@code findValue} (local store first, otherwise the k closest nodes).
 */
public class DHTGetCommand implements CLICommand {

	@Override
	public String commandName() {
		return "dht_get";
	}

	@Override
	public void execute(String args) {
		if (args == null || args.isBlank()) {
			AppConfig.timestampedErrorPrint("Usage: dht_get <key>");
			return;
		}

		String key = args.trim();
		NodeId keyId = NodeId.fromBytes(key.getBytes(StandardCharsets.UTF_8));
		// Runs on the CLI thread, so this blocking lookup is safe.
		byte[] value = AppConfig.kademliaService.findValue(keyId);

		if (value == null) {
			AppConfig.timestampedStandardPrint(key + ": not found");
		} else {
			AppConfig.timestampedStandardPrint(key + ": " + new String(value, StandardCharsets.UTF_8));
		}
	}

}
