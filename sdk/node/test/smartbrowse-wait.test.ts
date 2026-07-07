import { describe, expect, it } from "vitest";
import { RunFailedError, WaitTimeoutError, Webscrape } from "../src";
import { completedEnvelope, mockFetch, type RecordedRequest } from "./helpers";

const API_KEY = "wsg_live_0123456789abcdefghijklmnopqrstuv";

function runData(status: string, extra: Record<string, unknown> = {}) {
  return {
    id: "k7Xb9dRmQ2p",
    recipe_id: "m3Yc2tFvN8q",
    run_status: status,
    pages_extracted: 0,
    items_extracted: 0,
    credits_used: 0,
    created_at: "2026-07-06T12:00:00Z",
    ...extra,
  };
}

describe("waitForRun", () => {
  it("polls running→running→completed and returns the run", async () => {
    const statuses = ["running", "running", "completed"];
    const mock = mockFetch((_req: RecordedRequest, index: number) => ({
      status: 200,
      json: completedEnvelope(runData(statuses[Math.min(index, statuses.length - 1)]!), 0, 490),
    }));
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    const res = await client.smartbrowse.waitForRun("k7Xb9dRmQ2p", { pollIntervalMs: 1 });

    expect(mock.calls).toHaveLength(3);
    expect(res.data.run_status).toBe("completed");
  });

  it("raises RunFailedError carrying the full run on failure", async () => {
    const mock = mockFetch({
      status: 200,
      json: completedEnvelope(
        runData("failed", { error: "boom", pages_extracted: 2, credits_used: 4 }),
        0,
        490,
      ),
    });
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    await expect(
      client.smartbrowse.waitForRun("k7Xb9dRmQ2p", { pollIntervalMs: 1 }),
    ).rejects.toSatisfy((err: unknown) => {
      expect(err).toBeInstanceOf(RunFailedError);
      const e = err as RunFailedError;
      expect(e.run.run_status).toBe("failed");
      expect(e.run.error).toBe("boom");
      expect(e.run.pages_extracted).toBe(2);
      expect(e.run.credits_used).toBe(4);
      expect(e.message).toContain("boom");
      return true;
    });
  });

  it("raises RunFailedError on cancelled", async () => {
    const mock = mockFetch({
      status: 200,
      json: completedEnvelope(runData("cancelled", { error: "account deleted" }), 0, 490),
    });
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    await expect(
      client.smartbrowse.waitForRun("k7Xb9dRmQ2p", { pollIntervalMs: 1 }),
    ).rejects.toBeInstanceOf(RunFailedError);
  });

  it("raises WaitTimeoutError carrying the last-seen run when the deadline passes", async () => {
    const mock = mockFetch({
      status: 200,
      json: completedEnvelope(runData("running", { pages_extracted: 1 }), 0, 490),
    });
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    await expect(
      client.smartbrowse.waitForRun("k7Xb9dRmQ2p", { pollIntervalMs: 5, timeoutMs: 40 }),
    ).rejects.toSatisfy((err: unknown) => {
      expect(err).toBeInstanceOf(WaitTimeoutError);
      const e = err as WaitTimeoutError;
      expect(e.run.run_status).toBe("running");
      expect(e.run.pages_extracted).toBe(1);
      return true;
    });
    // Polled at least twice before timing out.
    expect(mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it("keeps polling through an unknown (non-terminal) status until the deadline", async () => {
    const mock = mockFetch({
      status: 200,
      json: completedEnvelope(runData("paused_by_moon_phase"), 0, 490),
    });
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    await expect(
      client.smartbrowse.waitForRun("k7Xb9dRmQ2p", { pollIntervalMs: 5, timeoutMs: 30 }),
    ).rejects.toBeInstanceOf(WaitTimeoutError);
  });
});

describe("runAndWait", () => {
  it("dispatches then polls to completion", async () => {
    const mock = mockFetch((req: RecordedRequest) => {
      if (req.method === "POST") {
        return {
          status: 202,
          json: {
            status: "queued",
            data: {
              run_id: "k7Xb9dRmQ2p",
              recipe_id: "m3Yc2tFvN8q",
              run_status: "running",
              poll_url: "/v1/smartbrowse/runs/k7Xb9dRmQ2p",
              created_at: "2026-07-06T12:00:00Z",
            },
            request_id: "req_disp01",
          },
        };
      }
      return {
        status: 200,
        json: completedEnvelope(runData("completed", { pages_extracted: 5, credits_used: 10 }), 0, 480),
      };
    });
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    const res = await client.smartbrowse.runAndWait("m3Yc2tFvN8q", { pollIntervalMs: 1 });

    expect(mock.calls[0]!.method).toBe("POST");
    expect(mock.calls[1]!.method).toBe("GET");
    expect(res.data.run_status).toBe("completed");
    expect(res.data.pages_extracted).toBe(5);
  });
});
