//! Async-first client.

use std::sync::Arc;
use std::time::{Duration, Instant};

use reqwest::Method;
use serde::de::DeserializeOwned;
use serde_json::Value;

use crate::error::{Error, Result};
use crate::request::{ScrapeRequest, SmartScraperRequest};
use crate::response::{
    Response, RunStatus, ScrapeData, SmartBrowseDispatch, SmartBrowseRun, SmartBrowseUsage,
    SmartScraperData, WaitOptions,
};
use crate::transport;
use crate::{user_agent, DEFAULT_BASE_URL};

const DEFAULT_TIMEOUT: Duration = Duration::from_secs(180);
const DEFAULT_MAX_RETRIES: u32 = 2;
const MAX_POLL_INTERVAL: Duration = Duration::from_secs(10);

struct Inner {
    http: reqwest::Client,
    base_url: String,
    api_key: String,
    user_agent: String,
    timeout: Duration,
    max_retries: u32,
}

/// Async webscrape.ai API client. Cheap to [`Clone`] (reference-counted).
#[derive(Clone)]
pub struct Client(Arc<Inner>);

impl Client {
    /// Construct a client with an explicit API key and otherwise default config.
    ///
    /// Returns [`Error::Config`] if `api_key` is empty.
    pub fn new(api_key: impl Into<String>) -> Result<Self> {
        Self::builder().api_key(api_key).build()
    }

    /// Construct a client reading the API key from the `WEBSCRAPE_API_KEY`
    /// environment variable.
    ///
    /// Returns [`Error::Config`] if the variable is unset or empty.
    pub fn from_env() -> Result<Self> {
        Self::builder().build()
    }

    /// Start a [`ClientBuilder`] for custom configuration.
    pub fn builder() -> ClientBuilder {
        ClientBuilder::default()
    }

    /// Fetch a URL as HTML, cleaned markdown, or links. Costs 1 credit (+2 with
    /// stealth).
    pub async fn scrape(&self, request: ScrapeRequest) -> Result<Response<ScrapeData>> {
        let body = to_value(&request)?;
        self.0
            .execute(Method::POST, "/scrape".to_string(), Some(body))
            .await
    }

    /// LLM structured extraction returning JSON. Costs 5 credits (+5 with
    /// stealth).
    pub async fn smartscraper(
        &self,
        request: SmartScraperRequest,
    ) -> Result<Response<SmartScraperData>> {
        let body = to_value(&request)?;
        self.0
            .execute(Method::POST, "/smartscraper".to_string(), Some(body))
            .await
    }

    /// Access the SmartBrowse namespace (run recipes, poll runs, usage).
    pub fn smartbrowse(&self) -> SmartBrowse<'_> {
        SmartBrowse { client: self }
    }
}

impl std::fmt::Debug for Client {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Client")
            .field("base_url", &self.0.base_url)
            .field("timeout", &self.0.timeout)
            .field("max_retries", &self.0.max_retries)
            .finish_non_exhaustive()
    }
}

impl Inner {
    async fn execute<T: DeserializeOwned>(
        &self,
        method: Method,
        path: String,
        body: Option<Value>,
    ) -> Result<Response<T>> {
        let url = format!("{}{}", self.base_url, path);
        let mut attempt = 0u32;

        loop {
            let mut rb = self
                .http
                .request(method.clone(), &url)
                .header("X-API-Key", &self.api_key)
                .header(reqwest::header::USER_AGENT, &self.user_agent)
                .header(reqwest::header::ACCEPT, "application/json")
                .timeout(self.timeout);
            if let Some(ref b) = body {
                rb = rb.json(b);
            }

            match rb.send().await {
                Ok(resp) => {
                    let status = resp.status().as_u16();
                    let request_id_header = transport::header_str(resp.headers(), "x-request-id");
                    let retry_after = transport::parse_retry_after(
                        transport::header_str(resp.headers(), "retry-after").as_deref(),
                    );
                    // Body-read failures are NOT retried: the request may have
                    // completed server-side, and a completed request must not be
                    // replayed.
                    let bytes = resp.bytes().await.map_err(Error::Transport)?;

                    if transport::is_retryable_status(status) && attempt < self.max_retries {
                        attempt += 1;
                        tokio::time::sleep(transport::backoff_delay(attempt, retry_after)).await;
                        continue;
                    }
                    return transport::process_response::<T>(
                        status,
                        request_id_header,
                        retry_after,
                        &bytes,
                    );
                }
                Err(e) => {
                    if e.is_timeout() {
                        return Err(Error::Timeout(self.timeout));
                    }
                    if transport::is_retryable_send_error(&e) && attempt < self.max_retries {
                        attempt += 1;
                        tokio::time::sleep(transport::backoff_delay(attempt, None)).await;
                        continue;
                    }
                    return Err(Error::Transport(e));
                }
            }
        }
    }
}

/// The SmartBrowse method namespace, obtained via [`Client::smartbrowse`].
pub struct SmartBrowse<'a> {
    client: &'a Client,
}

