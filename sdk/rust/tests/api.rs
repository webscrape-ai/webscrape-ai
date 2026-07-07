//! Async integration tests against the local mock server. Covers the full
//! test checklist: happy paths, optional-field serialization, typed errors,
//! the plain-401 body, unknown codes, retries, and the wait helper.

mod common;

use std::time::Duration;

use common::{MockResponse, MockServer};
use serde::Deserialize;
use webscrape_ai::{
    ApiError, Client, Error, ErrorCode, ScrapeRequest, SmartScraperRequest, WaitOptions,
};

fn client(server: &MockServer, max_retries: u32) -> Client {
    Client::builder()
        .api_key("wsg_live_test")
        .base_url(&server.base_url)
        .max_retries(max_retries)
        .timeout(Duration::from_secs(5))
        .build()
        .unwrap()
}

fn api_err(e: Error) -> ApiError {
    match e {
        Error::Api(a) => a,
        other => panic!("expected Error::Api, got {other:?}"),
    }
}

// ---- 1. happy paths for each endpoint ------------------------------------

#[tokio::test]
async fn scrape_happy_path() {
    let server = MockServer::start();
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"request_id":"eng-uuid-1","html":"Hello world","content_type":"html","cleaned":true,"links":[{"url":"https://a.com","text":"A"}],"metadata":{"title":"T","description":null,"language":"en"},"structured_data":null,"latency_ms":123},"credits_used":1,"credits_remaining":499,"request_id":"req_aB3xY9Kp"}"#,
    );

    let resp = client(&server, 2)
        .scrape(ScrapeRequest::new("https://example.com").clean(true))
        .await
        .unwrap();

    // Envelope fields + both distinct request ids.
    assert_eq!(resp.request_id.as_deref(), Some("req_aB3xY9Kp"));
    assert_eq!(resp.credits_used, Some(1));
    assert_eq!(resp.credits_remaining, Some(499));
    assert_eq!(resp.data.request_id.as_deref(), Some("eng-uuid-1"));
    assert_eq!(resp.data.html.as_deref(), Some("Hello world"));
    assert_eq!(resp.data.content_type.as_deref(), Some("html"));
    assert_eq!(resp.data.cleaned, Some(true));
    assert_eq!(resp.data.links.as_ref().unwrap().len(), 1);
    assert_eq!(
        resp.data.metadata.as_ref().unwrap().title.as_deref(),
        Some("T")
    );

    // Request assertions: method, path, auth, UA, JSON body.
    let req = server.last_request();
    assert_eq!(req.method, "POST");
    assert_eq!(req.path, "/scrape");
    assert_eq!(req.header("x-api-key"), Some("wsg_live_test"));
    assert_eq!(req.header("user-agent"), Some("webscrape-ai-rust/0.1.0"));
    assert!(req
        .header("content-type")
        .unwrap()
        .starts_with("application/json"));
    let body = req.json();
    assert_eq!(body["website_url"], "https://example.com");
    assert_eq!(body["clean"], true);
}

#[tokio::test]
async fn smartscraper_happy_path_and_result_as() {
    #[derive(Deserialize)]
    struct Page {
        stories: Vec<Story>,
    }
    #[derive(Deserialize)]
    struct Story {
        title: Option<String>,
        score: Option<i64>,
    }

    let server = MockServer::start();
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"request_id":"eng-2","result":{"stories":[{"title":"X","score":5}]},"latency_ms":10},"credits_used":5,"credits_remaining":495,"request_id":"req_ss"}"#,
    );

    let resp = client(&server, 2)
        .smartscraper(SmartScraperRequest::new(
            "https://news.ycombinator.com",
            "Extract stories",
        ))
        .await
        .unwrap();

    assert_eq!(resp.credits_used, Some(5));
    assert_eq!(resp.data.request_id.as_deref(), Some("eng-2"));

    let page: Page = resp.data.result_as().unwrap();
    assert_eq!(page.stories.len(), 1);
    assert_eq!(page.stories[0].title.as_deref(), Some("X"));
    assert_eq!(page.stories[0].score, Some(5));

    let req = server.last_request();
    assert_eq!(req.method, "POST");
    assert_eq!(req.path, "/smartscraper");
    let body = req.json();
    assert_eq!(body["website_url"], "https://news.ycombinator.com");
    assert_eq!(body["user_prompt"], "Extract stories");
}

