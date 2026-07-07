"""Asynchronous client — full test checklist (mirrors test_sync.py)."""

from __future__ import annotations

import httpx
import pytest

import webscrape_ai as ws
from conftest import (
    API_KEY,
    BARE_401,
    DISPATCH_OK,
    ERR_BAD_REQUEST,
    ERR_INSUFFICIENT,
    ERR_NOT_FOUND,
    ERR_RATE_LIMITED_SB,
    ERR_UNKNOWN_CODE,
    ERR_VALIDATION,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_RUNNING,
    SCRAPE_OK,
    SMARTSCRAPER_OK,
    USAGE_OK,
    make_async_client,
    method_router,
    response,
    sequence,
    static,
)


# --- §6.1 happy path for each of the five endpoints -----------------------


async def test_scrape_happy_path():
    client, cap = make_async_client(static(200, SCRAPE_OK))
    async with client:
        resp = await client.scrape(website_url="https://example.com", clean=True)

    req = cap.last
    assert req.method == "POST"
    assert req.url.path == "/v1/scrape"
    assert req.headers["X-API-Key"] == API_KEY
    assert req.headers["User-Agent"] == "webscrape-ai-python/0.1.0"
    assert cap.body() == {"website_url": "https://example.com", "clean": True}

    assert resp.request_id == "req_scrape01"
    assert resp.data.request_id == "eng_scrape_uuid"
    assert resp.credits_used == 1
    assert resp.data.links[0].text == "A"


async def test_smartscraper_happy_path():
    client, cap = make_async_client(static(200, SMARTSCRAPER_OK))
    async with client:
        resp = await client.smartscraper(
            website_url="https://news.ycombinator.com", user_prompt="Extract stories."
        )
    assert cap.last.url.path == "/v1/smartscraper"
    assert cap.body()["user_prompt"] == "Extract stories."
    assert resp.data.result["stories"][0]["score"] == 10


async def test_dispatch_happy_path():
    client, cap = make_async_client(static(202, DISPATCH_OK))
    async with client:
        dispatch = await client.smartbrowse.run("m3Yc2tFvN8q")
    assert cap.last.method == "POST"
    assert cap.last.url.path == "/v1/smartbrowse/recipes/m3Yc2tFvN8q/run"
    assert cap.last.content == b""
    assert dispatch.data.run_id == "k7Xb9dRmQ2p"
    assert dispatch.data.run_status == ws.RunStatus.RUNNING


async def test_get_run_happy_path():
    client, cap = make_async_client(static(200, RUN_COMPLETED))
    async with client:
        run = await client.smartbrowse.get_run("k7Xb9dRmQ2p")
    assert cap.last.method == "GET"
    assert run.credits_used == 0
    assert run.data.credits_used == 6
    assert run.data.run_status == ws.RunStatus.COMPLETED


async def test_usage_happy_path():
    client, cap = make_async_client(static(200, USAGE_OK))
    async with client:
        usage = await client.smartbrowse.usage()
    assert cap.last.url.path == "/v1/smartbrowse/usage"
    assert usage.data.runs_per_month_cap == 50
    assert usage.data.last_run.effective_cap == 20


# --- §6.2 optional-field serialization ------------------------------------


async def test_untouched_options_absent():
    client, cap = make_async_client(static(200, SCRAPE_OK))
    async with client:
        await client.scrape(website_url="https://example.com", clean=True)
    assert cap.body() == {"website_url": "https://example.com", "clean": True}


# --- §6.3 error envelope → typed error ------------------------------------


async def test_insufficient_credits():
    client, _ = make_async_client(static(402, ERR_INSUFFICIENT))
    async with client:
        with pytest.raises(ws.InsufficientCreditsError) as ei:
            await client.scrape(website_url="https://example.com")
    assert ei.value.balance == 2
    assert ei.value.required == 5


async def test_not_found():
    client, _ = make_async_client(static(404, ERR_NOT_FOUND))
    async with client:
        with pytest.raises(ws.NotFoundError):
            await client.smartbrowse.get_run("missing")


async def test_validation_failed():
    client, _ = make_async_client(static(422, ERR_VALIDATION))
    async with client:
        with pytest.raises(ws.ValidationError) as ei:
            await client.smartscraper(website_url="https://x.com", user_prompt="p")
    assert ei.value.details["type"] == "schema_validation_error"


async def test_rate_limited_reason():
    client, _ = make_async_client(static(429, ERR_RATE_LIMITED_SB))
    async with client:
        with pytest.raises(ws.RateLimitError) as ei:
            await client.smartbrowse.run("m3Yc2tFvN8q")
    assert ei.value.reason == "sb_runs_per_month"


# --- §6.4 bare 401 --------------------------------------------------------


async def test_bare_401():
    client, _ = make_async_client(static(401, BARE_401, request_id="req_hdr401"))
    async with client:
        with pytest.raises(ws.AuthenticationError) as ei:
            await client.scrape(website_url="https://example.com")
    assert ei.value.code == "unauthorized"
    assert ei.value.request_id == "req_hdr401"


