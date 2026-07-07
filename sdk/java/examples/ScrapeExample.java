import ai.webscrape.sdk.ScrapeData;
import ai.webscrape.sdk.ScrapeRequest;
import ai.webscrape.sdk.WebscrapeClient;
import ai.webscrape.sdk.WebscrapeResponse;

/**
 * Fetch a page as cleaned markdown.
 *
 * <pre>
 *   mvn -q -B package
 *   export WEBSCRAPE_API_KEY=wsg_live_...
 *   java --class-path target/classes examples/ScrapeExample.java [url]
 * </pre>
 */
public class ScrapeExample {
    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "https://example.com";

        // apiKey() omitted on purpose: the builder reads WEBSCRAPE_API_KEY from the environment.
        WebscrapeClient client = WebscrapeClient.builder().build();

        WebscrapeResponse<ScrapeData> resp = client.scrape(
                ScrapeRequest.builder(url)
                        .clean(true)
                        .extractLinks(true)
                        .build());

        ScrapeData data = resp.getData();
        System.out.println("content_type: " + data.getContentType());
        System.out.println("cleaned:      " + data.getCleaned());
        if (data.getMetadata() != null) {
            System.out.println("title:        " + data.getMetadata().getTitle());
        }
        if (data.getLinks() != null) {
            System.out.println("links:        " + data.getLinks().size());
        }
        System.out.println();
        System.out.println(data.getHtml());
        System.out.println();
        System.out.println("credits used: " + resp.getCreditsUsed()
                + ", remaining: " + resp.getCreditsRemaining()
                + " (request " + resp.getRequestId() + ")");
    }
}
