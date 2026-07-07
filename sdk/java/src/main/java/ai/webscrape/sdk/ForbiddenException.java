package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code forbidden} (HTTP 403) — authenticated but not allowed to perform the action. */
public class ForbiddenException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public ForbiddenException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }
}
