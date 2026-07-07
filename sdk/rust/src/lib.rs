//! Official Rust SDK for the [webscrape.ai](https://webscrape.ai) API.
//!
//! Covers the public API-key surface: [`Client::scrape`], [`Client::smartscraper`],
//! and the [`SmartBrowse`] namespace (dispatch a recipe run, poll it, wait for
//! completion, read usage). See <https://webscrape.ai/docs> for API docs.
//!
//! # Quick start
//!
//! ```no_run
//! # async fn run() -> Result<(), webscrape_ai::Error> {
//! use webscrape_ai::{Client, ScrapeRequest};
//!
//! // Reads WEBSCRAPE_API_KEY from the environment.
//! let client = Client::from_env()?;
//!
//! let resp = client
//!     .scrape(ScrapeRequest::new("https://example.com").clean(true))
//!     .await?;
//!
//! println!("credits used: {:?}", resp.credits_used);
//! println!("markdown: {:?}", resp.data.html);
//! # Ok(()) }
//! ```
//!
//! # Structured extraction
//!
//! ```no_run
//! # async fn run() -> Result<(), webscrape_ai::Error> {
//! use webscrape_ai::{Client, SmartScraperRequest};
//! use serde_json::json;
//!
//! let client = Client::new("wsg_live_...")?;
//! let resp = client
//!     .smartscraper(
//!         SmartScraperRequest::new(
//!             "https://news.ycombinator.com",
//!             "Extract the front-page stories with title, url, and score.",
//!         )
//!         .output_schema(json!({
//!             "type": "object",
//!             "properties": { "stories": { "type": "array" } }
//!         })),
//!     )
//!     .await?;
//!
//! // `data.result` is a serde_json::Value; decode it into your own type.
//! let value = resp.data.result;
//! println!("{value:?}");
//! # Ok(()) }
//! ```
//!
//! # Error handling
//!
//! Every method returns [`Result<T>`](crate::Result). Failures surface as
//! [`Error`], which distinguishes API errors ([`Error::Api`] carrying an
//! [`ApiError`] you branch on via [`ApiError::kind`]) from transport, timeout,
//! and wait-helper failures.

#![forbid(unsafe_code)]
// `Error` intentionally carries rich payloads (`ApiError`, and a full
// `SmartBrowseRun` in `RunFailed`/`WaitTimeout`) so callers can inspect the
// terminal run without a second request. That makes the enum large; boxing would
// change the public contract, so we accept the size rather than deviate.
#![allow(clippy::result_large_err)]

mod client;
mod error;
mod request;
mod response;
mod transport;

#[cfg(feature = "blocking")]
pub mod blocking;

pub use client::{Client, ClientBuilder, SmartBrowse};
pub use error::{ApiError, Error, ErrorCode, Result};
pub use request::{DetailLevel, PageComplexity, ParseMode, ScrapeRequest, SmartScraperRequest};
pub use response::{
    LinkInfo, PageMetadata, Response, RunStatus, ScrapeData, SmartBrowseDispatch,
    SmartBrowseLastRun, SmartBrowseRun, SmartBrowseUsage, SmartScraperData, WaitOptions,
};

/// Default production base URL (`https://api.webscrape.ai/v1`).
pub const DEFAULT_BASE_URL: &str = "https://api.webscrape.ai/v1";

/// Environment variable consulted for the API key when none is passed explicitly.
pub const API_KEY_ENV: &str = "WEBSCRAPE_API_KEY";

/// SDK version, sourced from `CARGO_PKG_VERSION` and used in the `User-Agent`.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// `User-Agent` sent on every request, e.g. `webscrape-ai-rust/0.1.0`.
pub(crate) fn user_agent() -> String {
    format!("webscrape-ai-rust/{VERSION}")
}
