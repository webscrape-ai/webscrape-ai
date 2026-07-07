"""Synchronous client — full test checklist."""

from __future__ import annotations

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
    ERR_SERVICE_UNAVAILABLE,
    ERR_UNKNOWN_CODE,
    ERR_VALIDATION,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_RUNNING,
    SCRAPE_OK,
    SMARTSCRAPER_OK,
    USAGE_OK,
    make_client,
    method_router,
    sequence,
    static,
)


# --- §6.1 happy path + headers/body + both request ids --------------------


def test_scrape_happy_path():
    client, cap = make_client(static(200, SCRAPE_OK))
    with client:
        resp = client.scrape(website_url="https://example.com", clean=True)

    req = cap.last
    assert req.method == "POST"
    assert req.url.path == "/v1/scrape"
    assert req.headers["X-API-Key"] == API_KEY
    assert req.headers["User-Agent"] == "webscrape-ai-python/0.1.0"
    assert req.headers["content-type"].startswith("application/json")
    assert cap.body() == {"website_url": "https://example.com", "clean": True}

    assert isinstance(resp, ws.ScrapeResponse)
    assert resp.request_id == "req_scrape01"          # envelope id
    assert resp.data.request_id == "eng_scrape_uuid"  # extraction id (distinct)
    assert resp.credits_used == 1
    assert resp.credits_remaining == 499
    assert resp.data.content_type == "html"
    assert resp.data.cleaned is True
    assert resp.data.links[0].url == "https://example.com/a"
    assert resp.data.metadata.title == "Example"
    assert resp.data.metadata.description is None
    assert resp.data.structured_data.json_ld == [{"@type": "Article"}]
    assert resp.data.structured_data.pagination.next == "https://example.com/p2"
    assert resp.data.latency_ms == 123


def test_smartscraper_happy_path():
    client, cap = make_client(static(200, SMARTSCRAPER_OK))
    with client:
        resp = client.smartscraper(
            website_url="https://news.ycombinator.com",
            user_prompt="Extract stories.",
            output_schema={"type": "object"},
        )

    req = cap.last
    assert req.method == "POST"
    assert req.url.path == "/v1/smartscraper"
    assert req.headers["X-API-Key"] == API_KEY
    body = cap.body()
    assert body["website_url"] == "https://news.ycombinator.com"
    assert body["user_prompt"] == "Extract stories."   # field is user_prompt, not prompt
    assert body["output_schema"] == {"type": "object"}

    assert resp.request_id == "req_ss01"
    assert resp.data.request_id == "eng_ss_uuid"
    assert resp.data.result == {"stories": [{"title": "T", "url": "u", "score": 10}]}
    assert resp.credits_used == 5


def test_smartbrowse_dispatch_happy_path():
    client, cap = make_client(static(202, DISPATCH_OK))
    with client:
        dispatch = client.smartbrowse.run("m3Yc2tFvN8q")

    req = cap.last
    assert req.method == "POST"
    assert req.url.path == "/v1/smartbrowse/recipes/m3Yc2tFvN8q/run"
    assert req.content == b""  # no request body on dispatch

    assert isinstance(dispatch, ws.SmartBrowseRunDispatch)
    assert dispatch.request_id == "req_dispatch01"
    assert dispatch.data.run_id == "k7Xb9dRmQ2p"
    assert dispatch.data.run_status == ws.RunStatus.RUNNING


def test_smartbrowse_get_run_happy_path():
    client, cap = make_client(static(200, RUN_COMPLETED))
    with client:
        run = client.smartbrowse.get_run("k7Xb9dRmQ2p")

    assert cap.last.method == "GET"
    assert cap.last.url.path == "/v1/smartbrowse/runs/k7Xb9dRmQ2p"
    assert run.credits_used == 0                       # envelope: polling is free
    assert run.data.credits_used == 6                  # run's accrued spend
    assert run.data.run_status == ws.RunStatus.COMPLETED
    assert run.data.pages_extracted == 3
    assert run.data.result.pages[0].items == [{"title": "x"}]
    assert run.data.result.mode == "recipe"


def test_smartbrowse_usage_happy_path():
    client, cap = make_client(static(200, USAGE_OK))
    with client:
        usage = client.smartbrowse.usage()

    assert cap.last.method == "GET"
    assert cap.last.url.path == "/v1/smartbrowse/usage"
    assert usage.credits_used == 0
    assert usage.data.runs_used_30d == 12
    assert usage.data.runs_per_month_cap == 50
    assert usage.data.cost_per_page == 2
    assert usage.data.schedules_allowed is True
    assert usage.data.last_run.status == ws.RunStatus.COMPLETED
    assert usage.data.last_run.clamped_by_credits is False


