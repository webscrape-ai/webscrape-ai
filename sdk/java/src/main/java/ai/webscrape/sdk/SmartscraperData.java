package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code data} payload of a {@code /smartscraper} response. The extracted {@code result} is
 * kept as a raw Jackson tree so it can be an object, array, string, or {@code null}; use
 * {@link #resultAs(Class)} to bind it to your own type.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SmartscraperData {

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("result")
    private JsonNode result;

    @JsonProperty("latency_ms")
    private Integer latencyMs;

    /** Extraction id (a UUID), distinct from the top-level {@code request_id}. */
    public String getRequestId() {
        return requestId;
    }

    /** The extracted output as a raw tree: object, array, string, or {@code null}. */
    public JsonNode getResult() {
        return result;
    }

    /** Total extraction time in milliseconds, or {@code null}. */
    public Integer getLatencyMs() {
        return latencyMs;
    }

    /**
     * Bind {@code result} to a POJO (or {@code List}/{@code Map}-shaped type token).
     *
     * @return the bound value, or {@code null} if {@code result} was absent/null
     * @throws WebscrapeException if the tree cannot be converted to {@code type}
     */
    public <T> T resultAs(Class<T> type) {
        if (result == null || result.isNull()) {
            return null;
        }
        try {
            return Json.MAPPER.treeToValue(result, type);
        } catch (JsonProcessingException e) {
            throw new WebscrapeException("failed to convert result to " + type.getName(), e);
        }
    }
}
