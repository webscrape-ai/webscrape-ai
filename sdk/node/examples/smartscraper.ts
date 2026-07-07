/**
 * Structured extraction with a JSON output schema and a typed result.
 *
 *   WEBSCRAPE_API_KEY=wsg_live_... npx tsx examples/smartscraper.ts
 */
import { InsufficientCreditsError, ValidationError, Webscrape } from "webscrape-ai";

interface Stories {
  stories: Array<{ title: string; url: string; score: number }>;
}

async function main(): Promise<void> {
  const client = new Webscrape();

  try {
    const res = await client.smartscraper<Stories>({
      website_url: "https://news.ycombinator.com",
      user_prompt: "Extract the front-page stories with title, url, and score.",
      output_schema: {
        type: "object",
        properties: {
          stories: {
            type: "array",
            items: {
              type: "object",
              properties: {
                title: { type: "string" },
                url: { type: "string" },
                score: { type: "integer" },
              },
            },
          },
        },
      },
    });

    for (const story of res.data.result?.stories ?? []) {
      console.log(`${story.score.toString().padStart(4)}  ${story.title}`);
    }
    console.log(`\ncredits remaining: ${res.credits_remaining}`);
  } catch (err) {
    if (err instanceof InsufficientCreditsError) {
      console.error(`out of credits: have ${err.balance}, need ${err.required}`);
    } else if (err instanceof ValidationError) {
      console.error("extraction did not match the schema:", err.details);
    } else {
      throw err;
    }
    process.exitCode = 1;
  }
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
