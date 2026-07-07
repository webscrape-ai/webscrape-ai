package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request body for {@code POST /smartscraper}. Build with {@link #builder(String, String)}.
 * The prompt field is {@code user_prompt}. Only fields the caller sets are serialized.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SmartscraperRequest {

    @JsonProperty("website_url")
    private final String websiteUrl;

    @JsonProperty("user_prompt")
    private final String userPrompt;

    @JsonProperty("output_schema")
    private final Object outputSchema;

    @JsonProperty("page_complexity")
    private final String pageComplexity;

    @JsonProperty("detail_level")
    private final String detailLevel;

    @JsonProperty("parse_mode")
    private final String parseMode;

    @JsonProperty("plain_text")
    private final Boolean plainText;

    @JsonProperty("include_tags")
    private final List<String> includeTags;

    @JsonProperty("exclude_tags")
    private final List<String> excludeTags;

    @JsonProperty("reduce_content")
    private final Boolean reduceContent;

    @JsonProperty("experimental")
    private final Boolean experimental;

    @JsonProperty("headers")
    private final Map<String, String> headers;

    @JsonProperty("max_age")
    private final Integer maxAge;

    @JsonProperty("stealth")
    private final Boolean stealth;

    private SmartscraperRequest(Builder b) {
        this.websiteUrl = b.websiteUrl;
        this.userPrompt = b.userPrompt;
        this.outputSchema = b.outputSchema;
        this.pageComplexity = b.pageComplexity;
        this.detailLevel = b.detailLevel;
        this.parseMode = b.parseMode;
        this.plainText = b.plainText;
        this.includeTags = b.includeTags;
        this.excludeTags = b.excludeTags;
        this.reduceContent = b.reduceContent;
        this.experimental = b.experimental;
        this.headers = b.headers;
        this.maxAge = b.maxAge;
        this.stealth = b.stealth;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String websiteUrl, String userPrompt) {
        return new Builder().websiteUrl(websiteUrl).userPrompt(userPrompt);
    }

    public String websiteUrl() {
        return websiteUrl;
    }

    public String userPrompt() {
        return userPrompt;
    }

    /** Fluent builder. */
    public static final class Builder {
        private String websiteUrl;
        private String userPrompt;
        private Object outputSchema;
        private String pageComplexity;
        private String detailLevel;
        private String parseMode;
        private Boolean plainText;
        private List<String> includeTags;
        private List<String> excludeTags;
        private Boolean reduceContent;
        private Boolean experimental;
        private Map<String, String> headers;
        private Integer maxAge;
        private Boolean stealth;

        /** Required. */
        public Builder websiteUrl(String websiteUrl) {
            this.websiteUrl = websiteUrl;
            return this;
        }

        /** Required. Plain-English description of what to extract. */
        public Builder userPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
            return this;
        }

        /**
         * JSON Schema the result is validated against (one repair attempt on mismatch).
         * Pass a {@code Map} representing the schema object.
         */
        public Builder outputSchema(Map<String, Object> outputSchema) {
            this.outputSchema = outputSchema == null ? null : new LinkedHashMap<>(outputSchema);
            return this;
        }

        /** {@code "low"} (default) or {@code "high"}. */
        public Builder pageComplexity(String pageComplexity) {
            this.pageComplexity = pageComplexity;
            return this;
        }

        /** {@code "low"}, {@code "medium"} (default), or {@code "high"}. */
        public Builder detailLevel(String detailLevel) {
            this.detailLevel = detailLevel;
            return this;
        }

        /** Cleaner mode: {@code "accurate"} (default) or {@code "speed"}. */
        public Builder parseMode(String parseMode) {
            this.parseMode = parseMode;
            return this;
        }

        /** Return the raw text under {@code result} instead of JSON; bypasses schema validation. */
        public Builder plainText(boolean plainText) {
            this.plainText = plainText;
            return this;
        }

        public Builder includeTags(List<String> includeTags) {
            this.includeTags = includeTags == null ? null : List.copyOf(includeTags);
            return this;
        }

        public Builder excludeTags(List<String> excludeTags) {
            this.excludeTags = excludeTags == null ? null : List.copyOf(excludeTags);
            return this;
        }

        /** Trim long content before extraction (tri-state; server default when unset). */
        public Builder reduceContent(boolean reduceContent) {
            this.reduceContent = reduceContent;
            return this;
        }

        /**
         * Opt in to an alternate extraction path that can do better on hard-to-parse pages.
         * Behavior may change without notice.
         */
        public Builder experimental(boolean experimental) {
            this.experimental = experimental;
            return this;
        }

        /**
         * Custom request headers forwarded to the fetcher. Providing headers disables URL
         * caching for this request.
         */
        public Builder headers(Map<String, String> headers) {
            this.headers = headers == null ? null : new LinkedHashMap<>(headers);
            return this;
        }

        /** Add a single custom header. */
        public Builder header(String name, String value) {
            if (this.headers == null) {
                this.headers = new LinkedHashMap<>();
            }
            this.headers.put(name, value);
            return this;
        }

        /** URL-cache opt-in — same semantics as {@code /scrape}. */
        public Builder maxAge(int maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        /** Browser-based stealth fetch. +5 credits. */
        public Builder stealth(boolean stealth) {
            this.stealth = stealth;
            return this;
        }

        public SmartscraperRequest build() {
            Objects.requireNonNull(websiteUrl, "websiteUrl is required");
            Objects.requireNonNull(userPrompt, "userPrompt is required");
            return new SmartscraperRequest(this);
        }
    }
}
