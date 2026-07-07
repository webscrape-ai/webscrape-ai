package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The {@code data} payload returned (HTTP 202) when a SmartBrowse run is dispatched. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SmartBrowseRunDispatch {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("recipe_id")
    private String recipeId;

    @JsonProperty("run_status")
    private RunStatus runStatus;

    @JsonProperty("poll_url")
    private String pollUrl;

    @JsonProperty("created_at")
    private String createdAt;

    /** The new run's id — pass to {@link SmartBrowse#getRun(String)} / {@link SmartBrowse#waitForRun(String)}. */
    public String getRunId() {
        return runId;
    }

    public String getRecipeId() {
        return recipeId;
    }

    /** Always {@link RunStatus#RUNNING} on dispatch. */
    public RunStatus getRunStatus() {
        return runStatus;
    }

    /** Server-relative poll URL, e.g. {@code /v1/smartbrowse/runs/<id>}. */
    public String getPollUrl() {
        return pollUrl;
    }

    /** RFC3339 creation timestamp. */
    public String getCreatedAt() {
        return createdAt;
    }
}
