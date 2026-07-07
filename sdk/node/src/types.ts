/**
 * Wire types for the webscrape.ai public API.
 *
 * These mirror the request/response shapes of the public API. Every response
 * field is modelled as optional and/or nullable because the API may emit an
 * explicit `null` for an absent field, and new fields may ship without an SDK
 * release.
 */

// ---------------------------------------------------------------------------
// Shared enums
// ---------------------------------------------------------------------------

/** Cleaner mode used when converting HTML to markdown. */
export type ParseMode = "accurate" | "speed";

/** Extraction thoroughness knob for `smartscraper`. */
export type DetailLevel = "low" | "medium" | "high";

/** Page-complexity knob for `smartscraper`. */
export type PageComplexity = "low" | "high";

/**
 * A SmartBrowse run's lifecycle state.
 *
 * The `(string & {})` member keeps literal-completion in editors while staying
 * tolerant of unknown states the server may add later — never crash on an
 * unrecognised status.
 */
export type RunStatus =
  | "queued"
  | "running"
  | "completed"
  | "failed"
  | "cancelled"
  // eslint-disable-next-line @typescript-eslint/ban-types
  | (string & {});

// ---------------------------------------------------------------------------
// Envelope
// ---------------------------------------------------------------------------

/**
 * A successful (HTTP 200) response. Exposes the full envelope: both request ids
 * (top-level support-facing `request_id` and the extraction id inside `data`) plus
 * the credit counters so callers can watch their balance drain.
 */
export interface CompletedResponse<D> {
  status: "completed";
  /** Support-facing per-request id (also mirrored in the `X-Request-ID` header). */
  request_id: string;
  /** Credits deducted for this call. `0` for free endpoints (polling, usage). */
  credits_used: number;
  /** Credit balance remaining after this call. */
  credits_remaining: number;
  data: D;
}

/**
 * An accepted-but-async (HTTP 202) response. Only the SmartBrowse dispatch
 * endpoint returns this shape; it carries no credit fields (nothing is billed
 * until the run completes).
 */
export interface QueuedResponse<D> {
  status: "queued";
  request_id: string;
  data: D;
}

export type Envelope<D> = CompletedResponse<D> | QueuedResponse<D>;

// ---------------------------------------------------------------------------
// /scrape
// ---------------------------------------------------------------------------

export interface ScrapeRequest {
  /** The URL to fetch. The only content source available to API callers. */
  website_url: string;
  /** Convert HTML to cleaned markdown. */
  clean?: boolean;
  /** Cleaner mode when `clean` is set. */
  parse_mode?: ParseMode;
  /** When cleaning, replace inline images with their alt text. Defaults true server-side. */
  tag_truncate?: boolean;
  /** Include a deduplicated list of outbound links. */
  extract_links?: boolean;
  /** Tag whitelist applied during cleaning. */
  include_tags?: string[];
  /** Tag blacklist applied during cleaning. */
  exclude_tags?: string[];
  /** Custom request headers forwarded to the fetcher. Providing any disables URL caching. */
  headers?: Record<string, string>;
  /**
   * URL-cache opt-in (seconds). Omit to always fetch fresh; `>0` accepts cache
   * entries fresher than N seconds. Never cached: stealth, custom headers, URLs
   * with a query string or fragment.
   */
  max_age?: number;
  /** Browser-based stealth fetch. +2 credits. */
  stealth?: boolean;
}

export interface LinkInfo {
  url: string;
  text: string;
}

export interface PageMetadata {
  title: string | null;
  description: string | null;
  language: string | null;
}

/**
 * Structured data extracted from the page (JSON-LD, microdata, pagination
 * hints), when available.
 */
export interface StructuredData {
  json_ld?: unknown[];
  microdata?: unknown[];
  pagination?: { next?: string | null; prev?: string | null } | null;
  [key: string]: unknown;
}

export interface ScrapeData {
  /** Extraction id, distinct from the top-level `request_id`. */
  request_id: string;
  /** Raw HTML, or markdown when `clean: true` or the source was a PDF. */
  html?: string | null;
  content_type?: "html" | "pdf";
  /** True when the cleaner pass actually ran. */
  cleaned?: boolean;
  /** Present only with `extract_links: true`. */
  links?: LinkInfo[];
  /** HTML-only metadata; null for PDFs. */
  metadata?: PageMetadata | null;
  structured_data?: StructuredData | null;
  latency_ms?: number | null;
}

