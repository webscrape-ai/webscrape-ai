//! Response models. Every response field is treated as optional/nullable —
//! responses may carry an explicit `null` for any absent field.

use std::time::Duration;

use serde::de::DeserializeOwned;
use serde::Deserialize;
use serde_json::Value;

use crate::error::{Error, Result};

/// A successful (`completed`/`queued`) response envelope with typed `data`.
///
/// The envelope fields are preserved rather than flattened so callers can watch
/// credit drain and keep the two request ids distinct.
#[derive(Debug, Clone)]
#[non_exhaustive]
pub struct Response<T> {
    /// Support-facing request id (`req_…`), from the envelope or `X-Request-ID`.
    pub request_id: Option<String>,
    /// Credits charged for this call. Absent on SmartBrowse dispatch, and `0`
    /// for free polling endpoints.
    pub credits_used: Option<i64>,
    /// Wallet balance after this call. Absent on SmartBrowse dispatch.
    pub credits_remaining: Option<i64>,
    /// The typed `data` payload.
    pub data: T,
}

/// An outbound link from [`ScrapeData::links`].
#[derive(Debug, Clone, Deserialize)]
pub struct LinkInfo {
    /// Absolute link URL.
    pub url: Option<String>,
    /// Visible anchor text.
    pub text: Option<String>,
}

/// Page metadata from [`ScrapeData::metadata`] (HTML only; null for PDFs).
#[derive(Debug, Clone, Deserialize)]
pub struct PageMetadata {
    /// Document title.
    pub title: Option<String>,
    /// Meta description.
    pub description: Option<String>,
    /// Detected language.
    pub language: Option<String>,
}

/// `data` for a successful [`Client::scrape`](crate::Client::scrape) call.
#[derive(Debug, Clone, Deserialize)]
pub struct ScrapeData {
    /// Extraction id (distinct from the top-level `request_id`).
    pub request_id: Option<String>,
    /// Raw HTML, or markdown when `clean: true` / for PDFs.
    pub html: Option<String>,
    /// `"html"` or `"pdf"`.
    pub content_type: Option<String>,
    /// True when the cleaner pass actually ran.
    pub cleaned: Option<bool>,
    /// Present only with `extract_links: true`.
    pub links: Option<Vec<LinkInfo>>,
    /// `{title, description, language}`; null for PDFs.
    pub metadata: Option<PageMetadata>,
    /// Structured data extracted from the page (JSON-LD, microdata, pagination
    /// hints), when available. Modeled as free-form JSON.
    pub structured_data: Option<Value>,
    /// Total fetch time in milliseconds.
    pub latency_ms: Option<i64>,
}

/// `data` for a successful [`Client::smartscraper`](crate::Client::smartscraper)
/// call.
#[derive(Debug, Clone, Deserialize)]
pub struct SmartScraperData {
    /// Extraction id (distinct from the top-level `request_id`).
    pub request_id: Option<String>,
    /// The extracted output — object, array, or string (when `plain_text`).
    pub result: Option<Value>,
    /// Total extraction time in milliseconds.
    pub latency_ms: Option<i64>,
}

impl SmartScraperData {
    /// Deserialize [`SmartScraperData::result`] into a concrete type `T`.
    ///
    /// Returns [`Error::Decode`] if `result` is `null` or does not match `T`.
    pub fn result_as<T: DeserializeOwned>(&self) -> Result<T> {
        match &self.result {
            Some(v) => serde_json::from_value(v.clone())
                .map_err(|e| Error::Decode(format!("failed to decode smartscraper result: {e}"))),
            None => Err(Error::Decode("smartscraper result was null".to_string())),
        }
    }
}

/// SmartBrowse run lifecycle state. Unknown values decode to
/// [`RunStatus::Unknown`] rather than failing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum RunStatus {
    /// Accepted, not yet started.
    Queued,
    /// In progress.
    Running,
    /// Finished successfully.
    Completed,
    /// Finished with an error.
    Failed,
    /// Force-stopped.
    Cancelled,
    /// A state not known to this SDK version.
    #[serde(other)]
    Unknown,
}

impl RunStatus {
    /// True for `completed`, `failed`, or `cancelled`.
    pub fn is_terminal(&self) -> bool {
        matches!(
            self,
            RunStatus::Completed | RunStatus::Failed | RunStatus::Cancelled
        )
    }
}