#[tokio::test]
async fn smartbrowse_run_happy_path() {
    let server = MockServer::start();
    server.enqueue_json(
        202,
        r#"{"status":"queued","data":{"run_id":"k7Xb9dRmQ2p","recipe_id":"m3Yc2tFvN8q","run_status":"running","poll_url":"/v1/smartbrowse/runs/k7Xb9dRmQ2p","created_at":"2026-07-06T12:00:00Z"},"request_id":"req_run"}"#,
    );

    let resp = client(&server, 2)
        .smartbrowse()
        .run("m3Yc2tFvN8q")
        .await
        .unwrap();

    assert_eq!(resp.request_id.as_deref(), Some("req_run"));
    assert_eq!(resp.credits_used, None); // no credits fields on dispatch
    assert_eq!(resp.credits_remaining, None);
    assert_eq!(resp.data.run_id, "k7Xb9dRmQ2p");
    assert_eq!(resp.data.recipe_id, "m3Yc2tFvN8q");
    assert_eq!(resp.data.run_status, webscrape_ai::RunStatus::Running);

    let req = server.last_request();
    assert_eq!(req.method, "POST");
    assert_eq!(req.path, "/smartbrowse/recipes/m3Yc2tFvN8q/run");
    assert_eq!(req.body, ""); // no request body
    assert_eq!(req.header("x-api-key"), Some("wsg_live_test"));
}

#[tokio::test]
async fn smartbrowse_get_run_happy_path() {
    let server = MockServer::start();
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"id":"k7Xb9dRmQ2p","recipe_id":"m3Yc2tFvN8q","run_status":"completed","pages_extracted":3,"items_extracted":12,"credits_used":6,"started_at":"2026-07-06T12:00:01Z","completed_at":"2026-07-06T12:00:09Z","result":{"pages":[],"mode":"replay","drift":0.0,"warnings":[]},"created_at":"2026-07-06T12:00:00Z"},"credits_used":0,"credits_remaining":494,"request_id":"req_get"}"#,
    );

    let resp = client(&server, 2)
        .smartbrowse()
        .get_run("k7Xb9dRmQ2p")
        .await
        .unwrap();

    assert_eq!(resp.credits_used, Some(0)); // polling is free
    assert_eq!(resp.data.run_status, webscrape_ai::RunStatus::Completed);
    assert_eq!(resp.data.pages_extracted, Some(3));
    assert_eq!(resp.data.credits_used, Some(6)); // accrued spend lives in data

    let req = server.last_request();
    assert_eq!(req.method, "GET");
    assert_eq!(req.path, "/smartbrowse/runs/k7Xb9dRmQ2p");
}

#[tokio::test]
async fn smartbrowse_usage_happy_path() {
    let server = MockServer::start();
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"runs_used_30d":4,"runs_per_month_cap":50,"pages_per_run_cap":20,"cost_per_page":2,"schedules_count":1,"schedules_allowed":true,"last_run":{"id":"k7","status":"completed","pages_extracted":3,"effective_cap":20,"clamped_by_credits":false,"created_at":"2026-07-06T12:00:00Z","completed_at":"2026-07-06T12:00:09Z"}},"credits_used":0,"credits_remaining":494,"request_id":"req_usage"}"#,
    );

    let resp = client(&server, 2).smartbrowse().usage().await.unwrap();

    assert_eq!(resp.credits_used, Some(0));
    assert_eq!(resp.data.runs_used_30d, Some(4));
    assert_eq!(resp.data.cost_per_page, Some(2));
    assert_eq!(resp.data.schedules_allowed, Some(true));
    let last = resp.data.last_run.unwrap();
    assert_eq!(last.id, "k7");
    assert_eq!(last.effective_cap, Some(20));

    assert_eq!(server.last_request().path, "/smartbrowse/usage");
}

// ---- 2. optional-field serialization -------------------------------------

#[tokio::test]
async fn untouched_options_are_absent_from_body() {
    let server = MockServer::start();
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"request_id":"e"},"credits_used":1,"credits_remaining":9,"request_id":"r"}"#,
    );

    let _ = client(&server, 2)
        .scrape(ScrapeRequest::new("https://example.com").clean(true))
        .await
        .unwrap();

    let body = server.last_request().json();
    assert_eq!(body["website_url"], "https://example.com");
    assert_eq!(body["clean"], true);
    // Every option the caller never set must be absent (not null / not default).
    for absent in [
        "parse_mode",
        "tag_truncate",
        "extract_links",
        "include_tags",
        "exclude_tags",
        "headers",
        "max_age",
        "stealth",
    ] {
        assert!(body.get(absent).is_none(), "{absent} should be absent");
    }
}

// ---- 3. error envelope -> typed error -------------------------------------

#[tokio::test]
async fn insufficient_credits_error_with_accessors() {
    let server = MockServer::start();
    server.enqueue_json(
        402,
        r#"{"status":"error","error":{"code":"insufficient_credits","message":"not enough credits","details":{"balance":2,"required":5}},"request_id":"req_ic"}"#,
    );

    let err = client(&server, 2)
        .scrape(ScrapeRequest::new("https://example.com"))
        .await
        .unwrap_err();
    let api = api_err(err);
    assert!(api.is_insufficient_credits());
    assert_eq!(api.kind(), &ErrorCode::InsufficientCredits);
    assert_eq!(api.status(), 402);
    assert_eq!(api.balance(), Some(2));
    assert_eq!(api.required(), Some(5));
    assert_eq!(api.request_id(), Some("req_ic"));
}

