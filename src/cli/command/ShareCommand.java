package cli.command;

import java.nio.file.Path;

import app.AppConfig;
import core.transfer.TorrentMetadata;

/**
 * <code>share &lt;path&gt;</code> — split + hash a file, seed it over TCP, and
 * announce its infohash in the DHT. Prints the infohash other nodes pass to
 * <code>download</code>. Runs on the CLI thread, so the blocking announce
 * ({@code storeValue}) is safe here.
 */
public class ShareCommand implements CLICommand {

	@Override
	public String commandName() {
		return "share";
	}

	@Override
	public void execute(String args) {
		if (args == null || args.isBlank()) {
			AppConfig.timestampedErrorPrint("Usage: share <path>");
			return;
		}
		String path = args.trim();
		try {
			TorrentMetadata meta = AppConfig.transferService.share(Path.of(path));
			AppConfig.timestampedStandardPrint("Sharing '" + path + "' infohash=" + meta.infohash()
					+ " (" + meta.pieceCount() + " pieces, " + meta.totalLength() + " bytes)");
		} catch (Exception e) {
			AppConfig.timestampedErrorPrint("share failed for '" + path + "': " + e.getMessage());
		}
	}
}
