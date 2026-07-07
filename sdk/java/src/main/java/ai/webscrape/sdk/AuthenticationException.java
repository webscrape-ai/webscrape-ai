package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code unauthorized} (HTTP 401) — missing, invalid, or revoked API key. */
public class AuthenticationException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public AuthenticationException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }
}
