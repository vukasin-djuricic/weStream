package core.kademlia;

import java.io.Serializable;

/**
 * A reference to another Kademlia node: its {@link NodeId} plus the address
 * needed to reach it. This is the Kademlia counterpart of the Chord
 * {@code core.ServentInfo}, but keyed by a 160-bit id rather than a Chord id.
 *
 * <p>{@code lastSeen} supports the k-bucket least-recently-seen policy and is
 * the only mutable field; identity ({@link #equals}/{@link #hashCode}) is based
 * solely on the immutable {@link NodeId}.
 */
public final class Contact implements Serializable {

	private static final long serialVersionUID = 1L;

	private final NodeId id;
	private final String host;
	private final int port;
	private volatile long lastSeen;

	public Contact(String host, int port) {
		this.host = host;
		this.port = port;
		this.id = NodeId.fromPort(port);
		this.lastSeen = System.currentTimeMillis();
	}

	public NodeId getId() {
		return id;
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}

	public long getLastSeen() {
		return lastSeen;
	}

	/** Mark this contact as just-seen (moves it to most-recently-seen). */
	public void touch() {
		this.lastSeen = System.currentTimeMillis();
	}

	@Override
	public boolean equals(Object obj) {
		return (obj instanceof Contact) && id.equals(((Contact) obj).id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public String toString() {
		return "[" + id + "|" + host + ":" + port + "]";
	}
}
