package webscrape

import (
	"context"
	"fmt"
	"net/http"
	"time"
)

// SmartBrowse wait-helper defaults.
const (
	defaultPollInterval = 2 * time.Second
	maxPollInterval     = 10 * time.Second
	pollIntervalFactor  = 1.5
	defaultWaitTimeout  = 900 * time.Second
)

// RunStatus is a SmartBrowse run's lifecycle state. It is a string type so that
// unknown values shipped by the server are preserved verbatim rather than
// rejected; [RunStatus.IsTerminal] treats unknown values as non-terminal.
type RunStatus string

// Known run states.
const (
	RunStatusQueued    RunStatus = "queued"
	RunStatusRunning   RunStatus = "running"
	RunStatusCompleted RunStatus = "completed"
	RunStatusFailed    RunStatus = "failed"
	RunStatusCancelled RunStatus = "cancelled"
)

// IsTerminal reports whether the run has reached a terminal state (completed,
// failed, or cancelled). Unknown/unrecognized statuses are treated as
// non-terminal so a wait helper keeps polling.
func (s RunStatus) IsTerminal() bool {
	switch s {
	case RunStatusCompleted, RunStatusFailed, RunStatusCancelled:
		return true
	default:
		return false
	}
}

// SmartBrowseAPI groups the SmartBrowse recipe-replay endpoints. Access it via
// the [Client.SmartBrowse] field.
type SmartBrowseAPI struct {
	client *Client
}

// SmartBrowseDispatchData is the data payload of a dispatch (queued) response.
type SmartBrowseDispatchData struct {
	RunID     string    `json:"run_id"`
	RecipeID  string    `json:"recipe_id"`
	RunStatus RunStatus `json:"run_status"`
	PollURL   string    `json:"poll_url"`
	CreatedAt string    `json:"created_at"`
}

// SmartBrowseRunDispatch is the result of [SmartBrowseAPI.Run]. The dispatch
// envelope is "queued" and carries no credit accounting (nothing is billed
// until the run completes).
type SmartBrowseRunDispatch struct {
	Data      SmartBrowseDispatchData
	RequestID string
}

// SmartBrowseRunPage is one extracted page's items in a completed run result.
type SmartBrowseRunPage struct {
	Items []map[string]any `json:"items"`
}

// SmartBrowseRunResult is present once a run has completed.
type SmartBrowseRunResult struct {
	Pages    []SmartBrowseRunPage `json:"pages"`
	Mode     string               `json:"mode"`
	Drift    float64              `json:"drift"`
	Warnings []string             `json:"warnings"`
}

// SmartBrowseRun is the state of a SmartBrowse run.
type SmartBrowseRun struct {
	ID             string    `json:"id"`
	RecipeID       string    `json:"recipe_id"`
	RunStatus      RunStatus `json:"run_status"`
	PagesExtracted int       `json:"pages_extracted"`
	ItemsExtracted int       `json:"items_extracted"`
	// CreditsUsed is the run's accrued credit spend (distinct from the
	// envelope's credits_used, which is always 0 for polling).
	CreditsUsed int `json:"credits_used"`
	// StartedAt / CompletedAt are RFC3339 strings, absent while pending.
	StartedAt   *string `json:"started_at"`
	CompletedAt *string `json:"completed_at"`
	// Error is set only on failed/cancelled runs.
	Error string `json:"error"`
	// Result is present only once RunStatus is completed.
	Result    *SmartBrowseRunResult `json:"result"`
	CreatedAt string                `json:"created_at"`
}

// SmartBrowseRunResponse is the result of [SmartBrowseAPI.GetRun] and the wait
// helpers.
type SmartBrowseRunResponse struct {
	Data SmartBrowseRun
	// CreditsUsed is the envelope value, always 0 for polling. The run's
	// accrued spend is Data.CreditsUsed.
	CreditsUsed      int
	CreditsRemaining int
	RequestID        string
}

// SmartBrowseLastRun summarizes the caller's most recent run in a usage response.
type SmartBrowseLastRun struct {
	ID               string    `json:"id"`
	Status           RunStatus `json:"status"`
	PagesExtracted   int       `json:"pages_extracted"`
	EffectiveCap     int       `json:"effective_cap"`
	ClampedByCredits bool      `json:"clamped_by_credits"`
	CreatedAt        string    `json:"created_at"`
	CompletedAt      *string   `json:"completed_at"`
}

// SmartBrowseUsage is the data payload of a usage response.
type SmartBrowseUsage struct {
	RunsUsed30d      int                 `json:"runs_used_30d"`
	RunsPerMonthCap  int                 `json:"runs_per_month_cap"`
	PagesPerRunCap   int                 `json:"pages_per_run_cap"`
	CostPerPage      int                 `json:"cost_per_page"`
	SchedulesCount   int                 `json:"schedules_count"`
	SchedulesAllowed bool                `json:"schedules_allowed"`
	LastRun          *SmartBrowseLastRun `json:"last_run"`
}

// SmartBrowseUsageResponse is the result of [SmartBrowseAPI.Usage].
type SmartBrowseUsageResponse struct {
	Data SmartBrowseUsage
	// CreditsUsed is always 0 (polling usage is free).
	CreditsUsed      int
	CreditsRemaining int
	RequestID        string
}