# --- §6.5 unknown code ----------------------------------------------------


async def test_unknown_code_generic():
    client, _ = make_async_client(static(400, ERR_UNKNOWN_CODE))
    async with client:
        with pytest.raises(ws.APIError) as ei:
            await client.scrape(website_url="https://example.com")
    assert type(ei.value) is ws.APIError
    assert ei.value.code == "teapot_melted"


# --- §6.6 retry -----------------------------------------------------------


async def test_retry_429_then_200():
    client, cap = make_async_client(
        sequence([(429, ERR_RATE_LIMITED_SB), (200, SCRAPE_OK)]), max_retries=2
    )
    async with client:
        resp = await client.scrape(website_url="https://example.com")
    assert resp.credits_used == 1
    assert len(cap.requests) == 2


async def test_no_retry_when_disabled():
    client, cap = make_async_client(
        sequence([(429, ERR_RATE_LIMITED_SB), (200, SCRAPE_OK)]), max_retries=0
    )
    async with client:
        with pytest.raises(ws.RateLimitError):
            await client.scrape(website_url="https://example.com")
    assert len(cap.requests) == 1


async def test_400_not_retried():
    client, cap = make_async_client(
        sequence([(400, ERR_BAD_REQUEST), (200, SCRAPE_OK)]), max_retries=2
    )
    async with client:
        with pytest.raises(ws.BadRequestError):
            await client.scrape(website_url="https://example.com")
    assert len(cap.requests) == 1


# --- §6.7 wait_for_run ----------------------------------------------------


async def test_wait_for_run_completes():
    handler = sequence([(200, RUN_RUNNING), (200, RUN_RUNNING), (200, RUN_COMPLETED)])
    client, cap = make_async_client(handler)
    async with client:
        run = await client.smartbrowse.wait_for_run("k7Xb9dRmQ2p", poll_interval=0.0)
    assert run.data.run_status == ws.RunStatus.COMPLETED
    assert len(cap.requests) == 3


async def test_wait_for_run_failed():
    handler = sequence([(200, RUN_RUNNING), (200, RUN_FAILED)])
    client, _ = make_async_client(handler)
    async with client:
        with pytest.raises(ws.RunFailedError) as ei:
            await client.smartbrowse.wait_for_run("k7Xb9dRmQ2p", poll_interval=0.0)
    assert ei.value.run.data.error == "selector drift too high"


async def test_wait_for_run_deadline():
    client, _ = make_async_client(static(200, RUN_RUNNING))
    async with client:
        with pytest.raises(ws.WaitTimeoutError) as ei:
            await client.smartbrowse.wait_for_run(
                "k7Xb9dRmQ2p", poll_interval=0.0, timeout=0.0
            )
    assert ei.value.run.data.run_status == ws.RunStatus.RUNNING


async def test_run_and_wait():
    def routes(method, path):
        if method == "POST" and path.endswith("/run"):
            return (202, DISPATCH_OK)
        return (200, RUN_COMPLETED)

    client, cap = make_async_client(method_router(routes))
    async with client:
        run = await client.smartbrowse.run_and_wait("m3Yc2tFvN8q", poll_interval=0.0)
    assert run.data.run_status == ws.RunStatus.COMPLETED
    assert cap.requests[0].method == "POST"
    assert cap.requests[1].method == "GET"


# --- §6.8 config: env-var pickup + missing key ----------------------------


async def test_env_var_pickup(monkeypatch):
    monkeypatch.setenv("WEBSCRAPE_API_KEY", "wsg_live_fromenv")
    seen = {}

    def handler(request):
        seen["key"] = request.headers.get("X-API-Key")
        return response(200, SCRAPE_OK)

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    async with ws.AsyncClient(http_client=http) as client:
        await client.scrape(website_url="https://example.com")
    assert seen["key"] == "wsg_live_fromenv"


def test_missing_key_raises(monkeypatch):
    monkeypatch.delenv("WEBSCRAPE_API_KEY", raising=False)
    with pytest.raises(ws.ConfigurationError):
        ws.AsyncClient()


# --- extras: transport error handling -------------------------------------


async def test_connect_error_retried():
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            raise httpx.ConnectError("refused")
        return response(200, SCRAPE_OK)

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    async with ws.AsyncClient(api_key=API_KEY, http_client=http, max_retries=2) as client:
        resp = await client.scrape(website_url="https://example.com")
    assert resp.credits_used == 1
    assert calls["n"] == 2


async def test_read_timeout_wrapped():
    def handler(request):
        raise httpx.ReadTimeout("slow")

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    async with ws.AsyncClient(api_key=API_KEY, http_client=http, max_retries=2) as client:
        with pytest.raises(ws.APITimeoutError):
            await client.scrape(website_url="https://example.com")
