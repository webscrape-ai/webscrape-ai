"""Shared, transport-agnostic client internals.

:class:`_BaseClient` holds config resolution and all the pure helpers (URL
building, header assembly, backoff computation, envelope parsing, exception
mapping). The sync and async clients add only the I/O loop on top, so retry,
error-mapping, and parsing behavior stay identical across both.
"""

from __future__ import annotations

import os
import random
from typing import Any, Mapping, Optional, Sequence

import httpx

from ._constants import (
    DEFAULT_BASE_URL,
    DEFAULT_MAX_RETRIES,
    DEFAULT_TIMEOUT,
    RETRY_BASE_SECONDS,
    RETRY_CAP_SECONDS,
    USER_AGENT,
)
from .errors import (
    APIError,
    APITimeoutError,
    ConfigurationError,
    TransportError,
    default_code_for_status,
    make_api_error,
)


def _drop_none(d: dict) -> dict:
    """Serialization rule: omit any option the caller left unset."""
    return {k: v for k, v in d.items() if v is not None}


def build_scrape_body(
    *,
    website_url: str,
    clean: Optional[bool],
    parse_mode: Optional[str],
    tag_truncate: Optional[bool],
    extract_links: Optional[bool],
    include_tags: Optional[Sequence[str]],
    exclude_tags: Optional[Sequence[str]],
    headers: Optional[Mapping[str, str]],
    max_age: Optional[int],
    stealth: Optional[bool],
) -> dict:
    return _drop_none(
        {
            "website_url": website_url,
            "clean": clean,
            "parse_mode": parse_mode,
            "tag_truncate": tag_truncate,
            "extract_links": extract_links,
            "include_tags": list(include_tags) if include_tags is not None else None,
            "exclude_tags": list(exclude_tags) if exclude_tags is not None else None,
            "headers": dict(headers) if headers is not None else None,
            "max_age": max_age,
            "stealth": stealth,
        }
    )


def build_smartscraper_body(
    *,
    website_url: str,
    user_prompt: str,
    output_schema: Optional[Mapping[str, Any]],
    page_complexity: Optional[str],
    detail_level: Optional[str],
    parse_mode: Optional[str],
    plain_text: Optional[bool],
    include_tags: Optional[Sequence[str]],
    exclude_tags: Optional[Sequence[str]],
    reduce_content: Optional[bool],
    experimental: Optional[bool],
    headers: Optional[Mapping[str, str]],
    max_age: Optional[int],
    stealth: Optional[bool],
) -> dict:
    return _drop_none(
        {
            "website_url": website_url,
            "user_prompt": user_prompt,
            "output_schema": dict(output_schema) if output_schema is not None else None,
            "page_complexity": page_complexity,
            "detail_level": detail_level,
            "parse_mode": parse_mode,
            "plain_text": plain_text,
            "include_tags": list(include_tags) if include_tags is not None else None,
            "exclude_tags": list(exclude_tags) if exclude_tags is not None else None,
            "reduce_content": reduce_content,
            "experimental": experimental,
            "headers": dict(headers) if headers is not None else None,
            "max_age": max_age,
            "stealth": stealth,
        }
    )


def retry_after_seconds(headers: Mapping[str, str]) -> Optional[float]:
    """Parse a ``Retry-After`` header (seconds form only). None if absent/bad.

    Honors a ``Retry-After`` header when present.
    """
    raw = headers.get("Retry-After")
    if not raw:
        return None
    try:
        val = float(raw)
    except (TypeError, ValueError):
        return None  # HTTP-date form unsupported; fall back to jittered backoff
    return val if val >= 0 else None


class _BaseClient:
    def __init__(
        self,
        api_key: Optional[str] = None,
        *,
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT,
        max_retries: int = DEFAULT_MAX_RETRIES,
    ) -> None:
        resolved = api_key if api_key is not None else os.environ.get("WEBSCRAPE_API_KEY")
        if not resolved:
            raise ConfigurationError(
                "No API key provided. Pass api_key=... to the client or set the "
                "WEBSCRAPE_API_KEY environment variable."
            )
        self._api_key: str = resolved
        self._base_url: str = (base_url or DEFAULT_BASE_URL).rstrip("/")
        self._timeout: float = timeout
        self._max_retries: int = max(0, int(max_retries))

    # --- pure helpers ------------------------------------------------------

    def _url(self, path: str) -> str:
        return f"{self._base_url}/{path.lstrip('/')}"

    def _request_headers(self) -> dict[str, str]:
        return {
            "X-API-Key": self._api_key,
            "User-Agent": USER_AGENT,
            "Accept": "application/json",
        }

    def _backoff(self, attempt: int, retry_after: Optional[float] = None) -> float:
        if retry_after is not None:
            return retry_after
        ceiling = min(RETRY_BASE_SECONDS * (2**attempt), RETRY_CAP_SECONDS)
        return random.uniform(0, ceiling)  # full jitter

    @staticmethod
    def _is_connect_failure(exc: Exception) -> bool:
        # Connection-establishment failures never reached the server, so retrying
        # them is always safe. Read/write timeouts and read errors are not retried.
        return isinstance(exc, (httpx.ConnectError, httpx.ConnectTimeout))

    @staticmethod
    def _wrap_transport(exc: Exception) -> TransportError:
        if isinstance(exc, httpx.TimeoutException):
            return APITimeoutError(f"request timed out: {exc}")
        return TransportError(f"transport error: {exc}")

    def _parse_envelope(self, response: httpx.Response) -> dict:
        """Return the success/queued envelope dict, or raise a typed APIError."""
        status_code = response.status_code
        header_request_id = response.headers.get("X-Request-ID")
        try:
            payload: Any = response.json()
        except Exception:
            payload = None

        if 200 <= status_code < 300:
            if isinstance(payload, dict) and payload.get("status") != "error":
                return payload
            # 2xx with an error envelope or a non-dict body: treat as an error.

        raise self._build_api_error(status_code, payload, header_request_id, response.headers)

    def _build_api_error(
        self,
        status_code: int,
        payload: Any,
        header_request_id: Optional[str],
        headers: Mapping[str, str],
    ) -> APIError:
        code: Optional[str] = None
        message: Optional[str] = None
        details: Any = None
        request_id: Optional[str] = header_request_id
        retry_after = retry_after_seconds(headers)

        if isinstance(payload, dict):
            rid = payload.get("request_id")
            if isinstance(rid, str):
                request_id = rid
            err = payload.get("error")
            if isinstance(err, dict):
                raw_code = err.get("code")
                code = raw_code if isinstance(raw_code, str) else None
                raw_msg = err.get("message")
                message = raw_msg if isinstance(raw_msg, str) else None
                details = err.get("details")
            elif isinstance(err, str):
                # Authentication failures may return a plain {"error": "<message>"}
                # body instead of the standard envelope; both shapes are handled.
                message = err
        elif isinstance(payload, str) and payload:
            message = payload

        if not code:
            code = default_code_for_status(status_code)
        if not message:
            message = f"HTTP {status_code}"

        return make_api_error(
            status_code=status_code,
            code=code,
            message=message,
            details=details,
            request_id=request_id,
            retry_after=retry_after,
        )
