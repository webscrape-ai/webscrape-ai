package webscrape

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"
)

func runEnvelope(status RunStatus, extra string) string {
	body := `{"status":"completed","data":{"id":"run1","recipe_id":"rec1","run_status":"` + string(status) + `","pages_extracted":3,"items_extracted":10,"credits_used":6,"created_at":"2026-07-06T12:00:00Z"` + extra + `},"credits_used":0,"credits_remaining":400,"request_id":"req_run"}`
	return body
}

func TestSmartBrowseRunDispatch(t *testing.T) {
	var cap capture
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		cap.record(r)
		mustJSON(t, w, http.StatusAccepted, `{"status":"queued","data":{"run_id":"k7Xb9dRmQ2p","recipe_id":"m3Yc2tFvN8q","run_status":"running","poll_url":"/v1/smartbrowse/runs/k7Xb9dRmQ2p","created_at":"2026-07-06T12:00:00Z"},"request_id":"req_disp"}`)
	})

	disp, err := client.SmartBrowse.Run(context.Background(), "m3Yc2tFvN8q")
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	method, path, header, body, _ := cap.snapshot()
	if method != http.MethodPost || path != "/smartbrowse/recipes/m3Yc2tFvN8q/run" {
		t.Errorf("got %s %s", method, path)
	}
	if len(body) != 0 {
		t.Errorf("dispatch must send no body, got %q", body)
	}
	if got := header.Get("Content-Type"); got != "" {
		t.Errorf("Content-Type should be unset for bodyless POST, got %q", got)
	}
	if disp.Data.RunID != "k7Xb9dRmQ2p" || disp.Data.RunStatus != RunStatusRunning {
		t.Errorf("dispatch data = %+v", disp.Data)
	}
	if disp.RequestID != "req_disp" {
		t.Errorf("RequestID = %q", disp.RequestID)
	}
}

func TestSmartBrowseGetRunAndUsage(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/smartbrowse/runs/run1":
			mustJSON(t, w, http.StatusOK, runEnvelope(RunStatusCompleted, `,"result":{"pages":[{"items":[{"a":"b"}]}],"mode":"replay","drift":0.1,"warnings":[]}`))
		case "/smartbrowse/usage":
			mustJSON(t, w, http.StatusOK, `{"status":"completed","data":{"runs_used_30d":4,"runs_per_month_cap":50,"pages_per_run_cap":20,"cost_per_page":2,"schedules_count":1,"schedules_allowed":true,"last_run":{"id":"run1","status":"completed","pages_extracted":3,"effective_cap":20,"clamped_by_credits":false,"created_at":"2026-07-06T12:00:00Z"}},"credits_used":0,"credits_remaining":400,"request_id":"req_usage"}`)
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	run, err := client.SmartBrowse.GetRun(context.Background(), "run1")
	if err != nil {
		t.Fatalf("GetRun: %v", err)
	}
	if run.Data.RunStatus != RunStatusCompleted {
		t.Errorf("run status = %q", run.Data.RunStatus)
	}
	if run.CreditsUsed != 0 {
		t.Errorf("envelope CreditsUsed should be 0 for polling, got %d", run.CreditsUsed)
	}
	if run.Data.CreditsUsed != 6 {
		t.Errorf("run accrued credits = %d, want 6", run.Data.CreditsUsed)
	}
	if run.Data.Result == nil || len(run.Data.Result.Pages) != 1 || len(run.Data.Result.Pages[0].Items) != 1 {
		t.Fatalf("result = %+v", run.Data.Result)
	}
	if run.Data.Result.Pages[0].Items[0]["a"] != "b" {
		t.Errorf("item = %+v", run.Data.Result.Pages[0].Items[0])
	}

	usage, err := client.SmartBrowse.Usage(context.Background())
	if err != nil {
		t.Fatalf("Usage: %v", err)
	}
	if usage.Data.RunsPerMonthCap != 50 || usage.Data.CostPerPage != 2 {
		t.Errorf("usage = %+v", usage.Data)
	}
	if usage.Data.LastRun == nil || usage.Data.LastRun.EffectiveCap != 20 {
		t.Errorf("last_run = %+v", usage.Data.LastRun)
	}
}

