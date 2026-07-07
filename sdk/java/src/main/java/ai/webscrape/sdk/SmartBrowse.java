package ai.webscrape.sdk;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * The SmartBrowse namespace, reached via {@link WebscrapeClient#smartBrowse()}. Dispatch a
 * recipe replay run, poll it, or use a wait helper that turns dispatch+poll into one call.
 * An API key can run and poll recipes; recipes themselves are authored in the dashboard.
 */
public final class SmartBrowse {

    private static final long POLL_CAP_MILLIS = 10_000L;
    private static final double POLL_GROWTH = 1.5;

    private final WebscrapeClient client;

    SmartBrowse(WebscrapeClient client) {
        this.client = client;
    }

    // ------------------------------------------------------------------- dispatch

    /** Dispatch a run (HTTP 202). Costs 2 credits/page, billed on completion. No request body. */
    public WebscrapeResponse<SmartBrowseRunDispatch> run(String recipeId) {
        return WebscrapeClient.await(runAsync(recipeId));
    }

    public CompletableFuture<WebscrapeResponse<SmartBrowseRunDispatch>> runAsync(String recipeId) {
        String path = "/smartbrowse/recipes/" + recipeId + "/run";
        return client.sendAsync(client.postEmpty(path), SmartBrowseRunDispatch.class);
    }

    // ------------------------------------------------------------------- poll

    /** Poll a run's state. Free — the envelope's {@code creditsUsed} is always 0. */
    public WebscrapeResponse<SmartBrowseRun> getRun(String runId) {
        return WebscrapeClient.await(getRunAsync(runId));
    }

    public CompletableFuture<WebscrapeResponse<SmartBrowseRun>> getRunAsync(String runId) {
        return client.sendAsync(client.get("/smartbrowse/runs/" + runId), SmartBrowseRun.class);
    }

    // ------------------------------------------------------------------- usage

    /** Plan caps + rolling-30-day usage. Free. */
    public WebscrapeResponse<SmartBrowseUsage> usage() {
        return WebscrapeClient.await(usageAsync());
    }

    public CompletableFuture<WebscrapeResponse<SmartBrowseUsage>> usageAsync() {
        return client.sendAsync(client.get("/smartbrowse/usage"), SmartBrowseUsage.class);
    }

    // ------------------------------------------------------------------- wait

    /** Poll until the run is terminal, using {@link WaitOptions#defaults()}. */
    public WebscrapeResponse<SmartBrowseRun> waitForRun(String runId) {
        return waitForRun(runId, WaitOptions.defaults());
    }

    /**
     * Poll until the run is terminal.
     *
     * @throws RunFailedException  if the run reaches {@code failed} / {@code cancelled}
     * @throws WaitTimeoutException if the deadline elapses first
     */
    public WebscrapeResponse<SmartBrowseRun> waitForRun(String runId, WaitOptions options) {
        return WebscrapeClient.await(waitForRunAsync(runId, options));
    }

    public CompletableFuture<WebscrapeResponse<SmartBrowseRun>> waitForRunAsync(String runId) {
        return waitForRunAsync(runId, WaitOptions.defaults());
    }

    public CompletableFuture<WebscrapeResponse<SmartBrowseRun>> waitForRunAsync(String runId, WaitOptions options) {
        long deadlineNanos = System.nanoTime() + options.getTimeout().toNanos();
        return poll(runId, options.getPollInterval().toMillis(), deadlineNanos, options.getTimeout());
    }

    // ------------------------------------------------------------------- run + wait

    /** Dispatch a run, then wait for it, using {@link WaitOptions#defaults()}. */
    public WebscrapeResponse<SmartBrowseRun> runAndWait(String recipeId) {
        return runAndWait(recipeId, WaitOptions.defaults());
    }

    public WebscrapeResponse<SmartBrowseRun> runAndWait(String recipeId, WaitOptions options) {
        return WebscrapeClient.await(runAndWaitAsync(recipeId, options));
    }

    public CompletableFuture<WebscrapeResponse<SmartBrowseRun>> runAndWaitAsync(String recipeId) {
        return runAndWaitAsync(recipeId, WaitOptions.defaults());
    }

    public CompletableFuture<WebscrapeResponse<SmartBrowseRun>> runAndWaitAsync(String recipeId, WaitOptions options) {
        return runAsync(recipeId)
                .thenCompose(dispatch -> waitForRunAsync(dispatch.getData().getRunId(), options));
    }

    // ------------------------------------------------------------------- poll helper

    private CompletableFuture<WebscrapeResponse<SmartBrowseRun>> poll(
            String runId, long intervalMillis, long deadlineNanos, Duration timeout) {
        return getRunAsync(runId).thenCompose(response -> {
            SmartBrowseRun run = response.getData();
            RunStatus status = run == null ? RunStatus.UNKNOWN : run.getRunStatus();

            if (status == RunStatus.COMPLETED) {
                return CompletableFuture.completedFuture(response);
            }
            if (status == RunStatus.FAILED || status == RunStatus.CANCELLED) {
                return CompletableFuture.failedFuture(new RunFailedException(run));
            }

            long now = System.nanoTime();
            if (now >= deadlineNanos) {
                return CompletableFuture.failedFuture(new WaitTimeoutException(run, timeout));
            }

            long remainingMillis = Math.max(0L, (deadlineNanos - now) / 1_000_000L);
            long sleepMillis = Math.min(intervalMillis, remainingMillis);
            long nextInterval = Math.min((long) (intervalMillis * POLL_GROWTH), POLL_CAP_MILLIS);

            Executor delayed = CompletableFuture.delayedExecutor(sleepMillis, TimeUnit.MILLISECONDS);
            return CompletableFuture.supplyAsync(() -> null, delayed)
                    .thenCompose(ignored -> poll(runId, nextInterval, deadlineNanos, timeout));
        });
    }
}
