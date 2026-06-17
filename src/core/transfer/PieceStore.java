package core.transfer;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Backs a single file's pieces on disk: writes verified pieces into a sparse
 * output file (download side) and reads pieces for upload (seed side).
 *
 * <p>{@link #writePiece} is the single integrity gate — a piece whose SHA-1 does
 * not match the metadata is rejected and never written, so the output file only
 * ever contains verified bytes. All file access is {@code synchronized} (one
 * seek+IO per call) so concurrent peer threads cannot interleave reads/writes.
 */
public final class PieceStore implements Closeable {

	private final RandomAccessFile file;
	private final TorrentMetadata meta;
	private final Bitfield have;

	/** Open {@code path} for read/write, pre-sized to the full file length — the
	 *  download sink (pieces get written + verified into it). */
	public PieceStore(Path path, TorrentMetadata meta) throws IOException {
		this(path, meta, true);
	}

	/**
	 * Open an existing, already-complete file <em>read-only</em> for seeding: never
	 * resized, never written. Use this for {@code share} so a read-only source file
	 * (e.g. a movie on a read-only volume, or any file we must not modify) can still
	 * be served. {@link #writePiece} fails cleanly on such a store (a seed never
	 * writes), and the user's original bytes are guaranteed untouched.
	 */
	public static PieceStore forSeeding(Path path, TorrentMetadata meta) throws IOException {
		return new PieceStore(path, meta, false);
	}

	private PieceStore(Path path, TorrentMetadata meta, boolean writable) throws IOException {
		this.meta = meta;
		this.have = new Bitfield(meta.pieceCount());
		this.file = new RandomAccessFile(path.toFile(), writable ? "rw" : "r");
		if (writable) {
			this.file.setLength(meta.totalLength());
		}
	}

	/**
	 * Verify {@code data} against piece {@code index}'s hash; if it matches, write
	 * it at the right offset and mark the bit. Returns false (and writes nothing)
	 * if the length or hash is wrong.
	 */
	public synchronized boolean writePiece(int index, byte[] data) {
		if (data.length != meta.lengthOfPiece(index)) {
			return false;
		}
		if (!Arrays.equals(PieceHasher.sha1(data), meta.pieceHash(index))) {
			return false;
		}
		try {
			file.seek(meta.offsetOfPiece(index));
			file.write(data);
		} catch (IOException e) {
			return false;
		}
		have.set(index);
		notifyAll(); // wake any streamer parked in awaitPiece for this (or a later) piece
		return true;
	}

	/** True if piece {@code index} is held and verified. */
	public synchronized boolean hasPiece(int index) {
		return have.get(index);
	}

	/**
	 * Block until piece {@code index} is available (verified) or {@code timeoutMs}
	 * elapses — the streaming endpoint's wait for a not-yet-downloaded piece. Parks
	 * the caller on this store's monitor (no busy-wait, blueprint rule #4); woken by
	 * {@link #writePiece}. Returns true if the piece is now available.
	 */
	public synchronized boolean awaitPiece(int index, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!have.get(index)) {
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				return false;
			}
			wait(remaining);
		}
		return true;
	}

	/** Read piece {@code index}'s bytes (for upload). */
	public synchronized byte[] readPiece(int index) throws IOException {
		byte[] data = new byte[meta.lengthOfPiece(index)];
		file.seek(meta.offsetOfPiece(index));
		file.readFully(data);
		return data;
	}

	/**
	 * Hash-check every piece already on disk and mark the bitfield for matches.
	 * A seed opening a complete file calls this to advertise a full bitfield;
	 * returns the number of verified pieces.
	 */
	public synchronized int verifyAndMarkExisting() throws IOException {
		for (int i = 0; i < meta.pieceCount(); i++) {
			byte[] data = new byte[meta.lengthOfPiece(i)];
			file.seek(meta.offsetOfPiece(i));
			file.readFully(data);
			if (Arrays.equals(PieceHasher.sha1(data), meta.pieceHash(i))) {
				have.set(i);
			}
		}
		return have.cardinality();
	}

	public Bitfield bitfield() {
		return have;
	}

	public boolean isComplete() {
		return have.isComplete();
	}

	public TorrentMetadata metadata() {
		return meta;
	}

	@Override
	public synchronized void close() throws IOException {
		file.close();
	}
}
