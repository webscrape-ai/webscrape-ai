//! Structured extraction with an `output_schema`, decoded into a typed struct.
//!
//! Run with: `WEBSCRAPE_API_KEY=wsg_live_... cargo run --example smartscraper`

use serde::Deserialize;
use serde_json::json;
use webscrape_ai::{Client, SmartScraperRequest};

#[derive(Debug, Deserialize)]
struct Page {
    stories: Vec<Story>,
}

#[derive(Debug, Deserialize)]
struct Story {
    title: Option<String>,
    url: Option<String>,
    score: Option<i64>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::from_env()?;

    let resp = client
        .smartscraper(
            SmartScraperRequest::new(
                "https://news.ycombinator.com",
                "Extract the front-page stories with title, url, and score.",
            )
            .output_schema(json!({
                "type": "object",
                "properties": {
                    "stories": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "title": { "type": "string" },
                                "url": { "type": "string" },
                                "score": { "type": "integer" }
                            }
                        }
                    }
                }
            })),
        )
        .await?;

    println!("credits_used={:?}", resp.credits_used);

    // The raw result is a serde_json::Value; decode it into your own type.
    let page: Page = resp.data.result_as()?;
    for story in page.stories.iter().take(10) {
        println!("[{:?}] {:?} ({:?})", story.score, story.title, story.url);
    }

    Ok(())
}
