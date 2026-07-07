package ai.webscrape.sdk;

import java.time.Duration;

/**
 * A SmartBrowse wait helper hit its deadline before the run reached a terminal state.
 * Carries the last-seen run (may be {@code null} if the first poll never returned).
 */
public class WaitTimeoutException extends WebscrapeException {
    private static final long serialVersionUID = 1L;

    private final transient SmartBrowseRun lastSeen;

    public WaitTimeoutException(SmartBrowseRun lastSeen, Duration timeout) {
        super(buildMessage(lastSeen, timeout));
        this.lastSeen = lastSeen;
    }

    /** The most recently polled run, or {@code null}. */
    public SmartBrowseRun getLastSeen() {
        return lastSeen;
    }

    private static String buildMessage(SmartBrowseRun lastSeen, Duration timeout) {
        StringBuilder sb = new StringBuilder("timed out after ").append(timeout);
        sb.append(" waiting for smartbrowse run");
        if (lastSeen != null) {
            sb.append(' ').append(lastSeen.getId()).append(" (last status ").append(lastSeen.getRunStatus()).append(')');
        }
        return sb.toString();
    }
}
