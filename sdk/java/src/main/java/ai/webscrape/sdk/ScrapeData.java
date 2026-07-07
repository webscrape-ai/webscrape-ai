package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The {@code data} payload of a {@code /scrape} response. Every field is nullable — absent
 * fields are returned as explicit {@code null}s, so treat each as optional.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ScrapeData {

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("html")
    private String html;

    @JsonProperty("content_type")
    private String contentType;

    @JsonProperty("cleaned")
    private Boolean cleaned;

    @JsonProperty("links")
    private List<LinkInfo> links;

    @JsonProperty("metadata")
    private PageMetadata metadata;

    @JsonProperty("structured_data")
    private JsonNode structuredData;

    @JsonProperty("latency_ms")
    private Integer latencyMs;

    /** Extraction id (a UUID), distinct from the top-level {@code request_id}. */
    public String getRequestId() {
        return requestId;
    }

    /** Raw HTML, or cleaned markdown when {@code clean: true} or the source was a PDF. */
    public String getHtml() {
        return html;
    }

    /** {@code "html"} or {@code "pdf"}. */
    public String getContentType() {
        return contentType;
    }

    /** {@code true} when the cleaner pass actually ran. */
    public Boolean getCleaned() {
        return cleaned;
    }

    /** Outbound links; non-null only when {@code extract_links} was set. */
    public List<LinkInfo> getLinks() {
        return links;
    }

    /** Page metadata (HTML only; {@code null} for PDFs). */
    public PageMetadata getMetadata() {
        return metadata;
    }

    /**
     * Structured data extracted from the page (JSON-LD, microdata, pagination hints), when
     * available. Modeled as a free-form tree.
     */
    public JsonNode getStructuredData() {
        return structuredData;
    }

    /** Total fetch time in milliseconds, or {@code null}. */
    public Integer getLatencyMs() {
        return latencyMs;
    }
}
