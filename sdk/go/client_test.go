package webscrape

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// capture records the last request a mock handler received and counts calls.
type capture struct {
	mu     sync.Mutex
	method string
	path   string
	header http.Header
	body   []byte
	calls  int
}

func (c *capture) record(r *http.Request) {
	b, _ := io.ReadAll(r.Body)
	c.mu.Lock()
	defer c.mu.Unlock()
	c.method = r.Method
	c.path = r.URL.Path
	c.header = r.Header.Clone()
	c.body = b
	c.calls++
}

func (c *capture) snapshot() (method, path string, header http.Header, body []byte, calls int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.method, c.path, c.header, c.body, c.calls
}

// newTestClient starts an httptest server with handler and returns a client
// pointed at it. Backoff is made deterministic and instant for tests.
func newTestClient(t *testing.T, handler http.HandlerFunc, opts ...Option) *Client {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	base := []Option{WithAPIKey("wsg_live_testkey"), WithBaseURL(srv.URL)}
	c, err := New(append(base, opts...)...)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	c.baseDelay = 0
	c.rng = func() float64 { return 0 }
	return c
}

func mustJSON(t *testing.T, w http.ResponseWriter, status int, body string) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if _, err := io.WriteString(w, body); err != nil {
		t.Fatalf("write body: %v", err)
	}
}

func TestUserAgentConstant(t *testing.T) {
	if userAgent != "webscrape-ai-go/0.1.0" {
		t.Fatalf("userAgent = %q, want webscrape-ai-go/0.1.0", userAgent)
	}
	if Version != "0.1.0" {
		t.Fatalf("Version = %q, want 0.1.0", Version)
	}
}

func TestScrapeHappyPath(t *testing.T) {
	var cap capture
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		cap.record(r)
		mustJSON(t, w, http.StatusOK, `{
			"status":"completed",
			"data":{"request_id":"eng-uuid-1","html":"# Hi","content_type":"html","cleaned":true,"links":null,"metadata":null,"structured_data":null,"latency_ms":42},
			"credits_used":1,"credits_remaining":499,"request_id":"req_aB3xY9Kp"
		}`)
	})

	resp, err := client.Scrape(context.Background(), &ScrapeRequest{
		WebsiteURL: "https://example.com",
		Clean:      Bool(true),
	})
	if err != nil {
		t.Fatalf("Scrape: %v", err)
	}

	method, path, header, body, calls := cap.snapshot()
	if method != http.MethodPost || path != "/scrape" {
		t.Errorf("got %s %s, want POST /scrape", method, path)
	}
	if calls != 1 {
		t.Errorf("calls = %d, want 1", calls)
	}
	if got := header.Get("X-API-Key"); got != "wsg_live_testkey" {
		t.Errorf("X-API-Key = %q", got)
	}
	if got := header.Get("User-Agent"); got != "webscrape-ai-go/0.1.0" {
		t.Errorf("User-Agent = %q", got)
	}
	if got := header.Get("Content-Type"); got != "application/json" {
		t.Errorf("Content-Type = %q", got)
	}

	// Optional-field serialization: only touched fields present.
	var sent map[string]json.RawMessage
	if err := json.Unmarshal(body, &sent); err != nil {
		t.Fatalf("unmarshal sent body: %v", err)
	}
	if _, ok := sent["website_url"]; !ok {
		t.Error("website_url missing from body")
	}
	if _, ok := sent["clean"]; !ok {
		t.Error("clean missing from body")
	}
	for _, absent := range []string{"tag_truncate", "stealth", "parse_mode", "extract_links", "max_age", "include_tags", "exclude_tags", "headers"} {
		if _, ok := sent[absent]; ok {
			t.Errorf("unexpected field %q in body: %s", absent, body)
		}
	}

	// Parsed response fields, including both request ids.
	if resp.RequestID != "req_aB3xY9Kp" {
		t.Errorf("envelope RequestID = %q", resp.RequestID)
	}
	if resp.Data.RequestID != "eng-uuid-1" {
		t.Errorf("data RequestID = %q", resp.Data.RequestID)
	}
	if resp.CreditsUsed != 1 || resp.CreditsRemaining != 499 {
		t.Errorf("credits = %d/%d", resp.CreditsUsed, resp.CreditsRemaining)
	}
	if resp.Data.HTML == nil || *resp.Data.HTML != "# Hi" {
		t.Errorf("HTML = %v", resp.Data.HTML)
	}
	if resp.Data.LatencyMs == nil || *resp.Data.LatencyMs != 42 {
		t.Errorf("LatencyMs = %v", resp.Data.LatencyMs)
	}
	if !resp.Data.Cleaned {
		t.Error("Cleaned = false")
	}
}

func TestSmartScraperHappyPathAndDecodeResult(t *testing.T) {
	var cap capture
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		cap.record(r)
		mustJSON(t, w, http.StatusOK, `{
			"status":"completed",
			"data":{"request_id":"eng-uuid-2","result":{"title":"Hello","score":9},"latency_ms":1200},
			"credits_used":5,"credits_remaining":495,"request_id":"req_smart"
		}`)
	})

	resp, err := client.SmartScraper(context.Background(), &SmartScraperRequest{
		WebsiteURL:   "https://news.ycombinator.com",
		UserPrompt:   "extract the title and score",
		OutputSchema: map[string]any{"type": "object"},
	})
	if err != nil {
		t.Fatalf("SmartScraper: %v", err)
	}

	_, path, _, body, _ := cap.snapshot()
	if path != "/smartscraper" {
		t.Errorf("path = %s", path)
	}
	var sent map[string]json.RawMessage
	_ = json.Unmarshal(body, &sent)
	if _, ok := sent["user_prompt"]; !ok {
		t.Error("user_prompt missing (field must be user_prompt, not prompt)")
	}
	if _, ok := sent["prompt"]; ok {
		t.Error("unexpected 'prompt' field in body")
	}
	if _, ok := sent["stealth"]; ok {
		t.Errorf("untouched stealth present: %s", body)
	}

	if resp.RequestID != "req_smart" || resp.Data.RequestID != "eng-uuid-2" {
		t.Errorf("request ids = %q / %q", resp.RequestID, resp.Data.RequestID)
	}

	var out struct {
		Title string `json:"title"`
		Score int    `json:"score"`
	}
	if err := resp.DecodeResult(&out); err != nil {
		t.Fatalf("DecodeResult: %v", err)
	}
	if out.Title != "Hello" || out.Score != 9 {
		t.Errorf("decoded = %+v", out)
	}
}

