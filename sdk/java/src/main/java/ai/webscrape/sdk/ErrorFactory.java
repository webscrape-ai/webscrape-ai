package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns an error response (envelope or plain {@code {"error": "<message>"}} body) into the
 * right typed exception. Package-private.
 */
final class ErrorFactory {

    private ErrorFactory() {
    }

    /**
     * @param httpStatus    HTTP status code
     * @param root          parsed JSON body, or {@code null} if the body was not JSON
     * @param headerReqId   value of the {@code X-Request-ID} response header, or {@code null}
     * @param rawBody       decoded response body (for non-JSON fallback messages)
     */
    static WebscrapeApiException from(int httpStatus, JsonNode root, String headerReqId, String rawBody) {
        String code = null;
        String message = null;
        JsonNode details = null;
        String requestId = headerReqId;

        if (root != null) {
            JsonNode error = root.get("error");
            if (error != null && error.isObject()) {
                // Standard error envelope.
                code = Json.text(error, "code");
                message = Json.text(error, "message");
                details = error.get("details");
                String envelopeReqId = Json.text(root, "request_id");
                if (envelopeReqId != null) {
                    requestId = envelopeReqId;
                }
            } else if (error != null && error.isTextual()) {
                // Authentication failures may return a plain {"error": "<message>"} body instead
                // of the standard envelope; both shapes are handled.
                message = error.asText();
            } else {
                // Some other JSON shape; still try to salvage a request id.
                String envelopeReqId = Json.text(root, "request_id");
                if (envelopeReqId != null) {
                    requestId = envelopeReqId;
                }
            }
        }

        // On a 401 with no usable code (plain-error body or absent), synthesize "unauthorized".
        if ((code == null || code.isEmpty()) && httpStatus == 401) {
            code = "unauthorized";
        }

        if (message == null || message.isEmpty()) {
            message = defaultMessage(code, httpStatus, rawBody);
        }

        return build(httpStatus, code, message, details, requestId);
    }

    private static WebscrapeApiException build(int status, String code, String message, JsonNode details, String requestId) {
        String c = code == null ? "" : code;
        switch (c) {
            case "unauthorized":
                return new AuthenticationException(status, code, message, details, requestId);
            case "insufficient_credits":
                return new InsufficientCreditsException(status, code, message, details, requestId);
            case "email_verification_required":
                return new EmailVerificationException(status, code, message, details, requestId);
            case "forbidden":
                return new ForbiddenException(status, code, message, details, requestId);
            case "not_found":
                return new NotFoundException(status, code, message, details, requestId);
            case "conflict":
            case "account_deletion_pending":
                return new ConflictException(status, code, message, details, requestId);
            case "invalid_request":
                return new BadRequestException(status, code, message, details, requestId);
            case "validation_failed":
                return new ValidationException(status, code, message, details, requestId);
            case "rate_limited":
                return new RateLimitException(status, code, message, details, requestId);
            case "internal_error":
            case "service_unavailable":
                return new ServerException(status, code, message, details, requestId);
            default:
                // Unknown or absent code: generic API error, raw code preserved.
                return new WebscrapeApiException(status, code, message, details, requestId);
        }
    }

    private static String defaultMessage(String code, int httpStatus, String rawBody) {
        if (code != null && !code.isEmpty()) {
            return code;
        }
        if (rawBody != null) {
            String trimmed = rawBody.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
            }
        }
        return "request failed with HTTP status " + httpStatus;
    }
}
