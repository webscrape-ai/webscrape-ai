package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The {@code data} payload of {@code GET /smartbrowse/usage}: plan caps + rolling-30-day usage. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SmartBrowseUsage {

    @JsonProperty("runs_used_30d")
    private Integer runsUsed30d;

    @JsonProperty("runs_per_month_cap")
    private Integer runsPerMonthCap;

    @JsonProperty("pages_per_run_cap")
    private Integer pagesPerRunCap;

    @JsonProperty("cost_per_page")
    private Integer costPerPage;

    @JsonProperty("schedules_count")
    private Integer schedulesCount;

    @JsonProperty("schedules_allowed")
    private Boolean schedulesAllowed;

    @JsonProperty("last_run")
    private SmartBrowseLastRun lastRun;

    /** Runs initiated by this account in the last 30 days. */
    public Integer getRunsUsed30d() {
        return runsUsed30d;
    }

    /** Hard ceiling from the caller's current plan. */
    public Integer getRunsPerMonthCap() {
        return runsPerMonthCap;
    }

    /** Per-run page ceiling from the caller's current plan. */
    public Integer getPagesPerRunCap() {
        return pagesPerRunCap;
    }

    /** Credit cost per extracted page (currently 2). */
    public Integer getCostPerPage() {
        return costPerPage;
    }

    /** Number of enabled recurring schedules on this account. */
    public Integer getSchedulesCount() {
        return schedulesCount;
    }

    /** Whether the caller's plan allows recurring schedules. */
    public Boolean getSchedulesAllowed() {
        return schedulesAllowed;
    }

    /** The most recent run, or {@code null} when the account has never run. */
    public SmartBrowseLastRun getLastRun() {
        return lastRun;
    }
}
