# webscrape.ai Go SDK

[![Go Reference](https://pkg.go.dev/badge/github.com/webscrape-ai/webscrape-ai/sdk/go.svg)](https://pkg.go.dev/github.com/webscrape-ai/webscrape-ai/sdk/go) [![Go Report Card](https://goreportcard.com/badge/github.com/webscrape-ai/webscrape-ai/sdk/go)](https://goreportcard.com/report/github.com/webscrape-ai/webscrape-ai/sdk/go)

Official Go client for the [webscrape.ai](https://webscrape.ai) API. Zero
dependencies (standard library only), context-first, with automatic
billing-safe retries and a SmartBrowse wait helper.

- Docs: <https://webscrape.ai/docs>
- Repository: <https://github.com/webscrape-ai/webscrape-ai>
- Module: `github.com/webscrape-ai/webscrape-ai/sdk/go`, package `webscrape`

## Install

```bash
go get github.com/webscrape-ai/webscrape-ai/sdk/go
```

The import path ends in `/go`, so use a named import:

```go
import webscrape "github.com/webscrape-ai/webscrape-ai/sdk/go"
```

Requires Go 1.22 or newer.

## Authentication

Create an API key in the [dashboard](https://webscrape.ai/app/api-keys)
(format `wsg_live_...`). Pass it explicitly or via the `WEBSCRAPE_API_KEY`
environment variable:

```go
client, err := webscrape.New(webscrape.WithAPIKey("wsg_live_..."))
// or, reading WEBSCRAPE_API_KEY from the environment:
client, err := webscrape.New()
```

If no key is provided and the env var is unset, `New` fails fast with
`webscrape.ErrNoAPIKey` — never a late 401.

## Quickstart

### Scrape — HTML, cleaned markdown, or links

```go
ctx := context.Background()
resp, err := client.Scrape(ctx, &webscrape.ScrapeRequest{
    WebsiteURL:   "https://example.com",
    Clean:        webscrape.Bool(true),  // HTML -> cleaned markdown
    ExtractLinks: webscrape.Bool(true),
})
if err != nil {
    log.Fatal(err)
}
fmt.Println(*resp.Data.HTML)
fmt.Printf("used %d credits, %d remaining\n", resp.CreditsUsed, resp.CreditsRemaining)
```

Optional request fields are pointers (`*bool`, `*int`) so an untouched field is
omitted from the request entirely rather than sent as a default. Use the
`webscrape.Bool`, `webscrape.Int`, and `webscrape.String` helpers to set them.

### SmartScraper — structured extraction

Provide a `UserPrompt` and (optionally) an `OutputSchema`; the result is
validated against the schema with one repair attempt. Decode it into your own
type with `DecodeResult`:

```go
resp, err := client.SmartScraper(ctx, &webscrape.SmartScraperRequest{
    WebsiteURL: "https://news.ycombinator.com",
    UserPrompt: "Extract the front-page stories with title, url, and score.",
    OutputSchema: map[string]any{
        "type": "object",
        "properties": map[string]any{
            "stories": map[string]any{"type": "array"},
        },
    },
})
if err != nil {
    log.Fatal(err)
}

var out struct {
    Stories []struct {
        Title string `json:"title"`
        URL   string `json:"url"`
        Score int    `json:"score"`
    } `json:"stories"`
}
if err := resp.DecodeResult(&out); err != nil {
    log.Fatal(err)
}
```

`resp.Data.Result` is a raw `json.RawMessage` if you'd rather handle the JSON
yourself.

### SmartBrowse — dispatch a recipe run and wait

Recipes are created in the dashboard at <https://webscrape.ai/app>; an API key
can *run* and *poll* them. `RunAndWait` combines dispatch + polling:

```go
run, err := client.SmartBrowse.RunAndWait(ctx, "m3Yc2tFvN8q")
if err != nil {
    var failed *webscrape.RunFailedError
    var timedOut *webscrape.WaitTimeoutError
    switch {
    case errors.As(err, &failed):
        log.Fatalf("run failed: %s", failed.Run.Error)
    case errors.As(err, &timedOut):
        log.Fatalf("timed out; last status %s", timedOut.Run.RunStatus)
    default:
        log.Fatal(err)
    }
}
fmt.Printf("%d pages, %d credits\n", run.Data.PagesExtracted, run.Data.CreditsUsed)
```

Lower-level methods are also available: `SmartBrowse.Run`,
`SmartBrowse.GetRun`, `SmartBrowse.WaitForRun`, and `SmartBrowse.Usage`.
Tune the wait with `webscrape.WithPollInterval` and `webscrape.WithWaitTimeout`.

## Error handling

All API-level failures return `*webscrape.APIError`, carrying the HTTP status,
stable error `Code`, message, raw `Details` JSON, and request id. Branch with
the predicate helpers or `errors.As`:

```go
_, err := client.Scrape(ctx, req)
switch {
case webscrape.IsInsufficientCredits(err):
    apiErr, _ := webscrape.AsAPIError(err)
    balance, required, _ := apiErr.Credits()
    log.Printf("need %d credits, have %d", required, balance)
case webscrape.IsRateLimited(err):
    apiErr, _ := webscrape.AsAPIError(err)
    log.Printf("rate limited: %s", apiErr.RateLimitReason())
case webscrape.IsNotFound(err):
    log.Print("not found")
default:
    var apiErr *webscrape.APIError
    if errors.As(err, &apiErr) {
        log.Printf("%s: %s (request_id=%s)", apiErr.Code, apiErr.Message, apiErr.RequestID)
    } else if err != nil {
        log.Printf("transport/timeout: %v", err) // errors.Is(err, context.DeadlineExceeded) etc.
    }
}
```

Predicates: `IsBadRequest`, `IsUnauthorized`, `IsInsufficientCredits`,
`IsEmailVerificationRequired`, `IsForbidden`, `IsNotFound`, `IsConflict`,
`IsAccountDeletionPending`, `IsValidationFailed`, `IsRateLimited`,
`IsServerError`. Unknown error codes surface as a generic `*APIError` with the
raw code string preserved in `Code`.

The SmartBrowse wait helpers additionally return `*RunFailedError` (wrapping
`webscrape.ErrRunFailed`) and `*WaitTimeoutError` (wrapping
`webscrape.ErrWaitTimeout`), both carrying the run for inspection.

## Configuration

| Option | Default | Notes |
|---|---|---|
| `WithAPIKey(string)` | `WEBSCRAPE_API_KEY` env var | required (option or env) |
| `WithBaseURL(string)` | `https://api.webscrape.ai/v1` | trailing slash tolerated |
| `WithTimeout(time.Duration)` | 180s | per HTTP attempt; `<=0` defers to the context |
| `WithMaxRetries(int)` | 2 | up to 3 attempts; 0 disables |
| `WithHTTPClient(*http.Client)` | `&http.Client{}` | custom transport/proxy/TLS |

Retries fire on HTTP 429/500/502/503 and connection-establishment failures
only (billing-safe — those requests were never charged), using exponential
backoff with full jitter (base 1s, cap 30s) and honoring `Retry-After`.

## Examples

Runnable programs under [`examples/`](examples/) read `WEBSCRAPE_API_KEY` from
the environment:

```bash
WEBSCRAPE_API_KEY=wsg_live_... go run ./examples/scrape
WEBSCRAPE_API_KEY=wsg_live_... go run ./examples/smartscraper
WEBSCRAPE_API_KEY=wsg_live_... go run ./examples/smartbrowse <recipe_id>
```

## License

MIT — see [LICENSE](LICENSE).
