# webscrape-ai (Rust)

[![crates.io](https://img.shields.io/crates/v/webscrape-ai.svg)](https://crates.io/crates/webscrape-ai) [![docs.rs](https://img.shields.io/docsrs/webscrape-ai)](https://docs.rs/webscrape-ai)

Official Rust SDK for the [webscrape.ai](https://webscrape.ai) API — fetch pages
and get back HTML, cleaned markdown, links, or LLM-structured JSON, plus
real-Chrome recipe replay via SmartBrowse.

- Async-first ([`tokio`]-based), with an optional synchronous `blocking` client.
- Typed, builder-pattern requests; unset options are omitted from the wire.
- One `Error` enum covering typed API errors, transport, timeouts, and the
  SmartBrowse wait-helper outcomes.
- Automatic, billing-safe retries with full-jitter backoff.

Full API docs: <https://webscrape.ai/docs>.

## Install

```toml
[dependencies]
webscrape-ai = "0.1"
tokio = { version = "1", features = ["full"] }
```

Enable the synchronous client with the `blocking` feature:

```toml
webscrape-ai = { version = "0.1", features = ["blocking"] }
```

## Authentication

Every request sends an `X-API-Key` header. Provide the key explicitly or via the
`WEBSCRAPE_API_KEY` environment variable. A missing key fails fast at
construction:

```rust,no_run
use webscrape_ai::Client;

# fn main() -> Result<(), webscrape_ai::Error> {
let client = Client::new("wsg_live_...")?; // explicit
let client = Client::from_env()?;          // reads WEBSCRAPE_API_KEY
# Ok(()) }
```

## Quickstart — scrape

```rust,no_run
use webscrape_ai::{Client, ScrapeRequest};

# async fn run() -> Result<(), webscrape_ai::Error> {
let client = Client::from_env()?;

let resp = client
    .scrape(
        ScrapeRequest::new("https://example.com")
            .clean(true)          // HTML -> cleaned markdown
            .extract_links(true),
    )
    .await?;

println!("credits used: {:?}", resp.credits_used);
println!("markdown:\n{}", resp.data.html.unwrap_or_default());
# Ok(()) }
```

## Quickstart — smartscraper (with `output_schema`)

```rust,no_run
use serde::Deserialize;
use serde_json::json;
use webscrape_ai::{Client, SmartScraperRequest};

#[derive(Deserialize)]
struct Page { stories: Vec<Story> }
#[derive(Deserialize)]
struct Story { title: Option<String>, url: Option<String>, score: Option<i64> }

# async fn run() -> Result<(), webscrape_ai::Error> {
let client = Client::from_env()?;

let resp = client
    .smartscraper(
        SmartScraperRequest::new(
            "https://news.ycombinator.com",
            "Extract the front-page stories with title, url, and score.",
        )
        .output_schema(json!({
            "type": "object",
            "properties": { "stories": { "type": "array" } }
        })),
    )
    .await?;

// `data.result` is a serde_json::Value; decode into your own type:
let page: Page = resp.data.result_as()?;
println!("{} stories", page.stories.len());
# Ok(()) }
```

## SmartBrowse — dispatch and wait

An API key can *run* a recipe and *poll* it; recipes are authored in the
dashboard. `run_and_wait` dispatches then polls until the run is terminal.

```rust,no_run
use webscrape_ai::{Client, Error, WaitOptions};

# async fn run() -> Result<(), webscrape_ai::Error> {
let client = Client::from_env()?;

match client
    .smartbrowse()
    .run_and_wait("m3Yc2tFvN8q", WaitOptions::default())
    .await
{
    Ok(resp) => println!("pages: {:?}", resp.data.pages_extracted),
    Err(Error::RunFailed { run }) => eprintln!("failed: {:?}", run.error),
    Err(Error::WaitTimeout { last }) => eprintln!("still {}", last.run_status),
    Err(e) => return Err(e),
}
# Ok(()) }
```

Lower-level methods: `smartbrowse().run(id)`, `.get_run(id)`,
`.wait_for_run(id, opts)`, `.usage()`.

## Error handling

All methods return `Result<T, Error>`. Branch on the stable
[`ErrorCode`](https://docs.rs/webscrape-ai) — never on the human message.

```rust,no_run
use webscrape_ai::{Client, Error, ErrorCode, ScrapeRequest};

# async fn run() -> Result<(), Box<dyn std::error::Error>> {
let client = Client::from_env()?;
match client.scrape(ScrapeRequest::new("https://example.com")).await {
    Ok(resp) => println!("{:?}", resp.data.html),
    Err(Error::Api(e)) => match e.kind() {
        ErrorCode::InsufficientCredits => {
            eprintln!("need {:?}, have {:?}", e.required(), e.balance());
        }
        ErrorCode::RateLimited => eprintln!("rate limited ({:?})", e.reason()),
        ErrorCode::Unauthorized => eprintln!("bad API key"),
        other => eprintln!("api error {other}: {}", e.message()),
    },
    Err(Error::Timeout(d)) => eprintln!("timed out after {d:?}"),
    Err(Error::Transport(e)) => eprintln!("network error: {e}"),
    Err(e) => eprintln!("{e}"),
}
# Ok(()) }
```

## Blocking client

With the `blocking` feature, `webscrape_ai::blocking::Client` mirrors the async
surface synchronously:

```rust,no_run
# #[cfg(feature = "blocking")]
# fn run() -> Result<(), webscrape_ai::Error> {
use webscrape_ai::{blocking::Client, ScrapeRequest};

let client = Client::from_env()?;
let resp = client.scrape(ScrapeRequest::new("https://example.com").clean(true))?;
println!("{:?}", resp.data.html);
# Ok(()) }
```

## Configuration

| Option | Builder method | Default |
|---|---|---|
| API key | `.api_key(...)` | `WEBSCRAPE_API_KEY` env var |
| Base URL | `.base_url(...)` | `https://api.webscrape.ai/v1` |
| Per-request timeout | `.timeout(...)` | 180s |
| Max retries | `.max_retries(...)` | 2 (0 disables) |
| Custom HTTP client | `.http_client(...)` | new `reqwest::Client` |

```rust,no_run
use std::time::Duration;
use webscrape_ai::Client;

# fn main() -> Result<(), webscrape_ai::Error> {
let client = Client::builder()
    .api_key("wsg_live_...")
    .timeout(Duration::from_secs(60))
    .max_retries(3)
    .build()?;
# let _ = client;
# Ok(()) }
```

Retries apply to HTTP 429/500/502/503 and connection-establishment failures
only (billing is deduct-on-success, so these were never charged). Backoff is
exponential with full jitter (base 1s, cap 30s) and honors `Retry-After`.

## Examples

Runnable programs in [`examples/`](examples): `scrape`, `smartscraper`,
`smartbrowse`. Each reads `WEBSCRAPE_API_KEY` from the environment.

```bash
WEBSCRAPE_API_KEY=wsg_live_... cargo run --example scrape
```

## License

MIT.
