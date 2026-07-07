package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request body for {@code POST /scrape}. Build with {@link #builder(String)}. Only fields the
 * caller sets are serialized — unset optionals are omitted entirely, so a tri-state field like
 * {@code tag_truncate} stays absent unless explicitly configured.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ScrapeRequest {

    @JsonProperty("website_url")
    private final String websiteUrl;

    @JsonProperty("clean")
    private final Boolean clean;

    @JsonProperty("parse_mode")
    private final String parseMode;

    @JsonProperty("tag_truncate")
    private final Boolean tagTruncate;

    @JsonProperty("extract_links")
    private final Boolean extractLinks;

    @JsonProperty("include_tags")
    private final List<String> includeTags;

    @JsonProperty("exclude_tags")
    private final List<String> excludeTags;

    @JsonProperty("headers")
    private final Map<String, String> headers;

    @JsonProperty("max_age")
    private final Integer maxAge;

    @JsonProperty("stealth")
    private final Boolean stealth;

    private ScrapeRequest(Builder b) {
        this.websiteUrl = b.websiteUrl;
        this.clean = b.clean;
        this.parseMode = b.parseMode;
        this.tagTruncate = b.tagTruncate;
        this.extractLinks = b.extractLinks;
        this.includeTags = b.includeTags;
        this.excludeTags = b.excludeTags;
        this.headers = b.headers;
        this.maxAge = b.maxAge;
        this.stealth = b.stealth;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String websiteUrl) {
        return new Builder().websiteUrl(websiteUrl);
    }

    public String websiteUrl() {
        return websiteUrl;
    }

    /** Fluent builder. */
    public static final class Builder {
        private String websiteUrl;
        private Boolean clean;
        private String parseMode;
        private Boolean tagTruncate;
        private Boolean extractLinks;
        private List<String> includeTags;
        private List<String> excludeTags;
        private Map<String, String> headers;
        private Integer maxAge;
        private Boolean stealth;

        /** Required. The only content source available to API callers. */
        public Builder websiteUrl(String websiteUrl) {
            this.websiteUrl = websiteUrl;
            return this;
        }

        /** Convert HTML to cleaned markdown. */
        public Builder clean(boolean clean) {
            this.clean = clean;
            return this;
        }

        /** Cleaner mode when {@code clean}: {@code "accurate"} (default) or {@code "speed"}. */
        public Builder parseMode(String parseMode) {
            this.parseMode = parseMode;
            return this;
        }

        /** When cleaning, replace inline images with their alt text (server default true). */
        public Builder tagTruncate(boolean tagTruncate) {
            this.tagTruncate = tagTruncate;
            return this;
        }

        /** Include a deduplicated list of outbound links. */
        public Builder extractLinks(boolean extractLinks) {
            this.extractLinks = extractLinks;
            return this;
        }

        /** Whitelist of HTML tags to keep when cleaning. */
        public Builder includeTags(List<String> includeTags) {
            this.includeTags = includeTags == null ? null : List.copyOf(includeTags);
            return this;
        }

        /** Blacklist of HTML tags to drop when cleaning. */
        public Builder excludeTags(List<String> excludeTags) {
            this.excludeTags = excludeTags == null ? null : List.copyOf(excludeTags);
            return this;
        }

        /** Custom request headers forwarded to the fetcher; providing any disables URL caching. */
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

        /** URL-cache opt-in: accept cache entries fresher than {@code maxAge} seconds. */
        public Builder maxAge(int maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        /** Browser-based stealth fetch. +2 credits. */
        public Builder stealth(boolean stealth) {
            this.stealth = stealth;
            return this;
        }

        public ScrapeRequest build() {
            Objects.requireNonNull(websiteUrl, "websiteUrl is required");
            return new ScrapeRequest(this);
        }
    }
}
