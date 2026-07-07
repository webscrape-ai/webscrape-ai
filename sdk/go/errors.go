package webscrape

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
)

// Code is a stable, machine-branchable error code returned by the API in
// error.code. New codes may ship without an SDK release, so callers must
// tolerate values not covered by the constants below (see [AsAPIError] and
// keep the raw string accessible via APIError.Code).
type Code string

// Stable error codes.
const (
	CodeInvalidRequest            Code = "invalid_request"
	CodeUnauthorized              Code = "unauthorized"
	CodeInsufficientCredits       Code = "insufficient_credits"
	CodeEmailVerificationRequired Code = "email_verification_required"
	CodeForbidden                 Code = "forbidden"
	CodeNotFound                  Code = "not_found"
	CodeConflict                  Code = "conflict"
	CodeAccountDeletionPending    Code = "account_deletion_pending"
	CodeValidationFailed          Code = "validation_failed"
	CodeRateLimited               Code = "rate_limited"
	CodeInternalError             Code = "internal_error"
	CodeServiceUnavailable        Code = "service_unavailable"
)

// ErrNoAPIKey is returned by [New] when no API key was provided and the
// WEBSCRAPE_API_KEY environment variable is unset.
var ErrNoAPIKey = errors.New("webscrape: no API key provided (set WithAPIKey or the WEBSCRAPE_API_KEY environment variable)")

// APIError is the single error type for all API-level failures (any non-2xx
// response with a decodable error envelope, plus the plain
// {"error": "<message>"} authentication-failure shape). It implements error and
// supports [errors.As].
//
// Branch on it with the package predicate helpers ([IsRateLimited],
// [IsNotFound], ...) or with [errors.As]:
//
//	var apiErr *webscrape.APIError
//	if errors.As(err, &apiErr) {
//		log.Printf("code=%s status=%d request_id=%s", apiErr.Code, apiErr.StatusCode, apiErr.RequestID)
//	}
type APIError struct {
	// StatusCode is the HTTP status of the response.
	StatusCode int
	// Code is the stable error code. Unknown codes are preserved verbatim.
	Code Code
	// Message is the human-readable error text. It may change between
	// releases; log it, never pattern-match it.
	Message string
	// Details is the raw error.details JSON, or nil when absent. Decode it
	// with [APIError.DecodeDetails] or use the typed accessors.
	Details json.RawMessage
	// RequestID is the envelope request_id (falling back to the
	// X-Request-ID response header). Include it when reporting issues.
	RequestID string
}

// Error implements the error interface.
func (e *APIError) Error() string {
	if e.RequestID != "" {
		return fmt.Sprintf("webscrape: %d %s: %s (request_id=%s)", e.StatusCode, e.Code, e.Message, e.RequestID)
	}
	return fmt.Sprintf("webscrape: %d %s: %s", e.StatusCode, e.Code, e.Message)
}

// DecodeDetails unmarshals error.details into v. It returns an error when there
// are no details to decode.
func (e *APIError) DecodeDetails(v any) error {
	if len(e.Details) == 0 {
		return errors.New("webscrape: error has no details")
	}
	return json.Unmarshal(e.Details, v)
}

// Credits returns the balance and required amounts from an insufficient_credits
// error. ok is false when the details are absent or not shaped that way.
func (e *APIError) Credits() (balance, required int, ok bool) {
	if len(e.Details) == 0 {
		return 0, 0, false
	}
	var d struct {
		Balance  *int `json:"balance"`
		Required *int `json:"required"`
	}
	if json.Unmarshal(e.Details, &d) != nil {
		return 0, 0, false
	}
	if d.Balance == nil && d.Required == nil {
		return 0, 0, false
	}
	if d.Balance != nil {
		balance = *d.Balance
	}
	if d.Required != nil {
		required = *d.Required
	}
	return balance, required, true
}

// RateLimitReason returns error.details.reason from a rate_limited error
// (e.g. "rate_limit_per_min", "max_concurrent_requests", "sb_runs_per_month"),
// or "" when absent.
func (e *APIError) RateLimitReason() string {
	if len(e.Details) == 0 {
		return ""
	}
	var d struct {
		Reason string `json:"reason"`
	}
	if json.Unmarshal(e.Details, &d) != nil {
		return ""
	}
	return d.Reason
}

// AsAPIError extracts an *APIError from err via [errors.As].
func AsAPIError(err error) (*APIError, bool) {
	var e *APIError
	if errors.As(err, &e) {
		return e, true
	}
	return nil, false
}

func hasCode(err error, code Code) bool {
	var e *APIError
	return errors.As(err, &e) && e.Code == code
}

// IsBadRequest reports whether err is an invalid_request (400) API error.
func IsBadRequest(err error) bool { return hasCode(err, CodeInvalidRequest) }

// IsUnauthorized reports whether err is an unauthorized (401) API error.
// Authentication failures may return a plain {"error": "<message>"} body
// instead of the standard envelope; both shapes are handled.
func IsUnauthorized(err error) bool { return hasCode(err, CodeUnauthorized) }

// IsInsufficientCredits reports whether err is an insufficient_credits (402)
// API error. Use [APIError.Credits] for the balance/required amounts.
func IsInsufficientCredits(err error) bool { return hasCode(err, CodeInsufficientCredits) }

// IsEmailVerificationRequired reports whether err is an
// email_verification_required (402) API error.
func IsEmailVerificationRequired(err error) bool { return hasCode(err, CodeEmailVerificationRequired) }

// IsForbidden reports whether err is a forbidden (403) API error.
func IsForbidden(err error) bool { return hasCode(err, CodeForbidden) }

// IsNotFound reports whether err is a not_found (404) API error.
func IsNotFound(err error) bool { return hasCode(err, CodeNotFound) }

