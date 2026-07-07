package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code validation_failed} (HTTP 422) — extraction ran but the output did not match the
 * requested schema after one repair attempt, or the extraction itself errored. The structured
 * error (e.g. {@code {type, errors: [...]}}) is available via {@link #getDetails()}.
 */
public class ValidationException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public ValidationException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }
}
