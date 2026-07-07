package ai.webscrape.sdk;

import java.time.Duration;
import java.util.Objects;

/**
 * Options for the SmartBrowse wait helpers. The poll interval is the <em>initial</em> gap; it
 * grows ×1.5 per poll, capped at 10s. The default timeout of 900s matches the 15-minute hard
 * cap on a run.
 */
public final class WaitOptions {

    private static final WaitOptions DEFAULTS =
            new WaitOptions(Duration.ofSeconds(2), Duration.ofSeconds(900));

    private final Duration pollInterval;
    private final Duration timeout;

    private WaitOptions(Duration pollInterval, Duration timeout) {
        this.pollInterval = pollInterval;
        this.timeout = timeout;
    }

    /** Defaults: 2s initial poll interval, 900s timeout. */
    public static WaitOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public Duration getTimeout() {
        return timeout;
    }

    /** Fluent builder for {@link WaitOptions}. */
    public static final class Builder {
        private Duration pollInterval = Duration.ofSeconds(2);
        private Duration timeout = Duration.ofSeconds(900);

        /** Initial poll interval (must be positive). */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = requirePositive(pollInterval, "pollInterval");
            return this;
        }

        /** Overall deadline (must be positive). */
        public Builder timeout(Duration timeout) {
            this.timeout = requirePositive(timeout, "timeout");
            return this;
        }

        public WaitOptions build() {
            return new WaitOptions(pollInterval, timeout);
        }

        private static Duration requirePositive(Duration d, String name) {
            Objects.requireNonNull(d, name);
            if (d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return d;
        }
    }
}
