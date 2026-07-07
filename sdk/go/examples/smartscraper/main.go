// Command smartscraper runs LLM structured extraction with an output schema and
// demonstrates branching on the typed error taxonomy.
//
// Run with: WEBSCRAPE_API_KEY=wsg_live_... go run ./examples/smartscraper
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"time"

	webscrape "github.com/webscrape-ai/webscrape-ai/sdk/go"
)

func main() {
	client, err := webscrape.New()
	if err != nil {
		log.Fatal(err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()

	resp, err := client.SmartScraper(ctx, &webscrape.SmartScraperRequest{
		WebsiteURL: "https://news.ycombinator.com",
		UserPrompt: "Extract the front-page stories with title, url, and score.",
		OutputSchema: map[string]any{
			"type": "object",
			"properties": map[string]any{
				"stories": map[string]any{
					"type": "array",
					"items": map[string]any{
						"type": "object",
						"properties": map[string]any{
							"title": map[string]any{"type": "string"},
							"url":   map[string]any{"type": "string"},
							"score": map[string]any{"type": "integer"},
						},
					},
				},
			},
		},
	})
	if err != nil {
		// Branch on the typed error taxonomy.
		switch {
		case webscrape.IsInsufficientCredits(err):
			apiErr, _ := webscrape.AsAPIError(err)
			balance, required, _ := apiErr.Credits()
			log.Fatalf("out of credits: have %d, need %d", balance, required)
		case webscrape.IsRateLimited(err):
			apiErr, _ := webscrape.AsAPIError(err)
			log.Fatalf("rate limited (%s)", apiErr.RateLimitReason())
		case webscrape.IsValidationFailed(err):
			log.Fatalf("extraction did not match the schema: %v", err)
		default:
			var apiErr *webscrape.APIError
			if errors.As(err, &apiErr) {
				log.Fatalf("api error %s: %s (request_id=%s)", apiErr.Code, apiErr.Message, apiErr.RequestID)
			}
			log.Fatal(err)
		}
	}

	var out struct {
		Stories []struct {
			Title string `json:"title"`
			URL   string `json:"url"`
			Score int    `json:"score"`
		} `json:"stories"`
	}
	if err := resp.DecodeResult(&out); err != nil {
		log.Fatal(err)
	}

	fmt.Printf("request_id=%s credits_used=%d\n", resp.RequestID, resp.CreditsUsed)
	for _, s := range out.Stories {
		fmt.Printf("[%d] %s — %s\n", s.Score, s.Title, s.URL)
	}
}
