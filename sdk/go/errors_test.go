package webscrape

import (
	"context"
	"errors"
	"net"
	"net/http"
	"testing"
)

func TestErrorEnvelopeMapping(t *testing.T) {
	tests := []struct {
		name       string
		status     int
		body       string
		wantCode   Code
		predicate  func(error) bool
		checkExtra func(t *testing.T, err error)
	}{
		{
			name:      "insufficient_credits with details",
			status:    http.StatusPaymentRequired,
			body:      `{"status":"error","error":{"code":"insufficient_credits","message":"nope","details":{"balance":2,"required":5}},"request_id":"req_ic"}`,
			wantCode:  CodeInsufficientCredits,
			predicate: IsInsufficientCredits,
			checkExtra: func(t *testing.T, err error) {
				apiErr, ok := AsAPIError(err)
				if !ok {
					t.Fatal("not an APIError")
				}
				balance, required, ok := apiErr.Credits()
				if !ok || balance != 2 || required != 5 {
					t.Errorf("Credits() = %d,%d,%v", balance, required, ok)
				}
				if apiErr.RequestID != "req_ic" {
					t.Errorf("RequestID = %q", apiErr.RequestID)
				}
			},
		},
		{
			name:      "email_verification_required",
			status:    http.StatusPaymentRequired,
			body:      `{"status":"error","error":{"code":"email_verification_required","message":"verify email"},"request_id":"req_ev"}`,
			wantCode:  CodeEmailVerificationRequired,
			predicate: IsEmailVerificationRequired,
		},
		{
			name:      "not_found",
			status:    http.StatusNotFound,
			body:      `{"status":"error","error":{"code":"not_found","message":"recipe not found"},"request_id":"req_nf"}`,
			wantCode:  CodeNotFound,
			predicate: IsNotFound,
		},
		{
			name:      "validation_failed",
			status:    http.StatusUnprocessableEntity,
			body:      `{"status":"error","error":{"code":"validation_failed","message":"schema mismatch","details":{"type":"schema_validation_error","errors":[]}},"request_id":"req_vf"}`,
			wantCode:  CodeValidationFailed,
			predicate: IsValidationFailed,
		},
		{
			name:      "rate_limited with reason",
			status:    http.StatusTooManyRequests,
			body:      `{"status":"error","error":{"code":"rate_limited","message":"quota","details":{"used":50,"limit":50,"window":"30d","reason":"sb_runs_per_month"}},"request_id":"req_rl"}`,
			wantCode:  CodeRateLimited,
			predicate: IsRateLimited,
			checkExtra: func(t *testing.T, err error) {
				apiErr, _ := AsAPIError(err)
				if got := apiErr.RateLimitReason(); got != "sb_runs_per_month" {
					t.Errorf("RateLimitReason() = %q", got)
				}
			},
		},
		{
			name:      "account_deletion_pending is a conflict",
			status:    http.StatusConflict,
			body:      `{"status":"error","error":{"code":"account_deletion_pending","message":"pending","details":{"deletion_scheduled_for":"2026-06-15T12:00:00Z"}},"request_id":"req_ad"}`,
			wantCode:  CodeAccountDeletionPending,
			predicate: IsConflict,
			checkExtra: func(t *testing.T, err error) {
				if !IsAccountDeletionPending(err) {
					t.Error("IsAccountDeletionPending = false")
				}
			},
		},
		{
			name:      "forbidden",
			status:    http.StatusForbidden,
			body:      `{"status":"error","error":{"code":"forbidden","message":"nope"},"request_id":"req_fb"}`,
			wantCode:  CodeForbidden,
			predicate: IsForbidden,
		},
		{
			name:      "internal_error is a server error",
			status:    http.StatusInternalServerError,
			body:      `{"status":"error","error":{"code":"internal_error","message":"boom"},"request_id":"req_ie"}`,
			wantCode:  CodeInternalError,
			predicate: IsServerError,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
				mustJSON(t, w, tc.status, tc.body)
			}, WithMaxRetries(0))

			_, err := client.Scrape(context.Background(), &ScrapeRequest{WebsiteURL: "https://example.com"})
			apiErr, ok := AsAPIError(err)
			if !ok {
				t.Fatalf("err = %v, want *APIError", err)
			}
			if apiErr.Code != tc.wantCode {
				t.Errorf("Code = %q, want %q", apiErr.Code, tc.wantCode)
			}
			if apiErr.StatusCode != tc.status {
				t.Errorf("StatusCode = %d, want %d", apiErr.StatusCode, tc.status)
			}
			if !tc.predicate(err) {
				t.Errorf("predicate returned false for %v", err)
			}
			if tc.checkExtra != nil {
				tc.checkExtra(t, err)
			}
		})
	}
}

func TestBare401AuthShape(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Request-ID", "req_fromheader")
		mustJSON(t, w, http.StatusUnauthorized, `{"error":"missing or invalid credentials — provide a session cookie or X-API-Key header"}`)
	}, WithMaxRetries(0))

	_, err := client.Scrape(context.Background(), &ScrapeRequest{WebsiteURL: "https://example.com"})
	apiErr, ok := AsAPIError(err)
	if !ok {
		t.Fatalf("err = %v, want *APIError", err)
	}
	if apiErr.Code != CodeUnauthorized {
		t.Errorf("Code = %q, want unauthorized", apiErr.Code)
	}
	if !IsUnauthorized(err) {
		t.Error("IsUnauthorized = false")
	}
	if apiErr.RequestID != "req_fromheader" {
		t.Errorf("RequestID = %q, want fallback to X-Request-ID header", apiErr.RequestID)
	}
	if apiErr.Message == "" {
		t.Error("Message is empty; want the bare-string error text")
	}
}

func TestUnknownErrorCodePreserved(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		mustJSON(t, w, http.StatusBadRequest, `{"status":"error","error":{"code":"some_new_code","message":"a code the SDK has never heard of"},"request_id":"req_unk"}`)
	}, WithMaxRetries(0))

	_, err := client.Scrape(context.Background(), &ScrapeRequest{WebsiteURL: "https://example.com"})
	apiErr, ok := AsAPIError(err)
	if !ok {
		t.Fatalf("err = %v, want *APIError", err)
	}
	if apiErr.Code != Code("some_new_code") {
		t.Errorf("Code = %q, want raw string preserved", apiErr.Code)
	}
	// Unknown code must not accidentally satisfy a known predicate.
	if IsBadRequest(err) || IsRateLimited(err) || IsUnauthorized(err) {
		t.Error("unknown code satisfied a known predicate")
	}
}

func TestIsRetryableTransportError(t *testing.T) {
	if isRetryableTransportError(nil) {
		t.Error("nil should not be retryable")
	}
	if isRetryableTransportError(context.DeadlineExceeded) {
		t.Error("deadline exceeded must not be retryable")
	}
	if isRetryableTransportError(context.Canceled) {
		t.Error("canceled must not be retryable")
	}
	dialErr := &net.OpError{Op: "dial", Err: errors.New("connection refused")}
	if !isRetryableTransportError(dialErr) {
		t.Error("dial OpError should be retryable")
	}
	readErr := &net.OpError{Op: "read", Err: errors.New("reset")}
	if isRetryableTransportError(readErr) {
		t.Error("read OpError (mid-response) must not be retryable")
	}
	dnsErr := &net.DNSError{Err: "no such host"}
	if !isRetryableTransportError(dnsErr) {
		t.Error("DNS error should be retryable")
	}
}