func TestSmartScraperPlainTextResult(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		mustJSON(t, w, http.StatusOK, `{"status":"completed","data":{"request_id":"e3","result":"just a string","latency_ms":null},"credits_used":5,"credits_remaining":490,"request_id":"req_pt"}`)
	})
	resp, err := client.SmartScraper(context.Background(), &SmartScraperRequest{
		WebsiteURL: "https://example.com",
		UserPrompt: "summarize",
		PlainText:  Bool(true),
	})
	if err != nil {
		t.Fatalf("SmartScraper: %v", err)
	}
	var s string
	if err := resp.DecodeResult(&s); err != nil {
		t.Fatalf("DecodeResult: %v", err)
	}
	if s != "just a string" {
		t.Errorf("result = %q", s)
	}
	if resp.Data.LatencyMs != nil {
		t.Errorf("LatencyMs should be nil, got %v", *resp.Data.LatencyMs)
	}
}

func TestRetryRetryableStatusThenSuccess(t *testing.T) {
	var cap capture
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		cap.record(r)
		if _, _, _, _, calls := cap.snapshot(); calls == 1 {
			mustJSON(t, w, http.StatusTooManyRequests, `{"status":"error","error":{"code":"rate_limited","message":"slow down","details":{"reason":"rate_limit_per_min","limit_per_min":60}},"request_id":"req_rl"}`)
			return
		}
		mustJSON(t, w, http.StatusOK, `{"status":"completed","data":{"request_id":"eng","content_type":"html"},"credits_used":1,"credits_remaining":10,"request_id":"req_ok"}`)
	})

	resp, err := client.Scrape(context.Background(), &ScrapeRequest{WebsiteURL: "https://example.com"})
	if err != nil {
		t.Fatalf("Scrape after retry: %v", err)
	}
	if resp.RequestID != "req_ok" {
		t.Errorf("RequestID = %q", resp.RequestID)
	}
	if _, _, _, _, calls := cap.snapshot(); calls != 2 {
		t.Errorf("calls = %d, want 2", calls)
	}
}

func TestRetryDisabledSurfacesError(t *testing.T) {
	var cap capture
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		cap.record(r)
		mustJSON(t, w, http.StatusTooManyRequests, `{"status":"error","error":{"code":"rate_limited","message":"slow down","details":{"reason":"max_concurrent_requests","max_concurrent":5}},"request_id":"req_rl"}`)
	}, WithMaxRetries(0))

	_, err := client.Scrape(context.Background(), &ScrapeRequest{WebsiteURL: "https://example.com"})
	if !IsRateLimited(err) {
		t.Fatalf("err = %v, want rate_limited", err)
	}
	if _, _, _, _, calls := cap.snapshot(); calls != 1 {
		t.Errorf("calls = %d, want 1 (no retry)", calls)
	}
}

func TestRetryNotAppliedToPlain400(t *testing.T) {
	var cap capture
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		cap.record(r)
		mustJSON(t, w, http.StatusBadRequest, `{"status":"error","error":{"code":"invalid_request","message":"url is required"},"request_id":"req_bad"}`)
	})

	_, err := client.Scrape(context.Background(), &ScrapeRequest{WebsiteURL: "not-a-url"})
	if !IsBadRequest(err) {
		t.Fatalf("err = %v, want invalid_request", err)
	}
	if _, _, _, _, calls := cap.snapshot(); calls != 1 {
		t.Errorf("calls = %d, want 1 (400 not retried)", calls)
	}
}

func TestEnvVarKeyPickup(t *testing.T) {
	t.Setenv("WEBSCRAPE_API_KEY", "wsg_live_fromenv")
	c, err := New()
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if c.apiKey != "wsg_live_fromenv" {
		t.Errorf("apiKey = %q, want wsg_live_fromenv", c.apiKey)
	}
}

func TestMissingKeyConstructionError(t *testing.T) {
	t.Setenv("WEBSCRAPE_API_KEY", "")
	_, err := New()
	if !errors.Is(err, ErrNoAPIKey) {
		t.Fatalf("err = %v, want ErrNoAPIKey", err)
	}
}

func TestBaseURLTrailingSlashTrimmed(t *testing.T) {
	c, err := New(WithAPIKey("k"), WithBaseURL("https://staging.example/v1/"))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if c.baseURL != "https://staging.example/v1" {
		t.Errorf("baseURL = %q", c.baseURL)
	}
}

func TestContextCancellationPassthrough(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(200 * time.Millisecond)
		mustJSON(t, w, http.StatusOK, `{"status":"completed","data":{},"credits_used":1,"credits_remaining":1,"request_id":"x"}`)
	})
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_, err := client.Scrape(ctx, &ScrapeRequest{WebsiteURL: "https://example.com"})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v, want context.DeadlineExceeded", err)
	}
}
