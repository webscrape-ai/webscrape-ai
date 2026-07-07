/**
 * The public client surface: the `Webscrape` class and its `smartbrowse`
 * namespace.
 */

import { ConfigurationError, RunFailedError, WaitTimeoutError } from "./errors";
import {
  DEFAULT_BASE_URL,
  DEFAULT_MAX_RETRIES,
  DEFAULT_TIMEOUT_MS,
  requestEnvelope,
  sleep,
  type FetchImpl,
  type HttpConfig,
  type RequestOptions,
} from "./http";
import type {
  ScrapeData,
  ScrapeRequest,
  ScrapeResponse,
  SmartBrowseRunData,
  SmartBrowseRunDispatchData,
  SmartBrowseRunDispatchResponse,
  SmartBrowseRunResponse,
  SmartBrowseUsageData,
  SmartBrowseUsageResponse,
  SmartScraperData,
  SmartScraperRequest,
  SmartScraperResponse,
} from "./types";
import { VERSION } from "./version";

const USER_AGENT = `webscrape-ai-node/${VERSION}`;

export interface WebscrapeOptions {
  /** API key. Falls back to `WEBSCRAPE_API_KEY`. Format `wsg_live_<32 base62>`. */
  apiKey?: string;
  /** Base URL. Default `https://api.webscrape.ai/v1`. Trailing slashes are trimmed. */
  baseUrl?: string;
  /** Per-request timeout in milliseconds. Default 180000. */
  timeoutMs?: number;
  /** Retries on 429/5xx and connection-establishment failures. Default 2; 0 disables. */
  maxRetries?: number;
  /** Custom fetch implementation (defaults to the global `fetch`). */
  fetch?: FetchImpl;
}

/** Options for the SmartBrowse wait helpers, on top of the per-call {@link RequestOptions}. */
export interface WaitOptions extends RequestOptions {
  /** Initial poll interval in ms (grows ×1.5, capped at 10s). Default 2000. */
  pollIntervalMs?: number;
  /** Overall wait budget in ms. Default 900000 (the service's 15-minute run cap). */
  timeoutMs?: number;
}

function readEnvApiKey(): string | undefined {
  // Guard process access so non-Node runtimes (Deno/edge/browser) don't crash.
  try {
    const proc = (globalThis as { process?: { env?: Record<string, string | undefined> } })
      .process;
    return proc?.env?.WEBSCRAPE_API_KEY;
  } catch {
    return undefined;
  }
}

function normalizeBaseUrl(url: string): string {
  return url.replace(/\/+$/, "");
}

/**
 * The webscrape.ai API client.
 *
 * ```ts
 * const client = new Webscrape({ apiKey: "wsg_live_..." });
 * const res = await client.scrape({ website_url: "https://example.com", clean: true });
 * console.log(res.data.html, res.credits_remaining);
 * ```
 */
export class Webscrape {
  /** SmartBrowse recipe-run operations. */
  readonly smartbrowse: SmartBrowse;
  private readonly config: HttpConfig;

  constructor(options: WebscrapeOptions = {}) {
    const apiKey = options.apiKey ?? readEnvApiKey();
    if (!apiKey) {
      throw new ConfigurationError(
        "Missing API key. Pass { apiKey } to new Webscrape(...) or set the " +
          "WEBSCRAPE_API_KEY environment variable.",
      );
    }

    const provided = options.fetch;
    const global = (globalThis as { fetch?: FetchImpl }).fetch;
    if (!provided && typeof global !== "function") {
      throw new ConfigurationError(
        "No global fetch found. Use Node 18+ (or Bun/Deno/an edge runtime) or " +
          "pass a { fetch } implementation.",
      );
    }
    // Bind the global fetch to globalThis to avoid "Illegal invocation" when it
    // is called without its original receiver.
    const fetchImpl = provided ?? (global!.bind(globalThis) as FetchImpl);

    this.config = {
      apiKey,
      baseUrl: normalizeBaseUrl(options.baseUrl ?? DEFAULT_BASE_URL),
      timeoutMs: options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
      maxRetries: options.maxRetries ?? DEFAULT_MAX_RETRIES,
      fetchImpl,
      userAgent: USER_AGENT,
    };
    this.smartbrowse = new SmartBrowse(this.config);
  }