// IsConflict reports whether err is a conflict (409) API error, including
// account_deletion_pending.
func IsConflict(err error) bool {
	return hasCode(err, CodeConflict) || hasCode(err, CodeAccountDeletionPending)
}

// IsAccountDeletionPending reports whether err is an account_deletion_pending
// (409) API error.
func IsAccountDeletionPending(err error) bool { return hasCode(err, CodeAccountDeletionPending) }

// IsValidationFailed reports whether err is a validation_failed (422) API error.
func IsValidationFailed(err error) bool { return hasCode(err, CodeValidationFailed) }

// IsRateLimited reports whether err is a rate_limited (429) API error. Use
// [APIError.RateLimitReason] for the throttle reason.
func IsRateLimited(err error) bool { return hasCode(err, CodeRateLimited) }

// IsServerError reports whether err is a server-side API error
// (internal_error or service_unavailable).
func IsServerError(err error) bool {
	return hasCode(err, CodeInternalError) || hasCode(err, CodeServiceUnavailable)
}

// ErrRunFailed is the sentinel wrapped by *[RunFailedError]. Match it with
// errors.Is(err, ErrRunFailed).
var ErrRunFailed = errors.New("webscrape: smartbrowse run did not complete successfully")

// ErrWaitTimeout is the sentinel wrapped by *[WaitTimeoutError]. Match it with
// errors.Is(err, ErrWaitTimeout).
var ErrWaitTimeout = errors.New("webscrape: timed out waiting for smartbrowse run")

// RunFailedError is returned by the SmartBrowse wait helpers when a run reaches
// a failed or cancelled terminal state. It carries the full run so error,
// pages_extracted, and credits_used are inspectable. It unwraps to
// [ErrRunFailed].
type RunFailedError struct {
	// Run is the terminal run state.
	Run *SmartBrowseRun
}

func (e *RunFailedError) Error() string {
	if e.Run == nil {
		return ErrRunFailed.Error()
	}
	msg := e.Run.Error
	if msg == "" {
		msg = "no error message"
	}
	return fmt.Sprintf("webscrape: smartbrowse run %s ended %s: %s", e.Run.ID, e.Run.RunStatus, msg)
}

// Unwrap returns [ErrRunFailed].
func (e *RunFailedError) Unwrap() error { return ErrRunFailed }

// WaitTimeoutError is returned by the SmartBrowse wait helpers when the wait
// deadline is exceeded before the run reaches a terminal state. It carries the
// last-seen run (which may be nil if the first poll failed). It unwraps to
// [ErrWaitTimeout].
type WaitTimeoutError struct {
	// Run is the most recently observed run state, or nil.
	Run *SmartBrowseRun
}

func (e *WaitTimeoutError) Error() string {
	if e.Run == nil {
		return ErrWaitTimeout.Error()
	}
	return fmt.Sprintf("webscrape: timed out waiting for smartbrowse run %s (last status %s)", e.Run.ID, e.Run.RunStatus)
}

// Unwrap returns [ErrWaitTimeout].
func (e *WaitTimeoutError) Unwrap() error { return ErrWaitTimeout }

// parseAPIError builds an *APIError from a non-2xx response body. Authentication
// failures may return a plain {"error": "<message>"} body instead of the
// standard envelope; both shapes are handled. headerRequestID is the
// X-Request-ID response header, used as a fallback when the body carries no
// request_id.
func parseAPIError(status int, body []byte, headerRequestID string) *APIError {
	apiErr := &APIError{StatusCode: status, RequestID: headerRequestID}

	var env struct {
		RequestID string          `json:"request_id"`
		Error     json.RawMessage `json:"error"`
	}
	if json.Unmarshal(body, &env) == nil {
		if env.RequestID != "" {
			apiErr.RequestID = env.RequestID
		}
		if len(env.Error) > 0 {
			// Standard envelope: error is an object with a code.
			var obj struct {
				Code    string          `json:"code"`
				Message string          `json:"message"`
				Details json.RawMessage `json:"details"`
			}
			if json.Unmarshal(env.Error, &obj) == nil && obj.Code != "" {
				apiErr.Code = Code(obj.Code)
				apiErr.Message = obj.Message
				apiErr.Details = obj.Details
				return apiErr
			}
			// Plain shape: error is a string (e.g. a plain 401
			// authentication failure). Synthesize a code from the status.
			var s string
			if json.Unmarshal(env.Error, &s) == nil {
				apiErr.Message = s
			}
		}
	}

	if apiErr.Code == "" {
		apiErr.Code = codeForStatus(status)
	}
	if apiErr.Message == "" {
		if txt := http.StatusText(status); txt != "" {
			apiErr.Message = txt
		} else {
			apiErr.Message = "unknown error"
		}
	}
	return apiErr
}

// codeForStatus maps an HTTP status to a best-effort error code, used only when
// the response body carries no structured code (the plain 401 authentication
// shape is the common case; other statuses are covered defensively for
// non-envelope errors emitted by intermediaries). 402 is intentionally omitted
// because it is ambiguous between insufficient_credits and
// email_verification_required.
func codeForStatus(status int) Code {
	switch status {
	case http.StatusBadRequest:
		return CodeInvalidRequest
	case http.StatusUnauthorized:
		return CodeUnauthorized
	case http.StatusForbidden:
		return CodeForbidden
	case http.StatusNotFound:
		return CodeNotFound
	case http.StatusConflict:
		return CodeConflict
	case http.StatusUnprocessableEntity:
		return CodeValidationFailed
	case http.StatusTooManyRequests:
		return CodeRateLimited
	case http.StatusInternalServerError:
		return CodeInternalError
	case http.StatusBadGateway, http.StatusServiceUnavailable:
		return CodeServiceUnavailable
	default:
		return ""
	}
}
