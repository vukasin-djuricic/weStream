package core.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import core.kademlia.KademliaService;
import core.kademlia.NodeId;
import core.kademlia.UdpTransport;

/**
 * Zero-dependency regression checks for the transfer/streaming layer — run via
 * {@code ./check.sh} alongside {@code KademliaCheck}.
 *
 * <p>Same shape as {@code core.kademlia.KademliaCheck}: lives in {@code test/}
 * but in package {@code core.transfer} to reach package-internal types, groups
 * run in isolation via {@link #runGroup}, and the process exits non-zero on any
 * failure so it plugs straight into the git hook / CI.
 */
public class TransferCheck {

	private static int passed = 0;
	private static final List<String> failures = new ArrayList<>();

	public static void main(String[] args) {
		runGroup("piece model (no sockets)", TransferCheck::pieceModelChecks);
		runGroup("transfer wire (codec + framing)", TransferCheck::wireChecks);
		runGroup("picker ordering (unit)", TransferCheck::pickerChecks);
		runGroup("announce piece sizing (unit)", TransferCheck::pieceSizingChecks);
		runGroup("upload throttle (unit)", TransferCheck::throttleChecks);
		runGroup("end-to-end transfer (real localhost TCP)", TransferCheck::endToEndChecks);
		runGroup("DHT discovery + announce (real UDP + TCP)", TransferCheck::dhtDiscoveryChecks);
		runGroup("multi-peer swarm (two seeds, real UDP + TCP)", TransferCheck::multiPeerChecks);

		System.out.println();
		System.out.println(passed + " passed, " + failures.size() + " failed");
		if (!failures.isEmpty()) {
			failures.forEach(f -> System.out.println("  FAILED: " + f));
			System.exit(1);
		}
		System.out.println("OK");
	}

	// ---------------------------------------------------------------- groups

