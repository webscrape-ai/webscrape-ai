import ai.webscrape.sdk.RunFailedException;
import ai.webscrape.sdk.SmartBrowseRun;
import ai.webscrape.sdk.WaitOptions;
import ai.webscrape.sdk.WaitTimeoutException;
import ai.webscrape.sdk.WebscrapeClient;
import ai.webscrape.sdk.WebscrapeResponse;

import java.time.Duration;

/**
 * Dispatch a SmartBrowse recipe run and wait for it to finish.
 *
 * <pre>
 *   mvn -q -B package
 *   export WEBSCRAPE_API_KEY=wsg_live_...
 *   java --class-path target/classes examples/SmartbrowseExample.java &lt;recipe_id&gt;
 * </pre>
 */
public class SmartbrowseExample {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: SmartbrowseExample <recipe_id>");
            System.exit(2);
        }
        String recipeId = args[0];

        WebscrapeClient client = WebscrapeClient.builder().build();

        WaitOptions opts = WaitOptions.builder()
                .pollInterval(Duration.ofSeconds(2))
                .timeout(Duration.ofMinutes(10))
                .build();

        try {
            // runAndWait = dispatch (POST) + poll (GET) until terminal.
            WebscrapeResponse<SmartBrowseRun> resp = client.smartBrowse().runAndWait(recipeId, opts);

            SmartBrowseRun run = resp.getData();
            System.out.println("run:    " + run.getId() + " (" + run.getRunStatus() + ")");
            System.out.println("pages:  " + run.getPagesExtracted());
            System.out.println("items:  " + run.getItemsExtracted());
            System.out.println("credits:" + run.getCreditsUsed());
            System.out.println("result: " + run.getResult());
        } catch (RunFailedException e) {
            SmartBrowseRun run = e.getRun();
            System.err.println("run " + run.getRunStatus() + ": " + run.getError());
        } catch (WaitTimeoutException e) {
            System.err.println("timed out; last status: "
                    + (e.getLastSeen() == null ? "unknown" : e.getLastSeen().getRunStatus()));
        }
    }
}
