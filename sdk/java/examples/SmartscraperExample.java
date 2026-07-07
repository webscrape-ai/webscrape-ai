import ai.webscrape.sdk.InsufficientCreditsException;
import ai.webscrape.sdk.SmartscraperData;
import ai.webscrape.sdk.SmartscraperRequest;
import ai.webscrape.sdk.ValidationException;
import ai.webscrape.sdk.WebscrapeClient;
import ai.webscrape.sdk.WebscrapeResponse;

import java.util.Map;

/**
 * Structured extraction with an output schema, plus typed error handling.
 *
 * <pre>
 *   mvn -q -B package
 *   export WEBSCRAPE_API_KEY=wsg_live_...
 *   java --class-path target/classes examples/SmartscraperExample.java
 * </pre>
 */
public class SmartscraperExample {
    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "https://news.ycombinator.com";

        WebscrapeClient client = WebscrapeClient.builder().build();

        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "stories", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "title", Map.of("type", "string"),
                                                "url", Map.of("type", "string"),
                                                "score", Map.of("type", "integer"))))));

        try {
            WebscrapeResponse<SmartscraperData> resp = client.smartscraper(
                    SmartscraperRequest.builder(url,
                                    "Extract the front-page stories with title, url, and score.")
                            .outputSchema(schema)
                            .detailLevel("high")
                            .build());

            SmartscraperData data = resp.getData();
            System.out.println("result: " + data.getResult());
            System.out.println("latency_ms: " + data.getLatencyMs());
            System.out.println("credits used: " + resp.getCreditsUsed()
                    + ", remaining: " + resp.getCreditsRemaining());
        } catch (InsufficientCreditsException e) {
            System.err.println("out of credits: need " + e.getRequired() + ", have " + e.getBalance());
        } catch (ValidationException e) {
            System.err.println("extraction did not match the schema: " + e.getDetails());
        }
    }
}