	private static void pieceModelChecks() throws Exception {
		int pieceSize = 1024;
		int totalLength = 3500; // 3 full pieces + 428
		byte[] content = deterministicBytes(totalLength, 0);

		Path src = writeTempFile(content);
		Path out = Files.createTempFile("westream-out", ".bin");
		Path badOut = Files.createTempFile("westream-bad", ".bin");
		try {
			TorrentMetadata meta = PieceHasher.fromFile(src, pieceSize);
			TorrentMetadata meta2 = PieceHasher.fromFile(src, pieceSize);
			check("infohash deterministic", meta.infohash().equals(meta2.infohash()));

			byte[] flipped = content.clone();
			flipped[1000] ^= 0x01;
			Path src2 = writeTempFile(flipped);
			try {
				TorrentMetadata metaFlipped = PieceHasher.fromFile(src2, pieceSize);
				check("infohash content-sensitive", !meta.infohash().equals(metaFlipped.infohash()));
			} finally {
				Files.deleteIfExists(src2);
			}

			check("pieceCount = ceil(total/pieceSize)", meta.pieceCount() == 4);
			check("last piece length = remainder", meta.lengthOfPiece(3) == 428);
			check("full piece length", meta.lengthOfPiece(0) == 1024);

			// Write every piece into a fresh store; the file must reconstruct byte-identically.
			try (PieceStore store = new PieceStore(out, meta)) {
				for (int i = 0; i < meta.pieceCount(); i++) {
					byte[] piece = slice(content, meta.offsetOfPiece(i), meta.lengthOfPiece(i));
					check("writePiece " + i + " accepted", store.writePiece(i, piece));
				}
				check("store complete after all pieces", store.isComplete());
				check("readPiece round-trips",
						Arrays.equals(store.readPiece(2), slice(content, meta.offsetOfPiece(2), meta.lengthOfPiece(2))));
			}
			check("reconstructed file byte-identical", Arrays.equals(Files.readAllBytes(out), content));

			// Seeding a READ-ONLY source must work (regression: share() opened the file
			// "rw" + setLength, which fails with "Access is denied" on a read-only file
			// — exactly what blocked sharing a movie on a read-only volume).
			boolean madeReadOnly = src.toFile().setWritable(false);
			try (PieceStore seed = PieceStore.forSeeding(src, meta)) {
				check("read-only source set non-writable", madeReadOnly);
				check("seed opens + verifies a read-only file",
						seed.verifyAndMarkExisting() == meta.pieceCount() && seed.isComplete());
				check("seed read-only writePiece fails cleanly",
						!seed.writePiece(0, slice(content, 0, meta.lengthOfPiece(0))));
			} finally {
				src.toFile().setWritable(true); // let the temp file be deleted on cleanup
			}
			check("seeding never changed the source bytes", Arrays.equals(Files.readAllBytes(src), content));

			// A corrupted piece (right length, wrong bytes) is rejected and leaves the bit clear.
			try (PieceStore store = new PieceStore(badOut, meta)) {
				byte[] corrupt = slice(content, 0, meta.lengthOfPiece(0));
				corrupt[0] ^= 0x01;
				check("bad piece rejected", !store.writePiece(0, corrupt));
				check("bad piece leaves bit clear", !store.bitfield().get(0));
				check("wrong-length piece rejected", !store.writePiece(0, new byte[5]));
			}

			// Bitfield pack/unpack + completeness.
			Bitfield bf = new Bitfield(10);
			bf.set(1);
			bf.set(3);
			bf.set(9);
			Bitfield round = new Bitfield(10, bf.toBytes());
			boolean sameBits = true;
			for (int i = 0; i < 10; i++) {
				sameBits &= (bf.get(i) == round.get(i));
			}
			check("bitfield pack/unpack preserves bits", sameBits);
			check("bitfield cardinality", round.cardinality() == 3);
			check("bitfield not complete", !round.isComplete());
			for (int i = 0; i < 10; i++) {
				round.set(i);
			}
			check("bitfield complete when all set", round.isComplete());
			checkThrows("bitfield rejects wrong-length packed", () -> new Bitfield(10, new byte[1]));

			// C1 (hostile announce): TorrentMetadata is the single gate every decode path
			// flows through, so it must reject the values that would otherwise blow up
			// PieceStore.setLength / the long→int piece-length math.
			List<byte[]> oneHash = List.of(new byte[20]);
			checkThrows("rejects totalLength=Long.MAX_VALUE",
					() -> new TorrentMetadata(1, Long.MAX_VALUE, oneHash));
			checkThrows("rejects pieceSize > MAX_FRAME",
					() -> new TorrentMetadata(TransferCodec.MAX_FRAME + 1, 10, oneHash));
			checkThrows("rejects pieceCount inconsistent with totalLength/pieceSize",
					() -> new TorrentMetadata(1024, 1, List.of(new byte[20], new byte[20]))); // expects 1, got 2
			// A consistent record still constructs (sanity: validation isn't over-eager).
			check("consistent metadata accepted",
					new TorrentMetadata(1024, 1500, List.of(new byte[20], new byte[20])).pieceCount() == 2);
		} finally {
			Files.deleteIfExists(src);
			Files.deleteIfExists(out);
			Files.deleteIfExists(badOut);
		}
	}

