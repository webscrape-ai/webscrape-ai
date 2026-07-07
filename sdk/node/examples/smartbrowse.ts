/**
 * Dispatch a SmartBrowse recipe run and wait for the result.
 *
 *   WEBSCRAPE_API_KEY=wsg_live_... RECIPE_ID=m3Yc2tFvN8q npx tsx examples/smartbrowse.ts
 */
import { RunFailedError, WaitTimeoutError, Webscrape } from "webscrape-ai";

async function main(): Promise<void> {
  const client = new Webscrape();
  const recipeId = process.env.RECIPE_ID;
  if (!recipeId) throw new Error("set RECIPE_ID to a recipe you own");

  // Check remaining SmartBrowse quota first.
  const usage = await client.smartbrowse.usage();
  console.log(
    `runs used (30d): ${usage.data.runs_used_30d}/${usage.data.runs_per_month_cap}, ` +
      `cost/page: ${usage.data.cost_per_page}`,
  );

  try {
    // Dispatch + poll to completion in one call.
    const run = await client.smartbrowse.runAndWait(recipeId, { pollIntervalMs: 3000 });
    console.log(
      `run ${run.data.id} completed: ${run.data.pages_extracted} pages, ` +
        `${run.data.items_extracted} items, ${run.data.credits_used} credits`,
    );
    for (const page of run.data.result?.pages ?? []) {
      console.log(`  page: ${page.items?.length ?? 0} items`);
    }
  } catch (err) {
    if (err instanceof RunFailedError) {
      console.error(`run ${err.run.id} ${err.run.run_status}: ${err.run.error}`);
    } else if (err instanceof WaitTimeoutError) {
      console.error(`still running after the deadline; last status: ${err.run.run_status}`);
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
