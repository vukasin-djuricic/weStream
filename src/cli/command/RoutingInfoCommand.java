package cli.command;

import app.AppConfig;
import core.kademlia.Contact;
import core.kademlia.RoutingTable;

/**
 * <code>routing_info</code> — prints the contacts this node currently knows, the
 * Kademlia counterpart of Chord's <code>successor_info</code>. Useful for
 * confirming that bootstrap populated the routing table.
 */
public class RoutingInfoCommand implements CLICommand {

	@Override
	public String commandName() {
		return "routing_info";
	}

	@Override
	public void execute(String args) {
		RoutingTable routingTable = AppConfig.kademliaService.routingTable();

		AppConfig.timestampedStandardPrint("Routing table size: " + routingTable.size());

		int num = 0;
		for (Contact contact : routingTable.allContacts()) {
			System.out.println(num + ": " + contact);
			num++;
		}
	}

}