impl SmartBrowse<'_> {
    /// Dispatch a recipe replay run (async). Returns immediately with a run id
    /// to poll. Costs 2 credits per page, billed on completion.
    pub async fn run(&self, recipe_id: &str) -> Result<Response<SmartBrowseDispatch>> {
        let path = format!("/smartbrowse/recipes/{recipe_id}/run");
        self.client.0.execute(Method::POST, path, None).await
    }

    /// Poll a run's current state (free).
    pub async fn get_run(&self, run_id: &str) -> Result<Response<SmartBrowseRun>> {
        let path = format!("/smartbrowse/runs/{run_id}");
        self.client.0.execute(Method::GET, path, None).await
    }

    /// Plan caps + rolling-30-day usage (free).
    pub async fn usage(&self) -> Result<Response<SmartBrowseUsage>> {
        self.client
            .0
            .execute(Method::GET, "/smartbrowse/usage".to_string(), None)
            .await
    }

    /// Poll `run_id` until it reaches a terminal state.
    ///
    /// - `completed` → returns the run response.
    /// - `failed` / `cancelled` → [`Error::RunFailed`] carrying the run.
    /// - deadline exceeded → [`Error::WaitTimeout`] carrying the last-seen run.
    pub async fn wait_for_run(
        &self,
        run_id: &str,
        opts: WaitOptions,
    ) -> Result<Response<SmartBrowseRun>> {
        let start = Instant::now();
        let mut interval = opts.poll_interval;

        loop {
            let resp = self.get_run(run_id).await?;
            match resp.data.run_status {
                RunStatus::Completed => return Ok(resp),
                RunStatus::Failed | RunStatus::Cancelled => {
                    return Err(Error::RunFailed { run: resp.data })
                }
                _ => {}
            }

            if start.elapsed() >= opts.timeout {
                return Err(Error::WaitTimeout { last: resp.data });
            }
            let remaining = opts.timeout.saturating_sub(start.elapsed());
            tokio::time::sleep(interval.min(remaining)).await;
            interval = interval.mul_f64(1.5).min(MAX_POLL_INTERVAL);
        }
    }

    /// [`run`](Self::run) followed by [`wait_for_run`](Self::wait_for_run).
    pub async fn run_and_wait(
        &self,
        recipe_id: &str,
        opts: WaitOptions,
    ) -> Result<Response<SmartBrowseRun>> {
        let dispatch = self.run(recipe_id).await?;
        self.wait_for_run(&dispatch.data.run_id, opts).await
    }
}

fn to_value<T: serde::Serialize>(request: &T) -> Result<Value> {
    serde_json::to_value(request)
        .map_err(|e| Error::Decode(format!("failed to serialize request: {e}")))
}

/// Builder for [`Client`].
#[derive(Default)]
pub struct ClientBuilder {
    api_key: Option<String>,
    base_url: Option<String>,
    timeout: Option<Duration>,
    max_retries: Option<u32>,
    http_client: Option<reqwest::Client>,
}

impl ClientBuilder {
    /// Set the API key explicitly. Overrides the `WEBSCRAPE_API_KEY` env fallback.
    pub fn api_key(mut self, api_key: impl Into<String>) -> Self {
        self.api_key = Some(api_key.into());
        self
    }

    /// Override the base URL (default `https://api.webscrape.ai/v1`). A trailing
    /// slash is accepted and normalized away.
    pub fn base_url(mut self, base_url: impl Into<String>) -> Self {
        self.base_url = Some(base_url.into());
        self
    }

    /// Set the per-request timeout (default 180s).
    pub fn timeout(mut self, timeout: Duration) -> Self {
        self.timeout = Some(timeout);
        self
    }

    /// Set the maximum number of retries (default 2; 0 disables).
    pub fn max_retries(mut self, max_retries: u32) -> Self {
        self.max_retries = Some(max_retries);
        self
    }

    /// Supply a preconfigured [`reqwest::Client`] (proxies, custom TLS, etc.).
    pub fn http_client(mut self, http_client: reqwest::Client) -> Self {
        self.http_client = Some(http_client);
        self
    }

    /// Build the [`Client`]. Resolves the API key from [`api_key`](Self::api_key)
    /// or `WEBSCRAPE_API_KEY`; returns [`Error::Config`] if neither is set.
    pub fn build(self) -> Result<Client> {
        let api_key = resolve_api_key(self.api_key)?;
        let base_url = self
            .base_url
            .unwrap_or_else(|| DEFAULT_BASE_URL.to_string())
            .trim_end_matches('/')
            .to_string();
        let timeout = self.timeout.unwrap_or(DEFAULT_TIMEOUT);
        let max_retries = self.max_retries.unwrap_or(DEFAULT_MAX_RETRIES);
        let http = match self.http_client {
            Some(c) => c,
            None => reqwest::Client::builder()
                .build()
                .map_err(|e| Error::Config(format!("failed to build HTTP client: {e}")))?,
        };

        Ok(Client(Arc::new(Inner {
            http,
            base_url,
            api_key,
            user_agent: user_agent(),
            timeout,
            max_retries,
        })))
    }
}

/// Resolve the API key from an explicit value or the environment, failing fast
/// when neither is present.
pub(crate) fn resolve_api_key(explicit: Option<String>) -> Result<String> {
    let key = explicit
        .filter(|k| !k.is_empty())
        .or_else(|| std::env::var(crate::API_KEY_ENV).ok())
        .filter(|k| !k.is_empty());
    key.ok_or_else(|| {
        Error::Config(format!(
            "no API key: pass one to the builder or set {}",
            crate::API_KEY_ENV
        ))
    })
}
