// Package webscrape is the official Go SDK for the webscrape.ai API.
//
// It covers the public, API-key-authenticated surface: [Client.Scrape],
// [Client.SmartScraper], and the SmartBrowse recipe-replay endpoints exposed
// through [Client.SmartBrowse].
//
// # Construction
//
// Create a client with [New] and functional options. With no WithAPIKey
// option the API key is read from the WEBSCRAPE_API_KEY environment variable;
// if neither is set, New fails fast with [ErrNoAPIKey] rather than surfacing a
// late 401.
//
//	client, err := webscrape.New()
//	if err != nil {
//		log.Fatal(err)
//	}
//	resp, err := client.Scrape(ctx, &webscrape.ScrapeRequest{
//		WebsiteURL: "https://example.com",
//		Clean:      webscrape.Bool(true),
//	})
//
// # Tri-state request options
//
// Optional request fields use pointer types with omitempty so that an untouched
// option is omitted from the JSON body entirely (never sent as null or a
// default-valued zero). Use the [Bool], [Int], and [String] helpers to set them.
//
// # Errors
//
// API-level failures are returned as *[APIError], which carries the HTTP status,
// stable error [Code], human message, raw details JSON, and request id. Use the
// predicate helpers ([IsRateLimited], [IsInsufficientCredits], ...) or
// [errors.As] to branch on the failure. Transport and timeout failures are
// returned wrapped so that [errors.Is] against [context.DeadlineExceeded] and
// friends still works. The SmartBrowse wait helpers additionally return
// *[RunFailedError] (wrapping [ErrRunFailed]) and *[WaitTimeoutError] (wrapping
// [ErrWaitTimeout]).
package webscrape
