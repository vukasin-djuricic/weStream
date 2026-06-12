package core.transfer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import core.kademlia.NodeId;

/**
 * Immutable description of a shareable file: how it is split into fixed-size
 * pieces, the SHA-1 of each piece, and a content-derived {@code infohash}.
 *
 * <p>This is weStream's tiny "torrent" record. It is small (a 20-byte hash per
 * piece) and is exactly what a downloader needs to verify pieces and reconstruct
 * the file — so in {@link core.transfer} it doubles as the value announced in the
 * DHT under the {@code infohash} key (see {@code TransferService}).
 *
 * <p>The {@code infohash} is {@code SHA-1(pieceSize ‖ totalLength ‖ pieceCount ‖
 * each 20-byte piece hash)}, wrapped as a {@link NodeId} so it drops straight into
 * the Kademlia key space. It is deterministic (same bytes → same infohash) and
 * sensitive to any content change (a flipped byte changes a piece hash).
 */
public final class TorrentMetadata {

	/** Default piece size: 256 KB. Tests use a smaller size to exercise multi-piece logic. */
	public static final int DEFAULT_PIECE_SIZE = 256 * 1024;

	private final int pieceSize;
	private final long totalLength;
	private final List<byte[]> pieceHashes;
	private final NodeId infohash;

	public TorrentMetadata(int pieceSize, long totalLength, List<byte[]> pieceHashes) {
		if (pieceSize <= 0) {
			throw new IllegalArgumentException("pieceSize must be positive: " + pieceSize);
		}
		if (totalLength < 0) {
			throw new IllegalArgumentException("totalLength must be non-negative: " + totalLength);
		}
		this.pieceSize = pieceSize;
		this.totalLength = totalLength;

		// Defensive deep copy — the metadata is immutable (blueprint rule #2).
		List<byte[]> copy = new ArrayList<>(pieceHashes.size());
		for (byte[] h : pieceHashes) {
			copy.add(h.clone());
		}
		this.pieceHashes = copy;
		this.infohash = computeInfohash();
	}

	private NodeId computeInfohash() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(pieceSize);
			out.writeLong(totalLength);
			out.writeInt(pieceHashes.size());
			for (byte[] h : pieceHashes) {
				out.write(h);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e); // ByteArrayOutputStream never actually throws
		}
		return NodeId.fromBytes(bytes.toByteArray());
	}

	public int pieceSize() {
		return pieceSize;
	}

	public long totalLength() {
		return totalLength;
	}

	public int pieceCount() {
		return pieceHashes.size();
	}

	/** Byte length of piece {@code index}: {@code pieceSize}, or the remainder for the last piece. */
	public int lengthOfPiece(int index) {
		checkIndex(index);
		if (index < pieceHashes.size() - 1) {
			return pieceSize;
		}
		return (int) (totalLength - (long) index * pieceSize);
	}

	/** Byte offset of piece {@code index} within the file. */
	public long offsetOfPiece(int index) {
		checkIndex(index);
		return (long) index * pieceSize;
	}

	/** Defensive copy of the 20-byte SHA-1 of piece {@code index}. */
	public byte[] pieceHash(int index) {
		checkIndex(index);
		return pieceHashes.get(index).clone();
	}

	public NodeId infohash() {
		return infohash;
	}

	private void checkIndex(int index) {
		if (index < 0 || index >= pieceHashes.size()) {
			throw new IndexOutOfBoundsException("piece index " + index + " of " + pieceHashes.size());
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof TorrentMetadata)) {
			return false;
		}
		TorrentMetadata other = (TorrentMetadata) obj;
		if (pieceSize != other.pieceSize || totalLength != other.totalLength
				|| pieceHashes.size() != other.pieceHashes.size()) {
			return false;
		}
		for (int i = 0; i < pieceHashes.size(); i++) {
			if (!Arrays.equals(pieceHashes.get(i), other.pieceHashes.get(i))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		return infohash.hashCode();
	}

	@Override
	public String toString() {
		return "TorrentMetadata[infohash=" + infohash + ", pieces=" + pieceHashes.size()
				+ ", pieceSize=" + pieceSize + ", totalLength=" + totalLength + "]";
	}
}
