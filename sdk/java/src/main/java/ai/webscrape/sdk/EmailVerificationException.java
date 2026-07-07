package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code email_verification_required} (HTTP 402) — verify your email before spending credits. */
public class EmailVerificationException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public EmailVerificationException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }
}
