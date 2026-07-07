package webscrape

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
)

// ScrapeRequest is the body for [Client.Scrape]. Only WebsiteURL is required;
// every other field is optional and, when left at its zero value (nil pointer /
// empty slice / empty string), is omitted from the JSON body entirely so that
// "unset" is never sent as a default value. Use [Bool] and [Int] to set the
// tri-state fields.
type ScrapeRequest struct {
	// WebsiteURL is the URL to fetch. Required.
	WebsiteURL string `json:"website_url"`
	// Clean converts HTML to cleaned markdown. Default false.
	Clean *bool `json:"clean,omitempty"`
	// ParseMode selects the cleaner mode when Clean is set: "accurate"
	// (default) or "speed".
	ParseMode string `json:"parse_mode,omitempty"`
	// TagTruncate replaces inline images with their alt text when Clean is
	// set. Server default true; set Bool(false) to keep image tags.
	TagTruncate *bool `json:"tag_truncate,omitempty"`
	// ExtractLinks includes a deduplicated list of outbound links. Default false.
	ExtractLinks *bool `json:"extract_links,omitempty"`
	// IncludeTags is a whitelist of HTML tags to keep when Clean is set.
	IncludeTags []string `json:"include_tags,omitempty"`
	// ExcludeTags is a blacklist of HTML tags to drop when Clean is set.
	ExcludeTags []string `json:"exclude_tags,omitempty"`
	// Headers are custom request headers forwarded to the fetcher. Providing
	// any header disables URL caching for the request.
	Headers map[string]string `json:"headers,omitempty"`
	// MaxAge opts into the URL cache: omitted fetches fresh; a value > 0
	// accepts cache entries fresher than that many seconds. Use Int to set.
	MaxAge *int `json:"max_age,omitempty"`
	// Stealth uses browser-based fetching. +2 credits. Default false.
	Stealth *bool `json:"stealth,omitempty"`
}

// LinkInfo is a single outbound link, present when ExtractLinks was set.
type LinkInfo struct {
	URL  string `json:"url"`
	Text string `json:"text"`
}

// PageMetadata carries HTML page metadata. Every field is nullable; the whole
// object is null for PDFs.
type PageMetadata struct {
	Title       *string `json:"title"`
	Description *string `json:"description"`
	Language    *string `json:"language"`
}

// Pagination holds discovered next/prev page URLs.
type Pagination struct {
	Next *string `json:"next"`
	Prev *string `json:"prev"`
}

// StructuredData holds structured data extracted from the page (JSON-LD,
// microdata, pagination hints), when available.
type StructuredData struct {
	JSONLD     []json.RawMessage `json:"json_ld"`
	Microdata  []json.RawMessage `json:"microdata"`
	Pagination *Pagination       `json:"pagination"`
}

// ScrapeData is the data payload of a scrape response. All fields are
// optional/nullable; absent fields are returned as explicit nulls.
type ScrapeData struct {
	// RequestID is the extraction id (a UUID), distinct from the top-level
	// envelope request id ([ScrapeResponse.RequestID]).
	RequestID string `json:"request_id"`
	// HTML is the raw HTML, or cleaned markdown when Clean was set (or for PDFs).
	HTML *string `json:"html"`
	// ContentType is "html" or "pdf".
	ContentType string `json:"content_type"`
	// Cleaned is true when the cleaner pass actually ran.
	Cleaned bool `json:"cleaned"`
	// Links is populated only when ExtractLinks was set.
	Links []LinkInfo `json:"links"`
	// Metadata is HTML-only; null for PDFs.
	Metadata *PageMetadata `json:"metadata"`
	// StructuredData is optional (json_ld, microdata, pagination).
	StructuredData *StructuredData `json:"structured_data"`
	// LatencyMs is the total fetch time in milliseconds.
	LatencyMs *int `json:"latency_ms"`
}

// ScrapeResponse is the result of [Client.Scrape]. It preserves the envelope:
// the support-facing RequestID (req_...) plus credit accounting, alongside the
// typed Data (whose own RequestID is the extraction UUID).
type ScrapeResponse struct {
	Data             ScrapeData
	CreditsUsed      int
	CreditsRemaining int
	RequestID        string
}

// Scrape fetches a URL as HTML, cleaned markdown, or links. Cost: 1 credit
// (+2 with Stealth).
func (c *Client) Scrape(ctx context.Context, req *ScrapeRequest) (*ScrapeResponse, error) {
	if req == nil {
		return nil, errors.New("webscrape: nil ScrapeRequest")
	}
	var data ScrapeData
	meta, err := c.call(ctx, http.MethodPost, "/scrape", req, &data)
	if err != nil {
		return nil, err
	}
	return &ScrapeResponse{
		Data:             data,
		CreditsUsed:      meta.creditsUsed,
		CreditsRemaining: meta.creditsRemaining,
		RequestID:        meta.requestID,
	}, nil
}
