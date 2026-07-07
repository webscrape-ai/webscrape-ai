"""Dispatch a SmartBrowse recipe run and wait for it to finish.

Recipes are authored in the dashboard; an API key can run and poll them.

Run:  WEBSCRAPE_API_KEY=wsg_live_... python examples/smartbrowse.py <recipe_id>
"""

import sys

from webscrape_ai import Client, RunFailedError, WaitTimeoutError


def main() -> None:
    recipe_id = sys.argv[1] if len(sys.argv) > 1 else "m3Yc2tFvN8q"

    with Client() as client:
        # Plan caps + rolling-30-day usage (free to poll).
        usage = client.smartbrowse.usage()
        print(
            f"runs this month: {usage.data.runs_used_30d}/{usage.data.runs_per_month_cap} "
            f"| {usage.data.cost_per_page} credits/page"
        )

        print(f"dispatching recipe {recipe_id} ...")
        try:
            run = client.smartbrowse.run_and_wait(recipe_id, poll_interval=2.0, timeout=900)
        except RunFailedError as e:
            print(f"run failed ({e.run.data.run_status}): {e.run.data.error}")
            return
        except WaitTimeoutError as e:
            status = e.run.data.run_status if e.run else "unknown"
            print(f"still running at the deadline (last status: {status})")
            return

        print(f"done: {run.data.run_status}")
        print(
            f"pages: {run.data.pages_extracted}, items: {run.data.items_extracted}, "
            f"credits: {run.data.credits_used}"
        )
        result = run.data.result
        if result is not None:
            for i, page in enumerate(result.pages or []):
                print(f"  page {i}: {len(page.items or [])} items")


if __name__ == "__main__":
    main()
