package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Happy paths, optional-field serialization, unknown error code, env-var key pickup, async. */
class ClientTest {

    private MockServer server;
    private WebscrapeClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockServer();
        client = WebscrapeClient.builder()
                .apiKey("wsg_live_testkey000000000000000000000")
                .baseUrl(server.baseUrl())
                .build();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    // ---- happy paths ----------------------------------------------------------

    @Test
    void scrapeHappyPath() {
        server.enqueueJson(200, "{"
                + "\"status\":\"completed\","
                + "\"data\":{\"request_id\":\"eng-uuid-123\",\"html\":\"# Title\",\"content_type\":\"html\","
                + "\"cleaned\":true,\"metadata\":{\"title\":\"Example\",\"description\":null,\"language\":\"en\"},"
                + "\"latency_ms\":842},"
                + "\"credits_used\":1,\"credits_remaining\":499,"
                + "\"request_id\":\"req_aB3xY9Kp\"}");

        WebscrapeResponse<ScrapeData> resp = client.scrape(
                ScrapeRequest.builder("https://example.com").clean(true).build());

        // method / path / auth / UA
        MockServer.Recorded req = server.lastRequest();
        assertEquals("POST", req.method);
        assertEquals("/v1/scrape", req.path);
        assertEquals("wsg_live_testkey000000000000000000000", req.header("X-API-Key"));
        assertEquals("webscrape-ai-java/0.1.0", req.header("User-Agent"));
        assertEquals("application/json", req.header("Content-Type"));
        assertEquals("https://example.com", req.bodyJson().get("website_url").asText());
        assertTrue(req.bodyJson().get("clean").asBoolean());

        // envelope preserved + both request ids distinct
        assertEquals("req_aB3xY9Kp", resp.getRequestId());
        assertEquals(1, resp.getCreditsUsed());
        assertEquals(499, resp.getCreditsRemaining());
        assertEquals("eng-uuid-123", resp.getData().getRequestId());
        assertEquals("# Title", resp.getData().getHtml());
        assertEquals("html", resp.getData().getContentType());
        assertTrue(resp.getData().getCleaned());
        assertEquals("Example", resp.getData().getMetadata().getTitle());
        assertNull(resp.getData().getMetadata().getDescription());
        assertEquals(842, resp.getData().getLatencyMs());
    }

    @Test
    void smartscraperHappyPathWithResultAs() {
        server.enqueueJson(200, "{"
                + "\"status\":\"completed\","
                + "\"data\":{\"request_id\":\"eng-uuid-777\",\"result\":{\"title\":\"Hello\",\"score\":42},\"latency_ms\":1200},"
                + "\"credits_used\":5,\"credits_remaining\":495,"
                + "\"request_id\":\"req_smart01\"}");

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        WebscrapeResponse<SmartscraperData> resp = client.smartscraper(
                SmartscraperRequest.builder("https://news.ycombinator.com", "Extract the title and score")
                        .outputSchema(schema)
                        .detailLevel("high")
                        .build());

        MockServer.Recorded req = server.lastRequest();
        assertEquals("POST", req.method);
        assertEquals("/v1/smartscraper", req.path);
        assertEquals("Extract the title and score", req.bodyJson().get("user_prompt").asText());
        assertEquals("high", req.bodyJson().get("detail_level").asText());
        assertEquals("object", req.bodyJson().get("output_schema").get("type").asText());

        assertEquals("req_smart01", resp.getRequestId());
        assertEquals(5, resp.getCreditsUsed());
        assertEquals("eng-uuid-777", resp.getData().getRequestId());

        JsonNode result = resp.getData().getResult();
        assertEquals("Hello", result.get("title").asText());

        Extracted typed = resp.getData().resultAs(Extracted.class);
        assertEquals("Hello", typed.title);
        assertEquals(42, typed.score);
    }

