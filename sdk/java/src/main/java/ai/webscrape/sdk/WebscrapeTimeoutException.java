package ai.webscrape.sdk;

/**
 * The per-request deadline elapsed before the response completed. Not retried automatically:
 * the request may have completed on the server, so a blind retry could repeat billable work.
 */
public class WebscrapeTimeoutException extends WebscrapeException {
    private static final long serialVersionUID = 1L;

    public WebscrapeTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
