package ai.webscrape.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The webscrape.ai API client. Immutable and thread-safe once built.
 *
 * <pre>{@code
 * WebscrapeClient client = WebscrapeClient.builder()
 *         .apiKey("wsg_live_...")   // or set WEBSCRAPE_API_KEY
 *         .build();
 *
 * WebscrapeResponse<ScrapeData> resp =
 *         client.scrape(ScrapeRequest.builder("https://example.com").clean(true).build());
 * System.out.println(resp.getData().getHtml());
 * }</pre>
 *
 * <p>Every call has a synchronous form and an {@code ...Async()} form returning a
 * {@link CompletableFuture}. Errors are thrown as {@link WebscrapeException} subtypes
 * (async futures complete exceptionally with the same types — see {@link WebscrapeException}).</p>
 */
public final class WebscrapeClient {

    /** Default production base URL. */
    public static final String DEFAULT_BASE_URL = "https://api.webscrape.ai/v1";

    private static final String ENV_API_KEY = "WEBSCRAPE_API_KEY";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final long BACKOFF_CAP_MILLIS = 30_000L;

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final Backoff backoff;
    private final SmartBrowse smartBrowse;

    private WebscrapeClient(Builder b, String resolvedKey, HttpClient resolvedHttp) {
        this.httpClient = resolvedHttp;
        this.apiKey = resolvedKey;
        this.baseUrl = stripTrailingSlash(b.baseUrl);
        this.timeout = b.timeout;
        this.maxRetries = b.maxRetries;
        this.backoff = new FullJitterBackoff(b.backoffBaseMillis, BACKOFF_CAP_MILLIS);
        this.smartBrowse = new SmartBrowse(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ----------------------------------------------------------------- public API

    /** Fetch a URL as HTML, cleaned markdown, or links. Costs 1 credit (+2 with stealth). */
    public WebscrapeResponse<ScrapeData> scrape(ScrapeRequest request) {
        return await(scrapeAsync(request));
    }

    public CompletableFuture<WebscrapeResponse<ScrapeData>> scrapeAsync(ScrapeRequest request) {
        return sendAsync(postJson("/scrape", Json.write(request)), ScrapeData.class);
    }

    /** LLM structured extraction. Costs 5 credits (+5 with stealth). */
    public WebscrapeResponse<SmartscraperData> smartscraper(SmartscraperRequest request) {
        return await(smartscraperAsync(request));
    }

    public CompletableFuture<WebscrapeResponse<SmartscraperData>> smartscraperAsync(SmartscraperRequest request) {
        return sendAsync(postJson("/smartscraper", Json.write(request)), SmartscraperData.class);
    }

    /** The SmartBrowse namespace: {@code run}, {@code getRun}, {@code waitForRun}, {@code runAndWait}, {@code usage}. */
    public SmartBrowse smartBrowse() {
        return smartBrowse;
    }

    // --------------------------------------------------------------- config getters

    public String getBaseUrl() {
        return baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    // ----------------------------------------------------- request construction (pkg)

    HttpRequest postJson(String path, byte[] body) {
        return baseRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    HttpRequest postEmpty(String path) {
        // Dispatch takes no body and no Content-Type.
        return baseRequest(path)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    HttpRequest get(String path) {
        return baseRequest(path).GET().build();
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("X-API-Key", apiKey)
                .header("User-Agent", Version.USER_AGENT)
                .header("Accept", "application/json");
    }

    // ----------------------------------------------------------- send + retry (pkg)

    <D> CompletableFuture<WebscrapeResponse<D>> sendAsync(HttpRequest request, Class<D> dataType) {
        return attempt(request, 0, resp -> parseEnvelope(resp, dataType));
    }

    private <T> CompletableFuture<T> attempt(HttpRequest request, int attemptNo, Function<HttpResponse<byte[]>, T> parser) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, error) -> route(request, attemptNo, parser, response, error))
                .thenCompose(Function.identity());
    }

    private <T> CompletableFuture<T> route(HttpRequest request, int attemptNo,
                                           Function<HttpResponse<byte[]>, T> parser,
                                           HttpResponse<byte[]> response, Throwable error) {
        if (error != null) {
            Throwable cause = unwrap(error);
            if (isRetryableTransport(cause) && attemptNo < maxRetries) {
                return retry(request, attemptNo, parser, null);
            }
            return CompletableFuture.failedFuture(mapTransport(cause));
        }

        int status = response.statusCode();
        if (isRetryableStatus(status) && attemptNo < maxRetries) {
            return retry(request, attemptNo, parser, retryAfterMillis(response));
        }

        // Terminal attempt: parse to a success response or throw the typed error.
        try {
            return CompletableFuture.completedFuture(parser.apply(response));
        } catch (WebscrapeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private <T> CompletableFuture<T> retry(HttpRequest request, int attemptNo,
                                           Function<HttpResponse<byte[]>, T> parser, Long retryAfterMillis) {
        long delay = backoff.delayMillis(attemptNo, retryAfterMillis);
        Executor delayed = CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS);
        return CompletableFuture.supplyAsync(() -> null, delayed)
                .thenCompose(ignored -> attempt(request, attemptNo + 1, parser));
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503;
    }

    private static boolean isRetryableTransport(Throwable cause) {
        // Connection-establishment failures only: the request never reached the server, so a
        // retry is safe (failed and rate-limited requests are never charged).
        return cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException;
    }

    private static WebscrapeException mapTransport(Throwable cause) {
        if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
            return new TransportException("failed to establish connection: " + cause.getMessage(), cause);
        }
        if (cause instanceof HttpTimeoutException) {
            return new WebscrapeTimeoutException("request timed out", cause);
        }
        return new TransportException("transport error: " + cause.getMessage(), cause);
    }

    private static Long retryAfterMillis(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .map(v -> {
                    try {
                        return Long.parseLong(v) * 1000L;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    // ----------------------------------------------------------- envelope parsing

    private <D> WebscrapeResponse<D> parseEnvelope(HttpResponse<byte[]> response, Class<D> dataType) {
        int http = response.statusCode();
        byte[] body = response.body();
        String headerRequestId = response.headers().firstValue("X-Request-ID").orElse(null);
        JsonNode root = Json.tryParse(body);

        boolean isError = http >= 400 || (root != null && "error".equals(Json.text(root, "status")));
        if (isError) {
            String raw = body == null ? null : new String(body, StandardCharsets.UTF_8);
            throw ErrorFactory.from(http, root, headerRequestId, raw);
        }
        if (root == null) {
            throw new TransportException(
                    "expected a JSON response body but received " + (body == null ? 0 : body.length) + " bytes", null);
        }

        String requestId = Json.text(root, "request_id");
        if (requestId == null) {
            requestId = headerRequestId;
        }
        Integer creditsUsed = Json.integer(root, "credits_used");
        Integer creditsRemaining = Json.integer(root, "credits_remaining");

        JsonNode dataNode = root.get("data");
        D data;
        if (dataNode == null || dataNode.isNull()) {
            data = null;
        } else {
            try {
                data = Json.MAPPER.treeToValue(dataNode, dataType);
            } catch (JsonProcessingException e) {
                throw new WebscrapeException("failed to deserialize response data", e);
            }
        }
        return new WebscrapeResponse<>(requestId, creditsUsed, creditsRemaining, data);
    }

    // ----------------------------------------------------------------- sync bridge

    /** Block on a future, unwrapping {@link CompletionException} so the real cause surfaces. */
    static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WebscrapeException) {
                throw (WebscrapeException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new WebscrapeException("unexpected error", cause == null ? e : cause);
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    // ------------------------------------------------------------------- builder

    /** Builder for {@link WebscrapeClient}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private HttpClient httpClient;

        // Package-private seams (tests only).
        Function<String, String> envLookup = System::getenv;
        long backoffBaseMillis = 1_000L;

        /** API key. Falls back to the {@code WEBSCRAPE_API_KEY} env var when unset. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Base URL; default {@link #DEFAULT_BASE_URL}. Trailing slashes are tolerated. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Per-request timeout; default 180s. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Max automatic retries on 429/5xx and connection failures; default 2, 0 disables. */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /** Supply a custom {@link HttpClient} (proxy, TLS, executor). One is built otherwise. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        // test seam: override the base backoff delay to keep retry tests fast.
        Builder backoffBaseMillis(long millis) {
            this.backoffBaseMillis = millis;
            return this;
        }

        // test seam: override how WEBSCRAPE_API_KEY is resolved.
        Builder envLookup(Function<String, String> envLookup) {
            this.envLookup = envLookup;
            return this;
        }

        /**
         * @throws IllegalStateException if no API key was supplied and {@code WEBSCRAPE_API_KEY} is unset
         */
        public WebscrapeClient build() {
            String key = apiKey;
            if (key == null || key.isEmpty()) {
                key = envLookup.apply(ENV_API_KEY);
            }
            if (key == null || key.isEmpty()) {
                throw new IllegalStateException(
                        "no API key: pass WebscrapeClient.builder().apiKey(...) or set the "
                                + ENV_API_KEY + " environment variable");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalStateException("timeout must be positive");
            }
            HttpClient http = httpClient;
            if (http == null) {
                http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return new WebscrapeClient(this, key, http);
        }
    }
}