// waitConfig holds the resolved SmartBrowse wait-helper options.
type waitConfig struct {
	pollInterval time.Duration
	timeout      time.Duration
}

// WaitOption configures [SmartBrowseAPI.WaitForRun] and
// [SmartBrowseAPI.RunAndWait].
type WaitOption func(*waitConfig)

// WithPollInterval sets the initial poll interval (default 2s). The interval
// grows by ×1.5 per poll, capped at 10s.
func WithPollInterval(d time.Duration) WaitOption {
	return func(w *waitConfig) {
		if d > 0 {
			w.pollInterval = d
		}
	}
}

// WithWaitTimeout sets the overall wait deadline (default 900s, matching the
// 15-minute hard run cap).
func WithWaitTimeout(d time.Duration) WaitOption {
	return func(w *waitConfig) {
		if d > 0 {
			w.timeout = d
		}
	}
}

// Run dispatches a recipe replay. It returns immediately with a run id to poll.
// The endpoint takes no request body. Cost: 2 credits per page, billed on
// completion. Endpoint errors include not_found (recipe missing/not owned),
// insufficient_credits, and rate_limited (reason "sb_runs_per_month").
func (s *SmartBrowseAPI) Run(ctx context.Context, recipeID string) (*SmartBrowseRunDispatch, error) {
	var data SmartBrowseDispatchData
	path := "/smartbrowse/recipes/" + recipeID + "/run"
	meta, err := s.client.call(ctx, http.MethodPost, path, nil, &data)
	if err != nil {
		return nil, err
	}
	return &SmartBrowseRunDispatch{Data: data, RequestID: meta.requestID}, nil
}

// GetRun polls a run's current state. Polling is free.
func (s *SmartBrowseAPI) GetRun(ctx context.Context, runID string) (*SmartBrowseRunResponse, error) {
	var data SmartBrowseRun
	path := "/smartbrowse/runs/" + runID
	meta, err := s.client.call(ctx, http.MethodGet, path, nil, &data)
	if err != nil {
		return nil, err
	}
	return &SmartBrowseRunResponse{
		Data:             data,
		CreditsUsed:      meta.creditsUsed,
		CreditsRemaining: meta.creditsRemaining,
		RequestID:        meta.requestID,
	}, nil
}

// Usage returns the caller's plan caps and rolling-30-day SmartBrowse usage.
func (s *SmartBrowseAPI) Usage(ctx context.Context) (*SmartBrowseUsageResponse, error) {
	var data SmartBrowseUsage
	meta, err := s.client.call(ctx, http.MethodGet, "/smartbrowse/usage", nil, &data)
	if err != nil {
		return nil, err
	}
	return &SmartBrowseUsageResponse{
		Data:             data,
		CreditsUsed:      meta.creditsUsed,
		CreditsRemaining: meta.creditsRemaining,
		RequestID:        meta.requestID,
	}, nil
}

// WaitForRun polls a run until it reaches a terminal state.
//
//   - completed → returns the run response.
//   - failed / cancelled → returns *[RunFailedError] (wrapping [ErrRunFailed])
//     carrying the full run.
//   - deadline exceeded → returns *[WaitTimeoutError] (wrapping
//     [ErrWaitTimeout]) carrying the last-seen run.
//
// The poll interval starts at 2s (override with [WithPollInterval]) and grows
// ×1.5 per poll, capped at 10s. The default timeout is 900s (override with
// [WithWaitTimeout]). Context cancellation is honored and returned as-is.
func (s *SmartBrowseAPI) WaitForRun(ctx context.Context, runID string, opts ...WaitOption) (*SmartBrowseRunResponse, error) {
	cfg := waitConfig{pollInterval: defaultPollInterval, timeout: defaultWaitTimeout}
	for _, opt := range opts {
		opt(&cfg)
	}

	deadline := time.Now().Add(cfg.timeout)
	interval := cfg.pollInterval
	var last *SmartBrowseRun

	for {
		resp, err := s.GetRun(ctx, runID)
		if err != nil {
			return nil, err
		}
		last = &resp.Data

		switch resp.Data.RunStatus {
		case RunStatusCompleted:
			return resp, nil
		case RunStatusFailed, RunStatusCancelled:
			return nil, &RunFailedError{Run: &resp.Data}
		}

		now := time.Now()
		if !now.Before(deadline) {
			return nil, &WaitTimeoutError{Run: last}
		}

		sleep := interval
		if now.Add(sleep).After(deadline) {
			sleep = deadline.Sub(now)
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(sleep):
		}

		interval = time.Duration(float64(interval) * pollIntervalFactor)
		if interval > maxPollInterval {
			interval = maxPollInterval
		}
	}
}

// RunAndWait dispatches a recipe replay and waits for it to finish, combining
// [SmartBrowseAPI.Run] and [SmartBrowseAPI.WaitForRun] with the same options.
func (s *SmartBrowseAPI) RunAndWait(ctx context.Context, recipeID string, opts ...WaitOption) (*SmartBrowseRunResponse, error) {
	dispatch, err := s.Run(ctx, recipeID)
	if err != nil {
		return nil, err
	}
	if dispatch.Data.RunID == "" {
		return nil, fmt.Errorf("webscrape: dispatch response missing run_id (request_id=%s)", dispatch.RequestID)
	}
	return s.WaitForRun(ctx, dispatch.Data.RunID, opts...)
}
