"""Fetch a page as cleaned markdown with outbound links.

Run:  WEBSCRAPE_API_KEY=wsg_live_... python examples/scrape.py
"""

from webscrape_ai import APIError, Client


def main() -> None:
    with Client() as client:  # reads WEBSCRAPE_API_KEY from the environment
        try:
            resp = client.scrape(
                website_url="https://example.com",
                clean=True,
                extract_links=True,
            )
        except APIError as e:
            print(f"request failed: {e.status_code} {e.code} — {e.message}")
            return

        print("=== markdown ===")
        print(resp.data.html)
        print("=== links ===")
        for link in resp.data.links or []:
            print(f"- {link.text}: {link.url}")
        print(f"\ncredits used: {resp.credits_used}, remaining: {resp.credits_remaining}")
        print(f"request id: {resp.request_id}")


if __name__ == "__main__":
    main()
