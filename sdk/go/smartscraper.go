package webscrape

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
)

// SmartScraperRequest is the body for [Client.SmartScraper]. WebsiteURL and
// UserPrompt are required; the rest are optional and omitted from the JSON body
// when left at their zero value. Use [Bool] and [Int] for the tri-state fields.
type SmartScraperRequest struct {
	// WebsiteURL is the URL to extract from. Required.
	WebsiteURL string `json:"website_url"`
	// UserPrompt is the plain-English description of what to extract.
	// Required. (The wire field is user_prompt, not prompt.)
	UserPrompt string `json:"user_prompt"`
	// OutputSchema is a JSON Schema the result is validated against (one
	// repair attempt is made). Providing both UserPrompt and OutputSchema
	// gives the best results.
	OutputSchema map[string]any `json:"output_schema,omitempty"`
	// PageComplexity is "low" (default) or "high".
	PageComplexity string `json:"page_complexity,omitempty"`
	// DetailLevel is "low", "medium" (default), or "high".
	DetailLevel string `json:"detail_level,omitempty"`
	// ParseMode selects the cleaner mode: "accurate" (default) or "speed".
	ParseMode string `json:"parse_mode,omitempty"`
	// PlainText returns the raw extracted text as a string under result,
	// bypassing OutputSchema validation. Default false.
	PlainText *bool `json:"plain_text,omitempty"`
	// IncludeTags is a whitelist of HTML tags to keep before extraction.
	IncludeTags []string `json:"include_tags,omitempty"`
	// ExcludeTags is a blacklist of HTML tags to drop before extraction.
	ExcludeTags []string `json:"exclude_tags,omitempty"`
	// ReduceContent trims long content before extraction (tri-state; server
	// default when unset). Use Bool to set.
	ReduceContent *bool `json:"reduce_content,omitempty"`
	// Experimental opts into an alternate extraction path that can do better
	// on hard-to-parse pages. Behavior may change without notice. Default false.
	Experimental *bool `json:"experimental,omitempty"`
	// Headers are custom request headers forwarded to the fetcher. Providing
	// headers disables URL caching for this request.
	Headers map[string]string `json:"headers,omitempty"`
	// MaxAge opts into the URL cache; same semantics as ScrapeRequest.MaxAge.
	MaxAge *int `json:"max_age,omitempty"`
	// Stealth uses browser-based fetching. +5 credits. Default false.
	Stealth *bool `json:"stealth,omitempty"`
}

// SmartScraperData is the data payload of a smartscraper response.
type SmartScraperData struct {
	// RequestID is the extraction id (a UUID), distinct from the top-level
	// envelope request id.
	RequestID string `json:"request_id"`
	// Result is the raw extracted output: a schema-shaped object, an array,
	// a string (when PlainText was set), or null. Decode it with
	// [SmartScraperResponse.DecodeResult].
	Result json.RawMessage `json:"result"`
	// LatencyMs is the total extraction time in milliseconds.
	LatencyMs *int `json:"latency_ms"`
}

// SmartScraperResponse is the result of [Client.SmartScraper].
type SmartScraperResponse struct {
	Data             SmartScraperData
	CreditsUsed      int
	CreditsRemaining int
	RequestID        string
}

// DecodeResult unmarshals the extracted result into v (a pointer). It returns
// an error when the result is empty. A JSON null result decodes without error
// (leaving v at its zero value / nil).
func (r *SmartScraperResponse) DecodeResult(v any) error {
	if len(r.Data.Result) == 0 {
		return errors.New("webscrape: smartscraper response has no result")
	}
	return json.Unmarshal(r.Data.Result, v)
}

// SmartScraper performs LLM structured extraction, returning JSON validated
// against OutputSchema when supplied. Cost: 5 credits (+5 with Stealth).
func (c *Client) SmartScraper(ctx context.Context, req *SmartScraperRequest) (*SmartScraperResponse, error) {
	if req == nil {
		return nil, errors.New("webscrape: nil SmartScraperRequest")
	}
	var data SmartScraperData
	meta, err := c.call(ctx, http.MethodPost, "/smartscraper", req, &data)
	if err != nil {
		return nil, err
	}
	return &SmartScraperResponse{
		Data:             data,
		CreditsUsed:      meta.creditsUsed,
		CreditsRemaining: meta.creditsRemaining,
		RequestID:        meta.requestID,
	}, nil
}
