//! Dispatch a SmartBrowse recipe run and wait for it to finish.
//!
//! Run with:
//! `WEBSCRAPE_API_KEY=wsg_live_... RECIPE_ID=m3Yc2tFvN8q cargo run --example smartbrowse`

use webscrape_ai::{Client, Error, WaitOptions};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::from_env()?;
    let recipe_id = std::env::var("RECIPE_ID").expect("set RECIPE_ID to a recipe you own");

    // Show current plan usage first.
    let usage = client.smartbrowse().usage().await?;
    println!(
        "runs_used_30d={:?} / cap={:?}  cost_per_page={:?}",
        usage.data.runs_used_30d, usage.data.runs_per_month_cap, usage.data.cost_per_page
    );

    // Dispatch and wait (poll until terminal).
    match client
        .smartbrowse()
        .run_and_wait(&recipe_id, WaitOptions::default())
        .await
    {
        Ok(resp) => {
            let run = resp.data;
            println!(
                "run {} completed: pages={:?} items={:?} credits={:?}",
                run.id, run.pages_extracted, run.items_extracted, run.credits_used
            );
        }
        Err(Error::RunFailed { run }) => {
            eprintln!(
                "run {} ended in {}: {:?}",
                run.id, run.run_status, run.error
            );
        }
        Err(Error::WaitTimeout { last }) => {
            eprintln!(
                "still {} after the deadline (run {})",
                last.run_status, last.id
            );
        }
        Err(e) => return Err(e.into()),
    }

    Ok(())
}