func TestWaitForRunPollsUntilCompleted(t *testing.T) {
	var cap capture
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		cap.record(r)
		_, _, _, _, calls := cap.snapshot()
		switch {
		case calls < 3:
			mustJSON(t, w, http.StatusOK, runEnvelope(RunStatusRunning, ""))
		default:
			mustJSON(t, w, http.StatusOK, runEnvelope(RunStatusCompleted, ""))
		}
	})

	run, err := client.SmartBrowse.WaitForRun(context.Background(), "run1",
		WithPollInterval(2*time.Millisecond), WithWaitTimeout(5*time.Second))
	if err != nil {
		t.Fatalf("WaitForRun: %v", err)
	}
	if run.Data.RunStatus != RunStatusCompleted {
		t.Errorf("final status = %q", run.Data.RunStatus)
	}
	if _, _, _, _, calls := cap.snapshot(); calls != 3 {
		t.Errorf("polls = %d, want 3", calls)
	}
}

func TestWaitForRunFailedReturnsRunFailedError(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		mustJSON(t, w, http.StatusOK, runEnvelope(RunStatusFailed, `,"error":"boom"`))
	})

	_, err := client.SmartBrowse.WaitForRun(context.Background(), "run1",
		WithPollInterval(2*time.Millisecond))
	if !errors.Is(err, ErrRunFailed) {
		t.Fatalf("err = %v, want ErrRunFailed", err)
	}
	var rfe *RunFailedError
	if !errors.As(err, &rfe) {
		t.Fatalf("err = %v, want *RunFailedError", err)
	}
	if rfe.Run == nil || rfe.Run.Error != "boom" || rfe.Run.RunStatus != RunStatusFailed {
		t.Errorf("carried run = %+v", rfe.Run)
	}
	if rfe.Run.CreditsUsed != 6 {
		t.Errorf("carried run credits = %d", rfe.Run.CreditsUsed)
	}
}

func TestWaitForRunDeadlineReturnsWaitTimeoutError(t *testing.T) {
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		mustJSON(t, w, http.StatusOK, runEnvelope(RunStatusRunning, ""))
	})

	_, err := client.SmartBrowse.WaitForRun(context.Background(), "run1",
		WithPollInterval(3*time.Millisecond), WithWaitTimeout(40*time.Millisecond))
	if !errors.Is(err, ErrWaitTimeout) {
		t.Fatalf("err = %v, want ErrWaitTimeout", err)
	}
	var wte *WaitTimeoutError
	if !errors.As(err, &wte) {
		t.Fatalf("err = %v, want *WaitTimeoutError", err)
	}
	if wte.Run == nil || wte.Run.RunStatus != RunStatusRunning {
		t.Errorf("last-seen run = %+v", wte.Run)
	}
}

func TestWaitForRunUnknownStatusKeepsPolling(t *testing.T) {
	var cap capture
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		cap.record(r)
		_, _, _, _, calls := cap.snapshot()
		if calls < 2 {
			// An unknown, non-terminal status must not stop the wait.
			mustJSON(t, w, http.StatusOK, runEnvelope(RunStatus("provisioning"), ""))
			return
		}
		mustJSON(t, w, http.StatusOK, runEnvelope(RunStatusCompleted, ""))
	})

	run, err := client.SmartBrowse.WaitForRun(context.Background(), "run1",
		WithPollInterval(2*time.Millisecond), WithWaitTimeout(5*time.Second))
	if err != nil {
		t.Fatalf("WaitForRun: %v", err)
	}
	if run.Data.RunStatus != RunStatusCompleted {
		t.Errorf("status = %q", run.Data.RunStatus)
	}
}

func TestRunAndWait(t *testing.T) {
	var cap capture
	client := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		cap.record(r)
		if r.Method == http.MethodPost {
			mustJSON(t, w, http.StatusAccepted, `{"status":"queued","data":{"run_id":"run1","recipe_id":"rec1","run_status":"running","poll_url":"/v1/smartbrowse/runs/run1","created_at":"2026-07-06T12:00:00Z"},"request_id":"req_disp"}`)
			return
		}
		mustJSON(t, w, http.StatusOK, runEnvelope(RunStatusCompleted, ""))
	})

	run, err := client.SmartBrowse.RunAndWait(context.Background(), "rec1",
		WithPollInterval(2*time.Millisecond))
	if err != nil {
		t.Fatalf("RunAndWait: %v", err)
	}
	if run.Data.RunStatus != RunStatusCompleted {
		t.Errorf("status = %q", run.Data.RunStatus)
	}
}
