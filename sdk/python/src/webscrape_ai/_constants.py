"""Internal constants shared by the sync and async clients."""

from __future__ import annotations

from ._version import __version__

# Default production base URL. Overridable via the client constructor for
# self-hosted / staging deployments. Values with or without a trailing slash
# are accepted (the client strips it).
DEFAULT_BASE_URL = "https://api.webscrape.ai/v1"

# Per-request timeout. smartscraper + stealth fetches can be slow, hence 180s.
DEFAULT_TIMEOUT = 180.0

# Up to (max_retries + 1) attempts. 0 disables retries.
DEFAULT_MAX_RETRIES = 2

USER_AGENT = f"webscrape-ai-python/{__version__}"

# Retryable HTTP statuses. Failed and rate-limited requests are never charged,
# so retrying them is always safe.
RETRYABLE_STATUS = frozenset({429, 500, 502, 503})

# Exponential backoff with full jitter: delay = uniform(0, min(base * 2**n, cap)).
RETRY_BASE_SECONDS = 1.0
RETRY_CAP_SECONDS = 30.0
