package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The server returned an error envelope (or a plain {@code {"error": "<message>"}} body).
 * This concrete type is also used directly for <em>unknown</em> error codes that ship without
 * a dedicated subtype; known codes map to the family subtypes.
 *
 * <p>Carries the HTTP status, the stable {@code error.code} string (may be {@code null} for
 * unknown/absent codes), the human message, the raw {@code error.details} JSON, and the
 * support-facing {@code request_id}.</p>
 */
public class WebscrapeApiException extends WebscrapeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String code;
    private final transient JsonNode details;
    private final String requestId;

    public WebscrapeApiException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
        this.details = details;
        this.requestId = requestId;
    }

    /** HTTP status code of the response. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Stable {@code error.code} string, or {@code null} when absent/unknown. */
    public String getCode() {
        return code;
    }

    /** Raw {@code error.details} JSON, or {@code null} when the server sent none. */
    public JsonNode getDetails() {
        return details;
    }

    /** Support-facing envelope request id ({@code req_...}), or {@code null}. */
    public String getRequestId() {
        return requestId;
    }

    /** Convenience: read a scalar string out of {@code details}, or {@code null}. */
    protected String detailString(String field) {
        return Json.text(details, field);
    }

    /** Convenience: read an integer out of {@code details}, or {@code null}. */
    protected Integer detailInt(String field) {
        return Json.integer(details, field);
    }
}
