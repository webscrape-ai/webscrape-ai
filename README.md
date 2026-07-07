# webscrape.ai SDKs

Official client SDKs for the [webscrape.ai](https://webscrape.ai) API.

> This directory ships as the `sdk/` folder of
> [`github.com/webscrape-ai/webscrape-ai`](https://github.com/webscrape-ai/webscrape-ai)
> and is fully self-contained.

| Language | Package | Directory |
|---|---|---|
| Go | [`github.com/webscrape-ai/webscrape-ai/sdk/go`](https://pkg.go.dev/github.com/webscrape-ai/webscrape-ai/sdk/go) | [`go/`](go/) |
| Python | [`webscrape-ai`](https://pypi.org/project/webscrape-ai/) (import `webscrape_ai`) | [`python/`](python/) |
| Node / TypeScript | [`webscrape-ai`](https://www.npmjs.com/package/webscrape-ai) | [`node/`](node/) |
| Rust | [`webscrape-ai`](https://crates.io/crates/webscrape-ai) (lib `webscrape_ai`) | [`rust/`](rust/) |
| Java | `ai.webscrape:webscrape-sdk` | [`java/`](java/) |

## Covered API surface

- `POST /v1/scrape` — fetch a URL as HTML, cleaned markdown, or links
- `POST /v1/smartscraper` — LLM structured extraction with optional JSON-schema validation
- `POST /v1/smartbrowse/recipes/{id}/run` — dispatch a SmartBrowse run
- `GET /v1/smartbrowse/runs/{id}` — poll a run
- `GET /v1/smartbrowse/usage` — plan caps + rolling 30-day usage

Every SDK ships the same conveniences: automatic retry with exponential backoff on
rate-limited and failed requests (failed requests are never charged, so retries are always
safe), a `run_and_wait` helper that turns the SmartBrowse dispatch+poll two-step into one
call, and `WEBSCRAPE_API_KEY` env-var pickup.

## API reference

[`openapi.yaml`](openapi.yaml) is the vendored public spec; full documentation lives at
[webscrape.ai/docs](https://webscrape.ai/docs).

## Development

```bash
make all        # build + test all five SDKs
make go python node rust java   # individually
```

Toolchains: Go ≥ 1.22, Python ≥ 3.10, Node ≥ 18 (+pnpm), Rust stable, JDK ≥ 11 (+Maven).

## License

MIT — see [LICENSE](LICENSE).