	private static void wireChecks() throws Exception {
		TransferCodec codec = new TransferCodec();

		NodeId infohash = NodeId.fromBytes("infohash".getBytes());
		NodeId sender = NodeId.fromBytes("sender".getBytes());
		List<TransferMessage> samples = List.of(
				TransferMessage.handshake(infohash, sender),
				TransferMessage.bitfield(new byte[] { 0x0A, (byte) 0xFF, 0x01 }),
				TransferMessage.have(42),
				TransferMessage.request(7),
				TransferMessage.piece(7, deterministicBytes(300, 5)));

		for (TransferMessage m : samples) {
			TransferMessage round = codec.decodeBody(codec.encodeBody(m));
			check("body round-trips: " + m.type, m.equals(round));
		}

		// Two frames back-to-back over one stream must both recover (framing works).
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dout = new DataOutputStream(baos);
		codec.writeFrame(dout, samples.get(0));
		codec.writeFrame(dout, samples.get(4));
		DataInputStream din = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
		check("first frame recovered from stream", codec.readFrame(din).equals(samples.get(0)));
		check("second frame recovered from stream", codec.readFrame(din).equals(samples.get(4)));

		checkThrows("truncated frame rejected", () -> {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			DataOutputStream d = new DataOutputStream(b);
			d.writeInt(100);            // claims 100 body bytes...
			d.write(new byte[10]);      // ...but only 10 follow
			new TransferCodec().readFrame(new DataInputStream(new ByteArrayInputStream(b.toByteArray())));
		});
		checkThrows("huge frame length rejected (no OOM)", () -> {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			new DataOutputStream(b).writeInt(Integer.MAX_VALUE);
			new TransferCodec().readFrame(new DataInputStream(new ByteArrayInputStream(b.toByteArray())));
		});
		checkThrows("negative frame length rejected", () -> {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			new DataOutputStream(b).writeInt(-1);
			new TransferCodec().readFrame(new DataInputStream(new ByteArrayInputStream(b.toByteArray())));
		});
		checkThrows("bad ordinal rejected", () -> new TransferCodec().decodeBody(new byte[] { (byte) 99 }));
	}

	/**
	 * {@link TransferService#pieceSizeFor} must keep the piece count (hence the
	 * single-datagram metadata announce) bounded for large files. Regression for
	 * the 1.8 GB share that overflowed the 64 KB UDP packet with 256 KB pieces.
	 */
	private static void pieceSizingChecks() {
		int dflt = TorrentMetadata.DEFAULT_PIECE_SIZE; // 256 KB
		check("small file keeps default piece size", TransferService.pieceSizeFor(10L << 20) == dflt);

		long[] sizes = {800L << 20, 1827038238L /* the Sherlock file */, 8L << 30, 50L << 30};
		for (long size : sizes) {
			int ps = TransferService.pieceSizeFor(size);
			long pieceCount = (size + ps - 1) / ps;
			check("piece size is power-of-two for " + size, (ps & (ps - 1)) == 0);
			check("piece size never below default for " + size, ps >= dflt);
			check("piece count bounded for " + size + " (" + pieceCount + " pieces)",
					pieceCount <= 2048);
			// The announce blob (20 bytes/hash + small header) must clear the 64 KB datagram.
			check("metadata hashes fit a UDP datagram for " + size,
					pieceCount * 20 + 64 < 64 * 1024);
		}
		// The Sherlock file specifically must land on 1 MB pieces.
		check("1.8 GB file scales to 1 MB pieces",
				TransferService.pieceSizeFor(1827038238L) == (1 << 20));
	}

