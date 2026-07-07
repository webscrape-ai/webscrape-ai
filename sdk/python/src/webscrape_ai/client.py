"""Synchronous client for the webscrape.ai API."""

from __future__ import annotations

import time
from typing import Any, Literal, Mapping, Optional, Sequence

import httpx

from ._constants import (
    DEFAULT_BASE_URL,
    DEFAULT_MAX_RETRIES,
    DEFAULT_TIMEOUT,
    RETRYABLE_STATUS,
)
from ._core import (
    _BaseClient,
    build_scrape_body,
    build_smartscraper_body,
    retry_after_seconds,
)
from .errors import RunFailedError, WaitTimeoutError
from .models import (
    RunStatus,
    ScrapeResponse,
    SmartBrowseRunDispatch,
    SmartBrowseRunResponse,
    SmartBrowseUsageResponse,
    SmartScraperResponse,
)

ParseMode = Literal["accurate", "speed"]
PageComplexity = Literal["low", "high"]
DetailLevel = Literal["low", "medium", "high"]

_TERMINAL = (RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED)


class Client(_BaseClient):
    """Synchronous webscrape.ai client.

    Usable as a context manager::

        with Client(api_key="wsg_live_...") as client:
            resp = client.scrape(website_url="https://example.com", clean=True)
    """

    def __init__(
        self,
        api_key: Optional[str] = None,
        *,
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT,
        max_retries: int = DEFAULT_MAX_RETRIES,
        http_client: Optional[httpx.Client] = None,
    ) -> None:
        super().__init__(
            api_key, base_url=base_url, timeout=timeout, max_retries=max_retries
        )
        if http_client is not None:
            self._http = http_client
            self._owns_http = False
        else:
            self._http = httpx.Client(timeout=timeout)
            self._owns_http = True
        self.smartbrowse = SmartBrowse(self)

    # --- lifecycle ---------------------------------------------------------

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def close(self) -> None:
        if self._owns_http:
            self._http.close()

    # --- transport ---------------------------------------------------------

    def _send(self, method: str, path: str, body: Optional[dict]) -> dict:
        url = self._url(path)
        headers = self._request_headers()
        attempt = 0
        while True:
            try:
                response = self._http.request(method, url, headers=headers, json=body)
            except httpx.HTTPError as exc:
                if self._is_connect_failure(exc) and attempt < self._max_retries:
                    time.sleep(self._backoff(attempt))
                    attempt += 1
                    continue
                raise self._wrap_transport(exc) from exc

            if response.status_code in RETRYABLE_STATUS and attempt < self._max_retries:
                time.sleep(self._backoff(attempt, retry_after_seconds(response.headers)))
                attempt += 1
                continue

            return self._parse_envelope(response)

    # --- endpoints ---------------------------------------------------------

    def scrape(
        self,
        *,
        website_url: str,
        clean: Optional[bool] = None,
        parse_mode: Optional[ParseMode] = None,
        tag_truncate: Optional[bool] = None,
        extract_links: Optional[bool] = None,
        include_tags: Optional[Sequence[str]] = None,
        exclude_tags: Optional[Sequence[str]] = None,
        headers: Optional[Mapping[str, str]] = None,
        max_age: Optional[int] = None,
        stealth: Optional[bool] = None,
    ) -> ScrapeResponse:
        """Fetch a URL as HTML, cleaned markdown, or links. 1 credit (+2 stealth)."""
        body = build_scrape_body(
            website_url=website_url,
            clean=clean,
            parse_mode=parse_mode,
            tag_truncate=tag_truncate,
            extract_links=extract_links,
            include_tags=include_tags,
            exclude_tags=exclude_tags,
            headers=headers,
            max_age=max_age,
            stealth=stealth,
        )
        return ScrapeResponse.from_envelope(self._send("POST", "/scrape", body))

    def smartscraper(
        self,
        *,
        website_url: str,
        user_prompt: str,
        output_schema: Optional[Mapping[str, Any]] = None,
        page_complexity: Optional[PageComplexity] = None,
        detail_level: Optional[DetailLevel] = None,
        parse_mode: Optional[ParseMode] = None,
        plain_text: Optional[bool] = None,
        include_tags: Optional[Sequence[str]] = None,
        exclude_tags: Optional[Sequence[str]] = None,
        reduce_content: Optional[bool] = None,
        experimental: Optional[bool] = None,
        headers: Optional[Mapping[str, str]] = None,
        max_age: Optional[int] = None,
        stealth: Optional[bool] = None,
    ) -> SmartScraperResponse:
        """LLM structured extraction → JSON. 5 credits (+5 stealth)."""
        body = build_smartscraper_body(
            website_url=website_url,
            user_prompt=user_prompt,
            output_schema=output_schema,
            page_complexity=page_complexity,
            detail_level=detail_level,
            parse_mode=parse_mode,
            plain_text=plain_text,
            include_tags=include_tags,
            exclude_tags=exclude_tags,
            reduce_content=reduce_content,
            experimental=experimental,
            headers=headers,
            max_age=max_age,
            stealth=stealth,
        )
        return SmartScraperResponse.from_envelope(
            self._send("POST", "/smartscraper", body)
        )


class SmartBrowse:
    """The ``client.smartbrowse`` namespace (synchronous)."""

    def __init__(self, client: Client) -> None:
        self._client = client

    def run(self, recipe_id: str) -> SmartBrowseRunDispatch:
        """Dispatch a recipe replay run (async on the server). 2 credits/page."""
        env = self._client._send(
            "POST", f"/smartbrowse/recipes/{recipe_id}/run", None
        )
        return SmartBrowseRunDispatch.from_envelope(env)

    def get_run(self, run_id: str) -> SmartBrowseRunResponse:
        """Poll a run. Free."""
        env = self._client._send("GET", f"/smartbrowse/runs/{run_id}", None)
        return SmartBrowseRunResponse.from_envelope(env)

    def usage(self) -> SmartBrowseUsageResponse:
        """Plan caps + rolling-30-day usage. Free."""
        env = self._client._send("GET", "/smartbrowse/usage", None)
        return SmartBrowseUsageResponse.from_envelope(env)

    def wait_for_run(
        self,
        run_id: str,
        *,
        poll_interval: float = 2.0,
        timeout: float = 900.0,
    ) -> SmartBrowseRunResponse:
        """Poll until the run is terminal.

        Returns the run on ``completed``; raises :class:`RunFailedError` on
        ``failed`` / ``cancelled`` and :class:`WaitTimeoutError` on deadline.
        The interval grows x1.5 per poll, capped at 10s.
        """
        deadline = time.monotonic() + timeout
        interval = poll_interval
        while True:
            run = self.get_run(run_id)
            status = run.data.run_status
            if status == RunStatus.COMPLETED:
                return run
            if status in (RunStatus.FAILED, RunStatus.CANCELLED):
                raise RunFailedError(run)
            if time.monotonic() >= deadline:
                raise WaitTimeoutError(run)
            remaining = deadline - time.monotonic()
            time.sleep(min(interval, max(0.0, remaining)))
            interval = min(interval * 1.5, 10.0)

    def run_and_wait(
        self,
        recipe_id: str,
        *,
        poll_interval: float = 2.0,
        timeout: float = 900.0,
    ) -> SmartBrowseRunResponse:
        """Dispatch a run and block until it finishes."""
        dispatch = self.run(recipe_id)
        run_id = dispatch.data.run_id
        if not run_id:
            raise WaitTimeoutError(None, "dispatch response contained no run_id")
        return self.wait_for_run(
            run_id, poll_interval=poll_interval, timeout=timeout
        )
