package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** An outbound link, present in scrape results only when {@code extract_links} was set. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class LinkInfo {

    @JsonProperty("url")
    private String url;

    @JsonProperty("text")
    private String text;

    public String getUrl() {
        return url;
    }

    public String getText() {
        return text;
    }
}
