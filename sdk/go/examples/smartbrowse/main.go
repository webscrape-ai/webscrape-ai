// Command smartbrowse dispatches a SmartBrowse recipe run and waits for it to
// finish, handling the terminal-state and timeout errors.
//
// Run with: WEBSCRAPE_API_KEY=wsg_live_... go run ./examples/smartbrowse <recipe_id>
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"

	webscrape "github.com/webscrape-ai/webscrape-ai/sdk/go"
)

func main() {
	if len(os.Args) < 2 {
		log.Fatalf("usage: %s <recipe_id>", os.Args[0])
	}
	recipeID := os.Args[1]

	client, err := webscrape.New()
	if err != nil {
		log.Fatal(err)
	}

	// Show current plan caps and usage first (free).
	usage, err := client.SmartBrowse.Usage(context.Background())
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("runs used (30d): %d / %d, cost/page: %d\n",
		usage.Data.RunsUsed30d, usage.Data.RunsPerMonthCap, usage.Data.CostPerPage)

	// Dispatch and wait in one call. Defaults: poll every 2s (growing to 10s),
	// time out after 900s.
	run, err := client.SmartBrowse.RunAndWait(context.Background(), recipeID)
	if err != nil {
		var failed *webscrape.RunFailedError
		var timedOut *webscrape.WaitTimeoutError
		switch {
		case errors.As(err, &failed):
			log.Fatalf("run %s ended %s: %s", failed.Run.ID, failed.Run.RunStatus, failed.Run.Error)
		case errors.As(err, &timedOut):
			log.Fatalf("run did not finish in time (last status %s)", timedOut.Run.RunStatus)
		default:
			log.Fatal(err)
		}
	}

	fmt.Printf("run %s completed: %d pages, %d items, %d credits\n",
		run.Data.ID, run.Data.PagesExtracted, run.Data.ItemsExtracted, run.Data.CreditsUsed)
	if run.Data.Result != nil {
		for i, page := range run.Data.Result.Pages {
			fmt.Printf("page %d: %d items\n", i+1, len(page.Items))
		}
	}
}