	/**
	 * {@link RateLimiter} must be off unless configured, let a one-second burst
	 * through instantly, then pace bytes beyond it at the set rate. This is what
	 * makes the sliding-window demo observable (throttled seed upload).
	 */
	private static void throttleChecks() throws Exception {
		// Off by default — no env/property set during the test run.
		check("throttle off by default (fromEnv null)", RateLimiter.fromEnv() == null);

		RateLimiter rl = new RateLimiter(10_000); // 10 KB/s, 10 KB burst bucket
		long t0 = System.nanoTime();
		rl.acquire(10_000); // drains the burst — should be effectively instant
		long burstMs = (System.nanoTime() - t0) / 1_000_000;
		check("burst passes without blocking (" + burstMs + " ms)", burstMs < 100);

		long t1 = System.nanoTime();
		rl.acquire(5_000); // 5 KB of debt at 10 KB/s ≈ 500 ms
		long pacedMs = (System.nanoTime() - t1) / 1_000_000;
		// Lower bound only (sleep never returns early); generous upper bound for slow CI.
		check("over-budget send is paced (" + pacedMs + " ms ~ 500)", pacedMs >= 400 && pacedMs < 3000);

		checkThrows("rejects non-positive rate", () -> new RateLimiter(0));

		// Regression (starvation): acquire() must not hold its lock while sleeping, or a
		// continuously-requesting connection starves every other one -- the symptom was
		// a second downloader from one throttled seed sitting at 0 B/s.
		RateLimiter shared = new RateLimiter(200_000); // 200 KB/s shared bucket
		java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
		Thread hammer = new Thread(() -> {
			try { while (!stop.get()) shared.acquire(20_000); } catch (InterruptedException ignored) { }
		});
		hammer.setDaemon(true);
		Thread other = new Thread(() -> {
			try { for (int i = 0; i < 3; i++) shared.acquire(20_000); } catch (InterruptedException ignored) { }
		});
		other.setDaemon(true);
		hammer.start();
		other.start();
		other.join(6000); // the "second downloader" must finish despite the hammerer
		boolean otherFinished = !other.isAlive();
		stop.set(true);
		other.interrupt();
		check("throttle shares fairly (concurrent acquirer not starved)", otherFinished);
	}

	private static void pickerChecks() {
		// Sliding window: nearest the playhead first.
		SlidingWindowPicker sw = new SlidingWindowPicker(10, 3);
		Bitfield have = new Bitfield(10);
		Bitfield available = full(10);
		Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
		check("sliding-window picks playhead first", sw.pick(have, available, inFlight) == 0);
		inFlight.add(0);
		check("sliding-window skips in-flight to next near piece", sw.pick(have, available, inFlight) == 1);
		inFlight.clear();
		sw.setPlayhead(5);
		check("sliding-window follows the playhead", sw.pick(have, available, inFlight) == 5);

		// Rarest first: the piece held by the fewest peers.
		RarestFirstPicker rf = new RarestFirstPicker(4);
		Bitfield peerA = full(4);                 // has every piece
		Bitfield peerB = new Bitfield(4);
		peerB.set(1);
		peerB.set(2);
		peerB.set(3);                              // lacks piece 0 -> 0 is rarest
		rf.onPeerBitfield(peerA);
		rf.onPeerBitfield(peerB);
		check("rarest-first picks the rarest piece",
				rf.pick(new Bitfield(4), full(4), ConcurrentHashMap.newKeySet()) == 0);
	}

	private static void endToEndChecks() throws Exception {
		int pieceSize = 512;
		int totalLength = 512 * 5 + 137; // 6 pieces, last one short
		byte[] content = deterministicBytes(totalLength, 9);

		Path src = writeTempFile(content);
		Path out = Files.createTempFile("westream-dl", ".bin");
		PieceStore seedStore = null;
		SeedSession seed = null;
		TcpPeerServer server = null;
		DownloadSession dl = null;
		try {
			TorrentMetadata meta = PieceHasher.fromFile(src, pieceSize);

			seedStore = new PieceStore(src, meta);
			check("seed verifies all pieces on open", seedStore.verifyAndMarkExisting() == meta.pieceCount());
			seed = new SeedSession(seedStore, NodeId.fromEndpoint("seed", 1));
			server = new TcpPeerServer(0);
			final SeedSession seedRef = seed;
			server.setConnectionHandler(seedRef::handle);
			server.start();

			PiecePicker picker = new SlidingWindowPicker(meta.pieceCount(), 4);
			dl = new DownloadSession(meta, out, NodeId.fromEndpoint("dl", 2), picker, 8, 8);
			dl.addPeer(new Socket("127.0.0.1", server.getLocalPort()));

			check("download completes", dl.awaitCompletion(10_000));
			check("output byte-identical to source", Arrays.equals(Files.readAllBytes(out), content));
			check("output infohash matches source",
					PieceHasher.fromFile(out, pieceSize).infohash().equals(meta.infohash()));

			// progress() — the Phase-5 UI snapshot. On a completed download every piece
			// must read HAVE, fraction()==1, and pieceStates length must equal pieceCount.
			DownloadSession.Progress p = dl.progress();
			check("progress() reports complete", p.have() == meta.pieceCount() && p.fraction() == 1.0);
			check("progress() pieceStates sized to pieceCount", p.pieceStates().length == meta.pieceCount());
			boolean allHave = true;
			for (byte st : p.pieceStates()) {
				if (st != DownloadSession.HAVE) {
					allHave = false;
				}
			}
			check("progress() all pieces marked HAVE", allHave);
		} finally {
			if (dl != null) {
				dl.close();
			}
			if (server != null) {
				server.close();
			}
			if (seed != null) {
				seed.close();
			}
			if (seedStore != null) {
				seedStore.close();
			}
			Files.deleteIfExists(src);
			Files.deleteIfExists(out);
		}
	}

