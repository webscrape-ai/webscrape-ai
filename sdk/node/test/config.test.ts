import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  ConfigurationError,
  TimeoutError,
  Webscrape,
  type FetchImpl,
} from "../src";
import { completedEnvelope, mockFetch } from "./helpers";

const API_KEY = "wsg_live_0123456789abcdefghijklmnopqrstuv";

/** A fetch that never resolves until its signal aborts (handles pre-aborted signals). */
const hangingFetch: FetchImpl = ((_url: unknown, init?: unknown) =>
  new Promise((_resolve, reject) => {
    const signal = (init as { signal?: AbortSignal }).signal;
    const onAbort = () =>
      reject(signal?.reason ?? Object.assign(new Error("aborted"), { name: "AbortError" }));
    if (!signal) return;
    if (signal.aborted) onAbort();
    else signal.addEventListener("abort", onAbort);
  })) as unknown as FetchImpl;

describe("construction & config", () => {
  const original = process.env.WEBSCRAPE_API_KEY;
  afterEach(() => {
    if (original === undefined) delete process.env.WEBSCRAPE_API_KEY;
    else process.env.WEBSCRAPE_API_KEY = original;
  });

  it("throws ConfigurationError when no key and no env var", () => {
    delete process.env.WEBSCRAPE_API_KEY;
    expect(() => new Webscrape()).toThrow(ConfigurationError);
  });

  it("picks up the WEBSCRAPE_API_KEY env var and sends it", async () => {
    process.env.WEBSCRAPE_API_KEY = "wsg_live_fromenv0000000000000000000000000";
    const mock = mockFetch(completedEnvelope({ request_id: "x", html: "" }));
    const client = new Webscrape({ fetch: mock.fetch });

    await client.scrape({ website_url: "https://example.com" });

    expect(mock.calls[0]!.headers["x-api-key"]).toBe(
      "wsg_live_fromenv0000000000000000000000000",
    );
  });

  it("explicit apiKey overrides the env var", async () => {
    process.env.WEBSCRAPE_API_KEY = "wsg_live_fromenv0000000000000000000000000";
    const mock = mockFetch(completedEnvelope({ request_id: "x" }));
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });

    await client.scrape({ website_url: "https://example.com" });
    expect(mock.calls[0]!.headers["x-api-key"]).toBe(API_KEY);
  });

  it("uses the default base URL", async () => {
    const mock = mockFetch(completedEnvelope({ request_id: "x" }));
    const client = new Webscrape({ apiKey: API_KEY, fetch: mock.fetch });
    await client.scrape({ website_url: "https://example.com" });
    expect(mock.calls[0]!.url).toBe("https://api.webscrape.ai/v1/scrape");
  });

  it("trims a trailing slash from a custom base URL", async () => {
    const mock = mockFetch(completedEnvelope({ request_id: "x" }));
    const client = new Webscrape({
      apiKey: API_KEY,
      baseUrl: "https://staging.example.com/v1/",
      fetch: mock.fetch,
    });
    await client.scrape({ website_url: "https://example.com" });
    expect(mock.calls[0]!.url).toBe("https://staging.example.com/v1/scrape");
  });
});

describe("timeout & abort", () => {
  it("throws TimeoutError when the per-request timeout elapses", async () => {
    const client = new Webscrape({ apiKey: API_KEY, fetch: hangingFetch, timeoutMs: 20 });
    await expect(client.scrape({ website_url: "https://example.com" })).rejects.toBeInstanceOf(
      TimeoutError,
    );
  });

  it("propagates a caller AbortSignal", async () => {
    const controller = new AbortController();
    const client = new Webscrape({ apiKey: API_KEY, fetch: hangingFetch, timeoutMs: 10_000 });
    const promise = client.scrape(
      { website_url: "https://example.com" },
      { signal: controller.signal },
    );
    setTimeout(() => controller.abort(new Error("user cancelled")), 5);
    await expect(promise).rejects.toThrow("user cancelled");
  });
});
