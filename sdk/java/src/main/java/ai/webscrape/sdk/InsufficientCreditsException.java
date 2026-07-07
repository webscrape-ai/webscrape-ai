package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code insufficient_credits} (HTTP 402) — the wallet cannot cover this request. */
public class InsufficientCreditsException extends WebscrapeApiException {
    private static final long serialVersionUID = 1L;

    public InsufficientCreditsException(int statusCode, String code, String message, JsonNode details, String requestId) {
        super(statusCode, code, message, details, requestId);
    }

    /** Current spendable balance from {@code details.balance}, or {@code null}. */
    public Integer getBalance() {
        return detailInt("balance");
    }

    /** Credits the request required from {@code details.required}, or {@code null}. */
    public Integer getRequired() {
        return detailInt("required");
    }
}