    @Test
    void smartBrowseRunDispatch() {
        server.enqueueJson(202, "{"
                + "\"status\":\"queued\","
                + "\"data\":{\"run_id\":\"k7Xb9dRmQ2p\",\"recipe_id\":\"m3Yc2tFvN8q\",\"run_status\":\"running\","
                + "\"poll_url\":\"/v1/smartbrowse/runs/k7Xb9dRmQ2p\",\"created_at\":\"2026-07-06T12:00:00Z\"},"
                + "\"request_id\":\"req_disp01\"}");

        WebscrapeResponse<SmartBrowseRunDispatch> resp = client.smartBrowse().run("m3Yc2tFvN8q");

        MockServer.Recorded req = server.lastRequest();
        assertEquals("POST", req.method);
        assertEquals("/v1/smartbrowse/recipes/m3Yc2tFvN8q/run", req.path);
        // no body on dispatch
        assertEquals(0, req.body.length);

        assertEquals("req_disp01", resp.getRequestId());
        assertNull(resp.getCreditsUsed(), "dispatch has no credits fields");
        assertNull(resp.getCreditsRemaining());
        assertEquals("k7Xb9dRmQ2p", resp.getData().getRunId());
        assertEquals(RunStatus.RUNNING, resp.getData().getRunStatus());
    }

    @Test
    void smartBrowseGetRun() {
        server.enqueueJson(200, "{"
                + "\"status\":\"completed\","
                + "\"data\":{\"id\":\"k7Xb9dRmQ2p\",\"recipe_id\":\"m3Yc2tFvN8q\",\"run_status\":\"completed\","
                + "\"pages_extracted\":3,\"items_extracted\":42,\"credits_used\":6,"
                + "\"started_at\":\"2026-07-06T12:00:01Z\",\"completed_at\":\"2026-07-06T12:00:30Z\","
                + "\"result\":{\"pages\":[{\"items\":[{\"a\":1}]}],\"mode\":\"paged\",\"drift\":0.1,\"warnings\":[]},"
                + "\"created_at\":\"2026-07-06T12:00:00Z\"},"
                + "\"credits_used\":0,\"credits_remaining\":494,"
                + "\"request_id\":\"req_getrun1\"}");

        WebscrapeResponse<SmartBrowseRun> resp = client.smartBrowse().getRun("k7Xb9dRmQ2p");

        MockServer.Recorded req = server.lastRequest();
        assertEquals("GET", req.method);
        assertEquals("/v1/smartbrowse/runs/k7Xb9dRmQ2p", req.path);

        // envelope credits_used is 0 (free), the run's accrued spend is data.credits_used
        assertEquals(0, resp.getCreditsUsed());
        assertEquals(6, resp.getData().getCreditsUsed());
        assertEquals(RunStatus.COMPLETED, resp.getData().getRunStatus());
        assertTrue(resp.getData().isTerminal());
        assertEquals(3, resp.getData().getPagesExtracted());
        assertEquals(42, resp.getData().getItemsExtracted());
        assertEquals(1, resp.getData().getResult().get("pages").get(0).get("items").size());
    }

    @Test
    void smartBrowseUsage() {
        server.enqueueJson(200, "{"
                + "\"status\":\"completed\","
                + "\"data\":{\"runs_used_30d\":12,\"runs_per_month_cap\":50,\"pages_per_run_cap\":25,"
                + "\"cost_per_page\":2,\"schedules_count\":1,\"schedules_allowed\":true,"
                + "\"last_run\":{\"id\":\"k7Xb9dRmQ2p\",\"status\":\"completed\",\"pages_extracted\":3,"
                + "\"effective_cap\":25,\"clamped_by_credits\":false,\"created_at\":\"2026-07-06T12:00:00Z\","
                + "\"completed_at\":\"2026-07-06T12:00:30Z\"}},"
                + "\"credits_used\":0,\"credits_remaining\":494,"
                + "\"request_id\":\"req_usage1\"}");

        WebscrapeResponse<SmartBrowseUsage> resp = client.smartBrowse().usage();

        assertEquals("GET", server.lastRequest().method);
        assertEquals("/v1/smartbrowse/usage", server.lastRequest().path);
        assertEquals(0, resp.getCreditsUsed());
        assertEquals(12, resp.getData().getRunsUsed30d());
        assertEquals(50, resp.getData().getRunsPerMonthCap());
        assertEquals(2, resp.getData().getCostPerPage());
        assertTrue(resp.getData().getSchedulesAllowed());
        assertEquals(RunStatus.COMPLETED, resp.getData().getLastRun().getStatus());
        assertFalse(resp.getData().getLastRun().getClampedByCredits());
    }

