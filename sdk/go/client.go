package webscrape

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// Default client configuration.
const (
	defaultBaseURL    = "https://api.webscrape.ai/v1"
	defaultTimeout    = 180 * time.Second
	defaultMaxRetries = 2
	defaultBaseDelay  = 1 * time.Second
	defaultMaxDelay   = 30 * time.Second
)

// Client is a webscrape.ai API client. It is safe for concurrent use.
//
// Construct one with [New]. The SmartBrowse recipe-replay endpoints are reached
// through the [Client.SmartBrowse] field.
type Client struct {
	apiKey     string
	baseURL    string
	userAgent  string
	timeout    time.Duration
	maxRetries int
	httpClient *http.Client

	// backoff parameters (overridable in tests for deterministic timing).
	baseDelay time.Duration
	maxDelay  time.Duration
	rng       func() float64

	// SmartBrowse groups the recipe-replay endpoints.
	SmartBrowse *SmartBrowseAPI
}

// Option configures a [Client] in [New].
type Option func(*Client)

// WithAPIKey sets the API key. When unset, the WEBSCRAPE_API_KEY environment
// variable is used.
func WithAPIKey(key string) Option {
	return func(c *Client) { c.apiKey = key }
}

// WithBaseURL overrides the API base URL (e.g. for staging or self-hosting).
// A trailing slash is tolerated. An empty value is ignored.
func WithBaseURL(baseURL string) Option {
	return func(c *Client) {
		if baseURL != "" {
			c.baseURL = baseURL
		}
	}
}

// WithTimeout sets the per-request timeout applied to each HTTP attempt
// (default 180s). A value <= 0 disables the SDK-managed timeout, deferring
// entirely to the caller's context.
func WithTimeout(d time.Duration) Option {
	return func(c *Client) { c.timeout = d }
}

// WithMaxRetries sets the maximum number of retries (default 2, i.e. up to 3
// attempts). 0 disables retries. Negative values are clamped to 0.
func WithMaxRetries(n int) Option {
	return func(c *Client) { c.maxRetries = n }
}

// WithHTTPClient supplies a custom *http.Client (custom transport, proxy, TLS,
// etc.). Per-request timeouts are applied via context, so setting the client's
// own Timeout is optional. A nil client is ignored.
func WithHTTPClient(hc *http.Client) Option {
	return func(c *Client) {
		if hc != nil {
			c.httpClient = hc
		}
	}
}

// New constructs a [Client]. With no [WithAPIKey] option the API key is read
// from the WEBSCRAPE_API_KEY environment variable; if neither is present, New
// returns [ErrNoAPIKey].
func New(opts ...Option) (*Client, error) {
	c := &Client{
		baseURL:    defaultBaseURL,
		userAgent:  userAgent,
		timeout:    defaultTimeout,
		maxRetries: defaultMaxRetries,
		baseDelay:  defaultBaseDelay,
		maxDelay:   defaultMaxDelay,
		rng:        rand.Float64,
	}
	for _, opt := range opts {
		opt(c)
	}
	if c.apiKey == "" {
		c.apiKey = os.Getenv("WEBSCRAPE_API_KEY")
	}
	if c.apiKey == "" {
		return nil, ErrNoAPIKey
	}
	if c.maxRetries < 0 {
		c.maxRetries = 0
	}
	if c.httpClient == nil {
		c.httpClient = &http.Client{}
	}
	c.baseURL = strings.TrimRight(c.baseURL, "/")
	c.SmartBrowse = &SmartBrowseAPI{client: c}
	return c, nil
}

// envelopeMeta holds the envelope-level fields shared by success responses.
type envelopeMeta struct {
	creditsUsed      int
	creditsRemaining int
	requestID        string
}

// attemptResult is the outcome of a single HTTP attempt that produced a
// response (not a transport error).
type attemptResult struct {
	status     int
	body       []byte
	requestID  string
	retryAfter time.Duration
}

// call performs a request with retries and decodes the success envelope. On a
// non-2xx response (or an envelope with status "error") it returns an
// *[APIError]. The decoded data is written into dataOut when non-nil.
func (c *Client) call(ctx context.Context, method, path string, reqBody, dataOut any) (envelopeMeta, error) {
	res, err := c.do(ctx, method, path, reqBody)
	if err != nil {
		return envelopeMeta{}, err
	}
	if res.status < 200 || res.status >= 300 {
		return envelopeMeta{}, parseAPIError(res.status, res.body, res.requestID)
	}

	var env struct {
		Status           string          `json:"status"`
		Data             json.RawMessage `json:"data"`
		CreditsUsed      *int            `json:"credits_used"`
		CreditsRemaining *int            `json:"credits_remaining"`
		RequestID        string          `json:"request_id"`
	}
	if err := json.Unmarshal(res.body, &env); err != nil {
		return envelopeMeta{}, fmt.Errorf("webscrape: decode response envelope: %w", err)
	}
	// Defensive: a 2xx with an error envelope should still surface as an error.
	if env.Status == "error" {
		return envelopeMeta{}, parseAPIError(res.status, res.body, res.requestID)
	}
	if dataOut != nil && len(env.Data) > 0 {
		if err := json.Unmarshal(env.Data, dataOut); err != nil {
			return envelopeMeta{}, fmt.Errorf("webscrape: decode response data: %w", err)
		}
	}

	meta := envelopeMeta{requestID: env.RequestID}
	if meta.requestID == "" {
		meta.requestID = res.requestID
	}
	if env.CreditsUsed != nil {
		meta.creditsUsed = *env.CreditsUsed
	}
	if env.CreditsRemaining != nil {
		meta.creditsRemaining = *env.CreditsRemaining
	}
	return meta, nil
}

