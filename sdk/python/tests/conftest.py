"""Shared fixtures, payloads, and mock-transport helpers.

All tests run against ``httpx.MockTransport`` — never a live network. Sleeps
(retry backoff + poll intervals) are patched to do nothing so retry/wait tests
are instant.
"""

from __future__ import annotations

import asyncio
import time
from typing import Callable

import httpx
import pytest

import webscrape_ai

API_KEY = "wsg_live_testkey0000000000000000000000"

# --- canonical success/error payloads -------------------------------------

SCRAPE_OK = {
    "status": "completed",
    "data": {
        "request_id": "eng_scrape_uuid",
        "html": "# Example\n\nhello",
        "content_type": "html",
        "cleaned": True,
        "links": [{"url": "https://example.com/a", "text": "A"}],
        "metadata": {"title": "Example", "description": None, "language": "en"},
        "structured_data": {
            "json_ld": [{"@type": "Article"}],
            "microdata": [],
            "pagination": {"next": "https://example.com/p2", "prev": None},
        },
        "latency_ms": 123,
    },
    "credits_used": 1,
    "credits_remaining": 499,
    "request_id": "req_scrape01",
}

SMARTSCRAPER_OK = {
    "status": "completed",
    "data": {
        "request_id": "eng_ss_uuid",
        "result": {"stories": [{"title": "T", "url": "u", "score": 10}]},
        "latency_ms": 4200,
    },
    "credits_used": 5,
    "credits_remaining": 495,
    "request_id": "req_ss01",
}

DISPATCH_OK = {
    "status": "queued",
    "data": {
        "run_id": "k7Xb9dRmQ2p",
        "recipe_id": "m3Yc2tFvN8q",
        "run_status": "running",
        "poll_url": "/v1/smartbrowse/runs/k7Xb9dRmQ2p",
        "created_at": "2026-07-06T12:00:00Z",
    },
    "request_id": "req_dispatch01",
}


def _run_payload(run_status, **extra):
    data = {
        "id": "k7Xb9dRmQ2p",
        "recipe_id": "m3Yc2tFvN8q",
        "run_status": run_status,
        "pages_extracted": extra.get("pages_extracted", 0),
        "items_extracted": extra.get("items_extracted", 0),
        "credits_used": extra.get("credits_used", 0),
        "started_at": extra.get("started_at"),
        "completed_at": extra.get("completed_at"),
        "created_at": "2026-07-06T12:00:00Z",
    }
    if "error" in extra:
        data["error"] = extra["error"]
    if "result" in extra:
        data["result"] = extra["result"]
    return {
        "status": "completed",
        "data": data,
        "credits_used": 0,
        "credits_remaining": 400,
        "request_id": "req_run01",
    }


RUN_RUNNING = _run_payload("running", pages_extracted=1)
RUN_COMPLETED = _run_payload(
    "completed",
    pages_extracted=3,
    items_extracted=30,
    credits_used=6,
    completed_at="2026-07-06T12:02:00Z",
    result={
        "pages": [{"items": [{"title": "x"}]}],
        "mode": "recipe",
        "drift": 0.0,
        "warnings": [],
    },
)
RUN_FAILED = _run_payload("failed", error="selector drift too high")

USAGE_OK = {
    "status": "completed",
    "data": {
        "runs_used_30d": 12,
        "runs_per_month_cap": 50,
        "pages_per_run_cap": 20,
        "cost_per_page": 2,
        "schedules_count": 1,
        "schedules_allowed": True,
        "last_run": {
            "id": "k7Xb9dRmQ2p",
            "status": "completed",
            "pages_extracted": 3,
            "effective_cap": 20,
            "clamped_by_credits": False,
            "created_at": "2026-07-06T12:00:00Z",
            "completed_at": "2026-07-06T12:02:00Z",
        },
    },
    "credits_used": 0,
    "credits_remaining": 400,
    "request_id": "req_usage01",
}

ERR_INSUFFICIENT = {
    "status": "error",
    "error": {
        "code": "insufficient_credits",
        "message": "not enough credits to complete this request",
        "details": {"balance": 2, "required": 5},
    },
    "request_id": "req_err402",
}

