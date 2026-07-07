import { describe, expect, it } from "vitest";
import {
  BadRequestError,
  RateLimitError,
  TransportError,
  Webscrape,
  type FetchImpl,
} from "../src";
import { completedEnvelope, errorEnvelope, mockFetch } from "./helpers";

const API_KEY = "wsg_live_0123456789abcdefghijklmnopqrstuv";

describe("retry policy", () => {
  it("retries a 429 then succeeds (max_retries=2)", async () => {
    const mock = mockFetch([
      {
        status: 429,
        json: errorEnvelope("rate_limited", "slow down", { reason: "rate_limit_per_min" }),
        // Retry-After: 0 keeps the backoff instant and deterministic.
        headers: { "Retry-After": "0" },
      },
      { status: 200, json: completedEnvelope({ request_id: "x", html: "ok" }) },
    ]);
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch, maxRetries: 2 });

    const res = await client.scrape({ website_url: "https://example.com" });

    expect(mock.calls).toHaveLength(2);
    expect(res.data.html).toBe("ok");
  });

  it("max_retries=0 surfaces the 429 immediately", async () => {
    const mock = mockFetch({
      status: 429,
      json: errorEnvelope("rate_limited", "slow down", { reason: "max_concurrent_requests" }),
    });
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch, maxRetries: 0 });

    await expect(client.scrape({ website_url: "https://example.com" })).rejects.toBeInstanceOf(
      RateLimitError,
    );
    expect(mock.calls).toHaveLength(1);
  });

  it("does not retry a plain 400", async () => {
    const mock = mockFetch([
      { status: 400, json: errorEnvelope("invalid_request", "url is required") },
      { status: 200, json: completedEnvelope({ request_id: "x" }) },
    ]);
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch, maxRetries: 2 });

    await expect(client.scrape({ website_url: "https://example.com" })).rejects.toBeInstanceOf(
      BadRequestError,
    );
    expect(mock.calls).toHaveLength(1);
  });

  it("exhausts retries and throws the final error", async () => {
    const mock = mockFetch({
      status: 503,
      json: errorEnvelope("service_unavailable", "down"),
      headers: { "Retry-After": "0" },
    });
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch, maxRetries: 2 });

    await expect(client.scrape({ website_url: "https://example.com" })).rejects.toMatchObject({
      status: 503,
    });
    // 1 initial + 2 retries = 3 attempts.
    expect(mock.calls).toHaveLength(3);
  });

  it("retries connection-establishment failures but not mid-response resets", async () => {
    let calls = 0;
    const connectRefused: FetchImpl = (async () => {
      calls += 1;
      if (calls === 1) {
        const err = new TypeError("fetch failed");
        (err as { cause?: unknown }).cause = { code: "ECONNREFUSED" };
        throw err;
      }
      return new Response(JSON.stringify(completedEnvelope({ request_id: "x", html: "ok" })), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }) as unknown as FetchImpl;

    const client = new Webscrape({ apiKey: API_KEY, fetch: connectRefused, maxRetries: 2 });
    const res = await client.scrape({ website_url: "https://example.com" });
    expect(res.data.html).toBe("ok");
    expect(calls).toBe(2);
  });

  it("does not retry ECONNRESET (may be mid-response, billing-unsafe)", async () => {
    let calls = 0;
    const reset: FetchImpl = (async () => {
      calls += 1;
      const err = new TypeError("fetch failed");
      (err as { cause?: unknown }).cause = { code: "ECONNRESET" };
      throw err;
    }) as unknown as FetchImpl;

    const client = new Webscrape({ apiKey: API_KEY, fetch: reset, maxRetries: 2 });
    await expect(client.scrape({ website_url: "https://example.com" })).rejects.toBeInstanceOf(
      TransportError,
    );
    expect(calls).toBe(1);
  });
});