export type ScrapeResponse = CompletedResponse<ScrapeData>;

// ---------------------------------------------------------------------------
// /smartscraper
// ---------------------------------------------------------------------------

export interface SmartScraperRequest {
  website_url: string;
  /** Plain-English description of what to extract. Field is `user_prompt`, not `prompt`. */
  user_prompt: string;
  /** JSON Schema the result is validated against (one repair attempt). */
  output_schema?: Record<string, unknown>;
  page_complexity?: PageComplexity;
  detail_level?: DetailLevel;
  parse_mode?: ParseMode;
  /** Return the raw extracted text as a string; bypasses schema validation. */
  plain_text?: boolean;
  include_tags?: string[];
  exclude_tags?: string[];
  /** Trim boilerplate before extraction. Tri-state: omit to use the server default. */
  reduce_content?: boolean;
  /**
   * Opt in to an alternate extraction path that can do better on hard-to-parse
   * pages. Behavior may change without notice.
   */
  experimental?: boolean;
  /** Custom request headers forwarded to the fetcher. Providing headers disables URL caching for this request. */
  headers?: Record<string, string>;
  /** URL-cache opt-in; same semantics as `/scrape`. */
  max_age?: number;
  /** Browser-based stealth fetch. +5 credits. */
  stealth?: boolean;
}

export interface SmartScraperData<T = unknown> {
  /** Extraction id, distinct from the top-level `request_id`. */
  request_id: string;
  /**
   * The extracted output: a schema-shaped object, an array, or a string when
   * `plain_text` was set. Typed via the `smartscraper<T>()` generic.
   */
  result?: T | null;
  latency_ms?: number | null;
}

export type SmartScraperResponse<T = unknown> = CompletedResponse<SmartScraperData<T>>;

// ---------------------------------------------------------------------------
// /smartbrowse
// ---------------------------------------------------------------------------

export interface SmartBrowseRunDispatchData {
  run_id: string;
  recipe_id: string;
  /** Always `running` on dispatch. */
  run_status: RunStatus;
  poll_url: string;
  created_at: string;
}

export type SmartBrowseRunDispatchResponse = QueuedResponse<SmartBrowseRunDispatchData>;

export interface SmartBrowseRunResultPage {
  items?: Array<Record<string, unknown>>;
  [key: string]: unknown;
}

export interface SmartBrowseRunResult {
  pages?: SmartBrowseRunResultPage[];
  mode?: string;
  drift?: number;
  warnings?: string[];
  [key: string]: unknown;
}

export interface SmartBrowseRunData {
  id: string;
  recipe_id: string;
  run_status: RunStatus;
  pages_extracted: number;
  items_extracted: number;
  /** The run's accrued credit spend (distinct from the free polling call). */
  credits_used: number;
  started_at?: string | null;
  completed_at?: string | null;
  /** Set only on `failed` / `cancelled` runs. */
  error?: string;
  /** Present once the run is `completed`. */
  result?: SmartBrowseRunResult;
  created_at: string;
}

export type SmartBrowseRunResponse = CompletedResponse<SmartBrowseRunData>;

export interface SmartBrowseLastRun {
  id: string;
  status: RunStatus;
  pages_extracted: number;
  /** Page ceiling actually applied: `min(plan pages/run, balance / cost_per_page)`. */
  effective_cap: number;
  /** True when credits, not the plan, were the binding page-count constraint. */
  clamped_by_credits: boolean;
  created_at: string;
  completed_at?: string;
}

export interface SmartBrowseUsageData {
  runs_used_30d: number;
  runs_per_month_cap: number;
  pages_per_run_cap: number;
  cost_per_page: number;
  schedules_count: number;
  schedules_allowed: boolean;
  /** The most recent run, or null when the account has never run. */
  last_run?: SmartBrowseLastRun | null;
}

export type SmartBrowseUsageResponse = CompletedResponse<SmartBrowseUsageData>;
