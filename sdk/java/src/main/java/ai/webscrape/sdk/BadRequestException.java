package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code invalid_request} (HTTP 400) — malformed request, missing field, or invalid URL. */
public class BadRequestException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public BadRequestException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }
}
