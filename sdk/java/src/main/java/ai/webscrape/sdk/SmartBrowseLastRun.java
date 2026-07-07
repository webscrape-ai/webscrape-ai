package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Summary of the account's most recent run, embedded in the usage response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SmartBrowseLastRun {

    @JsonProperty("id")
    private String id;

    @JsonProperty("status")
    private RunStatus status;

    @JsonProperty("pages_extracted")
    private Integer pagesExtracted;

    @JsonProperty("effective_cap")
    private Integer effectiveCap;

    @JsonProperty("clamped_by_credits")
    private Boolean clampedByCredits;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("completed_at")
    private String completedAt;

    public String getId() {
        return id;
    }

    public RunStatus getStatus() {
        return status == null ? RunStatus.UNKNOWN : status;
    }

    public Integer getPagesExtracted() {
        return pagesExtracted;
    }

    /** Page ceiling actually applied: {@code min(plan pages/run, balance / cost_per_page)}. */
    public Integer getEffectiveCap() {
        return effectiveCap;
    }

    /** {@code true} when credits, not the plan, bounded the run. */
    public Boolean getClampedByCredits() {
        return clampedByCredits;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    /** RFC3339, or {@code null} while still in flight. */
    public String getCompletedAt() {
        return completedAt;
    }
}
