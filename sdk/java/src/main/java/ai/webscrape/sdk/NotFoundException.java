package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code not_found} (HTTP 404) — resource does not exist or belongs to another user. */
public class NotFoundException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }
}
