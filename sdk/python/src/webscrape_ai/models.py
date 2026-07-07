"""Response models — frozen dataclasses parsed defensively from JSON.

Every ``from_*`` constructor ignores unknown keys and maps missing keys to
``None``, so the models tolerate server-side additions and explicit ``null``
values for absent response fields.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Mapping, Optional


class RunStatus(str, Enum):
    """SmartBrowse run lifecycle state.

    Unknown-tolerant: any value the server sends that isn't recognized parses
    to :attr:`UNKNOWN`. Because it subclasses ``str``, members compare equal
    to their wire string (``RunStatus.COMPLETED == "completed"``).
    """

    QUEUED = "queued"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    UNKNOWN = "unknown"

    @classmethod
    def _missing_(cls, value: object) -> "RunStatus":
        return cls.UNKNOWN


def _parse_run_status(value: Any) -> Optional[RunStatus]:
    if not isinstance(value, str):
        return None
    return RunStatus(value)


def _as_dict(value: Any) -> Optional[dict]:
    return value if isinstance(value, dict) else None


# --- /scrape ---------------------------------------------------------------


@dataclass(frozen=True)
class LinkInfo:
    url: Optional[str]
    text: Optional[str]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "LinkInfo":
        return cls(url=d.get("url"), text=d.get("text"))


@dataclass(frozen=True)
class PageMetadata:
    title: Optional[str]
    description: Optional[str]
    language: Optional[str]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "PageMetadata":
        return cls(
            title=d.get("title"),
            description=d.get("description"),
            language=d.get("language"),
        )


@dataclass(frozen=True)
class Pagination:
    next: Optional[str]
    prev: Optional[str]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "Pagination":
        return cls(next=d.get("next"), prev=d.get("prev"))


@dataclass(frozen=True)
class StructuredData:
    """Structured data extracted from the page (JSON-LD, microdata, pagination
    hints), when available."""

    json_ld: Optional[list]
    microdata: Optional[list]
    pagination: Optional[Pagination]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "StructuredData":
        jl = d.get("json_ld")
        md = d.get("microdata")
        pg = _as_dict(d.get("pagination"))
        return cls(
            json_ld=jl if isinstance(jl, list) else None,
            microdata=md if isinstance(md, list) else None,
            pagination=Pagination.from_dict(pg) if pg is not None else None,
        )


@dataclass(frozen=True)
class ScrapeData:
    request_id: Optional[str]  # extraction id (distinct from the envelope request_id)
    html: Optional[str]
    content_type: Optional[str]
    cleaned: Optional[bool]
    links: Optional[list[LinkInfo]]
    metadata: Optional[PageMetadata]
    structured_data: Optional[StructuredData]
    latency_ms: Optional[int]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "ScrapeData":
        raw_links = d.get("links")
        links = (
            [LinkInfo.from_dict(x) for x in raw_links if isinstance(x, Mapping)]
            if isinstance(raw_links, list)
            else None
        )
        meta = _as_dict(d.get("metadata"))
        sd = _as_dict(d.get("structured_data"))
        return cls(
            request_id=d.get("request_id"),
            html=d.get("html"),
            content_type=d.get("content_type"),
            cleaned=d.get("cleaned"),
            links=links,
            metadata=PageMetadata.from_dict(meta) if meta is not None else None,
            structured_data=StructuredData.from_dict(sd) if sd is not None else None,
            latency_ms=d.get("latency_ms"),
        )


@dataclass(frozen=True)
class ScrapeResponse:
    request_id: Optional[str]  # envelope request id (support-facing, req_...)
    credits_used: Optional[int]
    credits_remaining: Optional[int]
    data: ScrapeData

    @classmethod
    def from_envelope(cls, env: Mapping[str, Any]) -> "ScrapeResponse":
        return cls(
            request_id=env.get("request_id"),
            credits_used=env.get("credits_used"),
            credits_remaining=env.get("credits_remaining"),
            data=ScrapeData.from_dict(_as_dict(env.get("data")) or {}),
        )


# --- /smartscraper ---------------------------------------------------------


@dataclass(frozen=True)
class SmartScraperData:
    request_id: Optional[str]
    result: Any  # schema-shaped object, array, string (plain_text), or None
    latency_ms: Optional[int]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "SmartScraperData":
        return cls(
            request_id=d.get("request_id"),
            result=d.get("result"),
            latency_ms=d.get("latency_ms"),
        )


@dataclass(frozen=True)
class SmartScraperResponse:
    request_id: Optional[str]
    credits_used: Optional[int]
    credits_remaining: Optional[int]
    data: SmartScraperData

    @classmethod
    def from_envelope(cls, env: Mapping[str, Any]) -> "SmartScraperResponse":
        return cls(
            request_id=env.get("request_id"),
            credits_used=env.get("credits_used"),
            credits_remaining=env.get("credits_remaining"),
            data=SmartScraperData.from_dict(_as_dict(env.get("data")) or {}),
        )


# --- /smartbrowse/recipes/{id}/run (dispatch) ------------------------------


@dataclass(frozen=True)
class SmartBrowseDispatchData:
    run_id: Optional[str]
    recipe_id: Optional[str]
    run_status: Optional[RunStatus]
    poll_url: Optional[str]
    created_at: Optional[str]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "SmartBrowseDispatchData":
        return cls(
            run_id=d.get("run_id"),
            recipe_id=d.get("recipe_id"),
            run_status=_parse_run_status(d.get("run_status")),
            poll_url=d.get("poll_url"),
            created_at=d.get("created_at"),
        )


@dataclass(frozen=True)
class SmartBrowseRunDispatch:
    """Response to a dispatch call. No credit fields — nothing is billed yet."""

    request_id: Optional[str]
    data: SmartBrowseDispatchData

    @classmethod
    def from_envelope(cls, env: Mapping[str, Any]) -> "SmartBrowseRunDispatch":
        return cls(
            request_id=env.get("request_id"),
            data=SmartBrowseDispatchData.from_dict(_as_dict(env.get("data")) or {}),
        )


# --- /smartbrowse/runs/{id} (poll) -----------------------------------------


@dataclass(frozen=True)
class RunPage:
    items: Optional[list]  # free-form objects

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "RunPage":
        items = d.get("items")
        return cls(items=items if isinstance(items, list) else None)


@dataclass(frozen=True)
class RunResult:
    pages: Optional[list[RunPage]]
    mode: Optional[str]
    drift: Optional[float]
    warnings: Optional[list[str]]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "RunResult":
        raw_pages = d.get("pages")
        pages = (
            [RunPage.from_dict(p) for p in raw_pages if isinstance(p, Mapping)]
            if isinstance(raw_pages, list)
            else None
        )
        warnings = d.get("warnings")
        return cls(
            pages=pages,
            mode=d.get("mode"),
            drift=d.get("drift"),
            warnings=warnings if isinstance(warnings, list) else None,
        )


@dataclass(frozen=True)
class SmartBrowseRunData:
    id: Optional[str]
    recipe_id: Optional[str]
    run_status: Optional[RunStatus]
    pages_extracted: Optional[int]
    items_extracted: Optional[int]
    credits_used: Optional[int]  # the run's accrued spend
    started_at: Optional[str]
    completed_at: Optional[str]
    error: Optional[str]
    result: Optional[RunResult]
    created_at: Optional[str]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "SmartBrowseRunData":
        res = _as_dict(d.get("result"))
        return cls(
            id=d.get("id"),
            recipe_id=d.get("recipe_id"),
            run_status=_parse_run_status(d.get("run_status")),
            pages_extracted=d.get("pages_extracted"),
            items_extracted=d.get("items_extracted"),
            credits_used=d.get("credits_used"),
            started_at=d.get("started_at"),
            completed_at=d.get("completed_at"),
            error=d.get("error"),
            result=RunResult.from_dict(res) if res is not None else None,
            created_at=d.get("created_at"),
        )


@dataclass(frozen=True)
class SmartBrowseRunResponse:
    request_id: Optional[str]
    credits_used: Optional[int]  # envelope-level; always 0 (polling is free)
    credits_remaining: Optional[int]
    data: SmartBrowseRunData

    @classmethod
    def from_envelope(cls, env: Mapping[str, Any]) -> "SmartBrowseRunResponse":
        return cls(
            request_id=env.get("request_id"),
            credits_used=env.get("credits_used"),
            credits_remaining=env.get("credits_remaining"),
            data=SmartBrowseRunData.from_dict(_as_dict(env.get("data")) or {}),
        )


# --- /smartbrowse/usage ----------------------------------------------------


@dataclass(frozen=True)
class LastRun:
    id: Optional[str]
    status: Optional[RunStatus]
    pages_extracted: Optional[int]
    effective_cap: Optional[int]
    clamped_by_credits: Optional[bool]
    created_at: Optional[str]
    completed_at: Optional[str]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "LastRun":
        return cls(
            id=d.get("id"),
            status=_parse_run_status(d.get("status")),
            pages_extracted=d.get("pages_extracted"),
            effective_cap=d.get("effective_cap"),
            clamped_by_credits=d.get("clamped_by_credits"),
            created_at=d.get("created_at"),
            completed_at=d.get("completed_at"),
        )


@dataclass(frozen=True)
class SmartBrowseUsageData:
    runs_used_30d: Optional[int]
    runs_per_month_cap: Optional[int]
    pages_per_run_cap: Optional[int]
    cost_per_page: Optional[int]
    schedules_count: Optional[int]
    schedules_allowed: Optional[bool]
    last_run: Optional[LastRun]

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "SmartBrowseUsageData":
        lr = _as_dict(d.get("last_run"))
        return cls(
            runs_used_30d=d.get("runs_used_30d"),
            runs_per_month_cap=d.get("runs_per_month_cap"),
            pages_per_run_cap=d.get("pages_per_run_cap"),
            cost_per_page=d.get("cost_per_page"),
            schedules_count=d.get("schedules_count"),
            schedules_allowed=d.get("schedules_allowed"),
            last_run=LastRun.from_dict(lr) if lr is not None else None,
        )


@dataclass(frozen=True)
class SmartBrowseUsageResponse:
    request_id: Optional[str]
    credits_used: Optional[int]  # always 0
    credits_remaining: Optional[int]
    data: SmartBrowseUsageData

    @classmethod
    def from_envelope(cls, env: Mapping[str, Any]) -> "SmartBrowseUsageResponse":
        return cls(
            request_id=env.get("request_id"),
            credits_used=env.get("credits_used"),
            credits_remaining=env.get("credits_remaining"),
            data=SmartBrowseUsageData.from_dict(_as_dict(env.get("data")) or {}),
        )
