package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code data} payload of a {@code GET /smartbrowse/runs/{id}} poll. {@code creditsUsed} here
 * is the run's accrued spend (the envelope's {@code credits_used} is always 0 for polls).
 * Timestamps are kept as RFC3339 strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SmartBrowseRun {

    @JsonProperty("id")
    private String id;

    @JsonProperty("recipe_id")
    private String recipeId;

    @JsonProperty("run_status")
    private RunStatus runStatus;

    @JsonProperty("pages_extracted")
    private Integer pagesExtracted;

    @JsonProperty("items_extracted")
    private Integer itemsExtracted;

    @JsonProperty("credits_used")
    private Integer creditsUsed;

    @JsonProperty("started_at")
    private String startedAt;

    @JsonProperty("completed_at")
    private String completedAt;

    @JsonProperty("error")
    private String error;

    @JsonProperty("result")
    private JsonNode result;

    @JsonProperty("created_at")
    private String createdAt;

    public String getId() {
        return id;
    }

    public String getRecipeId() {
        return recipeId;
    }

    /** Run lifecycle state (unknown-tolerant; never {@code null} once deserialized). */
    public RunStatus getRunStatus() {
        return runStatus == null ? RunStatus.UNKNOWN : runStatus;
    }

    /** {@code true} once the run reached a terminal state. */
    public boolean isTerminal() {
        return getRunStatus().isTerminal();
    }

    public Integer getPagesExtracted() {
        return pagesExtracted;
    }

    public Integer getItemsExtracted() {
        return itemsExtracted;
    }

    /** The run's accrued credit spend. */
    public Integer getCreditsUsed() {
        return creditsUsed;
    }

    /** RFC3339, or {@code null} while pending. */
    public String getStartedAt() {
        return startedAt;
    }

    /** RFC3339, or {@code null} while pending. */
    public String getCompletedAt() {
        return completedAt;
    }

    /** Set only on {@code failed} / {@code cancelled} runs. */
    public String getError() {
        return error;
    }

    /**
     * Present once completed: {@code {pages: [{items: [...]}], mode, drift, warnings}}. Kept as a
     * raw tree because {@code pages[].items[]} are free-form.
     */
    public JsonNode getResult() {
        return result;
    }

    /** RFC3339 creation timestamp. */
    public String getCreatedAt() {
        return createdAt;
    }
}
