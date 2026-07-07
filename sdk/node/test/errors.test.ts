import { describe, expect, it } from "vitest";
import {
  APIError,
  AuthenticationError,
  ConflictError,
  InsufficientCreditsError,
  NotFoundError,
  RateLimitError,
  ValidationError,
  Webscrape,
  WebscrapeError,
} from "../src";
import { errorEnvelope, mockFetch } from "./helpers";

const API_KEY = "wsg_live_0123456789abcdefghijklmnopqrstuv";

function client(spec: Parameters<typeof mockFetch>[0]) {
  return new Webscrape({ apiKey: API_KEY, fetch: mockFetch(spec).fetch, maxRetries: 0 });
}

describe("error envelope → typed errors", () => {
  it("402 insufficient_credits with balance/required accessors", async () => {
    const c = client({
      status: 402,
      json: errorEnvelope("insufficient_credits", "not enough credits", {
        balance: 2,
        required: 5,
      }),
    });
    await expect(c.scrape({ website_url: "https://example.com" })).rejects.toSatisfy(
      (err: unknown) => {
        expect(err).toBeInstanceOf(InsufficientCreditsError);
        expect(err).toBeInstanceOf(APIError);
        expect(err).toBeInstanceOf(WebscrapeError);
        const e = err as InsufficientCreditsError;
        expect(e.status).toBe(402);
        expect(e.code).toBe("insufficient_credits");
        expect(e.balance).toBe(2);
        expect(e.required).toBe(5);
        expect(e.requestId).toBe("req_test0001");
        return true;
      },
    );
  });

  it("402 email_verification_required maps to EmailVerificationError, not credits", async () => {
    const c = client({
      status: 402,
      json: errorEnvelope("email_verification_required", "verify your email"),
    });
    await expect(c.scrape({ website_url: "https://example.com" })).rejects.toSatisfy(
      (err: unknown) => {
        expect((err as APIError).code).toBe("email_verification_required");
        expect(err).not.toBeInstanceOf(InsufficientCreditsError);
        return true;
      },
    );
  });

  it("404 not_found", async () => {
    const c = client({ status: 404, json: errorEnvelope("not_found", "recipe not found") });
    await expect(c.smartbrowse.getRun("missing")).rejects.toBeInstanceOf(NotFoundError);
  });

  it("409 account_deletion_pending maps to ConflictError with details", async () => {
    const c = client({
      status: 409,
      json: errorEnvelope("account_deletion_pending", "scheduled for deletion", {
        deletion_scheduled_for: "2026-06-15T12:00:00Z",
      }),
    });
    await expect(c.scrape({ website_url: "https://example.com" })).rejects.toSatisfy(
      (err: unknown) => {
        expect(err).toBeInstanceOf(ConflictError);
        expect((err as ConflictError).code).toBe("account_deletion_pending");
        return true;
      },
    );
  });

  it("422 validation_failed", async () => {
    const c = client({
      status: 422,
      json: errorEnvelope("validation_failed", "schema mismatch", {
        type: "schema_validation_error",
        errors: ["bad"],
      }),
    });
    await expect(
      c.smartscraper({ website_url: "https://example.com", user_prompt: "x" }),
    ).rejects.toBeInstanceOf(ValidationError);
  });

  it("429 rate_limited exposes reason accessor", async () => {
    const c = client({
      status: 429,
      json: errorEnvelope("rate_limited", "slow down", {
        reason: "rate_limit_per_min",
        limit_per_min: 60,
      }),
    });
    await expect(c.scrape({ website_url: "https://example.com" })).rejects.toSatisfy(
      (err: unknown) => {
        expect(err).toBeInstanceOf(RateLimitError);
        expect((err as RateLimitError).reason).toBe("rate_limit_per_min");
        return true;
      },
    );
  });
});

describe("non-standard response shapes", () => {
  it("bare-401 { error: string } → AuthenticationError with header request id", async () => {
    const c = client({
      status: 401,
      text: JSON.stringify({
        error: "missing or invalid credentials — provide a session cookie or X-API-Key header",
      }),
      headers: { "X-Request-ID": "req_hdr01" },
    });
    await expect(c.scrape({ website_url: "https://example.com" })).rejects.toSatisfy(
      (err: unknown) => {
        expect(err).toBeInstanceOf(AuthenticationError);
        const e = err as AuthenticationError;
        expect(e.code).toBe("unauthorized");
        expect(e.status).toBe(401);
        expect(e.message).toContain("missing or invalid credentials");
        expect(e.requestId).toBe("req_hdr01");
        return true;
      },
    );
  });

  it("unknown error.code → generic APIError with raw code preserved", async () => {
    const c = client({
      status: 418,
      json: errorEnvelope("teapot_meltdown", "brewing failure"),
    });
    await expect(c.scrape({ website_url: "https://example.com" })).rejects.toSatisfy(
      (err: unknown) => {
        expect(err).toBeInstanceOf(APIError);
        // Not one of the known subtypes.
        expect(err).not.toBeInstanceOf(AuthenticationError);
        expect(err).not.toBeInstanceOf(RateLimitError);
        expect((err as APIError).code).toBe("teapot_meltdown");
        expect((err as APIError).status).toBe(418);
        expect((err as APIError).constructor).toBe(APIError);
        return true;
      },
    );
  });
});