// do runs the retry loop around doAttempt.
func (c *Client) do(ctx context.Context, method, path string, reqBody any) (attemptResult, error) {
	var payload []byte
	if reqBody != nil {
		var err error
		payload, err = json.Marshal(reqBody)
		if err != nil {
			return attemptResult{}, fmt.Errorf("webscrape: encode request body: %w", err)
		}
	}

	url := c.baseURL + path
	maxAttempts := c.maxRetries + 1
	var lastErr error

	for attempt := 0; attempt < maxAttempts; attempt++ {
		res, err := c.doAttempt(ctx, method, url, payload)
		if err != nil {
			// Caller cancelled or its deadline elapsed: stop and pass through.
			if ctxErr := ctx.Err(); ctxErr != nil {
				return attemptResult{}, ctxErr
			}
			lastErr = err
			if attempt < maxAttempts-1 && isRetryableTransportError(err) {
				if werr := c.waitBackoff(ctx, attempt, 0); werr != nil {
					return attemptResult{}, werr
				}
				continue
			}
			return attemptResult{}, fmt.Errorf("webscrape: request failed: %w", err)
		}

		if attempt < maxAttempts-1 && isRetryableStatus(res.status) {
			if werr := c.waitBackoff(ctx, attempt, res.retryAfter); werr != nil {
				return attemptResult{}, werr
			}
			continue
		}
		return res, nil
	}

	return attemptResult{}, fmt.Errorf("webscrape: request failed after %d attempts: %w", maxAttempts, lastErr)
}

// doAttempt performs a single HTTP attempt and fully reads the response body.
func (c *Client) doAttempt(ctx context.Context, method, url string, payload []byte) (attemptResult, error) {
	reqCtx, cancel := c.requestContext(ctx)
	defer cancel()

	var body io.Reader
	if payload != nil {
		body = bytes.NewReader(payload)
	}
	req, err := http.NewRequestWithContext(reqCtx, method, url, body)
	if err != nil {
		return attemptResult{}, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("X-API-Key", c.apiKey)
	req.Header.Set("User-Agent", c.userAgent)
	req.Header.Set("Accept", "application/json")
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return attemptResult{}, err
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		// The request reached the server but the body read failed mid-stream,
		// so it is not safe to retry; surface the error instead.
		return attemptResult{}, fmt.Errorf("read response body: %w", err)
	}

	return attemptResult{
		status:     resp.StatusCode,
		body:       data,
		requestID:  resp.Header.Get("X-Request-ID"),
		retryAfter: parseRetryAfter(resp.Header.Get("Retry-After")),
	}, nil
}

// requestContext derives a per-attempt context bounded by the configured
// timeout (if any).
func (c *Client) requestContext(ctx context.Context) (context.Context, context.CancelFunc) {
	if c.timeout <= 0 {
		return ctx, func() {}
	}
	return context.WithTimeout(ctx, c.timeout)
}

// waitBackoff sleeps before the next retry attempt, honoring context
// cancellation.
func (c *Client) waitBackoff(ctx context.Context, attempt int, retryAfter time.Duration) error {
	d := c.backoffDelay(attempt, retryAfter)
	if d <= 0 {
		return nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// backoffDelay computes the delay for a retry using exponential backoff with
// full jitter (base 1s, factor 2, cap 30s). A positive Retry-After takes
// precedence.
func (c *Client) backoffDelay(attempt int, retryAfter time.Duration) time.Duration {
	if retryAfter > 0 {
		return retryAfter
	}
	if c.baseDelay <= 0 {
		return 0
	}
	exp := c.baseDelay << attempt
	if exp <= 0 || exp > c.maxDelay {
		exp = c.maxDelay
	}
	// Full jitter: uniform in [0, exp).
	return time.Duration(c.rng() * float64(exp))
}

// isRetryableStatus reports whether an HTTP status is in the billing-safe
// retry set (429, 500, 502, 503).
func isRetryableStatus(status int) bool {
	switch status {
	case http.StatusTooManyRequests,
		http.StatusInternalServerError,
		http.StatusBadGateway,
		http.StatusServiceUnavailable:
		return true
	default:
		return false
	}
}

// isRetryableTransportError reports whether a transport error is a
// connection-establishment failure, the only transport class that is
// billing-safe to retry (the request demonstrably never reached the server).
// Context cancellation/deadline and mid-response failures are excluded.
func isRetryableTransportError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return false
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		return true
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) {
		// A "dial" op means the connection was never established.
		return opErr.Op == "dial"
	}
	return false
}

// parseRetryAfter parses a Retry-After header value (delta-seconds or HTTP
// date) into a duration. It returns 0 when absent or unparseable.
func parseRetryAfter(v string) time.Duration {
	v = strings.TrimSpace(v)
	if v == "" {
		return 0
	}
	if secs, err := strconv.Atoi(v); err == nil {
		if secs <= 0 {
			return 0
		}
		return time.Duration(secs) * time.Second
	}
	if t, err := http.ParseTime(v); err == nil {
		if d := time.Until(t); d > 0 {
			return d
		}
	}
	return 0
}
