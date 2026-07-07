/**
 * Compile-time type assertions. Not run by vitest (the filename does not match
 * its `*.test.ts` glob); enforced by `tsc --noEmit`, which is part of `pnpm test`.
 *
 * If `smartscraper<T>` stops flowing `T` into `data.result`, this file fails to
 * compile.
 */
import type { ScrapeResponse, SmartScraperResponse } from "webscrape-ai";
import { Webscrape } from "webscrape-ai";

type Equal<A, B> =
  (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B ? 1 : 2 ? true : false;
type Expect<T extends true> = T;

interface Story {
  title: string;
  score: number;
}

declare const client: Webscrape;

async function _typeChecks(): Promise<void> {
  // Generic flows to data.result.
  const typed = await client.smartscraper<Story>({
    website_url: "u",
    user_prompt: "p",
  });
  type TypedResponse = typeof typed;
  type TypedResult = (typeof typed)["data"]["result"];
  type _t1 = Expect<Equal<TypedResponse, SmartScraperResponse<Story>>>;
  type _t2 = Expect<Equal<TypedResult, Story | null | undefined>>;

  // Default generic is `unknown`.
  const untyped = await client.smartscraper({ website_url: "u", user_prompt: "p" });
  type UntypedResult = (typeof untyped)["data"]["result"];
  type _t3 = Expect<Equal<UntypedResult, unknown>>;

  // scrape returns the concrete completed response.
  const scraped = await client.scrape({ website_url: "u" });
  type _t4 = Expect<Equal<typeof scraped, ScrapeResponse>>;

  // Envelope fields are present and typed on completed responses.
  const creditsUsed: number = typed.credits_used;
  const creditsRemaining: number = scraped.credits_remaining;
  const extractionId: string = typed.data.request_id;

  // Silence "declared but never used" without asserting anything runtime.
  void creditsUsed;
  void creditsRemaining;
  void extractionId;
  // Reference the type aliases so they are not flagged.
  const _witness: [_t1, _t2, _t3, _t4] = [true, true, true, true];
  void _witness;
}

void _typeChecks;
