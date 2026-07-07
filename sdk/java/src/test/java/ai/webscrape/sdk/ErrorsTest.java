package ai.webscrape.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Error-envelope to typed error, and the plain {"error": "..."} 401 body. */
class ErrorsTest {

    private MockServer server;
    private WebscrapeClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockServer();
        client = WebscrapeClient.builder()
                .apiKey("wsg_live_testkey000000000000000000000")
                .baseUrl(server.baseUrl())
                .maxRetries(0) // don't retry so 429 surfaces immediately in these tests
                .build();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void insufficientCreditsWithDetailAccessors() {
        server.enqueueJson(402, "{\"status\":\"error\",\"error\":{\"code\":\"insufficient_credits\","
                + "\"message\":\"not enough credits\",\"details\":{\"balance\":2,\"required\":5}},"
                + "\"request_id\":\"req_credits\"}");

        InsufficientCreditsException ex = assertThrows(InsufficientCreditsException.class,
                () -> client.smartscraper(SmartscraperRequest.builder("https://example.com", "x").build()));

        assertEquals(402, ex.getStatusCode());
        assertEquals("insufficient_credits", ex.getCode());
        assertEquals(2, ex.getBalance());
        assertEquals(5, ex.getRequired());
        assertEquals("req_credits", ex.getRequestId());
    }

    @Test
    void emailVerificationRequired() {
        server.enqueueJson(402, "{\"status\":\"error\",\"error\":{\"code\":\"email_verification_required\","
                + "\"message\":\"verify your email\"},\"request_id\":\"req_ev\"}");

        assertThrows(EmailVerificationException.class,
                () -> client.scrape(ScrapeRequest.builder("https://example.com").build()));
    }

    @Test
    void notFound() {
        server.enqueueJson(404, "{\"status\":\"error\",\"error\":{\"code\":\"not_found\","
                + "\"message\":\"recipe not found\"},\"request_id\":\"req_nf\"}");

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> client.smartBrowse().run("missing"));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    void validationFailed() {
        server.enqueueJson(422, "{\"status\":\"error\",\"error\":{\"code\":\"validation_failed\","
                + "\"message\":\"schema mismatch\",\"details\":{\"type\":\"schema_validation_error\",\"errors\":[\"bad\"]}},"
                + "\"request_id\":\"req_vf\"}");

        ValidationException ex = assertThrows(ValidationException.class,
                () -> client.smartscraper(SmartscraperRequest.builder("https://example.com", "x").build()));
        assertEquals(422, ex.getStatusCode());
        assertEquals("schema_validation_error", ex.getDetails().get("type").asText());
    }

    @Test
    void rateLimitedWithReason() {
        server.enqueueJson(429, "{\"status\":\"error\",\"error\":{\"code\":\"rate_limited\","
                + "\"message\":\"too many in-flight\",\"details\":{\"max_concurrent\":5,\"reason\":\"max_concurrent_requests\"}},"
                + "\"request_id\":\"req_rl\"}");

        RateLimitException ex = assertThrows(RateLimitException.class,
                () -> client.scrape(ScrapeRequest.builder("https://example.com").build()));
        assertEquals(429, ex.getStatusCode());
        assertEquals("max_concurrent_requests", ex.getReason());
        assertEquals(5, ex.getMaxConcurrent());
    }

    @Test
    void conflictAccountDeletionPending() {
        server.enqueueJson(409, "{\"status\":\"error\",\"error\":{\"code\":\"account_deletion_pending\","
                + "\"message\":\"scheduled for deletion\",\"details\":{\"deletion_scheduled_for\":\"2026-06-15T12:00:00Z\"}},"
                + "\"request_id\":\"req_del\"}");

        ConflictException ex = assertThrows(ConflictException.class,
                () -> client.scrape(ScrapeRequest.builder("https://example.com").build()));
        assertEquals("account_deletion_pending", ex.getCode());
        assertEquals("2026-06-15T12:00:00Z", ex.getDeletionScheduledFor());
    }

    @Test
    void serverErrorMapsToServerException() {
        // maxRetries=0 so the 500 surfaces immediately without retry.
        server.enqueueJson(500, "{\"status\":\"error\",\"error\":{\"code\":\"internal_error\","
                + "\"message\":\"boom\"},\"request_id\":\"req_500\"}");

        ServerException ex = assertThrows(ServerException.class,
                () -> client.scrape(ScrapeRequest.builder("https://example.com").build()));
        assertEquals(500, ex.getStatusCode());
    }

    // ---- plain-error 401 ------------------------------------------------------

    @Test
    void bare401IsAuthenticationError() {
        // Authentication failures may return a plain string `error` instead of the envelope,
        // and no body request_id; both shapes are handled.
        server.enqueue(new MockServer.Canned(401,
                "{\"error\":\"missing or invalid credentials — provide a session cookie or X-API-Key header\"}")
                .header("Content-Type", "application/json")
                .header("X-Request-ID", "req_hdr401"));

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> client.scrape(ScrapeRequest.builder("https://example.com").build()));

        assertEquals(401, ex.getStatusCode());
        assertEquals("unauthorized", ex.getCode(), "synthesized code");
        assertEquals("missing or invalid credentials — provide a session cookie or X-API-Key header", ex.getMessage());
        // request_id falls back to the X-Request-ID header when the body has none.
        assertEquals("req_hdr401", ex.getRequestId());
        assertNull(ex.getDetails());
    }

    @Test
    void envelope401IsAlsoAuthenticationError() {
        server.enqueueJson(401, "{\"status\":\"error\",\"error\":{\"code\":\"unauthorized\","
                + "\"message\":\"revoked key\"},\"request_id\":\"req_401e\"}");

        AuthenticationException ex = assertThrows(AuthenticationException.class,
                () -> client.scrape(ScrapeRequest.builder("https://example.com").build()));
        assertEquals("unauthorized", ex.getCode());
        assertEquals("req_401e", ex.getRequestId());
    }
}
