//! A tiny dependency-free HTTP mock server for the SDK tests.
//!
//! It binds an ephemeral loopback port, records every request, and replies with
//! scripted responses in FIFO order (one per accepted connection, closing after
//! each). That covers retry sequences and poll sequences for both the async and
//! blocking clients without any async-runtime coupling.

#![allow(dead_code)]

use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;

#[derive(Debug, Clone)]
pub struct RecordedRequest {
    pub method: String,
    pub path: String,
    pub headers: Vec<(String, String)>,
    pub body: String,
}

impl RecordedRequest {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    pub fn json(&self) -> serde_json::Value {
        serde_json::from_str(&self.body).unwrap_or(serde_json::Value::Null)
    }
}

#[derive(Clone)]
pub struct MockResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: String,
}

impl MockResponse {
    /// A JSON response with the given status and body.
    pub fn json(status: u16, body: impl Into<String>) -> Self {
        Self {
            status,
            headers: vec![],
            body: body.into(),
        }
    }

    pub fn header(mut self, name: &str, value: &str) -> Self {
        self.headers.push((name.to_string(), value.to_string()));
        self
    }
}

pub struct MockServer {
    pub base_url: String,
    requests: Arc<Mutex<Vec<RecordedRequest>>>,
    responses: Arc<Mutex<VecDeque<MockResponse>>>,
}

impl MockServer {
    pub fn start() -> MockServer {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind mock server");
        let port = listener.local_addr().unwrap().port();
        let base_url = format!("http://127.0.0.1:{port}");
        let requests = Arc::new(Mutex::new(Vec::new()));
        let responses = Arc::new(Mutex::new(VecDeque::new()));

        let req_handle = Arc::clone(&requests);
        let resp_handle = Arc::clone(&responses);
        thread::spawn(move || {
            for stream in listener.incoming() {
                match stream {
                    Ok(s) => handle_conn(s, &req_handle, &resp_handle),
                    Err(_) => break,
                }
            }
        });

        MockServer {
            base_url,
            requests,
            responses,
        }
    }

    /// Queue a response to be served next (FIFO).
    pub fn enqueue(&self, resp: MockResponse) {
        self.responses.lock().unwrap().push_back(resp);
    }

    /// Queue a JSON response.
    pub fn enqueue_json(&self, status: u16, body: impl Into<String>) {
        self.enqueue(MockResponse::json(status, body));
    }

    /// All requests received so far, in order.
    pub fn requests(&self) -> Vec<RecordedRequest> {
        self.requests.lock().unwrap().clone()
    }

    pub fn request_count(&self) -> usize {
        self.requests.lock().unwrap().len()
    }

    pub fn last_request(&self) -> RecordedRequest {
        self.requests
            .lock()
            .unwrap()
            .last()
            .cloned()
            .expect("at least one request received")
    }
}

fn handle_conn(
    stream: TcpStream,
    requests: &Arc<Mutex<Vec<RecordedRequest>>>,
    responses: &Arc<Mutex<VecDeque<MockResponse>>>,
) {
    let read_stream = match stream.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    let mut reader = BufReader::new(read_stream);

    let mut request_line = String::new();
    if reader.read_line(&mut request_line).unwrap_or(0) == 0 {
        return;
    }
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let path = parts.next().unwrap_or("").to_string();

    let mut headers = Vec::new();
    let mut content_length = 0usize;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap_or(0) == 0 {
            break;
        }
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed.is_empty() {
            break;
        }
        if let Some((k, v)) = trimmed.split_once(':') {
            let key = k.trim().to_string();
            let value = v.trim().to_string();
            if key.eq_ignore_ascii_case("content-length") {
                content_length = value.parse().unwrap_or(0);
            }
            headers.push((key, value));
        }
    }

    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        let _ = reader.read_exact(&mut body);
    }

    requests.lock().unwrap().push(RecordedRequest {
        method,
        path,
        headers,
        body: String::from_utf8_lossy(&body).into_owned(),
    });

    let resp = responses
        .lock()
        .unwrap()
        .pop_front()
        .unwrap_or_else(|| MockResponse::json(200, "{}"));

    let mut out = stream;
    let mut wire = format!("HTTP/1.1 {} {}\r\n", resp.status, reason(resp.status));
    let mut has_content_type = false;
    for (k, v) in &resp.headers {
        if k.eq_ignore_ascii_case("content-type") {
            has_content_type = true;
        }
        wire.push_str(&format!("{k}: {v}\r\n"));
    }
    if !has_content_type {
        wire.push_str("Content-Type: application/json\r\n");
    }
    wire.push_str(&format!("Content-Length: {}\r\n", resp.body.len()));
    wire.push_str("Connection: close\r\n\r\n");

    let _ = out.write_all(wire.as_bytes());
    let _ = out.write_all(resp.body.as_bytes());
    let _ = out.flush();
}

fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        202 => "Accepted",
        400 => "Bad Request",
        401 => "Unauthorized",
        402 => "Payment Required",
        403 => "Forbidden",
        404 => "Not Found",
        409 => "Conflict",
        422 => "Unprocessable Entity",
        429 => "Too Many Requests",
        500 => "Internal Server Error",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        _ => "Status",
    }
}
