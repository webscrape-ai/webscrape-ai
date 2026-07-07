"""LLM structured extraction against a JSON schema.

Run:  WEBSCRAPE_API_KEY=wsg_live_... python examples/smartscraper.py
"""

from webscrape_ai import Client, InsufficientCreditsError, ValidationError

SCHEMA = {
    "type": "object",
    "properties": {
        "stories": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "url": {"type": "string"},
                    "score": {"type": "integer"},
                },
            },
        }
    },
}


def main() -> None:
    with Client() as client:
        try:
            resp = client.smartscraper(
                website_url="https://news.ycombinator.com",
                user_prompt="Extract the front-page stories with title, url, and score.",
                output_schema=SCHEMA,
            )
        except InsufficientCreditsError as e:
            print(f"out of credits: need {e.required}, have {e.balance}")
            return
        except ValidationError as e:
            print(f"extraction did not match the schema: {e.details}")
            return

        print(resp.data.result)
        print(f"\ncredits used: {resp.credits_used}, remaining: {resp.credits_remaining}")


if __name__ == "__main__":
    main()
