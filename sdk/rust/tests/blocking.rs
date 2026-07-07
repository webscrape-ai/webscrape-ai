//! Blocking-client tests. Compiled only with the `blocking` feature; this also
//! serves as the compile-test for that feature.
#![cfg(feature = "blocking")]

mod common;

use std::time::Duration;

use common::MockServer;
use webscrape_ai::blocking::Client;
use webscrape_ai::{Error, ScrapeRequest, WaitOptions};

fn client(server: &MockServer, max_retries: u32) -> Client {
    Client::builder()
        .api_key("wsg_live_test")
        .base_url(&server.base_url)
        .max_retries(max_retries)
        .timeout(Duration::from_secs(5))
        .build()
        .unwrap()
}

#[test]
fn blocking_scrape_happy_path() {
    let server = MockServer::start();
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"request_id":"eng-1","html":"Hi there","content_type":"html","cleaned":true},"credits_used":1,"credits_remaining":9,"request_id":"req_ok"}"#,
    );

    let resp = client(&server, 2)
        .scrape(ScrapeRequest::new("https://example.com").clean(true))
        .unwrap();

    assert_eq!(resp.request_id.as_deref(), Some("req_ok"));
    assert_eq!(resp.credits_used, Some(1));
    assert_eq!(resp.data.html.as_deref(), Some("Hi there"));

    let req = server.last_request();
    assert_eq!(req.method, "POST");
    assert_eq!(req.path, "/scrape");
    assert_eq!(req.header("x-api-key"), Some("wsg_live_test"));
    assert_eq!(req.header("user-agent"), Some("webscrape-ai-rust/0.1.0"));
}

#[test]
fn blocking_insufficient_credits_error() {
    let server = MockServer::start();
    server.enqueue_json(
        402,
        r#"{"status":"error","error":{"code":"insufficient_credits","message":"nope","details":{"balance":1,"required":5}},"request_id":"req_ic"}"#,
    );

    let err = client(&server, 2)
        .scrape(ScrapeRequest::new("https://example.com"))
        .unwrap_err();
    match err {
        Error::Api(api) => {
            assert!(api.is_insufficient_credits());
            assert_eq!(api.balance(), Some(1));
            assert_eq!(api.required(), Some(5));
        }
        other => panic!("expected Error::Api, got {other:?}"),
    }
}

#[test]
fn blocking_wait_for_run_completes() {
    let server = MockServer::start();
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"id":"k7","recipe_id":"m3","run_status":"running","pages_extracted":1,"items_extracted":0,"credits_used":0,"created_at":"2026-07-06T12:00:00Z"},"credits_used":0,"credits_remaining":500,"request_id":"req_r"}"#,
    );
    server.enqueue_json(
        200,
        r#"{"status":"completed","data":{"id":"k7","recipe_id":"m3","run_status":"completed","pages_extracted":2,"items_extracted":4,"credits_used":4,"result":{"pages":[]},"created_at":"2026-07-06T12:00:00Z"},"credits_used":0,"credits_remaining":496,"request_id":"req_done"}"#,
    );

    let opts = WaitOptions::default()
        .poll_interval(Duration::from_millis(10))
        .timeout(Duration::from_secs(5));
    let resp = client(&server, 2)
        .smartbrowse()
        .wait_for_run("k7", opts)
        .unwrap();

    assert_eq!(resp.data.run_status, webscrape_ai::RunStatus::Completed);
    assert_eq!(resp.data.pages_extracted, Some(2));
    assert_eq!(server.request_count(), 2);
}
