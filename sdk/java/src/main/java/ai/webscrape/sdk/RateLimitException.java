package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code rate_limited} (HTTP 429) — a per-plan throttle was exceeded. Branch on
 * {@link #getReason()} to tell the causes apart: {@code rate_limit_per_min},
 * {@code max_concurrent_requests}, or {@code sb_runs_per_month}.
 */
public class RateLimitException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public RateLimitException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }

    /** {@code details.reason} discriminator, or {@code null}. */
    public String getReason() {
        return detailString("reason");
    }

    /** {@code details.limit_per_min} for {@code rate_limit_per_min}, or {@code null}. */
    public Integer getLimitPerMin() {
        return detailInt("limit_per_min");
    }

    /** {@code details.max_concurrent} for {@code max_concurrent_requests}, or {@code null}. */
    public Integer getMaxConcurrent() {
        return detailInt("max_concurrent");
    }

    /** {@code details.limit} for {@code sb_runs_per_month}, or {@code null}. */
    public Integer getLimit() {
        return detailInt("limit");
    }

    /** {@code details.used} for {@code sb_runs_per_month}, or {@code null}. */
    public Integer getUsed() {
        return detailInt("used");
    }
}
