# webscrape.ai Java SDK

Official Java client for the [webscrape.ai](https://webscrape.ai) API. Covers the public
API-key surface: `/scrape`, `/smartscraper`, and SmartBrowse recipe replay.

- Java 11+, single runtime dependency (Jackson databind). HTTP via the JDK's `java.net.http.HttpClient`.
- Synchronous and `...Async()` (`CompletableFuture`) methods.
- Automatic, billing-safe retry with backoff on 429/5xx and connection failures.
- A `runAndWait` helper that collapses SmartBrowse dispatch + polling into one call.
- Typed exceptions you can branch on.

## Install

Maven:

```xml
<dependency>
  <groupId>ai.webscrape</groupId>
  <artifactId>webscrape-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation "ai.webscrape:webscrape-sdk:0.1.0"
```

## Authentication

Generate a key in the [dashboard](https://webscrape.ai/app/api-keys) (format
`wsg_live_...`). Pass it to the builder, or set the `WEBSCRAPE_API_KEY` environment variable
and let the builder pick it up:

```java
WebscrapeClient client = WebscrapeClient.builder()
        .apiKey("wsg_live_...")   // optional; falls back to WEBSCRAPE_API_KEY
        .build();
```

If neither is present, `build()` throws `IllegalStateException` immediately — no late 401.

## Quickstart

### Scrape a page

```java
import ai.webscrape.sdk.*;

WebscrapeResponse<ScrapeData> resp = client.scrape(
        ScrapeRequest.builder("https://example.com")
                .clean(true)          // HTML -> cleaned markdown
                .extractLinks(true)
                .build());

System.out.println(resp.getData().getHtml());
System.out.println("credits used: " + resp.getCreditsUsed()
        + ", remaining: " + resp.getCreditsRemaining());
```

### Structured extraction with an output schema

```java
Map<String, Object> schema = Map.of(
        "type", "object",
        "properties", Map.of(
                "title", Map.of("type", "string"),
                "score", Map.of("type", "integer")));

WebscrapeResponse<SmartscraperData> resp = client.smartscraper(
        SmartscraperRequest.builder("https://news.ycombinator.com",
                        "Extract the top story's title and score")
                .outputSchema(schema)
                .detailLevel("high")
                .build());

// Raw tree...
JsonNode result = resp.getData().getResult();
// ...or bind to your own type:
Story story = resp.getData().resultAs(Story.class);
```

### Run a SmartBrowse recipe and wait for the result

```java
WebscrapeResponse<SmartBrowseRun> run =
        client.smartBrowse().runAndWait("m3Yc2tFvN8q");   // dispatch + poll to terminal

System.out.println("pages: " + run.getData().getPagesExtracted()
        + ", items: " + run.getData().getItemsExtracted()
        + ", credits: " + run.getData().getCreditsUsed());
```

Prefer to drive it yourself?

```java
WebscrapeResponse<SmartBrowseRunDispatch> dispatch = client.smartBrowse().run("m3Yc2tFvN8q");
String runId = dispatch.getData().getRunId();

WaitOptions opts = WaitOptions.builder()
        .pollInterval(Duration.ofSeconds(2))
        .timeout(Duration.ofMinutes(10))
        .build();
WebscrapeResponse<SmartBrowseRun> result = client.smartBrowse().waitForRun(runId, opts);
```

### Async

Every method has an `...Async()` variant returning a `CompletableFuture`:

```java
client.scrapeAsync(ScrapeRequest.builder("https://example.com").build())
        .thenAccept(r -> System.out.println(r.getData().getHtml()));
```

Async futures **complete exceptionally with the same exceptions** as the sync methods.
Because that is how `CompletableFuture` works, `join()` surfaces them wrapped in a
`CompletionException` and `get()` wrapped in an `ExecutionException` — unwrap `getCause()`.

## Error handling

Every failure is an unchecked `WebscrapeException`. Server error envelopes become typed
`WebscrapeApiException` subtypes you can branch on; branch on `getCode()` / the subtype,
never on the human-readable message.

```java
try {
    client.smartscraper(SmartscraperRequest.builder(url, prompt).build());
} catch (InsufficientCreditsException e) {
    System.out.println("need " + e.getRequired() + ", have " + e.getBalance());
} catch (RateLimitException e) {
    System.out.println("rate limited: " + e.getReason());
} catch (ValidationException e) {
    System.out.println("schema mismatch: " + e.getDetails());
} catch (AuthenticationException e) {
    System.out.println("bad API key");
} catch (WebscrapeApiException e) {
    // any other/unknown API error — raw code is still available
    System.out.println("api error " + e.getStatusCode() + " / " + e.getCode());
} catch (WebscrapeException e) {
    // transport, timeout, run-failed, wait-timeout
    System.out.println("client error: " + e.getMessage());
}
```

Exception families: `AuthenticationException`, `InsufficientCreditsException`,
`EmailVerificationException`, `ForbiddenException`, `NotFoundException`, `ConflictException`,
`BadRequestException`, `ValidationException`, `RateLimitException`, `ServerException`, and the
generic `WebscrapeApiException` for unknown codes. Plus `TransportException`,
`WebscrapeTimeoutException`, `RunFailedException` (carries the run), and `WaitTimeoutException`
(carries the last-seen run).

## Configuration

| Builder option | Default | Notes |
|---|---|---|
| `apiKey(String)` | `WEBSCRAPE_API_KEY` env var | required (via option or env) |
| `baseUrl(String)` | `https://api.webscrape.ai/v1` | trailing slash tolerated |
| `timeout(Duration)` | 180s | per request |
| `maxRetries(int)` | 2 | up to 3 attempts; 0 disables |
| `httpClient(HttpClient)` | built-in | supply your own for proxy/TLS/executor |

Retries fire on HTTP 429/500/502/503 and connection-establishment failures only (the
billing-safe set — a failed request is never charged), with exponential backoff + full jitter
capped at 30s, honoring `Retry-After` when present.

## Examples

Runnable programs in [`examples/`](examples/) (`ScrapeExample`, `SmartscraperExample`,
`SmartbrowseExample`) read `WEBSCRAPE_API_KEY` from the environment. Build the jar, then run
one with Java's single-file launcher:

```bash
mvn -q -B package
export WEBSCRAPE_API_KEY=wsg_live_...
java --class-path target/classes examples/ScrapeExample.java
```

## License

MIT. Full API docs: <https://webscrape.ai/docs>.