	private static void dhtDiscoveryChecks() throws Exception {
		int n = 3;
		List<UdpTransport> transports = new ArrayList<>();
		List<KademliaService> nodes = new ArrayList<>();
		TransferService seederSvc = null;
		TransferService leecherSvc = null;
		Path src = null;
		Path out = null;
		Path miss = null;
		try {
			for (int i = 0; i < n; i++) {
				UdpTransport t = new UdpTransport(0);
				KademliaService s = new KademliaService("127.0.0.1", t.getLocalPort(), t);
				s.start();
				transports.add(t);
				nodes.add(s);
			}
			for (int i = 1; i < n; i++) {
				nodes.get(i).bootstrap(nodes.get(0).self());
			}

			seederSvc = new TransferService(nodes.get(0));
			leecherSvc = new TransferService(nodes.get(2));

			byte[] content = deterministicBytes(512 * 4 + 11, 3); // 5 pieces at pieceSize 512
			src = writeTempFile(content);
			out = Files.createTempFile("westream-dht-dl", ".bin");
			miss = Files.createTempFile("westream-dht-miss", ".bin");

			TorrentMetadata meta = seederSvc.share(src, 512);
			check("announce resolvable via findValue", nodes.get(2).findValue(meta.infohash()) != null);
			check("download via DHT completes", leecherSvc.download(meta.infohash(), out));
			check("DHT-downloaded file byte-identical", Arrays.equals(Files.readAllBytes(out), content));

			NodeId bogus = NodeId.fromBytes("never-announced".getBytes());
			check("unknown infohash fails cleanly", !leecherSvc.download(bogus, miss));
		} finally {
			if (seederSvc != null) {
				seederSvc.close();
			}
			if (leecherSvc != null) {
				leecherSvc.close();
			}
			transports.forEach(UdpTransport::close);
			if (src != null) {
				Files.deleteIfExists(src);
			}
			if (out != null) {
				Files.deleteIfExists(out);
			}
			if (miss != null) {
				Files.deleteIfExists(miss);
			}
		}
	}