# --- §6.2 optional-field serialization ------------------------------------


def test_untouched_options_absent_from_body():
    client, cap = make_client(static(200, SCRAPE_OK))
    with client:
        client.scrape(website_url="https://example.com", clean=True)
    # tag_truncate defaults true server-side but must NOT be sent; likewise
    # parse_mode/stealth/etc. Only what the caller touched appears.
    assert cap.body() == {"website_url": "https://example.com", "clean": True}


def test_smartscraper_minimal_body():
    client, cap = make_client(static(200, SMARTSCRAPER_OK))
    with client:
        client.smartscraper(website_url="https://x.com", user_prompt="hi")
    assert cap.body() == {"website_url": "https://x.com", "user_prompt": "hi"}


def test_explicit_false_is_sent():
    # A boolean the caller explicitly set (even to False) IS transmitted.
    client, cap = make_client(static(200, SCRAPE_OK))
    with client:
        client.scrape(website_url="https://example.com", stealth=False, max_age=0)
    assert cap.body() == {
        "website_url": "https://example.com",
        "stealth": False,
        "max_age": 0,
    }


# --- §6.3 error envelope → typed error ------------------------------------


def test_insufficient_credits_error():
    client, _ = make_client(static(402, ERR_INSUFFICIENT))
    with client, pytest.raises(ws.InsufficientCreditsError) as ei:
        client.scrape(website_url="https://example.com")
    err = ei.value
    assert err.status_code == 402
    assert err.code == "insufficient_credits"
    assert err.balance == 2
    assert err.required == 5
    assert err.request_id == "req_err402"


def test_not_found_error():
    client, _ = make_client(static(404, ERR_NOT_FOUND))
    with client, pytest.raises(ws.NotFoundError) as ei:
        client.smartbrowse.get_run("missing")
    assert ei.value.code == "not_found"
    assert ei.value.status_code == 404


def test_validation_error():
    client, _ = make_client(static(422, ERR_VALIDATION))
    with client, pytest.raises(ws.ValidationError) as ei:
        client.smartscraper(website_url="https://x.com", user_prompt="p")
    assert ei.value.code == "validation_failed"
    assert ei.value.details["type"] == "schema_validation_error"


def test_rate_limited_error_reason():
    client, _ = make_client(static(429, ERR_RATE_LIMITED_SB))
    with client, pytest.raises(ws.RateLimitError) as ei:
        client.smartbrowse.run("m3Yc2tFvN8q")
    assert ei.value.reason == "sb_runs_per_month"
    assert ei.value.status_code == 429


# --- §6.4 bare 401 --------------------------------------------------------


def test_bare_401_becomes_authentication_error():
    # No request_id in the body → falls back to X-Request-ID header.
    client, _ = make_client(static(401, BARE_401, request_id="req_hdr401"))
    with client, pytest.raises(ws.AuthenticationError) as ei:
        client.scrape(website_url="https://example.com")
    err = ei.value
    assert err.code == "unauthorized"
    assert "missing or invalid credentials" in err.message
    assert err.request_id == "req_hdr401"


# --- §6.5 unknown code → generic APIError ---------------------------------


def test_unknown_code_is_generic_api_error():
    client, _ = make_client(static(400, ERR_UNKNOWN_CODE))
    with client, pytest.raises(ws.APIError) as ei:
        client.scrape(website_url="https://example.com")
    err = ei.value
    assert type(err) is ws.APIError                 # not a specialized subtype
    assert err.code == "teapot_melted"              # raw code preserved
    assert not isinstance(err, ws.BadRequestError)


# --- §6.6 retry policy ----------------------------------------------------


def test_retry_429_then_200_succeeds():
    client, cap = make_client(
        sequence([(429, ERR_RATE_LIMITED_SB), (200, SCRAPE_OK)]), max_retries=2
    )
    with client:
        resp = client.scrape(website_url="https://example.com")
    assert resp.credits_used == 1
    assert len(cap.requests) == 2                   # one retry


def test_no_retry_when_disabled():
    client, cap = make_client(
        sequence([(429, ERR_RATE_LIMITED_SB), (200, SCRAPE_OK)]), max_retries=0
    )
    with client, pytest.raises(ws.RateLimitError):
        client.scrape(website_url="https://example.com")
    assert len(cap.requests) == 1                   # surfaced immediately


def test_400_is_not_retried():
    client, cap = make_client(
        sequence([(400, ERR_BAD_REQUEST), (200, SCRAPE_OK)]), max_retries=2
    )
    with client, pytest.raises(ws.BadRequestError):
        client.scrape(website_url="https://example.com")
    assert len(cap.requests) == 1


