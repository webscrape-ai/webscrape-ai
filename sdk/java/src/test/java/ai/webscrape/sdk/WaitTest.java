package ai.webscrape.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wait helper: running→running→completed, →failed, and deadline. */
class WaitTest {

    private MockServer server;
    private WebscrapeClient client;

    private static final WaitOptions FAST = WaitOptions.builder()
            .pollInterval(Duration.ofMillis(5))
            .timeout(Duration.ofSeconds(5))
            .build();

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

    private void enqueueRun(String status) {
        server.enqueueJson(200, "{\"status\":\"completed\","
                + "\"data\":{\"id\":\"run1\",\"recipe_id\":\"rec1\",\"run_status\":\"" + status + "\","
                + "\"pages_extracted\":1,\"items_extracted\":2,\"credits_used\":2,\"created_at\":\"2026-07-06T12:00:00Z\"},"
                + "\"credits_used\":0,\"credits_remaining\":1,\"request_id\":\"req_poll\"}");
    }

    @Test
    void pollsUntilCompleted() {
        enqueueRun("running");
        enqueueRun("running");
        enqueueRun("completed");

        WebscrapeResponse<SmartBrowseRun> resp = client.smartBrowse().waitForRun("run1", FAST);

        assertEquals(RunStatus.COMPLETED, resp.getData().getRunStatus());
        assertEquals(3, server.requestCount());
    }

    @Test
    void failedRaisesRunFailedCarryingRun() {
        enqueueRun("running");
        server.enqueueJson(200, "{\"status\":\"completed\","
                + "\"data\":{\"id\":\"run1\",\"recipe_id\":\"rec1\",\"run_status\":\"failed\","
                + "\"pages_extracted\":1,\"items_extracted\":0,\"credits_used\":2,\"error\":\"boom\","
                + "\"created_at\":\"2026-07-06T12:00:00Z\"},"
                + "\"credits_used\":0,\"credits_remaining\":1,\"request_id\":\"req_poll\"}");

        RunFailedException ex = assertThrows(RunFailedException.class,
                () -> client.smartBrowse().waitForRun("run1", FAST));

        assertNotNull(ex.getRun());
        assertEquals(RunStatus.FAILED, ex.getRun().getRunStatus());
        assertEquals("boom", ex.getRun().getError());
        assertEquals(2, ex.getRun().getCreditsUsed());
    }

    @Test
    void deadlineRaisesWaitTimeoutCarryingLastRun() {
        // Server always returns running; a short timeout forces a WaitTimeoutException.
        for (int i = 0; i < 50; i++) {
            enqueueRun("running");
        }
        WaitOptions tiny = WaitOptions.builder()
                .pollInterval(Duration.ofMillis(5))
                .timeout(Duration.ofMillis(40))
                .build();

        WaitTimeoutException ex = assertThrows(WaitTimeoutException.class,
                () -> client.smartBrowse().waitForRun("run1", tiny));

        assertNotNull(ex.getLastSeen());
        assertEquals(RunStatus.RUNNING, ex.getLastSeen().getRunStatus());
    }

    @Test
    void runAndWaitDispatchesThenPolls() {
        server.enqueueJson(202, "{\"status\":\"queued\","
                + "\"data\":{\"run_id\":\"run1\",\"recipe_id\":\"rec1\",\"run_status\":\"running\","
                + "\"poll_url\":\"/v1/smartbrowse/runs/run1\",\"created_at\":\"2026-07-06T12:00:00Z\"},"
                + "\"request_id\":\"req_disp\"}");
        enqueueRun("completed");

        WebscrapeResponse<SmartBrowseRun> resp = client.smartBrowse().runAndWait("rec1", FAST);

        assertEquals(RunStatus.COMPLETED, resp.getData().getRunStatus());
        assertEquals("POST", server.requests().get(0).method);
        assertEquals("/v1/smartbrowse/recipes/rec1/run", server.requests().get(0).path);
        assertEquals("GET", server.requests().get(1).method);
    }

    @Test
    void waitForRunAsyncCompletesExceptionallyOnFailure() {
        enqueueRun("cancelled");

        // async variant completes exceptionally with the same exception type (wrapped by the CF API).
        Throwable thrown = assertThrows(java.util.concurrent.CompletionException.class,
                () -> client.smartBrowse().waitForRunAsync("run1", FAST).join());
        assertTrue(thrown.getCause() instanceof RunFailedException);
        assertEquals(RunStatus.CANCELLED, ((RunFailedException) thrown.getCause()).getRun().getRunStatus());
    }
}
