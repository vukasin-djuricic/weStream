package core.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a file into fixed-size pieces and SHA-1-hashes each, producing the
 * {@link TorrentMetadata} that identifies and verifies the file.
 */
public final class PieceHasher {

	private PieceHasher() {
	}

	/** Stream {@code file} in {@code pieceSize} chunks, SHA-1 each, and build its metadata. */
	public static TorrentMetadata fromFile(Path file, int pieceSize) throws IOException {
		if (pieceSize <= 0) {
			throw new IllegalArgumentException("pieceSize must be positive: " + pieceSize);
		}
		long totalLength = Files.size(file);
		List<byte[]> hashes = new ArrayList<>();
		byte[] buffer = new byte[pieceSize];

		try (InputStream in = Files.newInputStream(file)) {
			int filled;
			while ((filled = readFully(in, buffer)) > 0) {
				byte[] piece = (filled == pieceSize) ? buffer : java.util.Arrays.copyOf(buffer, filled);
				hashes.add(sha1(piece));
			}
		}
		return new TorrentMetadata(pieceSize, totalLength, hashes);
	}

	/** SHA-1 of {@code data} (20 bytes). */
	public static byte[] sha1(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-1").digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 is required but not available", e);
		}
	}

	/** Read until {@code buffer} is full or EOF; returns bytes read (0 at clean EOF). */
	private static int readFully(InputStream in, byte[] buffer) throws IOException {
		int total = 0;
		int n;
		while (total < buffer.length && (n = in.read(buffer, total, buffer.length - total)) != -1) {
			total += n;
		}
		return total;
	}
}
