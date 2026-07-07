"""Defensive model parsing."""

from __future__ import annotations

import dataclasses

import webscrape_ai as ws
from webscrape_ai.models import ScrapeResponse, SmartBrowseUsageData, RunStatus


def test_unknown_keys_ignored_and_missing_become_none():
    env = {
        "status": "completed",
        "request_id": "req_x",
        "credits_used": 1,
        "credits_remaining": 2,
        "data": {
            "request_id": "eng_x",
            "html": "<p>hi</p>",
            # content_type, cleaned, links, metadata, structured_data, latency_ms all absent
            "brand_new_field": {"nested": True},  # unknown → ignored, no crash
        },
    }
    resp = ScrapeResponse.from_envelope(env)
    assert resp.data.html == "<p>hi</p>"
    assert resp.data.content_type is None
    assert resp.data.links is None
    assert resp.data.metadata is None
    assert resp.data.structured_data is None
    assert resp.data.latency_ms is None


def test_explicit_nulls_tolerated():
    env = {
        "status": "completed",
        "request_id": "req_x",
        "credits_used": None,
        "credits_remaining": None,
        "data": {
            "request_id": "eng_x",
            "html": None,
            "metadata": None,     # response may contain an explicit null
            "latency_ms": None,
        },
    }
    resp = ScrapeResponse.from_envelope(env)
    assert resp.credits_used is None
    assert resp.data.html is None
    assert resp.data.metadata is None


def test_last_run_null():
    data = SmartBrowseUsageData.from_dict(
        {
            "runs_used_30d": 0,
            "runs_per_month_cap": 10,
            "pages_per_run_cap": 5,
            "cost_per_page": 2,
            "schedules_count": 0,
            "schedules_allowed": False,
            "last_run": None,
        }
    )
    assert data.last_run is None
    assert data.schedules_allowed is False


def test_run_status_enum_equals_wire_string():
    assert RunStatus.COMPLETED == "completed"
    assert RunStatus("running") is RunStatus.RUNNING
    assert RunStatus("something-new") is RunStatus.UNKNOWN


def test_response_models_are_frozen():
    resp = ScrapeResponse.from_envelope(
        {"status": "completed", "request_id": "r", "data": {"request_id": "e"}}
    )
    assert dataclasses.is_dataclass(resp)
    try:
        resp.request_id = "mutated"  # type: ignore[misc]
    except dataclasses.FrozenInstanceError:
        pass
    else:  # pragma: no cover
        raise AssertionError("expected frozen dataclass")


def test_version_single_source():
    assert ws.__version__ == "0.1.0"
