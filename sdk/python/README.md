# webscrape-ai (Python)

[![PyPI](https://img.shields.io/pypi/v/webscrape-ai.svg)](https://pypi.org/project/webscrape-ai/) [![Python versions](https://img.shields.io/pypi/pyversions/webscrape-ai.svg)](https://pypi.org/project/webscrape-ai/)

Official Python SDK for the [webscrape.ai](https://webscrape.ai) web scraping API.
Fetch pages, run LLM structured extraction, and dispatch/poll SmartBrowse recipe
replays — with typed responses, an ergonomic error hierarchy, and automatic,
billing-safe retries.

- Sync `Client` and async `AsyncClient` (both context managers)
- Frozen dataclass responses, fully type-hinted, ships `py.typed`
- One runtime dependency: [`httpx`](https://www.python-httpx.org/)
- Python 3.10+

## Install

```bash
pip install webscrape-ai
```

## Authentication

Every request sends your API key in the `X-API-Key` header. Provide it explicitly
or via the `WEBSCRAPE_API_KEY` environment variable (the client reads it at
construction and fails fast if neither is set):

```python
from webscrape_ai import Client

client = Client(api_key="wsg_live_...")   # or: Client()  → reads WEBSCRAPE_API_KEY
```

Get a key from the [dashboard](https://webscrape.ai/app/api-keys). Format:
`wsg_live_<32 base62 chars>`.

## Quickstart

### Scrape a page

```python
from webscrape_ai import Client

with Client() as client:
    resp = client.scrape(website_url="https://example.com", clean=True)
    print(resp.data.html)            # cleaned markdown
    print(resp.credits_used, resp.credits_remaining)
    print(resp.request_id)           # support-facing envelope id (req_...)
    print(resp.data.request_id)      # extraction id (distinct from the top-level request_id)
```

### Structured extraction with `smartscraper`

```python
schema = {
    "type": "object",
    "properties": {
        "stories": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "url": {"type": "string"},
                    "score": {"type": "integer"},
                },
            },
        }
    },
}

with Client() as client:
    resp = client.smartscraper(
        website_url="https://news.ycombinator.com",
        user_prompt="Extract the front-page stories with title, url, and score.",
        output_schema=schema,
    )
    print(resp.data.result)   # schema-shaped dict (Any)
```

### SmartBrowse: dispatch a run and wait for it

```python
from webscrape_ai import Client, RunFailedError, WaitTimeoutError

with Client() as client:
    try:
        run = client.smartbrowse.run_and_wait("m3Yc2tFvN8q", timeout=900)
        print(run.data.run_status)        # RunStatus.COMPLETED
        print(run.data.pages_extracted, run.data.credits_used)
        for page in (run.data.result.pages or []):
            for item in (page.items or []):
                print(item)
    except RunFailedError as e:
        print("run failed:", e.run.data.error)
    except WaitTimeoutError as e:
        print("still running at deadline:", e.run.data.run_status)

    # Or drive the loop yourself:
    dispatch = client.smartbrowse.run("m3Yc2tFvN8q")
    run = client.smartbrowse.get_run(dispatch.data.run_id)

    usage = client.smartbrowse.usage()
    print(usage.data.runs_used_30d, "/", usage.data.runs_per_month_cap)
```

### Async

Every method is mirrored on `AsyncClient`:

```python
import asyncio
from webscrape_ai import AsyncClient

async def main():
    async with AsyncClient() as client:
        resp = await client.scrape(website_url="https://example.com", clean=True)
        print(resp.data.html)
        run = await client.smartbrowse.run_and_wait("m3Yc2tFvN8q")
        print(run.data.run_status)

asyncio.run(main())
```

## Error handling

Error envelopes are raised as typed exceptions, all rooted at `WebscrapeError`.
Branch on the subtype (never on the human-readable `message`):

```python
from webscrape_ai import (
    Client,
    AuthenticationError,
    InsufficientCreditsError,
    RateLimitError,
    ValidationError,
    NotFoundError,
    APIError,
)

with Client() as client:
    try:
        resp = client.smartscraper(
            website_url="https://example.com",
            user_prompt="Extract the product price.",
        )
    except InsufficientCreditsError as e:
        print(f"need {e.required}, have {e.balance}")
    except RateLimitError as e:
        print("throttled:", e.reason)          # rate_limit_per_min | max_concurrent_requests | sb_runs_per_month
    except AuthenticationError:
        print("bad or missing API key")
    except ValidationError as e:
        print("extraction failed validation:", e.details)
    except NotFoundError:
        print("no such resource")
    except APIError as e:
        # Any other (or brand-new) error code lands here; the raw code is preserved.
        print(e.status_code, e.code, e.message, e.request_id)
```

Exception families (subtypes of `APIError` unless noted):

| Exception | Error code(s) |
|---|---|
| `AuthenticationError` | `unauthorized` |
| `InsufficientCreditsError` | `insufficient_credits` (`.balance`, `.required`) |
| `EmailVerificationError` | `email_verification_required` |
| `ForbiddenError` | `forbidden` |
| `NotFoundError` | `not_found` |
| `ConflictError` | `conflict`, `account_deletion_pending` (`.deletion_scheduled_for`) |
| `BadRequestError` | `invalid_request` |
| `ValidationError` | `validation_failed` |
| `RateLimitError` | `rate_limited` (`.reason`) |
| `ServerError` | `internal_error`, `service_unavailable` |
| `APIError` | any unknown code (raw `.code` preserved) |
| `TransportError` / `APITimeoutError` | network failure / timeout (not HTTP) |
| `ConfigurationError` | missing API key at construction |
| `RunFailedError` / `WaitTimeoutError` | terminal-failed run / wait deadline (carry `.run`) |

## Configuration

| Option | Default | Notes |
|---|---|---|
| `api_key` | `WEBSCRAPE_API_KEY` env var | fails fast if neither set |
| `base_url` | `https://api.webscrape.ai/v1` | override for self-hosted / staging |
| `timeout` | `180.0` seconds | per request |
| `max_retries` | `2` | up to 3 attempts; `0` disables |
| `http_client` | new `httpx.Client` / `httpx.AsyncClient` | pass your own to customize transport, proxies, TLS |

Retries are automatic and billing-safe: only `429/500/502/503` and
connection-establishment failures are retried (exponential backoff, full jitter,
capped at 30s; `Retry-After` honored if present). Other 4xx are never retried.

## Docs

Full API reference: <https://webscrape.ai/docs>

## License

MIT
