package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code conflict} / {@code account_deletion_pending} (HTTP 409) — the resource or account
 * is in a state that disallows the action.
 */
public class ConflictException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public ConflictException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }

    /**
     * For {@code account_deletion_pending}: the RFC3339 timestamp the account is scheduled
     * for deletion ({@code details.deletion_scheduled_for}), or {@code null}.
     */
    public String getDeletionScheduledFor() {
        return detailString("deletion_scheduled_for");
    }
}