	/**
	 * The headline multi-peer test: TWO independent seeds announce the SAME file,
	 * and a leecher must discover and pull from BOTH (the union the old one-value
	 * announce could never express).
	 */
	private static void multiPeerChecks() throws Exception {
		int n = 4;
		List<UdpTransport> transports = new ArrayList<>();
		List<KademliaService> nodes = new ArrayList<>();
		TransferService seed0 = null;
		TransferService seed1 = null;
		TransferService leecher = null;
		Path src = null;
		Path out = null;
		try {
			for (int i = 0; i < n; i++) {
				UdpTransport t = new UdpTransport(0);
				KademliaService s = new KademliaService("127.0.0.1", t.getLocalPort(), t);
				s.start();
				transports.add(t);
				nodes.add(s);
			}
			for (int i = 1; i < n; i++) {
				nodes.get(i).bootstrap(nodes.get(0).self());
			}

			// 40 pieces at 512: large enough that a per-peer cap (8) forces BOTH seeds to
			// serve pieces (neither can hold the whole file in flight), so we can prove
			// the swarm actually spreads the work — not just connects two sockets.
			byte[] content = deterministicBytes(512 * 40, 4);
			src = writeTempFile(content);
			out = Files.createTempFile("westream-swarm-dl", ".bin");

			// Two independent seeds share the SAME file -> same infohash, two peers.
			seed0 = new TransferService(nodes.get(0), Files.createTempDirectory("ws-seed0"));
			seed1 = new TransferService(nodes.get(1), Files.createTempDirectory("ws-seed1"));
			leecher = new TransferService(nodes.get(3), Files.createTempDirectory("ws-leech"));

			TorrentMetadata meta = seed0.share(src, 512);
			TorrentMetadata meta1 = seed1.share(src, 512);
			check("both seeds derive the same infohash", meta.infohash().equals(meta1.infohash()));

			List<core.kademlia.PeerStore.PeerEntry> swarm = nodes.get(3).getPeers(meta.infohash());
			check("getPeers sees both seeds", swarm.size() == 2);

			DownloadSession dl = leecher.startDownload(meta.infohash(), out);
			check("startDownload returns a session", dl != null);
			// addPeer connects synchronously, so both peers attach before we return.
			check("download connects to BOTH seeds", dl != null && dl.peerCount() == 2);
			check("multi-peer download completes", dl != null && dl.awaitCompletion(10_000));
			check("swarm-downloaded file byte-identical", Arrays.equals(Files.readAllBytes(out), content));
			// The fix: per-peer in-flight cap + re-pump all peers means the work spreads
			// across the swarm. Both seeds must have actually delivered pieces (regression
			// for the bug where the first seeder to send its bitfield hogged the whole
			// global budget and the second one starved).
			check("download pulled pieces from BOTH seeds (not just one)",
					dl != null && dl.contributingPeerCount() == 2);
		} finally {
			if (seed0 != null) {
				seed0.close();
			}
			if (seed1 != null) {
				seed1.close();
			}
			if (leecher != null) {
				leecher.close();
			}
			transports.forEach(UdpTransport::close);
			if (src != null) {
				Files.deleteIfExists(src);
			}
			if (out != null) {
				Files.deleteIfExists(out);
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	private static Bitfield full(int pieceCount) {
		Bitfield bf = new Bitfield(pieceCount);
		for (int i = 0; i < pieceCount; i++) {
			bf.set(i);
		}
		return bf;
	}

	private static byte[] deterministicBytes(int n, int seed) {
		byte[] data = new byte[n];
		for (int i = 0; i < n; i++) {
			data[i] = (byte) ((i * 31 + 7 + seed) & 0xff);
		}
		return data;
	}

	private static byte[] slice(byte[] all, long offset, int length) {
		return Arrays.copyOfRange(all, (int) offset, (int) offset + length);
	}

	private static Path writeTempFile(byte[] content) throws IOException {
		Path p = Files.createTempFile("westream-src", ".bin");
		Files.write(p, content);
		return p;
	}

	private interface Group {
		void run() throws Exception;
	}

	/** A throwing action, so {@link #checkThrows} can wrap code that declares checked exceptions. */
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static void runGroup(String name, Group group) {
		System.out.println("-- " + name + " --");
		try {
			group.run();
		} catch (Throwable t) {
			failures.add(name + " group crashed: " + t);
			System.out.println("GROUP CRASH: " + t);
		}
	}

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("ok: " + name);
		} else {
			failures.add(name);
			System.out.println("FAIL: " + name);
		}
	}

	/** Passes iff {@code action} throws — used to assert hostile/invalid input is rejected. */
	private static void checkThrows(String name, ThrowingRunnable action) {
		try {
			action.run();
			failures.add(name + " (did not throw)");
			System.out.println("FAIL: " + name + " (did not throw)");
		} catch (Throwable expected) {
			passed++;
			System.out.println("ok: " + name + " (threw " + expected.getClass().getSimpleName() + ")");
		}
	}
}
