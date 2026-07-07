//! Error taxonomy.

use std::fmt;
use std::time::Duration;

use serde_json::Value;

use crate::response::{RunStatus, SmartBrowseRun};

/// Convenience alias for `Result<T, webscrape_ai::Error>`.
pub type Result<T> = std::result::Result<T, Error>;

/// Top-level error returned by every SDK method.
#[derive(Debug, thiserror::Error)]
#[non_exhaustive]
pub enum Error {
    /// The server returned a structured API error envelope (or a bare-401 body).
    #[error(transparent)]
    Api(#[from] ApiError),

    /// A network/transport failure that is not a timeout (connection reset,
    /// DNS, body-read failure, TLS, …).
    #[error("transport error: {0}")]
    Transport(#[source] reqwest::Error),

    /// The request exceeded the configured per-request timeout.
    #[error("request timed out after {0:?}")]
    Timeout(Duration),

    /// A SmartBrowse run reached a terminal `failed`/`cancelled` state while
    /// waiting. Carries the full run so `error`, `pages_extracted`, and
    /// `credits_used` are inspectable.
    #[error("smartbrowse run {} ended in state {}", .run.id, .run.run_status)]
    RunFailed {
        /// The terminal run.
        run: SmartBrowseRun,
    },

    /// [`SmartBrowse::wait_for_run`](crate::SmartBrowse::wait_for_run) exceeded
    /// its deadline before the run finished. Carries the last-seen run.
    #[error("timed out waiting for smartbrowse run {} (last state: {})", .last.id, .last.run_status)]
    WaitTimeout {
        /// The most recent run state observed before the deadline.
        last: SmartBrowseRun,
    },

    /// Client construction failed (e.g. missing API key, bad HTTP client).
    #[error("configuration error: {0}")]
    Config(String),

    /// A well-formed HTTP response could not be decoded into the expected shape.
    #[error("decode error: {0}")]
    Decode(String),
}

impl Error {
    /// Returns the inner [`ApiError`] if this is an [`Error::Api`].
    pub fn as_api(&self) -> Option<&ApiError> {
        match self {
            Error::Api(e) => Some(e),
            _ => None,
        }
    }

    /// True when this is an API error with the given [`ErrorCode`].
    pub fn is_code(&self, code: &ErrorCode) -> bool {
        self.as_api().map(|e| e.code() == code).unwrap_or(false)
    }

    /// True for `unauthorized` API errors (both envelope and bare-401 shapes).
    pub fn is_unauthorized(&self) -> bool {
        self.as_api()
            .map(ApiError::is_unauthorized)
            .unwrap_or(false)
    }

    /// True for `insufficient_credits` API errors.
    pub fn is_insufficient_credits(&self) -> bool {
        self.as_api()
            .map(ApiError::is_insufficient_credits)
            .unwrap_or(false)
    }

    /// True for `rate_limited` API errors.
    pub fn is_rate_limited(&self) -> bool {
        self.as_api()
            .map(ApiError::is_rate_limited)
            .unwrap_or(false)
    }

    /// True for `not_found` API errors.
    pub fn is_not_found(&self) -> bool {
        self.as_api().map(ApiError::is_not_found).unwrap_or(false)
    }
}

/// A structured API error decoded from the response envelope.
///
/// Branch on [`ApiError::kind`] (a stable [`ErrorCode`]); never pattern-match the
/// human-readable [`ApiError::message`], which may change between releases.
#[derive(Debug, Clone, thiserror::Error)]
#[error("api error [{status}] {code}: {message}")]
pub struct ApiError {
    status: u16,
    code: ErrorCode,
    message: String,
    details: Option<Value>,
    request_id: Option<String>,
    retry_after: Option<Duration>,
}

impl ApiError {
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn new(
        status: u16,
        code: ErrorCode,
        message: String,
        details: Option<Value>,
        request_id: Option<String>,
        retry_after: Option<Duration>,
    ) -> Self {
        Self {
            status,
            code,
            message,
            details,
            request_id,
            retry_after,
        }
    }

    /// HTTP status code that carried this error.
    pub fn status(&self) -> u16 {
        self.status
    }

    /// The stable error code. Alias for [`ApiError::kind`].
    pub fn code(&self) -> &ErrorCode {
        &self.code
    }

    /// The stable error code (the "kind" of API error).
    pub fn kind(&self) -> &ErrorCode {
        &self.code
    }

    /// Human-readable message. Log it; do not pattern-match it.
    pub fn message(&self) -> &str {
        &self.message
    }

    /// Optional structured `error.details` payload as raw JSON.
    pub fn details(&self) -> Option<&Value> {
        self.details.as_ref()
    }

    /// The support-facing `request_id` (`req_…`), from the envelope or the
    /// `X-Request-ID` response header.
    pub fn request_id(&self) -> Option<&str> {
        self.request_id.as_deref()
    }

    /// A honored `Retry-After` value, if the server sent one.
    pub fn retry_after(&self) -> Option<Duration> {
        self.retry_after
    }

    /// For `insufficient_credits`: the caller's current balance, if present.
    pub fn balance(&self) -> Option<i64> {
        self.detail_i64("balance")
    }

    /// For `insufficient_credits`: the credits required, if present.
    pub fn required(&self) -> Option<i64> {
        self.detail_i64("required")
    }

    /// For `rate_limited`: the `reason` discriminator
    /// (`rate_limit_per_min` | `max_concurrent_requests` | `sb_runs_per_month`).
    pub fn reason(&self) -> Option<&str> {
        self.detail_str("reason")
    }

    /// For `account_deletion_pending`: the scheduled deletion timestamp.
    pub fn deletion_scheduled_for(&self) -> Option<&str> {
        self.detail_str("deletion_scheduled_for")
    }

    /// True for `unauthorized`.
    pub fn is_unauthorized(&self) -> bool {
        matches!(self.code, ErrorCode::Unauthorized)
    }

    /// True for `insufficient_credits`.
    pub fn is_insufficient_credits(&self) -> bool {
        matches!(self.code, ErrorCode::InsufficientCredits)
    }

    /// True for `rate_limited`.
    pub fn is_rate_limited(&self) -> bool {
        matches!(self.code, ErrorCode::RateLimited)
    }

    /// True for `not_found`.
    pub fn is_not_found(&self) -> bool {
        matches!(self.code, ErrorCode::NotFound)
    }

    /// True for `validation_failed`.
    pub fn is_validation_failed(&self) -> bool {
        matches!(self.code, ErrorCode::ValidationFailed)
    }

    fn detail_i64(&self, key: &str) -> Option<i64> {
        self.details.as_ref()?.get(key)?.as_i64()
    }

    fn detail_str(&self, key: &str) -> Option<&str> {
        self.details.as_ref()?.get(key)?.as_str()
    }
}

/// Stable API error code. Unknown codes decode to
/// [`ErrorCode::Unknown`] with the raw string preserved.
#[derive(Debug, Clone, PartialEq, Eq)]
#[non_exhaustive]
pub enum ErrorCode {
    /// `invalid_request` (HTTP 400)
    InvalidRequest,
    /// `unauthorized` (HTTP 401)
    Unauthorized,
    /// `insufficient_credits` (HTTP 402)
    InsufficientCredits,
    /// `email_verification_required` (HTTP 402)
    EmailVerificationRequired,
    /// `forbidden` (HTTP 403)
    Forbidden,
    /// `not_found` (HTTP 404)
    NotFound,
    /// `conflict` (HTTP 409)
    Conflict,
    /// `account_deletion_pending` (HTTP 409)
    AccountDeletionPending,
    /// `validation_failed` (HTTP 422)
    ValidationFailed,
    /// `rate_limited` (HTTP 429)
    RateLimited,
    /// `internal_error` (HTTP 500)
    InternalError,
    /// `service_unavailable` (HTTP 502)
    ServiceUnavailable,
    /// Any code not known to this SDK version. The raw string is preserved.
    Unknown(String),
}

impl ErrorCode {
    /// The canonical wire string for this code.
    pub fn as_str(&self) -> &str {
        match self {
            ErrorCode::InvalidRequest => "invalid_request",
            ErrorCode::Unauthorized => "unauthorized",
            ErrorCode::InsufficientCredits => "insufficient_credits",
            ErrorCode::EmailVerificationRequired => "email_verification_required",
            ErrorCode::Forbidden => "forbidden",
            ErrorCode::NotFound => "not_found",
            ErrorCode::Conflict => "conflict",
            ErrorCode::AccountDeletionPending => "account_deletion_pending",
            ErrorCode::ValidationFailed => "validation_failed",
            ErrorCode::RateLimited => "rate_limited",
            ErrorCode::InternalError => "internal_error",
            ErrorCode::ServiceUnavailable => "service_unavailable",
            ErrorCode::Unknown(s) => s.as_str(),
        }
    }

    /// Parse a wire string into an [`ErrorCode`], falling back to
    /// [`ErrorCode::Unknown`].
    pub(crate) fn parse(s: &str) -> Self {
        match s {
            "invalid_request" => ErrorCode::InvalidRequest,
            "unauthorized" => ErrorCode::Unauthorized,
            "insufficient_credits" => ErrorCode::InsufficientCredits,
            "email_verification_required" => ErrorCode::EmailVerificationRequired,
            "forbidden" => ErrorCode::Forbidden,
            "not_found" => ErrorCode::NotFound,
            "conflict" => ErrorCode::Conflict,
            "account_deletion_pending" => ErrorCode::AccountDeletionPending,
            "validation_failed" => ErrorCode::ValidationFailed,
            "rate_limited" => ErrorCode::RateLimited,
            "internal_error" => ErrorCode::InternalError,
            "service_unavailable" => ErrorCode::ServiceUnavailable,
            other => ErrorCode::Unknown(other.to_string()),
        }
    }

    /// Best-effort mapping from an HTTP status when the body carried no code
    /// (e.g. a non-envelope error body).
    pub(crate) fn from_status(status: u16) -> Self {
        match status {
            400 => ErrorCode::InvalidRequest,
            401 => ErrorCode::Unauthorized,
            403 => ErrorCode::Forbidden,
            404 => ErrorCode::NotFound,
            409 => ErrorCode::Conflict,
            422 => ErrorCode::ValidationFailed,
            429 => ErrorCode::RateLimited,
            500 => ErrorCode::InternalError,
            502 | 503 => ErrorCode::ServiceUnavailable,
            other => ErrorCode::Unknown(format!("http_{other}")),
        }
    }
}

impl fmt::Display for ErrorCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl fmt::Display for RunStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let s = match self {
            RunStatus::Queued => "queued",
            RunStatus::Running => "running",
            RunStatus::Completed => "completed",
            RunStatus::Failed => "failed",
            RunStatus::Cancelled => "cancelled",
            RunStatus::Unknown => "unknown",
        };
        f.write_str(s)
    }
}
