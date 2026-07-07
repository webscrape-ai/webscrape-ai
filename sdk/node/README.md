# webscrape-ai

Official Node / TypeScript SDK for the [webscrape.ai](https://webscrape.ai) API.

- Zero runtime dependencies — uses the global `fetch` (Node ≥ 18, Bun, Deno, edge runtimes).
- Dual ESM + CommonJS builds with bundled type declarations.
- Typed responses, a typed error hierarchy, automatic billing-safe retries, and a
  `runAndWait` helper for SmartBrowse.

## Install

```bash
npm install webscrape-ai
# or: pnpm add webscrape-ai / yarn add webscrape-ai / bun add webscrape-ai
```

## Authentication

Create an API key in the [dashboard](https://webscrape.ai/app/api-keys) (format
`wsg_live_…`). Pass it to the client, or set `WEBSCRAPE_API_KEY` and let the SDK pick it up:

```ts
import { Webscrape } from "webscrape-ai";

const client = new Webscrape({ apiKey: "wsg_live_..." });
// or, with WEBSCRAPE_API_KEY set in the environment:
const client2 = new Webscrape();
```

If no key is passed and none is in the environment, the constructor throws a
`ConfigurationError` immediately (not a late 401).

## Quickstart

### Scrape a page

```ts
const res = await client.scrape({
  website_url: "https://example.com",
  clean: true,          // HTML → cleaned markdown
  extract_links: true,
});

console.log(res.data.html);           // markdown
console.log(res.data.links);          // [{ url, text }, ...]
console.log(res.credits_remaining);   // watch your balance drain
```

### Structured extraction

`smartscraper<T>()` is generic — `T` types `data.result`:

```ts
interface Stories {
  stories: Array<{ title: string; url: string; score: number }>;
}

const res = await client.smartscraper<Stories>({
  website_url: "https://news.ycombinator.com",
  user_prompt: "Extract the front-page stories with title, url, and score.",
  output_schema: {
    type: "object",
    properties: {
      stories: {
        type: "array",
        items: {
          type: "object",
          properties: {
            title: { type: "string" },
            url: { type: "string" },
            score: { type: "integer" },
          },
        },
      },
    },
  },
});

for (const story of res.data.result?.stories ?? []) {
  console.log(story.score, story.title);
}
```

### SmartBrowse: dispatch and wait

Recipes are authored in the dashboard; an API key can *run* and *poll* them. The dispatch is
asynchronous — `runAndWait` handles the dispatch-then-poll two-step for you:

```ts
const run = await client.smartbrowse.runAndWait("m3Yc2tFvN8q");
console.log(run.data.pages_extracted, run.data.items_extracted, run.data.credits_used);

// Or drive it manually:
const dispatched = await client.smartbrowse.run("m3Yc2tFvN8q");
const finished = await client.smartbrowse.waitForRun(dispatched.data.run_id, {
  pollIntervalMs: 3000,
  timeoutMs: 900_000,
});

// Plan caps + rolling-30-day usage:
const usage = await client.smartbrowse.usage();
```

## Error handling

Every failure is an instance of `WebscrapeError`. API errors are typed subclasses keyed off
the server's stable `error.code`; branch on the class, never on `err.message`:

```ts
import {
  Webscrape,
  WebscrapeError,
  InsufficientCreditsError,
  RateLimitError,
  ValidationError,
  AuthenticationError,
  RunFailedError,
  WaitTimeoutError,
} from "webscrape-ai";

try {
  const res = await client.smartscraper({
    website_url: "https://example.com",
    user_prompt: "extract the title",
  });
  console.log(res.data.result);
} catch (err) {
  if (err instanceof InsufficientCreditsError) {
    console.error(`need ${err.required}, have ${err.balance}`);
  } else if (err instanceof RateLimitError) {
    console.error(`rate limited (${err.reason})`);
  } else if (err instanceof ValidationError) {
    console.error("schema validation failed:", err.details);
  } else if (err instanceof AuthenticationError) {
    console.error("bad or missing API key");
  } else if (err instanceof WebscrapeError) {
    console.error(`${err.name}: ${err.message}`);
  } else {
    throw err;
  }
}
```

The wait helper throws `RunFailedError` (run ended `failed`/`cancelled`) or `WaitTimeoutError`
(deadline elapsed); both carry the full run on `err.run`.

### Error types

| Class | Trigger |
|---|---|
| `AuthenticationError` | `unauthorized` (401) |
| `InsufficientCreditsError` | `insufficient_credits` (402) — `.balance`, `.required` |
| `EmailVerificationError` | `email_verification_required` (402) |
| `ForbiddenError` | `forbidden` (403) |
| `NotFoundError` | `not_found` (404) |
| `ConflictError` | `conflict`, `account_deletion_pending` (409) |
| `BadRequestError` | `invalid_request` (400) |
| `ValidationError` | `validation_failed` (422) |
| `RateLimitError` | `rate_limited` (429) — `.reason` |
| `ServerError` | `internal_error` (500), `service_unavailable` (502) |
| `APIError` | any other/unknown code (raw `.code` preserved) |
| `TransportError` | network/connection failure |
| `TimeoutError` | per-request timeout elapsed |
| `ConfigurationError` | no API key / no `fetch` at construction |
| `RunFailedError` | SmartBrowse run ended failed/cancelled (`.run`) |
| `WaitTimeoutError` | wait deadline elapsed (`.run`) |

Every `APIError` carries `.status`, `.code`, `.message`, `.details`, and `.requestId`.

## Configuration

```ts
new Webscrape({
  apiKey,          // default: WEBSCRAPE_API_KEY env var
  baseUrl,         // default: "https://api.webscrape.ai/v1"
  timeoutMs,       // default: 180000 (per request)
  maxRetries,      // default: 2 (0 disables). Retries 429/5xx + connection failures
  fetch,           // default: global fetch. Provide your own for tests/proxies
});
```

Per-call, every method accepts an `AbortSignal`:

```ts
const controller = new AbortController();
const res = client.scrape({ website_url: "https://example.com" }, { signal: controller.signal });
controller.abort();
```

Retries use exponential backoff with full jitter (base 1s, cap 30s) and honor a `Retry-After`
header. Retries are billing-safe: a 429/5xx was never charged, and only connection-*establishment*
failures (never mid-response resets) are retried.

## Examples

Runnable programs are in [`examples/`](examples/): `scrape.ts`, `smartscraper.ts`,
`smartbrowse.ts`. Each reads `WEBSCRAPE_API_KEY` from the environment.

## Docs

Full API reference: <https://webscrape.ai/docs>.

## License

MIT
