package ai.webscrape.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;

/**
 * A tiny loopback HTTP server built on the JDK's {@link HttpServer} (no wiremock). Canned
 * responses are dequeued in order; every request is recorded for assertions.
 */
final class MockServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final Deque<Canned> responses = new ArrayDeque<>();
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());

    MockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    /** Base URL including the {@code /v1} prefix, matching production shape. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    void enqueue(Canned canned) {
        responses.add(canned);
    }

    /** Enqueue a JSON body with the given status. */
    void enqueueJson(int status, String body) {
        Canned c = new Canned(status, body);
        c.headers.put("Content-Type", "application/json");
        responses.add(c);
    }

    List<Recorded> requests() {
        return requests;
    }

    Recorded lastRequest() {
        synchronized (requests) {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }

    int requestCount() {
        return requests.size();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        // Case-insensitive: com.sun's Headers canonicalizes names (e.g. "X-API-Key" -> "X-Api-Key").
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                headers.put(k, v.get(0));
            }
        });
        requests.add(new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                headers,
                body));

        Canned canned = responses.poll();
        if (canned == null) {
            canned = new Canned(500, "{\"status\":\"error\",\"error\":{\"code\":\"internal_error\",\"message\":\"no canned response\"},\"request_id\":\"req_none\"}");
            canned.headers.put("Content-Type", "application/json");
        }
        canned.headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] out = canned.body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(canned.status, out.length == 0 ? -1 : out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** A canned response. */
    static final class Canned {
        final int status;
        final String body;
        final Map<String, String> headers = new LinkedHashMap<>();

        Canned(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        Canned header(String name, String value) {
            headers.put(name, value);
            return this;
        }
    }

    /** A recorded inbound request. */
    static final class Recorded {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        Recorded(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name);
        }

        String bodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        JsonNode bodyJson() {
            try {
                return MAPPER.readTree(body);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
