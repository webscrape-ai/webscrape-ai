package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Page metadata for HTML scrapes. All fields nullable; the whole object is {@code null} for PDFs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PageMetadata {

    @JsonProperty("title")
    private String title;

    @JsonProperty("description")
    private String description;

    @JsonProperty("language")
    private String language;

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getLanguage() {
        return language;
    }
}
