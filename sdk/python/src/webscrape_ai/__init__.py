"""Official Python SDK for the webscrape.ai API.

Quickstart::

    from webscrape_ai import Client

    with Client(api_key="wsg_live_...") as client:
        resp = client.scrape(website_url="https://example.com", clean=True)
        print(resp.data.html)

See https://webscrape.ai/docs for API documentation.
"""

from __future__ import annotations

from ._version import __version__
from .async_client import AsyncClient
from .client import Client
from .errors import (
    APIError,
    APITimeoutError,
    AuthenticationError,
    BadRequestError,
    ConfigurationError,
    ConflictError,
    EmailVerificationError,
    ForbiddenError,
    InsufficientCreditsError,
    NotFoundError,
    RateLimitError,
    RunFailedError,
    ServerError,
    TransportError,
    ValidationError,
    WaitTimeoutError,
    WebscrapeError,
)
from .models import (
    LastRun,
    LinkInfo,
    PageMetadata,
    Pagination,
    RunPage,
    RunResult,
    RunStatus,
    ScrapeData,
    ScrapeResponse,
    SmartBrowseDispatchData,
    SmartBrowseRunData,
    SmartBrowseRunDispatch,
    SmartBrowseRunResponse,
    SmartBrowseUsageData,
    SmartBrowseUsageResponse,
    SmartScraperData,
    SmartScraperResponse,
    StructuredData,
)

__all__ = [
    "__version__",
    # clients
    "Client",
    "AsyncClient",
    # errors
    "WebscrapeError",
    "ConfigurationError",
    "TransportError",
    "APITimeoutError",
    "APIError",
    "AuthenticationError",
    "InsufficientCreditsError",
    "EmailVerificationError",
    "ForbiddenError",
    "NotFoundError",
    "ConflictError",
    "BadRequestError",
    "ValidationError",
    "RateLimitError",
    "ServerError",
    "RunFailedError",
    "WaitTimeoutError",
    # models
    "RunStatus",
    "LinkInfo",
    "PageMetadata",
    "Pagination",
    "StructuredData",
    "ScrapeData",
    "ScrapeResponse",
    "SmartScraperData",
    "SmartScraperResponse",
    "SmartBrowseDispatchData",
    "SmartBrowseRunDispatch",
    "RunPage",
    "RunResult",
    "SmartBrowseRunData",
    "SmartBrowseRunResponse",
    "LastRun",
    "SmartBrowseUsageData",
    "SmartBrowseUsageResponse",
]
