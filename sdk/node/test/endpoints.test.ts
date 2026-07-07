import { describe, expect, it } from "vitest";
import { Webscrape } from "../src";
import { completedEnvelope, mockFetch } from "./helpers";

const API_KEY = "wsg_live_0123456789abcdefghijklmnopqrstuv";

function pathOf(url: string): string {
  return new URL(url).pathname;
}

describe("endpoint happy paths", () => {
  it("scrape: method, path, headers, body, and parsed response", async () => {
    const mock = mockFetch(
      completedEnvelope(
        {
          request_id: "eng_scrape_uuid",
          html: "# Hello",
          content_type: "html",
          cleaned: true,
          metadata: { title: "Hello", description: null, language: "en" },
          latency_ms: 42,
        },
        1,
        499,
        "req_scrape01",
      ),
    );
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    const res = await client.scrape({ website_url: "https://example.com", clean: true });

    expect(mock.calls).toHaveLength(1);
    const call = mock.calls[0]!;
    expect(call.method).toBe("POST");
    expect(pathOf(call.url)).toBe("/v1/scrape");
    expect(call.headers["x-api-key"]).toBe(API_KEY);
    expect(call.headers["user-agent"]).toBe("webscrape-ai-node/0.1.0");
    expect(call.headers["content-type"]).toBe("application/json");
    expect(call.headers["accept"]).toBe("application/json");
    expect(call.body).toEqual({ website_url: "https://example.com", clean: true });

    // Envelope preserved: both request ids exposed and distinct.
    expect(res.status).toBe("completed");
    expect(res.request_id).toBe("req_scrape01");
    expect(res.data.request_id).toBe("eng_scrape_uuid");
    expect(res.credits_used).toBe(1);
    expect(res.credits_remaining).toBe(499);
    expect(res.data.html).toBe("# Hello");
    expect(res.data.metadata?.title).toBe("Hello");
  });

  it("smartscraper: user_prompt body, typed result, both request ids", async () => {
    interface Story {
      title: string;
      score: number;
    }
    const mock = mockFetch(
      completedEnvelope(
        { request_id: "eng_ss_uuid", result: { title: "A", score: 10 }, latency_ms: 900 },
        5,
        495,
        "req_ss01",
      ),
    );
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    const res = await client.smartscraper<Story>({
      website_url: "https://news.ycombinator.com",
      user_prompt: "Extract the top story",
    });

    const call = mock.calls[0]!;
    expect(call.method).toBe("POST");
    expect(pathOf(call.url)).toBe("/v1/smartscraper");
    expect(call.body).toEqual({
      website_url: "https://news.ycombinator.com",
      user_prompt: "Extract the top story",
    });

    expect(res.request_id).toBe("req_ss01");
    expect(res.data.request_id).toBe("eng_ss_uuid");
    expect(res.credits_used).toBe(5);
    // Runtime read; the compile-time typing is asserted in types.test-d.ts.
    expect(res.data.result?.title).toBe("A");
    expect(res.data.result?.score).toBe(10);
  });

  it("smartbrowse.run: POST with no body, 202 queued envelope", async () => {
    const mock = mockFetch({
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
        request_id: "req_run01",
      },
    });
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    const res = await client.smartbrowse.run("m3Yc2tFvN8q");

    const call = mock.calls[0]!;
    expect(call.method).toBe("POST");
    expect(pathOf(call.url)).toBe("/v1/smartbrowse/recipes/m3Yc2tFvN8q/run");
    // No request body on dispatch.
    expect(call.body).toBeUndefined();
    expect(call.headers["content-type"]).toBeUndefined();

    expect(res.status).toBe("queued");
    expect(res.request_id).toBe("req_run01");
    expect(res.data.run_id).toBe("k7Xb9dRmQ2p");
    expect(res.data.recipe_id).toBe("m3Yc2tFvN8q");
    // Queued responses carry no credit fields.
    expect("credits_used" in res).toBe(false);
  });

  it("smartbrowse.getRun: GET path and run data", async () => {
    const mock = mockFetch(
      completedEnvelope(
        {
          id: "k7Xb9dRmQ2p",
          recipe_id: "m3Yc2tFvN8q",
          run_status: "completed",
          pages_extracted: 3,
          items_extracted: 30,
          credits_used: 6,
          created_at: "2026-07-06T12:00:00Z",
          result: { pages: [{ items: [{ a: 1 }] }], mode: "recipe", drift: 0, warnings: [] },
        },
        0,
        493,
        "req_get01",
      ),
    );
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    const res = await client.smartbrowse.getRun("k7Xb9dRmQ2p");

    const call = mock.calls[0]!;
    expect(call.method).toBe("GET");
    expect(pathOf(call.url)).toBe("/v1/smartbrowse/runs/k7Xb9dRmQ2p");
    expect(call.body).toBeUndefined();

    // Envelope credits_used is 0 (free); the run's accrued spend is in data.
    expect(res.credits_used).toBe(0);
    expect(res.data.credits_used).toBe(6);
    expect(res.data.run_status).toBe("completed");
    expect(res.data.pages_extracted).toBe(3);
  });

  it("smartbrowse.usage: GET path and usage data", async () => {
    const mock = mockFetch(
      completedEnvelope(
        {
          runs_used_30d: 4,
          runs_per_month_cap: 50,
          pages_per_run_cap: 20,
          cost_per_page: 2,
          schedules_count: 1,
          schedules_allowed: true,
          last_run: null,
        },
        0,
        493,
        "req_usage01",
      ),
    );
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    const res = await client.smartbrowse.usage();

    const call = mock.calls[0]!;
    expect(call.method).toBe("GET");
    expect(pathOf(call.url)).toBe("/v1/smartbrowse/usage");
    expect(res.credits_used).toBe(0);
    expect(res.data.runs_used_30d).toBe(4);
    expect(res.data.cost_per_page).toBe(2);
    expect(res.data.last_run).toBeNull();
  });
});

describe("request serialization", () => {
  it("omits every optional field the caller did not set", async () => {
    const mock = mockFetch(completedEnvelope({ request_id: "x", html: "" }));
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    await client.scrape({ website_url: "https://example.com" });

    expect(mock.calls[0]!.body).toEqual({ website_url: "https://example.com" });
    expect(Object.keys(mock.calls[0]!.body as object)).toEqual(["website_url"]);
  });

  it("sends explicitly-set falsey / zero tri-state values", async () => {
    const mock = mockFetch(completedEnvelope({ request_id: "x", html: "" }));
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    await client.scrape({
      website_url: "https://example.com",
      tag_truncate: false,
      max_age: 0,
      stealth: false,
    });

    expect(mock.calls[0]!.body).toEqual({
      website_url: "https://example.com",
      tag_truncate: false,
      max_age: 0,
      stealth: false,
    });
  });

  it("passes output_schema through untouched", async () => {
    const mock = mockFetch(completedEnvelope({ request_id: "x", result: {} }));
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    const schema = { type: "object", properties: { title: { type: "string" } } };
    await client.smartscraper({
      website_url: "https://example.com",
      user_prompt: "extract",
      output_schema: schema,
    });

    expect((mock.calls[0]!.body as Record<string, unknown>).output_schema).toEqual(schema);
  });
});
