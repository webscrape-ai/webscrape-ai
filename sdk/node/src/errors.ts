/**
 * Error taxonomy for the webscrape.ai SDK.
 *
 * Everything is rooted at {@link WebscrapeError} (which extends the native
 * `Error`), so a single `catch (err) { if (err instanceof WebscrapeError) ... }`
 * covers every failure mode. API failures become {@link APIError} subtypes keyed
 * off the stable `error.code`; transport/timeout/config/wait failures are their
 * own subtypes.
 */

import type { SmartBrowseRunData } from "./types";

/** Base class for every error thrown by the SDK. */
export class WebscrapeError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message);
    this.name = "WebscrapeError";
    // Restore the prototype chain so `instanceof` works even if this file is
    // ever down-levelled past native classes by a bundler.
    Object.setPrototypeOf(this, new.target.prototype);
    if (options && "cause" in options && (this as { cause?: unknown }).cause === undefined) {
      (this as { cause?: unknown }).cause = options.cause;
    }
  }
}

export interface ApiErrorInit {
  status: number;
  code: string;
  message: string;
  details?: unknown;
  requestId?: string;
}

/**
 * A structured API error returned by the server. Also the fallback type for any
 * `error.code` the SDK doesn't recognise (the raw `code` string is preserved).
 */
export class APIError extends WebscrapeError {
  /** HTTP status code. */
  readonly status: number;
  /** Stable machine-readable error code. Branch on this, never on `message`. */
  readonly code: string;
  /** Optional structured context (`error.details`), as raw JSON. */
  readonly details: unknown;
  /** Support-facing request id (envelope `request_id` or the `X-Request-ID` header). */
  readonly requestId: string | undefined;

  constructor(init: ApiErrorInit) {
    super(init.message);
    this.name = "APIError";
    this.status = init.status;
    this.code = init.code;
    this.details = init.details;
    this.requestId = init.requestId;
  }
}

/** `unauthorized` — missing/invalid/revoked key. Covers the bare-401 body shape too. */
export class AuthenticationError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "AuthenticationError";
  }
}

/** `insufficient_credits` — not enough balance for the request. */
export class InsufficientCreditsError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "InsufficientCreditsError";
  }
  /** Current balance, from `details.balance`. */
  get balance(): number | undefined {
    return numberField(this.details, "balance");
  }
  /** Credits required for the request, from `details.required`. */
  get required(): number | undefined {
    return numberField(this.details, "required");
  }
}

/** `email_verification_required` — verify the account email before spending credits. */
export class EmailVerificationError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "EmailVerificationError";
  }
}

/** `forbidden` — authenticated but not allowed. */
export class ForbiddenError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "ForbiddenError";
  }
}

/** `not_found` — resource missing or owned by another account. */
export class NotFoundError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "NotFoundError";
  }
}

/** `conflict` / `account_deletion_pending` — resource state disallows the action. */
export class ConflictError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "ConflictError";
  }
}

/** `invalid_request` — malformed request. */
export class BadRequestError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "BadRequestError";
  }
}

/** `validation_failed` — extraction output failed schema validation. */
export class ValidationError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "ValidationError";
  }
}

/** `rate_limited` — a plan throttle was hit. */
export class RateLimitError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "RateLimitError";
  }
  /**
   * The throttle that fired, from `details.reason`:
   * `rate_limit_per_min` | `max_concurrent_requests` | `sb_runs_per_month`.
   */
  get reason(): string | undefined {
    return stringField(this.details, "reason");
  }
}

/** `internal_error` / `service_unavailable` — server-side failure, safe to retry. */
export class ServerError extends APIError {
  constructor(init: ApiErrorInit) {
    super(init);
    this.name = "ServerError";
  }
}

/** Thrown at construction when no API key is available, or no fetch implementation exists. */
export class ConfigurationError extends WebscrapeError {
  constructor(message: string) {
    super(message);
    this.name = "ConfigurationError";
  }
}

/** A network-level failure (DNS, connection refused, reset, body-read error). */
export class TransportError extends WebscrapeError {
  /** True when the failure demonstrably happened before the request reached the server. */
  readonly retryable: boolean;
  constructor(message: string, options?: { cause?: unknown; retryable?: boolean }) {
    super(message, { cause: options?.cause });
    this.name = "TransportError";
    this.retryable = options?.retryable ?? false;
  }
}

/** The per-request timeout elapsed before a response arrived. */
export class TimeoutError extends WebscrapeError {
  constructor(message: string) {
    super(message);
    this.name = "TimeoutError";
  }
}

/** A SmartBrowse run finished in a `failed` / `cancelled` state while waiting. */
export class RunFailedError extends WebscrapeError {
  /** The full run so `error`, `pages_extracted`, `credits_used`, etc. stay inspectable. */
  readonly run: SmartBrowseRunData;
  constructor(run: SmartBrowseRunData) {
    super(
      `SmartBrowse run ${run.id} ${String(run.run_status)}` +
        (run.error ? `: ${run.error}` : ""),
    );
    this.name = "RunFailedError";
    this.run = run;
  }
}

/** The wait deadline elapsed before a SmartBrowse run reached a terminal state. */
export class WaitTimeoutError extends WebscrapeError {
  /** The last run state seen before giving up. */
  readonly run: SmartBrowseRunData;
  constructor(run: SmartBrowseRunData) {
    super(
      `Timed out waiting for SmartBrowse run ${run.id} ` +
        `(last status: ${String(run.run_status)})`,
    );
    this.name = "WaitTimeoutError";
    this.run = run;
  }
}

function numberField(details: unknown, key: string): number | undefined {
  if (details && typeof details === "object") {
    const value = (details as Record<string, unknown>)[key];
    if (typeof value === "number") return value;
  }
  return undefined;
}

function stringField(details: unknown, key: string): string | undefined {
  if (details && typeof details === "object") {
    const value = (details as Record<string, unknown>)[key];
    if (typeof value === "string") return value;
  }
  return undefined;
}
