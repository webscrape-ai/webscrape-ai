package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code internal_error} (HTTP 500) / {@code service_unavailable} (HTTP 502) — safe to retry. */
public class ServerException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public ServerException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }
}
