package ai.webscrape.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retry policy. */
class RetryTest {

    private MockServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private WebscrapeClient client(int maxRetries) {
        return WebscrapeClient.builder()
                .apiKey("wsg_live_testkey000000000000000000000")
                .baseUrl(server.baseUrl())
                .maxRetries(maxRetries)
                .backoffBaseMillis(1) // keep tests fast; full jitter over ~[0,1ms]
                .build();
    }

    @Test
    void retriesOn429ThenSucceeds() {
        server.enqueueJson(429, "{\"status\":\"error\",\"error\":{\"code\":\"rate_limited\","
                + "\"message\":\"slow down\",\"details\":{\"reason\":\"rate_limit_per_min\",\"limit_per_min\":60}},"
                + "\"request_id\":\"req_429\"}");
        server.enqueueJson(200, "{\"status\":\"completed\",\"data\":{\"request_id\":\"eng\",\"html\":\"ok\"},"
                + "\"credits_used\":1,\"credits_remaining\":9,\"request_id\":\"req_ok\"}");

        WebscrapeResponse<ScrapeData> resp =
                client(2).scrape(ScrapeRequest.builder("https://example.com").build());

        assertEquals("ok", resp.getData().getHtml());
        assertEquals(2, server.requestCount(), "one retry after the 429");
    }

    @Test
    void maxRetriesZeroSurfacesFirst429() {
        server.enqueueJson(429, "{\"status\":\"error\",\"error\":{\"code\":\"rate_limited\","
                + "\"message\":\"slow down\",\"details\":{\"reason\":\"rate_limit_per_min\"}},"
                + "\"request_id\":\"req_429\"}");
        // A success is queued behind it that must NOT be consumed.
        server.enqueueJson(200, "{\"status\":\"completed\",\"data\":{\"request_id\":\"eng\"},"
                + "\"credits_used\":1,\"credits_remaining\":9,\"request_id\":\"req_ok\"}");

        assertThrows(RateLimitException.class,
                () -> client(0).scrape(ScrapeRequest.builder("https://example.com").build()));
        assertEquals(1, server.requestCount(), "no retry when maxRetries=0");
    }

    @Test
    void plainBadRequestIsNotRetried() {
        server.enqueueJson(400, "{\"status\":\"error\",\"error\":{\"code\":\"invalid_request\","
                + "\"message\":\"url is required\"},\"request_id\":\"req_400\"}");

        assertThrows(BadRequestException.class,
                () -> client(2).scrape(ScrapeRequest.builder("https://example.com").build()));
        assertEquals(1, server.requestCount(), "4xx (other than 429) is never retried");
    }

    @Test
    void exhaustedRetriesSurfaceTypedError() {
        // Three 500s; maxRetries=2 => 3 attempts, then the typed ServerException.
        for (int i = 0; i < 3; i++) {
            server.enqueueJson(500, "{\"status\":\"error\",\"error\":{\"code\":\"internal_error\","
                    + "\"message\":\"boom\"},\"request_id\":\"req_500\"}");
        }

        ServerException ex = assertThrows(ServerException.class,
                () -> client(2).scrape(ScrapeRequest.builder("https://example.com").build()));
        assertEquals(500, ex.getStatusCode());
        assertEquals(3, server.requestCount(), "3 attempts total");
    }

    @Test
    void connectionFailureWrapsAsTransportException() {
        // Point at a closed port: connection refused => ConnectException => TransportException.
        WebscrapeClient c = WebscrapeClient.builder()
                .apiKey("wsg_live_testkey000000000000000000000")
                .baseUrl("http://127.0.0.1:1/v1")
                .maxRetries(0)
                .build();

        TransportException ex = assertThrows(TransportException.class,
                () -> c.scrape(ScrapeRequest.builder("https://example.com").build()));
        assertTrue(ex.getCause() instanceof IOException || ex.getCause() != null);
    }
}
