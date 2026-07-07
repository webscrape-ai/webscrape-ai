/**
 * Fetch a page as cleaned markdown.
 *
 *   WEBSCRAPE_API_KEY=wsg_live_... npx tsx examples/scrape.ts
 */
import { Webscrape, WebscrapeError } from "webscrape-ai";

async function main(): Promise<void> {
  const client = new Webscrape(); // reads WEBSCRAPE_API_KEY

  const res = await client.scrape({
    website_url: "https://example.com",
    clean: true,
    extract_links: true,
  });

  console.log("markdown:\n", res.data.html);
  console.log("links:", res.data.links?.length ?? 0);
  console.log(`credits used: ${res.credits_used}, remaining: ${res.credits_remaining}`);
  console.log("request id:", res.request_id);
}

main().catch((err) => {
  if (err instanceof WebscrapeError) {
    console.error(`${err.name}: ${err.message}`);
  } else {
    console.error(err);
  }
  process.exitCode = 1;
});
