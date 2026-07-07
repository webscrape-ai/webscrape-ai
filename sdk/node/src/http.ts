/**
 * The transport core: request building, timeout + abort handling, envelope
 * parsing, error mapping, and the retry loop. Both `Webscrape` and the
 * `SmartBrowse` namespace call {@link requestEnvelope} with a shared
 * {@link HttpConfig}, so there's a single billing-safe code path.
 */

import {
  APIError,
  AuthenticationError,
  BadRequestError,
  ConflictError,
  EmailVerificationError,
  ForbiddenError,
  InsufficientCreditsError,
  NotFoundError,
  RateLimitError,
  ServerError,
  TimeoutError,
  TransportError,
  ValidationError,
  type ApiErrorInit,
} from "./errors";
import type { Envelope } from "./types";

export const DEFAULT_BASE_URL = "https://api.webscrape.ai/v1";
export const DEFAULT_TIMEOUT_MS = 180_000;
export const DEFAULT_MAX_RETRIES = 2;

/**
 * HTTP statuses that are safe to retry. Failed and rate-limited requests are
 * never charged, so retrying them is always safe.
 */
const RETRYABLE_STATUSES = new Set([429, 500, 502, 503]);

/**
 * Transport error codes that indicate the request never reached the server, so
 * a retry can't double-bill. `ECONNRESET` is deliberately excluded — it can
 * happen mid-response, after the server already did (and billed) the work.
 */
const RETRYABLE_CONNECT_CODES = new Set([
  "ECONNREFUSED",
  "ENOTFOUND",
  "EAI_AGAIN",
  "EHOSTUNREACH",
  "ENETUNREACH",
  "ETIMEDOUT",
  "ECONNTIMEDOUT",
  "UND_ERR_CONNECT_TIMEOUT",
]);

export type FetchImpl = typeof fetch;

export interface HttpConfig {
  apiKey: string;
  baseUrl: string;
  timeoutMs: number;
  maxRetries: number;
  fetchImpl: FetchImpl;
  userAgent: string;
}

/** Per-call options accepted by every method. */
export interface RequestOptions {
  /** An AbortSignal, combined with the client's per-request timeout. */
  signal?: AbortSignal;
}

export async function requestEnvelope<D>(
  cfg: HttpConfig,
  method: "GET" | "POST",
  path: string,
  body: unknown,
  options?: RequestOptions,
): Promise<Envelope<D>> {
  const url = cfg.baseUrl + path;
  const maxAttempts = Math.max(1, cfg.maxRetries + 1);

  for (let attempt = 1; ; attempt++) {
    let response: Response;
    try {
      response = await doFetch(cfg, method, url, body, options?.signal);
    } catch (err) {
      if (err instanceof TransportError && err.retryable && attempt < maxAttempts) {
        await sleep(backoffMs(attempt, undefined), options?.signal);
        continue;
      }
      throw err;
    }

    const headerRequestId = response.headers.get("x-request-id") ?? undefined;

    let rawText: string;
    try {
      rawText = await response.text();
    } catch (err) {
      // The server responded (and may have billed) but the body read failed.
      // Never retry this — it is not billing-safe.
      throw new TransportError("failed to read response body", {
        cause: err,
        retryable: false,
      });
    }

    let json: unknown;
    if (rawText.length > 0) {
      try {
        json = JSON.parse(rawText);
      } catch {
        json = undefined;
      }
    }

    if (response.ok || response.status === 202) {
      return normalizeEnvelope<D>(response.status, json, headerRequestId);
    }

    const apiError = errorFromResponse(response.status, json, rawText, headerRequestId);
    if (RETRYABLE_STATUSES.has(response.status) && attempt < maxAttempts) {
      const retryAfter = parseRetryAfter(response.headers.get("retry-after"));
      await sleep(backoffMs(attempt, retryAfter), options?.signal);
      continue;
    }
    throw apiError;
  }
}

async function doFetch(
  cfg: HttpConfig,
  method: "GET" | "POST",
  url: string,
  body: unknown,
  callSignal: AbortSignal | undefined,
): Promise<Response> {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, cfg.timeoutMs);

  const onAbort = () => controller.abort();
  if (callSignal) {
    if (callSignal.aborted) controller.abort();
    else callSignal.addEventListener("abort", onAbort);
  }

  const headers: Record<string, string> = {
    "X-API-Key": cfg.apiKey,
    Accept: "application/json",
    // Some non-Node fetch implementations forbid setting User-Agent and will
    // silently drop it — that is fine; we never depend on it being present.
    "User-Agent": cfg.userAgent,
  };
  let serializedBody: string | undefined;
  if (body !== undefined) {
    serializedBody = JSON.stringify(body);
    headers["Content-Type"] = "application/json";
  }

  const init: RequestInit = { method, headers, signal: controller.signal };
  if (serializedBody !== undefined) init.body = serializedBody;

  try {
    return await cfg.fetchImpl(url, init);
  } catch (err) {
    if (timedOut) {
      throw new TimeoutError(`request timed out after ${cfg.timeoutMs}ms`);
    }
    if (callSignal?.aborted) {
      // Surface the caller's abort as-is (typically an AbortError DOMException).
      throw callSignal.reason ?? err;
    }
    throw new TransportError(transportMessage(err), {
      cause: err,
      retryable: isRetryableConnectError(err),
    });
  } finally {
    clearTimeout(timer);
    if (callSignal) callSignal.removeEventListener("abort", onAbort);
  }
}

