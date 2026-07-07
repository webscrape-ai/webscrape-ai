package ai.webscrape.sdk;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Retry backoff strategy. Package-private.
 */
interface Backoff {
    /**
     * @param attempt          zero-based index of the attempt that just failed
     * @param retryAfterMillis honored server {@code Retry-After} in millis, or {@code null}
     * @return delay in millis before the next attempt
     */
    long delayMillis(int attempt, Long retryAfterMillis);
}

/**
 * Exponential backoff with full jitter and a hard cap. Honors a {@code Retry-After} header
 * when present (clamped to the cap).
 */
final class FullJitterBackoff implements Backoff {

    private final long baseMillis;
    private final long capMillis;

    FullJitterBackoff(long baseMillis, long capMillis) {
        this.baseMillis = baseMillis;
        this.capMillis = capMillis;
    }

    @Override
    public long delayMillis(int attempt, Long retryAfterMillis) {
        if (retryAfterMillis != null) {
            return Math.min(Math.max(0L, retryAfterMillis), capMillis);
        }
        // base * 2^attempt, guarding against shift overflow, then full jitter in [0, window].
        long window;
        if (attempt >= 32) {
            window = capMillis;
        } else {
            long scaled = baseMillis * (1L << attempt);
            window = (scaled < 0 || scaled > capMillis) ? capMillis : scaled;
        }
        if (window <= 0) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(0, window + 1);
    }
}
