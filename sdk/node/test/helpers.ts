import type { FetchImpl } from "../src";

export interface RecordedRequest {
  url: string;
  method: string;
  /** Lower-cased header names → values. */
  headers: Record<string, string>;
  /** Parsed JSON body, or the raw string if not JSON, or undefined if absent. */
  body: unknown;
}

export interface MockResponseSpec {
  status?: number;
  json?: unknown;
  text?: string;
  headers?: Record<string, string>;
}

/** Either a full response spec, or a raw JSON body (served as HTTP 200). */
type SpecOrBody = MockResponseSpec | Record<string, unknown>;

type Responder =
  | SpecOrBody
  | SpecOrBody[]
  | ((req: RecordedRequest, index: number) => SpecOrBody);

export interface MockFetch {
  fetch: FetchImpl;
  calls: RecordedRequest[];
}

/**
 * Disambiguate a response spec from a raw envelope body. A spec carries HTTP
 * transport fields (`json` / `text` / `headers` / a numeric `status`); anything
 * else — e.g. a `completedEnvelope(...)` whose `status` is the string
 * `"completed"` — is treated as a 200 JSON body.
 */
function toSpec(value: SpecOrBody): MockResponseSpec {
  if (
    "json" in value ||
    "text" in value ||
    "headers" in value ||
    typeof (value as MockResponseSpec).status === "number"
  ) {
    return value as MockResponseSpec;
  }
  return { json: value };
}

/**
 * A deterministic, undici-independent fetch stub. Records every outgoing
 * request and returns programmed responses. Pass a single spec, an array (one
 * per call, last repeats), or a function of (request, index).
 */
export function mockFetch(responder: Responder): MockFetch {
  const calls: RecordedRequest[] = [];

  const fetchFn = async (input: unknown, init?: unknown): Promise<Response> => {
    const request = init as { method?: string; headers?: Record<string, string>; body?: string };
    const headers: Record<string, string> = {};
    if (request?.headers) {
      for (const [key, value] of Object.entries(request.headers)) {
        headers[key.toLowerCase()] = String(value);
      }
    }
    let body: unknown;
    if (request?.body !== undefined) {
      try {
        body = JSON.parse(request.body);
      } catch {
        body = request.body;
      }
    }
    const recorded: RecordedRequest = {
      url: String(input),
      method: request?.method ?? "GET",
      headers,
      body,
    };
    calls.push(recorded);

    const index = calls.length - 1;
    let spec: MockResponseSpec;
    if (typeof responder === "function") {
      spec = toSpec(responder(recorded, index));
    } else if (Array.isArray(responder)) {
      spec = toSpec(responder[Math.min(index, responder.length - 1)]!);
    } else {
      spec = toSpec(responder);
    }

    const responseHeaders = new Headers(spec.headers ?? {});
    let payload: string;
    if (spec.text !== undefined) {
      payload = spec.text;
    } else if (spec.json !== undefined) {
      payload = JSON.stringify(spec.json);
      if (!responseHeaders.has("content-type")) {
        responseHeaders.set("content-type", "application/json");
      }
    } else {
      payload = "";
    }

    return new Response(payload, { status: spec.status ?? 200, headers: responseHeaders });
  };

  return { fetch: fetchFn as unknown as FetchImpl, calls };
}

/** Build a standard error-envelope body. */
export function errorEnvelope(
  code: string,
  message: string,
  details?: unknown,
  requestId = "req_test0001",
): Record<string, unknown> {
  const error: Record<string, unknown> = { code, message };
  if (details !== undefined) error.details = details;
  return { status: "error", error, request_id: requestId };
}

/** Build a standard completed-envelope body. */
export function completedEnvelope(
  data: unknown,
  creditsUsed = 1,
  creditsRemaining = 999,
  requestId = "req_test0001",
): Record<string, unknown> {
  return {
    status: "completed",
    data,
    credits_used: creditsUsed,
    credits_remaining: creditsRemaining,
    request_id: requestId,
  };
}
