package core.transfer;

/**
 * The peer-to-peer transfer protocol messages, exchanged over a persistent TCP
 * connection between two peers sharing one torrent.
 *
 * <p>Ordinals are written on the wire (see {@code TransferCodec}), so do NOT
 * reorder them. This is the minimal complete set for a working transfer;
 * INTERESTED/CHOKE (upload fairness) are deferred — they only matter once many
 * peers compete for upload slots.
 */
public enum TransferMessageType {
	/** First message on a connection: which torrent + who I am. */
	HANDSHAKE,
	/** Sender's full piece availability (packed bits). */
	BITFIELD,
	/** Sender just acquired one piece. */
	HAVE,
	/** Please send me this piece. */
	REQUEST,
	/** Here is the piece you requested. */
	PIECE;
}