#[tokio::test]
async fn not_found_error() {
    let server = MockServer::start();
    server.enqueue_json(
        404,
        r#"{"status":"error","error":{"code":"not_found","message":"recipe not found"},"request_id":"req_nf"}"#,
    );

    let err = client(&server, 2)
        .smartbrowse()
        .run("nope")
        .await
        .unwrap_err();
    let api = api_err(err);
    assert!(api.is_not_found());
    assert_eq!(api.status(), 404);
}

#[tokio::test]
async fn validation_failed_error() {
    let server = MockServer::start();
    server.enqueue_json(
        422,
        r#"{"status":"error","error":{"code":"validation_failed","message":"schema mismatch","details":{"type":"schema_validation_error","errors":[]}},"request_id":"req_vf"}"#,
    );

    let err = client(&server, 2)
        .smartscraper(SmartScraperRequest::new("https://x.com", "p"))
        .await
        .unwrap_err();
    let api = api_err(err);
    assert!(api.is_validation_failed());
    assert_eq!(api.kind(), &ErrorCode::ValidationFailed);
    assert!(api.details().is_some());
}

#[tokio::test]
async fn rate_limited_error_with_reason() {
    let server = MockServer::start();
    // max_retries = 0 so the 429 surfaces immediately instead of retrying.
    server.enqueue_json(
        429,
        r#"{"status":"error","error":{"code":"rate_limited","message":"slow down","details":{"reason":"rate_limit_per_min","limit_per_min":60}},"request_id":"req_rl"}"#,
    );

    let err = client(&server, 0)
        .scrape(ScrapeRequest::new("https://example.com"))
        .await
        .unwrap_err();
    let api = api_err(err);
    assert!(api.is_rate_limited());
    assert_eq!(api.reason(), Some("rate_limit_per_min"));
    assert_eq!(api.status(), 429);
}

// ---- 4. bare 401 ----------------------------------------------------------

#[tokio::test]
async fn bare_401_becomes_authentication_error() {
    let server = MockServer::start();
    server.enqueue(
        MockResponse::json(
            401,
            r#"{"error":"missing or invalid credentials — provide a session cookie or X-API-Key header"}"#,
        )
        .header("X-Request-ID", "req_hdr123"),
    );

    let err = client(&server, 2)
        .scrape(ScrapeRequest::new("https://example.com"))
        .await
        .unwrap_err();
    let api = api_err(err);
    assert!(api.is_unauthorized());
    assert_eq!(api.kind(), &ErrorCode::Unauthorized);
    assert_eq!(api.status(), 401);
    assert!(api.message().contains("missing or invalid credentials"));
    // request_id falls back to the X-Request-ID header (no envelope request_id).
    assert_eq!(api.request_id(), Some("req_hdr123"));
}

// ---- 5. unknown error code -----------------------------------------------

#[tokio::test]
async fn unknown_error_code_preserved() {
    let server = MockServer::start();
    server.enqueue_json(
        400,
        r#"{"status":"error","error":{"code":"teapot_brewing","message":"nope"},"request_id":"req_uc"}"#,
    );

    let err = client(&server, 2)
        .scrape(ScrapeRequest::new("https://example.com"))
        .await
        .unwrap_err();
    let api = api_err(err);
    assert_eq!(
        api.kind(),
        &ErrorCode::Unknown("teapot_brewing".to_string())
    );
    assert_eq!(api.kind().as_str(), "teapot_brewing");
    assert_eq!(api.status(), 400);
}

// ---- 6. retry policy ------------------------------------------------------

#[tokio::test]
async fn retry_429_then_200_succeeds() {
    let server = MockServer::start();
    server.enqueue(
        MockResponse::json(
            429,
            r#"{"status":"error","error":{"code":"rate_limited","message":"slow"},"request_id":"req_rl"}"#,
        )
        .header("Retry-After", "0"),
    );
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"request_id":"e"},"credits_used":1,"credits_remaining":9,"request_id":"req_ok"}"#,
    );

    let resp = client(&server, 2)
        .scrape(ScrapeRequest::new("https://example.com"))
        .await
        .unwrap();
    assert_eq!(resp.request_id.as_deref(), Some("req_ok"));
    assert_eq!(server.request_count(), 2);
}

