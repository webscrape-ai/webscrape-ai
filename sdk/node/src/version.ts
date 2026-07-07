/**
 * The single source of truth for the SDK version.
 *
 * This feeds the `User-Agent` header (`webscrape-ai-node/<VERSION>`). It is kept
 * in sync with `package.json` by a test (`test/version.test.ts`) so the two can
 * never drift.
 */
export const VERSION = "0.1.0";
