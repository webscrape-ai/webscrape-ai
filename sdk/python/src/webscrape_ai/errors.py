"""Exception hierarchy for the webscrape.ai SDK.

Rooted at :class:`WebscrapeError`. HTTP error envelopes are mapped to typed
:class:`APIError` subtypes keyed on the stable ``error.code`` string; unknown
codes fall back to the generic :class:`APIError` with the raw code preserved.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any, Optional

if TYPE_CHECKING:  # avoid a runtime import cycle with models.py
    from .models import SmartBrowseRunResponse


class WebscrapeError(Exception):
    """Base class for every error raised by this SDK."""


class ConfigurationError(WebscrapeError):
    """The client was constructed incorrectly (e.g. no API key available)."""


class TransportError(WebscrapeError):
    """A network/transport failure before a usable HTTP response was received.

    Wraps the underlying ``httpx`` exception (available via ``__cause__``).
    """


class APITimeoutError(TransportError):
    """The request exceeded the configured timeout."""


class APIError(WebscrapeError):
    """An error envelope returned by the API.

    Also the generic type used for unknown ``error.code`` values — the raw
    ``code`` string is always preserved so callers can branch on new codes that
    ship before an SDK release.
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: Optional[int] = None,
        code: Optional[str] = None,
        details: Any = None,
        request_id: Optional[str] = None,
        retry_after: Optional[float] = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = details
        self.request_id = request_id
        self.retry_after = retry_after

    def __str__(self) -> str:
        prefix = []
        if self.status_code is not None:
            prefix.append(f"HTTP {self.status_code}")
        if self.code:
            prefix.append(self.code)
        head = " ".join(prefix)
        return f"{head}: {self.message}" if head else str(self.message)


class AuthenticationError(APIError):
    """``unauthorized`` — missing, invalid, or revoked API key (401)."""


class InsufficientCreditsError(APIError):
    """``insufficient_credits`` — wallet can't cover the request (402)."""

    @property
    def balance(self) -> Optional[int]:
        return self.details.get("balance") if isinstance(self.details, dict) else None

    @property
    def required(self) -> Optional[int]:
        return self.details.get("required") if isinstance(self.details, dict) else None


class EmailVerificationError(APIError):
    """``email_verification_required`` — verify the account email first (402)."""


class ForbiddenError(APIError):
    """``forbidden`` — authenticated but not allowed (403)."""


class NotFoundError(APIError):
    """``not_found`` — resource missing or owned by another user (404)."""


class ConflictError(APIError):
    """``conflict`` / ``account_deletion_pending`` — state disallows action (409)."""

    @property
    def deletion_scheduled_for(self) -> Optional[str]:
        if isinstance(self.details, dict):
            return self.details.get("deletion_scheduled_for")
        return None


class BadRequestError(APIError):
    """``invalid_request`` — malformed request (400)."""


class ValidationError(APIError):
    """``validation_failed`` — extraction output failed schema validation (422)."""


class RateLimitError(APIError):
    """``rate_limited`` — a per-plan throttle was hit (429)."""

    @property
    def reason(self) -> Optional[str]:
        """One of ``rate_limit_per_min`` / ``max_concurrent_requests`` /
        ``sb_runs_per_month`` when the server supplied it."""
        return self.details.get("reason") if isinstance(self.details, dict) else None


class ServerError(APIError):
    """``internal_error`` / ``service_unavailable`` — server-side failure (5xx)."""


class RunFailedError(WebscrapeError):
    """A SmartBrowse run reached a terminal ``failed`` / ``cancelled`` state.

    Carries the full run response so ``error``, ``pages_extracted``, and
    ``credits_used`` remain inspectable.
    """

    def __init__(self, run: "SmartBrowseRunResponse", message: Optional[str] = None) -> None:
        self.run = run
        data = getattr(run, "data", None)
        status = getattr(data, "run_status", None)
        run_error = getattr(data, "error", None)
        run_id = getattr(data, "id", None)
        if message is None:
            message = f"SmartBrowse run {run_id} ended with status {status}"
            if run_error:
                message += f": {run_error}"
        super().__init__(message)


class WaitTimeoutError(WebscrapeError):
    """``wait_for_run`` exceeded its deadline before the run became terminal.

    Carries the last-seen run response (may be ``None`` if none was fetched).
    """

    def __init__(
        self,
        run: "Optional[SmartBrowseRunResponse]" = None,
        message: Optional[str] = None,
    ) -> None:
        self.run = run
        super().__init__(message or "timed out waiting for the SmartBrowse run to finish")


# --- code → exception mapping ---------------------------------------------

_CODE_TO_EXC: dict[str, type[APIError]] = {
    "invalid_request": BadRequestError,
    "unauthorized": AuthenticationError,
    "insufficient_credits": InsufficientCreditsError,
    "email_verification_required": EmailVerificationError,
    "forbidden": ForbiddenError,
    "not_found": NotFoundError,
    "conflict": ConflictError,
    "account_deletion_pending": ConflictError,
    "validation_failed": ValidationError,
    "rate_limited": RateLimitError,
    "internal_error": ServerError,
    "service_unavailable": ServerError,
}

_STATUS_DEFAULT_CODE: dict[int, str] = {
    400: "invalid_request",
    401: "unauthorized",
    402: "insufficient_credits",
    403: "forbidden",
    404: "not_found",
    409: "conflict",
    422: "validation_failed",
    429: "rate_limited",
    500: "internal_error",
    502: "service_unavailable",
    503: "service_unavailable",
}


def default_code_for_status(status_code: int) -> str:
    """Synthesize a stable code when the server omitted one (e.g. bare 401)."""
    code = _STATUS_DEFAULT_CODE.get(status_code)
    if code:
        return code
    return "internal_error" if status_code >= 500 else "invalid_request"


def make_api_error(
    *,
    status_code: int,
    code: str,
    message: str,
    details: Any = None,
    request_id: Optional[str] = None,
    retry_after: Optional[float] = None,
) -> APIError:
    """Build the most specific :class:`APIError` subtype for ``code``.

    Unknown codes map to the generic :class:`APIError`.
    """
    cls = _CODE_TO_EXC.get(code, APIError)
    return cls(
        message,
        status_code=status_code,
        code=code,
        details=details,
        request_id=request_id,
        retry_after=retry_after,
    )
