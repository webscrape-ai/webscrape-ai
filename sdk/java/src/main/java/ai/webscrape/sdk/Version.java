package ai.webscrape.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The single source of truth for the SDK version string, derived at build time
 * from the Maven {@code pom.xml} {@code <version>} via resource filtering.
 * Feeds the {@code User-Agent} header sent on every request.
 */
public final class Version {

    /** The SDK version, e.g. {@code "0.1.0"}. */
    public static final String VERSION = load();

    /** The {@code User-Agent} value: {@code webscrape-ai-java/<version>}. */
    public static final String USER_AGENT = "webscrape-ai-java/" + VERSION;

    private Version() {
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/webscrape-sdk-version.properties")) {
            if (in == null) {
                return "0.0.0-dev";
            }
            Properties props = new Properties();
            props.load(in);
            String value = props.getProperty("version");
            if (value == null || value.isEmpty() || value.startsWith("${")) {
                return "0.0.0-dev";
            }
            return value;
        } catch (IOException e) {
            return "0.0.0-dev";
        }
    }
}