function normalizeEnvelope<D>(
  status: number,
  json: unknown,
  headerRequestId: string | undefined,
): Envelope<D> {
  const obj =
    json && typeof json === "object" ? (json as Record<string, unknown>) : {};
  const requestId =
    typeof obj.request_id === "string" ? obj.request_id : headerRequestId ?? "";
  const data = obj.data as D;

  if (obj.status === "queued" || status === 202) {
    return { status: "queued", request_id: requestId, data };
  }
  return {
    status: "completed",
    request_id: requestId,
    credits_used: typeof obj.credits_used === "number" ? obj.credits_used : 0,
    credits_remaining:
      typeof obj.credits_remaining === "number" ? obj.credits_remaining : 0,
    data,
  };
}

/**
 * Maps an error HTTP response to the right {@link APIError} subtype. Handles the
 * standard envelope, the bare-401 `{ "error": "..." }` shape, and unknown codes.
 */
export function errorFromResponse(
  status: number,
  json: unknown,
  rawText: string,
  headerRequestId: string | undefined,
): APIError {
  let code: string | undefined;
  let message: string | undefined;
  let details: unknown;
  let requestId = headerRequestId;

  if (json && typeof json === "object") {
    const obj = json as Record<string, unknown>;
    const err = obj.error;
    if (err && typeof err === "object") {
      const e = err as Record<string, unknown>;
      if (typeof e.code === "string") code = e.code;
      if (typeof e.message === "string") message = e.message;
      details = e.details;
    } else if (typeof err === "string") {
      // Authentication failures may return a plain { "error": "<message>" }
      // body instead of the standard envelope; both shapes are handled.
      message = err;
    }
    if (typeof obj.request_id === "string") requestId = obj.request_id;
  }

  if (!code && status === 401) code = "unauthorized";
  if (!message) message = rawText.length > 0 ? rawText : `HTTP ${status}`;

  return apiErrorForCode({ status, code: code ?? "", message, details, requestId });
}

export function apiErrorForCode(init: ApiErrorInit): APIError {
  switch (init.code) {
    case "unauthorized":
      return new AuthenticationError(init);
    case "insufficient_credits":
      return new InsufficientCreditsError(init);
    case "email_verification_required":
      return new EmailVerificationError(init);
    case "forbidden":
      return new ForbiddenError(init);
    case "not_found":
      return new NotFoundError(init);
    case "conflict":
    case "account_deletion_pending":
      return new ConflictError(init);
    case "invalid_request":
      return new BadRequestError(init);
    case "validation_failed":
      return new ValidationError(init);
    case "rate_limited":
      return new RateLimitError(init);
    case "internal_error":
    case "service_unavailable":
      return new ServerError(init);
    default:
      return new APIError(init);
  }
}

function isRetryableConnectError(err: unknown): boolean {
  const candidate = err as { code?: unknown; cause?: { code?: unknown } } | null;
  if (!candidate) return false;
  if (typeof candidate.code === "string" && RETRYABLE_CONNECT_CODES.has(candidate.code)) {
    return true;
  }
  const causeCode = candidate.cause?.code;
  return typeof causeCode === "string" && RETRYABLE_CONNECT_CODES.has(causeCode);
}

function transportMessage(err: unknown): string {
  if (err && typeof err === "object" && typeof (err as { message?: unknown }).message === "string") {
    return (err as { message: string }).message;
  }
  return "network request failed";
}

/** Exponential backoff with full jitter, capped at 30s. Honors `Retry-After`. */
export function backoffMs(attempt: number, retryAfterMs: number | undefined): number {
  if (retryAfterMs !== undefined && retryAfterMs >= 0) return retryAfterMs;
  const cap = 30_000;
  const exponential = Math.min(cap, 1000 * 2 ** (attempt - 1));
  return Math.random() * exponential;
}

function parseRetryAfter(header: string | null): number | undefined {
  if (!header) return undefined;
  const seconds = Number(header);
  if (Number.isFinite(seconds) && seconds >= 0) return seconds * 1000;
  const date = Date.parse(header);
  if (!Number.isNaN(date)) return Math.max(0, date - Date.now());
  return undefined;
}

/** A cancellable sleep. Rejects with the signal's reason if aborted mid-wait. */
export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  if (ms <= 0) return Promise.resolve();
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason ?? new Error("aborted"));
      return;
    }
    const cleanup = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
    };
    const onAbort = () => {
      cleanup();
      reject(signal?.reason ?? new Error("aborted"));
    };
    const timer = setTimeout(() => {
      cleanup();
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort);
  });
}
