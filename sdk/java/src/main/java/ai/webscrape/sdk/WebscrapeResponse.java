package ai.webscrape.sdk;

/**
 * A successful ({@code completed}) or dispatched ({@code queued}) response envelope. Never an
 * error — error envelopes are thrown as {@link WebscrapeApiException}. The envelope is
 * preserved rather than flattened: users watch credit drain, and the two request ids stay
 * distinct — the envelope {@link #getRequestId()} ({@code req_...}, support-facing) versus the
 * extraction id on the {@code data} object (distinct from this top-level {@code request_id}).
 *
 * @param <T> the typed {@code data} payload
 */
public final class WebscrapeResponse<T> {

    private final String requestId;
    private final Integer creditsUsed;
    private final Integer creditsRemaining;
    private final T data;

    WebscrapeResponse(String requestId, Integer creditsUsed, Integer creditsRemaining, T data) {
        this.requestId = requestId;
        this.creditsUsed = creditsUsed;
        this.creditsRemaining = creditsRemaining;
        this.data = data;
    }

    /** Support-facing envelope request id ({@code req_...}); mirrors the {@code X-Request-ID} header. */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Credits charged for this request, or {@code null} on a {@code queued} dispatch (which is
     * billed later, on completion). Always {@code 0} for the free polling/usage endpoints.
     */
    public Integer getCreditsUsed() {
        return creditsUsed;
    }

    /** Remaining wallet balance after this request, or {@code null} on dispatch. */
    public Integer getCreditsRemaining() {
        return creditsRemaining;
    }

    /** The typed payload. */
    public T getData() {
        return data;
    }
}
