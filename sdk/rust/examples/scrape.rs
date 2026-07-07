//! Fetch a URL as cleaned markdown with outbound links.
//!
//! Run with: `WEBSCRAPE_API_KEY=wsg_live_... cargo run --example scrape`

use webscrape_ai::{Client, ScrapeRequest};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::from_env()?;

    let resp = client
        .scrape(
            ScrapeRequest::new("https://example.com")
                .clean(true)
                .extract_links(true),
        )
        .await?;

    println!(
        "request_id={:?} credits_used={:?} credits_remaining={:?}",
        resp.request_id, resp.credits_used, resp.credits_remaining
    );
    println!(
        "content_type={:?} cleaned={:?}",
        resp.data.content_type, resp.data.cleaned
    );

    if let Some(html) = resp.data.html.as_deref() {
        let preview: String = html.chars().take(500).collect();
        println!("--- markdown (first 500 chars) ---\n{preview}");
    }

    if let Some(links) = resp.data.links {
        println!("--- {} links ---", links.len());
        for link in links.iter().take(10) {
            println!("{:?} -> {:?}", link.text, link.url);
        }
    }

    Ok(())
}
