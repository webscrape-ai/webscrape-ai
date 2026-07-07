package ai.webscrape.sdk;

/**
 * A SmartBrowse wait helper observed the run reach a terminal {@code failed} or
 * {@code cancelled} state. Carries the full run so {@code error}, {@code pagesExtracted},
 * and {@code creditsUsed} remain inspectable.
 */
public class RunFailedException extends WebscrapeException {
    private static final long serialVersionUID = 1L;

    private final transient SmartBrowseRun run;

    public RunFailedException(SmartBrowseRun run) {
        super(buildMessage(run));
        this.run = run;
    }

    /** The terminal run. */
    public SmartBrowseRun getRun() {
        return run;
    }

    private static String buildMessage(SmartBrowseRun run) {
        if (run == null) {
            return "smartbrowse run failed";
        }
        StringBuilder sb = new StringBuilder("smartbrowse run ");
        sb.append(run.getId()).append(' ').append(run.getRunStatus());
        if (run.getError() != null && !run.getError().isEmpty()) {
            sb.append(": ").append(run.getError());
        }
        return sb.toString();
    }
}
