// Command scrape fetches a URL as cleaned markdown.
//
// Run with: WEBSCRAPE_API_KEY=wsg_live_... go run ./examples/scrape
package main

import (
	"context"
	"fmt"
	"log"
	"time"

	webscrape "github.com/webscrape-ai/webscrape-ai/sdk/go"
)

func main() {
	// API key comes from the WEBSCRAPE_API_KEY environment variable.
	client, err := webscrape.New()
	if err != nil {
		log.Fatal(err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	resp, err := client.Scrape(ctx, &webscrape.ScrapeRequest{
		WebsiteURL:   "https://example.com",
		Clean:        webscrape.Bool(true),
		ExtractLinks: webscrape.Bool(true),
	})
	if err != nil {
		log.Fatal(err)
	}

	fmt.Printf("request_id=%s credits_used=%d credits_remaining=%d\n",
		resp.RequestID, resp.CreditsUsed, resp.CreditsRemaining)
	if resp.Data.HTML != nil {
		fmt.Println(*resp.Data.HTML)
	}
	for _, link := range resp.Data.Links {
		fmt.Printf("- %s (%s)\n", link.URL, link.Text)
	}
}