    // ---- optional-field serialization -----------------------------------------

    @Test
    void untouchedOptionalsAreAbsentFromBody() {
        server.enqueueJson(200, "{\"status\":\"completed\",\"data\":{\"request_id\":\"x\"},"
                + "\"credits_used\":1,\"credits_remaining\":1,\"request_id\":\"req_x\"}");

        client.scrape(ScrapeRequest.builder("https://example.com").clean(true).build());

        JsonNode body = server.lastRequest().bodyJson();
        assertTrue(body.has("website_url"));
        assertTrue(body.has("clean"));
        // Everything the caller never touched must be omitted entirely.
        assertFalse(body.has("tag_truncate"), "tag_truncate must be absent");
        assertFalse(body.has("parse_mode"));
        assertFalse(body.has("extract_links"));
        assertFalse(body.has("stealth"));
        assertFalse(body.has("max_age"));
        assertFalse(body.has("headers"));
        assertFalse(body.has("include_tags"));
        assertEquals(2, body.size(), "only website_url + clean should be present");
    }

    // ---- unknown error code ---------------------------------------------------

    @Test
    void unknownErrorCodeMapsToGenericApiException() {
        server.enqueueJson(418, "{\"status\":\"error\",\"error\":{\"code\":\"teapot_meltdown\","
                + "\"message\":\"i'm a teapot\"},\"request_id\":\"req_teapot\"}");

        WebscrapeApiException ex = assertThrows(WebscrapeApiException.class,
                () -> client.scrape(ScrapeRequest.builder("https://example.com").build()));

        // Exactly the generic type, not a family subtype.
        assertEquals(WebscrapeApiException.class, ex.getClass());
        assertEquals("teapot_meltdown", ex.getCode(), "raw code preserved");
        assertEquals(418, ex.getStatusCode());
        assertEquals("req_teapot", ex.getRequestId());
    }

    // ---- env-var pickup + missing-key construction ----------------------------

    @Test
    void apiKeyPickedUpFromEnvVar() {
        WebscrapeClient envClient = WebscrapeClient.builder()
                .envLookup(name -> "WEBSCRAPE_API_KEY".equals(name) ? "wsg_live_fromenv0000000000000000000" : null)
                .baseUrl(server.baseUrl())
                .build();

        server.enqueueJson(200, "{\"status\":\"completed\",\"data\":{\"request_id\":\"x\"},"
                + "\"credits_used\":1,\"credits_remaining\":1,\"request_id\":\"req_x\"}");

        envClient.scrape(ScrapeRequest.builder("https://example.com").build());
        assertEquals("wsg_live_fromenv0000000000000000000", server.lastRequest().header("X-API-Key"));
    }

    @Test
    void missingKeyThrowsAtBuild() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> WebscrapeClient.builder()
                        .envLookup(name -> null)
                        .build());
        assertTrue(ex.getMessage().contains("WEBSCRAPE_API_KEY"));
    }

    // ---- async variant --------------------------------------------------------

    @Test
    void scrapeAsyncHappyPath() {
        server.enqueueJson(200, "{\"status\":\"completed\",\"data\":{\"request_id\":\"eng-async\",\"html\":\"ok\"},"
                + "\"credits_used\":1,\"credits_remaining\":498,\"request_id\":\"req_async1\"}");

        WebscrapeResponse<ScrapeData> resp =
                client.scrapeAsync(ScrapeRequest.builder("https://example.com").build()).join();

        assertEquals("req_async1", resp.getRequestId());
        assertEquals("eng-async", resp.getData().getRequestId());
        assertEquals("ok", resp.getData().getHtml());
        assertNotNull(server.lastRequest());
    }

    /** Target type for {@link SmartscraperData#resultAs(Class)}. */
    static final class Extracted {
        public String title;
        public int score;
    }
}
