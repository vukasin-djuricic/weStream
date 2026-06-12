package cli.command;

import java.nio.file.Path;

import app.AppConfig;
import core.kademlia.NodeId;

/**
 * <code>download &lt;infohash-hex&gt; &lt;outpath&gt;</code> — resolve an infohash
 * via the DHT and pull the file from its seed. The infohash is the 40-hex string
 * printed by <code>share</code>. Runs on the CLI thread (blocking is fine here).
 */
public class DownloadCommand implements CLICommand {

	@Override
	public String commandName() {
		return "download";
	}

	@Override
	public void execute(String args) {
		int spacePos = (args == null) ? -1 : args.indexOf(' ');
		if (spacePos == -1) {
			AppConfig.timestampedErrorPrint("Usage: download <infohash-hex> <outpath>");
			return;
		}
		String hex = args.substring(0, spacePos).trim();
		String out = args.substring(spacePos + 1).trim();

		NodeId infohash;
		try {
			infohash = NodeId.fromValueBytes(hexToBytes(hex));
		} catch (IllegalArgumentException e) {
			AppConfig.timestampedErrorPrint("Invalid infohash (expected 40 hex chars): " + hex);
			return;
		}

		try {
			boolean ok = AppConfig.transferService.download(infohash, Path.of(out));
			if (ok) {
				AppConfig.timestampedStandardPrint("Downloaded " + hex + " -> " + out);
			} else {
				AppConfig.timestampedStandardPrint("download failed: " + hex + " not found or incomplete");
			}
		} catch (Exception e) {
			AppConfig.timestampedErrorPrint("download failed for " + hex + ": " + e.getMessage());
		}
	}

	private static byte[] hexToBytes(String hex) {
		if (hex.length() != 40) {
			throw new IllegalArgumentException("expected 40 hex chars, got " + hex.length());
		}
		byte[] out = new byte[20];
		for (int i = 0; i < 20; i++) {
			int hi = Character.digit(hex.charAt(i * 2), 16);
			int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
			if (hi < 0 || lo < 0) {
				throw new IllegalArgumentException("non-hex character in infohash");
			}
			out[i] = (byte) ((hi << 4) | lo);
		}
		return out;
	}
}
