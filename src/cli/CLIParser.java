package cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import app.AppConfig;
import app.Cancellable;
import cli.command.CLICommand;
import cli.command.DHTGetCommand;
import cli.command.DHTPutCommand;
import cli.command.InfoCommand;
import cli.command.PauseCommand;
import cli.command.RoutingInfoCommand;
import cli.command.StopCommand;

/**
 * A simple CLI parser. Each command has a name and arbitrary arguments.
 *
 * Currently supported commands:
 *
 * <ul>
 * <li><code>info</code> - prints this Kademlia node's id and endpoint</li>
 * <li><code>pause [ms]</code> - pauses execution given number of ms - useful when scripting</li>
 * <li><code>routing_info</code> - prints the contacts in this node's routing table</li>
 * <li><code>dht_put [key] [value]</code> - stores a string value under a string key (storeValue)</li>
 * <li><code>dht_get [key]</code> - retrieves the value for a string key (findValue)</li>
 * <li><code>stop</code> - stops the node and program finishes</li>
 * </ul>
 *
 * @author bmilojkovic
 *
 */
public class CLIParser implements Runnable, Cancellable {

	private volatile boolean working = true;

	private final List<CLICommand> commandList;

	public CLIParser() {
		this.commandList = new ArrayList<>();

		commandList.add(new InfoCommand());
		commandList.add(new PauseCommand());
		commandList.add(new RoutingInfoCommand());
		commandList.add(new DHTGetCommand());
		commandList.add(new DHTPutCommand());
		commandList.add(new StopCommand(this));
	}
	
	@Override
	public void run() {
		Scanner sc = new Scanner(System.in);
		
		while (working) {
			String commandLine = sc.nextLine();
			
			int spacePos = commandLine.indexOf(" ");
			
			String commandName = null;
			String commandArgs = null;
			if (spacePos != -1) {
				commandName = commandLine.substring(0, spacePos);
				commandArgs = commandLine.substring(spacePos+1, commandLine.length());
			} else {
				commandName = commandLine;
			}
			
			boolean found = false;
			
			for (CLICommand cliCommand : commandList) {
				if (cliCommand.commandName().equals(commandName)) {
					cliCommand.execute(commandArgs);
					found = true;
					break;
				}
			}
			
			if (!found) {
				AppConfig.timestampedErrorPrint("Unknown command: " + commandName);
			}
		}
		
		sc.close();
	}
	
	@Override
	public void stop() {
		this.working = false;
		
	}
}
