//! Runtime-agnostic transport helpers shared by the async and blocking clients:
//! envelope parsing, error synthesis (including the plain-401 body shape),
//! retry classification, and full-jitter backoff.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::header::HeaderMap;
use serde::de::DeserializeOwned;
use serde_json::Value;

use crate::error::{ApiError, Error, ErrorCode, Result};
use crate::response::Response;

/// Loosely-typed view of any envelope shape (completed / queued / error / bare).
#[derive(Debug, serde::Deserialize)]
struct RawEnvelope {
    status: Option<String>,
    request_id: Option<String>,
    data: Option<Value>,
    credits_used: Option<i64>,
    credits_remaining: Option<i64>,
    /// Object for enveloped errors, or a bare string for the plain-401 body.
    error: Option<Value>,
}

/// HTTP statuses that are safe to retry. Failed and rate-limited requests are
/// never charged, so retrying them is always safe.
pub(crate) fn is_retryable_status(status: u16) -> bool {
    matches!(status, 429 | 500 | 502 | 503)
}

/// Read a response header as an owned string.
pub(crate) fn header_str(headers: &HeaderMap, name: &str) -> Option<String> {
    headers
        .get(name)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string())
}

/// Parse a `Retry-After` header (seconds form only; HTTP-date form ignored).
pub(crate) fn parse_retry_after(value: Option<&str>) -> Option<Duration> {
    let secs: u64 = value?.trim().parse().ok()?;
    Some(Duration::from_secs(secs.min(300)))
}

/// Delay before the next retry attempt (`attempt` is 1-based). Honors
/// `Retry-After` when present; otherwise exponential base-1s ×2 full-jitter,
/// capped at 30s.
pub(crate) fn backoff_delay(attempt: u32, retry_after: Option<Duration>) -> Duration {
    if let Some(ra) = retry_after {
        return ra;
    }
    const BASE_MS: u64 = 1_000;
    const CAP_MS: u64 = 30_000;
    let exp = attempt.saturating_sub(1).min(20);
    let window_ms = BASE_MS.saturating_mul(1u64 << exp).min(CAP_MS);
    Duration::from_millis((window_ms as f64 * pseudo_random_unit()) as u64)
}

/// A cheap, non-cryptographic float in `[0, 1)` for jitter. Seeded from the
/// clock plus a process-global counter so concurrent callers don't collide.
fn pseudo_random_unit() -> f64 {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0);
    let c = COUNTER.fetch_add(1, Ordering::Relaxed);
    let mut x = nanos
        .wrapping_mul(6364136223846793005)
        .wrapping_add(c.wrapping_mul(1442695040888963407))
        .wrapping_add(1);
    // splitmix64 finalizer
    x ^= x >> 30;
    x = x.wrapping_mul(0xbf58476d1ce4e5b9);
    x ^= x >> 27;
    x = x.wrapping_mul(0x94d049bb133111eb);
    x ^= x >> 31;
    (x >> 11) as f64 / (1u64 << 53) as f64
}

/// True when a reqwest send error is a connection-establishment failure — the
/// only transport failure class safe to retry (the request never reached the
/// server, so it was never billed).
pub(crate) fn is_retryable_send_error(err: &reqwest::Error) -> bool {
    err.is_connect()
}

/// Turn a `(status, headers, body)` triple into either a typed [`Response<T>`]
/// or an [`Error`].
pub(crate) fn process_response<T: DeserializeOwned>(
    status: u16,
    request_id_header: Option<String>,
    retry_after: Option<Duration>,
    body: &[u8],
) -> Result<Response<T>> {
    let raw: Option<RawEnvelope> = serde_json::from_slice(body).ok();
    let request_id = raw
        .as_ref()
        .and_then(|r| r.request_id.clone())
        .or(request_id_header);

    let is_error_envelope = raw
        .as_ref()
        .is_some_and(|r| r.status.as_deref() == Some("error") || r.error.is_some());

    if status >= 400 || is_error_envelope {
        return Err(Error::Api(build_api_error(
            status,
            request_id,
            retry_after,
            raw.as_ref(),
            body,
        )));
    }

    let raw = raw.ok_or_else(|| {
        Error::Decode(format!(
            "response body was not valid JSON (HTTP {status}, {} bytes)",
            body.len()
        ))
    })?;
    let data_value = raw.data.unwrap_or(Value::Null);
    let data = serde_json::from_value(data_value)
        .map_err(|e| Error::Decode(format!("failed to decode response data: {e}")))?;

    Ok(Response {
        request_id,
        credits_used: raw.credits_used,
        credits_remaining: raw.credits_remaining,
        data,
    })
}

fn build_api_error(
    status: u16,
    request_id: Option<String>,
    retry_after: Option<Duration>,
    raw: Option<&RawEnvelope>,
    _body: &[u8],
) -> ApiError {
    let error_field = raw.and_then(|r| r.error.as_ref());

    let (code_opt, message_opt, details) = match error_field {
        // Enveloped error: `error: { code, message, details }`.
        Some(Value::Object(map)) => (
            map.get("code").and_then(Value::as_str).map(str::to_string),
            map.get("message")
                .and_then(Value::as_str)
                .map(str::to_string),
            map.get("details").cloned(),
        ),
        // Plain-401 body: `error: "<string>"` with no envelope. Authentication
        // failures may return a plain `{"error": "<message>"}` body instead of
        // the standard envelope; both shapes are handled.
        Some(Value::String(s)) => (None, Some(s.clone()), None),
        _ => (None, None, None),
    };

    let code = match code_opt {
        Some(c) => ErrorCode::parse(&c),
        // Missing code: 401 -> unauthorized (covers the bare-401 body), else
        // best-effort from the HTTP status.
        None if status == 401 => ErrorCode::Unauthorized,
        None => ErrorCode::from_status(status),
    };

    let message =
        message_opt.unwrap_or_else(|| format!("request failed with HTTP status {status}"));

    ApiError::new(status, code, message, details, request_id, retry_after)
}