def test_retry_exhausted_surfaces_last_error():
    client, cap = make_client(static(503, ERR_SERVICE_UNAVAILABLE), max_retries=2)
    with client, pytest.raises(ws.ServerError):
        client.scrape(website_url="https://example.com")
    assert len(cap.requests) == 3                   # initial + 2 retries


# --- §6.7 wait_for_run ----------------------------------------------------


def test_wait_for_run_completes():
    handler = sequence([(200, RUN_RUNNING), (200, RUN_RUNNING), (200, RUN_COMPLETED)])
    client, cap = make_client(handler)
    with client:
        run = client.smartbrowse.wait_for_run("k7Xb9dRmQ2p", poll_interval=0.0)
    assert run.data.run_status == ws.RunStatus.COMPLETED
    assert run.data.pages_extracted == 3
    assert len(cap.requests) == 3


def test_wait_for_run_failed_raises():
    handler = sequence([(200, RUN_RUNNING), (200, RUN_FAILED)])
    client, _ = make_client(handler)
    with client, pytest.raises(ws.RunFailedError) as ei:
        client.smartbrowse.wait_for_run("k7Xb9dRmQ2p", poll_interval=0.0)
    assert ei.value.run.data.run_status == ws.RunStatus.FAILED
    assert ei.value.run.data.error == "selector drift too high"


def test_wait_for_run_deadline_raises():
    client, _ = make_client(static(200, RUN_RUNNING))
    with client, pytest.raises(ws.WaitTimeoutError) as ei:
        client.smartbrowse.wait_for_run("k7Xb9dRmQ2p", poll_interval=0.0, timeout=0.0)
    assert ei.value.run.data.run_status == ws.RunStatus.RUNNING


def test_run_and_wait():
    def routes(method, path):
        if method == "POST" and path.endswith("/run"):
            return (202, DISPATCH_OK)
        return (200, RUN_COMPLETED)

    client, cap = make_client(method_router(routes))
    with client:
        run = client.smartbrowse.run_and_wait("m3Yc2tFvN8q", poll_interval=0.0)
    assert run.data.run_status == ws.RunStatus.COMPLETED
    assert cap.requests[0].method == "POST"
    assert cap.requests[1].method == "GET"


# --- §6.8 config: env-var pickup + missing key ----------------------------


def test_env_var_key_pickup(monkeypatch):
    monkeypatch.setenv("WEBSCRAPE_API_KEY", "wsg_live_fromenv")
    import httpx

    cap_holder = {}

    def handler(request):
        cap_holder["key"] = request.headers.get("X-API-Key")
        from conftest import response

        return response(200, SCRAPE_OK)

    http = httpx.Client(transport=httpx.MockTransport(handler))
    with ws.Client(http_client=http) as client:  # no explicit api_key
        client.scrape(website_url="https://example.com")
    assert cap_holder["key"] == "wsg_live_fromenv"


def test_missing_key_raises_configuration_error(monkeypatch):
    monkeypatch.delenv("WEBSCRAPE_API_KEY", raising=False)
    with pytest.raises(ws.ConfigurationError):
        ws.Client()


# --- extras: transport errors + unknown run_status -----------------------


def test_connection_error_wrapped_and_retried():
    import httpx

    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            raise httpx.ConnectError("connection refused")
        from conftest import response

        return response(200, SCRAPE_OK)

    http = httpx.Client(transport=httpx.MockTransport(handler))
    with ws.Client(api_key=API_KEY, http_client=http, max_retries=2) as client:
        resp = client.scrape(website_url="https://example.com")
    assert resp.credits_used == 1
    assert calls["n"] == 2                          # connect failure retried


def test_read_timeout_not_retried_and_wrapped():
    import httpx

    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        raise httpx.ReadTimeout("read timed out")

    http = httpx.Client(transport=httpx.MockTransport(handler))
    with ws.Client(api_key=API_KEY, http_client=http, max_retries=2) as client:
        with pytest.raises(ws.APITimeoutError):
            client.scrape(website_url="https://example.com")
    assert calls["n"] == 1                          # timeout NOT retried (may be billed)


def test_unknown_run_status_tolerated():
    from conftest import _run_payload

    payload = _run_payload("paused_by_admin")       # not in the enum
    client, _ = make_client(static(200, payload))
    with client:
        run = client.smartbrowse.get_run("k7Xb9dRmQ2p")
    assert run.data.run_status == ws.RunStatus.UNKNOWN


def test_base_url_trailing_slash_normalized():
    client, cap = make_client(static(200, SCRAPE_OK), base_url="https://api.example.com/v1/")
    with client:
        client.scrape(website_url="https://example.com")
    assert str(cap.last.url) == "https://api.example.com/v1/scrape"
