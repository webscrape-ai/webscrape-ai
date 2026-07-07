package ai.webscrape.sdk;

/**
 * Base type for every error raised by the SDK. Unchecked, so callers are not forced
 * to declare or catch it, but may catch it to handle all SDK failures uniformly.
 *
 * <p>Subtypes:</p>
 * <ul>
 *   <li>{@link WebscrapeApiException} and its family — the server returned an error envelope.</li>
 *   <li>{@link TransportException} — the request never reached (or could not leave) the client.</li>
 *   <li>{@link WebscrapeTimeoutException} — the per-request deadline elapsed.</li>
 *   <li>{@link RunFailedException} / {@link WaitTimeoutException} — SmartBrowse wait-helper outcomes.</li>
 * </ul>
 *
 * <p><b>Async note:</b> the {@code ...Async()} methods return a {@link java.util.concurrent.CompletableFuture}
 * that completes exceptionally with the same exception types. Because that is how the
 * {@code CompletableFuture} API works, callers using {@code join()} see the exception wrapped in a
 * {@link java.util.concurrent.CompletionException}, and callers using {@code get()} see it wrapped in an
 * {@link java.util.concurrent.ExecutionException}; unwrap {@code getCause()} to recover the original.</p>
 */
public class WebscrapeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WebscrapeException(String message) {
        super(message);
    }

    public WebscrapeException(String message, Throwable cause) {
        super(message, cause);
    }
}