#[tokio::test]
async fn max_retries_zero_surfaces_429_immediately() {
    let server = MockServer::start();
    server.enqueue_json(
        429,
        r#"{"status":"error","error":{"code":"rate_limited","message":"slow"},"request_id":"req_rl"}"#,
    );

    let err = client(&server, 0)
        .scrape(ScrapeRequest::new("https://example.com"))
        .await
        .unwrap_err();
    assert!(api_err(err).is_rate_limited());
    assert_eq!(server.request_count(), 1);
}

#[tokio::test]
async fn plain_400_is_not_retried() {
    let server = MockServer::start();
    server.enqueue_json(
        400,
        r#"{"status":"error","error":{"code":"invalid_request","message":"bad url"},"request_id":"req_br"}"#,
    );

    let err = client(&server, 2)
        .scrape(ScrapeRequest::new("not-a-url"))
        .await
        .unwrap_err();
    assert_eq!(api_err(err).kind(), &ErrorCode::InvalidRequest);
    assert_eq!(server.request_count(), 1);
}

// ---- 7. wait_for_run ------------------------------------------------------

fn running_run(id: &str) -> String {
    format!(
        r#"{{"status":"completed","data":{{"id":"{id}","recipe_id":"m3","run_status":"running","pages_extracted":1,"items_extracted":0,"credits_used":0,"created_at":"2026-07-06T12:00:00Z"}},"credits_used":0,"credits_remaining":500,"request_id":"req_r"}}"#
    )
}

#[tokio::test]
async fn wait_for_run_polls_until_completed() {
    let server = MockServer::start();
    server.enqueue_json(200, running_run("k7"));
    server.enqueue_json(200, running_run("k7"));
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"id":"k7","recipe_id":"m3","run_status":"completed","pages_extracted":3,"items_extracted":9,"credits_used":6,"result":{"pages":[]},"created_at":"2026-07-06T12:00:00Z"},"credits_used":0,"credits_remaining":494,"request_id":"req_done"}"#,
    );

    let opts = WaitOptions::default()
        .poll_interval(Duration::from_millis(10))
        .timeout(Duration::from_secs(5));
    let resp = client(&server, 2)
        .smartbrowse()
        .wait_for_run("k7", opts)
        .await
        .unwrap();

    assert_eq!(resp.data.run_status, webscrape_ai::RunStatus::Completed);
    assert_eq!(resp.data.pages_extracted, Some(3));
    assert_eq!(server.request_count(), 3);
}

#[tokio::test]
async fn wait_for_run_failed_raises_run_failed() {
    let server = MockServer::start();
    server.enqueue_json(200, running_run("k7"));
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"id":"k7","recipe_id":"m3","run_status":"failed","pages_extracted":0,"items_extracted":0,"credits_used":0,"error":"boom","created_at":"2026-07-06T12:00:00Z"},"credits_used":0,"credits_remaining":500,"request_id":"req_f"}"#,
    );

    let opts = WaitOptions::default()
        .poll_interval(Duration::from_millis(10))
        .timeout(Duration::from_secs(5));
    let err = client(&server, 2)
        .smartbrowse()
        .wait_for_run("k7", opts)
        .await
        .unwrap_err();

    match err {
        Error::RunFailed { run } => {
            assert_eq!(run.run_status, webscrape_ai::RunStatus::Failed);
            assert_eq!(run.error.as_deref(), Some("boom"));
        }
        other => panic!("expected RunFailed, got {other:?}"),
    }
}

#[tokio::test]
async fn wait_for_run_deadline_raises_wait_timeout() {
    let server = MockServer::start();
    for _ in 0..12 {
        server.enqueue_json(200, running_run("k7"));
    }

    let opts = WaitOptions::default()
        .poll_interval(Duration::from_millis(20))
        .timeout(Duration::from_millis(50));
    let err = client(&server, 2)
        .smartbrowse()
        .wait_for_run("k7", opts)
        .await
        .unwrap_err();

    match err {
        Error::WaitTimeout { last } => {
            assert_eq!(last.run_status, webscrape_ai::RunStatus::Running);
        }
        other => panic!("expected WaitTimeout, got {other:?}"),
    }
}

// ---- 8. env-var key pickup + missing-key construction error ---------------

#[tokio::test]
async fn env_var_pickup_and_missing_key_error() {
    std::env::remove_var("WEBSCRAPE_API_KEY");

    // Missing key -> construction error, not a late 401.
    assert!(matches!(Client::from_env(), Err(Error::Config(_))));
    assert!(matches!(Client::builder().build(), Err(Error::Config(_))));
    assert!(matches!(Client::new(""), Err(Error::Config(_))));

    // Env var is picked up.
    std::env::set_var("WEBSCRAPE_API_KEY", "wsg_live_from_env");
    assert!(Client::from_env().is_ok());
    std::env::remove_var("WEBSCRAPE_API_KEY");
}