/// `data` returned when dispatching a SmartBrowse run (HTTP 202 `queued`).
#[derive(Debug, Clone, Deserialize)]
pub struct SmartBrowseDispatch {
    /// Opaque run id to poll.
    pub run_id: String,
    /// The recipe that was run.
    pub recipe_id: String,
    /// Run lifecycle state (always `running` on dispatch).
    pub run_status: RunStatus,
    /// Relative poll URL, e.g. `/v1/smartbrowse/runs/{id}`.
    pub poll_url: Option<String>,
    /// Dispatch timestamp (RFC3339).
    pub created_at: Option<String>,
}

/// `data` returned when polling a SmartBrowse run.
#[derive(Debug, Clone, Deserialize)]
pub struct SmartBrowseRun {
    /// Opaque run id.
    pub id: String,
    /// The recipe that was run.
    pub recipe_id: String,
    /// Run lifecycle state.
    pub run_status: RunStatus,
    /// Pages extracted so far.
    pub pages_extracted: Option<i64>,
    /// Items extracted so far.
    pub items_extracted: Option<i64>,
    /// Credits accrued by the run (distinct from the free polling envelope).
    pub credits_used: Option<i64>,
    /// Start timestamp (RFC3339); absent while pending.
    pub started_at: Option<String>,
    /// Completion timestamp (RFC3339); absent until terminal.
    pub completed_at: Option<String>,
    /// Failure detail; set only on `failed`/`cancelled` runs.
    pub error: Option<String>,
    /// Full result payload; present once `completed`. Free-form JSON.
    pub result: Option<Value>,
    /// Creation timestamp (RFC3339).
    pub created_at: Option<String>,
}

/// Summary of the account's most recent run, from [`SmartBrowseUsage`].
#[derive(Debug, Clone, Deserialize)]
pub struct SmartBrowseLastRun {
    /// Opaque run id.
    pub id: String,
    /// Run lifecycle state.
    pub status: RunStatus,
    /// Pages extracted by the run.
    pub pages_extracted: Option<i64>,
    /// Page ceiling actually applied — `min(plan cap, balance / cost_per_page)`.
    pub effective_cap: Option<i64>,
    /// True when credits, not the plan, bound the page ceiling.
    pub clamped_by_credits: Option<bool>,
    /// Creation timestamp (RFC3339).
    pub created_at: Option<String>,
    /// Completion timestamp (RFC3339); absent while in flight.
    pub completed_at: Option<String>,
}

/// `data` for [`SmartBrowse::usage`](crate::SmartBrowse::usage) — plan caps and
/// rolling-30-day usage.
#[derive(Debug, Clone, Deserialize)]
pub struct SmartBrowseUsage {
    /// Runs initiated in the last 30 days.
    pub runs_used_30d: Option<i64>,
    /// Plan ceiling on runs per rolling 30-day window.
    pub runs_per_month_cap: Option<i64>,
    /// Plan ceiling on pages per run.
    pub pages_per_run_cap: Option<i64>,
    /// Credit cost per extracted page (currently 2).
    pub cost_per_page: Option<i64>,
    /// Number of enabled recurring schedules.
    pub schedules_count: Option<i64>,
    /// Whether the plan allows recurring schedules.
    pub schedules_allowed: Option<bool>,
    /// The most recent run, or `None` if the account has never run.
    pub last_run: Option<SmartBrowseLastRun>,
}

/// Options for [`SmartBrowse::wait_for_run`](crate::SmartBrowse::wait_for_run)
/// and [`SmartBrowse::run_and_wait`](crate::SmartBrowse::run_and_wait).
///
/// The poll interval grows ×1.5 per poll, capped at 10s. The default timeout of
/// 900s matches the service's 15-minute hard run cap.
#[derive(Debug, Clone, Copy)]
pub struct WaitOptions {
    /// Initial delay between polls (default 2s).
    pub poll_interval: Duration,
    /// Overall deadline before giving up (default 900s).
    pub timeout: Duration,
}

impl Default for WaitOptions {
    fn default() -> Self {
        Self {
            poll_interval: Duration::from_secs(2),
            timeout: Duration::from_secs(900),
        }
    }
}

impl WaitOptions {
    /// Default options (2s initial interval, 900s timeout).
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the initial poll interval.
    pub fn poll_interval(mut self, interval: Duration) -> Self {
        self.poll_interval = interval;
        self
    }

    /// Set the overall deadline.
    pub fn timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }
}