  /** Fetch a URL as HTML, cleaned markdown, or links. Costs 1 credit (+2 with `stealth`). */
  scrape(request: ScrapeRequest, options?: RequestOptions): Promise<ScrapeResponse> {
    return requestEnvelope<ScrapeData>(
      this.config,
      "POST",
      "/scrape",
      request,
      options,
    ) as Promise<ScrapeResponse>;
  }

  /**
   * LLM structured extraction. Costs 5 credits (+5 with `stealth`).
   *
   * The generic types `data.result`: `smartscraper<Story>(...)` gives
   * `res.data.result: Story | null | undefined`.
   */
  smartscraper<T = unknown>(
    request: SmartScraperRequest,
    options?: RequestOptions,
  ): Promise<SmartScraperResponse<T>> {
    return requestEnvelope<SmartScraperData<T>>(
      this.config,
      "POST",
      "/smartscraper",
      request,
      options,
    ) as Promise<SmartScraperResponse<T>>;
  }
}

/** SmartBrowse recipe-run operations, reached via `client.smartbrowse`. */
export class SmartBrowse {
  private readonly config: HttpConfig;

  /** @internal — construct via `new Webscrape(...)`, not directly. */
  constructor(config: HttpConfig) {
    this.config = config;
  }

  /** Dispatch a recipe replay run (async). Returns immediately with a `run_id`. */
  run(recipeId: string, options?: RequestOptions): Promise<SmartBrowseRunDispatchResponse> {
    const path = `/smartbrowse/recipes/${encodeURIComponent(recipeId)}/run`;
    return requestEnvelope<SmartBrowseRunDispatchData>(
      this.config,
      "POST",
      path,
      undefined,
      options,
    ) as Promise<SmartBrowseRunDispatchResponse>;
  }

  /** Poll a run's state. Free. */
  getRun(runId: string, options?: RequestOptions): Promise<SmartBrowseRunResponse> {
    const path = `/smartbrowse/runs/${encodeURIComponent(runId)}`;
    return requestEnvelope<SmartBrowseRunData>(
      this.config,
      "GET",
      path,
      undefined,
      options,
    ) as Promise<SmartBrowseRunResponse>;
  }

  /** Plan caps and rolling 30-day usage. Free. */
  usage(options?: RequestOptions): Promise<SmartBrowseUsageResponse> {
    return requestEnvelope<SmartBrowseUsageData>(
      this.config,
      "GET",
      "/smartbrowse/usage",
      undefined,
      options,
    ) as Promise<SmartBrowseUsageResponse>;
  }

  /**
   * Poll a run until it reaches a terminal state.
   *
   * - `completed` → resolves with the run.
   * - `failed` / `cancelled` → rejects with {@link RunFailedError} carrying the run.
   * - deadline exceeded → rejects with {@link WaitTimeoutError} carrying the last run.
   */
  async waitForRun(runId: string, options: WaitOptions = {}): Promise<SmartBrowseRunResponse> {
    const pollInterval = options.pollIntervalMs ?? 2000;
    const deadline = Date.now() + (options.timeoutMs ?? 900_000);
    let interval = pollInterval;

    for (;;) {
      const response = await this.getRun(runId, { signal: options.signal });
      const status = response.data.run_status;

      if (status === "completed") return response;
      if (status === "failed" || status === "cancelled") {
        throw new RunFailedError(response.data);
      }

      const now = Date.now();
      if (now >= deadline) throw new WaitTimeoutError(response.data);

      await sleep(Math.min(interval, deadline - now), options.signal);
      interval = Math.min(interval * 1.5, 10_000);
    }
  }

  /** Dispatch a run and wait for it to finish. Shorthand for `run()` then `waitForRun()`. */
  async runAndWait(recipeId: string, options: WaitOptions = {}): Promise<SmartBrowseRunResponse> {
    const dispatch = await this.run(recipeId, { signal: options.signal });
    return this.waitForRun(dispatch.data.run_id, options);
  }
}
