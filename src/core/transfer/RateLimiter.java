package core.transfer;

/**
 * A token-bucket rate limiter for the upload path — a zero-dependency way to
 * simulate a slow/congested link so the {@link SlidingWindowPicker}'s effect is
 * actually visible. On loopback (and even LAN) a transfer is bounded by disk +
 * CPU and finishes in seconds, which hides the window behaviour; throttling the
 * seed's upload to a few MB/s lets you watch pieces fill in around the playhead
 * and jump when you seek.
 *
 * <p><b>Off by default.</b> {@link #fromEnv()} returns {@code null} unless
 * {@code WS_THROTTLE_KBPS} (environment) or {@code westream.throttleKbps} (system
 * property) is set to a positive number, so normal runs and {@code ./check.sh}
 * are completely unaffected — the only code that consults a limiter is one
 * branch in {@link PeerConnection#sendPiece}, skipped entirely when it is null.
 *
 * <p>The bucket fills at {@code bytesPerSec} and holds up to one second's worth
 * of burst. A single shared instance per node caps that node's <em>total</em>
 * upload (like a real uplink shared across peers). The unit is <b>kilobytes</b>
 * per second (KB/s), so {@code WS_THROTTLE_KBPS=1500} ≈ 1.5 MB/s.
 */
public final class RateLimiter {

	private final double bytesPerSec;
	private final double capacity;
	private double tokens;
	private long lastRefillNanos;

	/** @param bytesPerSec sustained rate in bytes/second (must be positive). */
	public RateLimiter(long bytesPerSec) {
		if (bytesPerSec <= 0) {
			throw new IllegalArgumentException("bytesPerSec must be positive: " + bytesPerSec);
		}
		this.bytesPerSec = bytesPerSec;
		this.capacity = bytesPerSec; // 1s burst bucket
		this.tokens = bytesPerSec;   // start full so the first piece isn't penalised
		this.lastRefillNanos = System.nanoTime();
	}

	/**
	 * Block the caller until {@code bytes} of budget is available, then consume it.
	 * Tokens may go into (transient) debt, which is paid back by sleeping exactly
	 * the time needed to accrue it — this is what enforces the average rate without
	 * any busy-wait (blueprint rule #4).
	 */
	public synchronized void acquire(int bytes) throws InterruptedException {
		if (bytes <= 0) {
			return;
		}
		refill();
		tokens -= bytes;
		if (tokens < 0) {
			long sleepMs = (long) Math.ceil(-tokens / bytesPerSec * 1000.0);
			if (sleepMs > 0) {
				Thread.sleep(sleepMs);
			}
		}
	}

	private void refill() {
		long now = System.nanoTime();
		double add = (now - lastRefillNanos) / 1e9 * bytesPerSec;
		lastRefillNanos = now;
		tokens = Math.min(capacity, tokens + add);
	}

	/** The configured sustained rate in bytes/second (for diagnostics/tests). */
	public double bytesPerSecond() {
		return bytesPerSec;
	}

	/**
	 * A shared limiter built from {@code westream.throttleKbps} (system property,
	 * takes precedence) or {@code WS_THROTTLE_KBPS} (environment), interpreted as
	 * kilobytes/second; {@code null} if neither is set to a positive value (the
	 * default — no throttling).
	 */
	public static RateLimiter fromEnv() {
		long kbps = readKbps();
		return kbps > 0 ? new RateLimiter(kbps * 1024L) : null;
	}

	private static long readKbps() {
		String v = System.getProperty("westream.throttleKbps");
		if (v == null) {
			v = System.getenv("WS_THROTTLE_KBPS");
		}
		if (v == null) {
			return 0;
		}
		try {
			return Math.max(0, Long.parseLong(v.trim()));
		} catch (NumberFormatException e) {
			return 0; // malformed → treat as off rather than crash a node on boot
		}
	}
}