ERR_NOT_FOUND = {
    "status": "error",
    "error": {"code": "not_found", "message": "recipe not found"},
    "request_id": "req_err404",
}

ERR_VALIDATION = {
    "status": "error",
    "error": {
        "code": "validation_failed",
        "message": "Output did not match the requested schema after one repair attempt.",
        "details": {"type": "schema_validation_error", "errors": ["bad field"]},
    },
    "request_id": "req_err422",
}

ERR_RATE_LIMITED_SB = {
    "status": "error",
    "error": {
        "code": "rate_limited",
        "message": "smartbrowse run quota exceeded for plan",
        "details": {"used": 50, "limit": 50, "window": "30d", "reason": "sb_runs_per_month"},
    },
    "request_id": "req_err429",
}

ERR_SERVICE_UNAVAILABLE = {
    "status": "error",
    "error": {"code": "service_unavailable", "message": "extraction service is unreachable"},
    "request_id": "req_err503",
}

ERR_BAD_REQUEST = {
    "status": "error",
    "error": {"code": "invalid_request", "message": "website_url is required"},
    "request_id": "req_err400",
}

ERR_UNKNOWN_CODE = {
    "status": "error",
    "error": {"code": "teapot_melted", "message": "brand new failure mode"},
    "request_id": "req_errX",
}

BARE_401 = {
    "error": "missing or invalid credentials — provide a session cookie or X-API-Key header"
}


# --- mock-transport plumbing ----------------------------------------------


def response(status_code: int, payload, *, request_id: str | None = "req_hdr", headers=None):
    hdrs = dict(headers or {})
    if request_id is not None:
        hdrs.setdefault("X-Request-ID", request_id)
    return httpx.Response(status_code, json=payload, headers=hdrs)


class Capture:
    """Records the most recent request(s) seen by a handler."""

    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []

    @property
    def last(self) -> httpx.Request:
        return self.requests[-1]

    def body(self):
        import json

        return json.loads(self.last.content) if self.last.content else None


def handler_from(fn: Callable[[httpx.Request], httpx.Response], cap: Capture):
    def _handler(request: httpx.Request) -> httpx.Response:
        request.read()
        cap.requests.append(request)
        return fn(request)
    return _handler


def make_client(handler, cap=None, **kwargs) -> tuple[webscrape_ai.Client, Capture]:
    cap = cap or Capture()
    transport = httpx.MockTransport(handler_from(handler, cap))
    http = httpx.Client(transport=transport)
    kwargs.setdefault("api_key", API_KEY)
    client = webscrape_ai.Client(http_client=http, **kwargs)
    return client, cap


def make_async_client(handler, cap=None, **kwargs) -> tuple[webscrape_ai.AsyncClient, Capture]:
    cap = cap or Capture()
    transport = httpx.MockTransport(handler_from(handler, cap))
    http = httpx.AsyncClient(transport=transport)
    kwargs.setdefault("api_key", API_KEY)
    client = webscrape_ai.AsyncClient(http_client=http, **kwargs)
    return client, cap


def static(status_code, payload, **kw):
    """A handler that always returns the same response."""
    return lambda request: response(status_code, payload, **kw)


def sequence(items):
    """A handler returning ``items[i]`` on the i-th call (clamped to the last).

    ``items`` is a list of ``(status_code, payload)`` tuples.
    """
    state = {"i": 0}

    def _handler(request):
        idx = min(state["i"], len(items) - 1)
        state["i"] += 1
        status_code, payload = items[idx]
        return response(status_code, payload)

    return _handler


def method_router(fn):
    """Wrap a ``(method, path) -> (status, payload)`` function as a handler."""

    def _handler(request):
        status_code, payload = fn(request.method, request.url.path)
        return response(status_code, payload)

    return _handler


@pytest.fixture(autouse=True)
def _no_sleep(monkeypatch):
    """Make retry backoff and poll intervals instantaneous."""
    monkeypatch.setattr(time, "sleep", lambda *a, **k: None)

    async def _async_noop(*a, **k):
        return None

    monkeypatch.setattr(asyncio, "sleep", _async_noop)
    yield
