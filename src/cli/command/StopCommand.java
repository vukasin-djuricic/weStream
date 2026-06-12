package cli.command;

import app.AppConfig;
import cli.CLIParser;

/**
 * <code>stop</code> — stops the CLI loop and closes the UDP transport. Closing
 * the socket ends the daemon receive thread; with no non-daemon threads left,
 * the JVM exits.
 */
public class StopCommand implements CLICommand {

	private final CLIParser parser;

	public StopCommand(CLIParser parser) {
		this.parser = parser;
	}

	@Override
	public String commandName() {
		return "stop";
	}

	@Override
	public void execute(String args) {
		AppConfig.timestampedStandardPrint("Stopping...");
		parser.stop();
		if (AppConfig.transferService != null) {
			AppConfig.transferService.close();
		}
		AppConfig.transport.close();
	}

}
