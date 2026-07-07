package ai.webscrape.sdk;

/**
 * A network-level failure that is not an HTTP error envelope: connection establishment
 * failed, DNS failed, the socket dropped, or the response body could not be read/parsed.
 * Connection-establishment failures (the request demonstrably never reached the server) are
 * retried; other transport failures are surfaced without retry to stay billing-safe.
 */
public class TransportException extends WebscrapeException {
    private static final long serialVersionUID = 1L;

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
