package de.bsommerfeld.tinybrowser;

import com.sun.net.httpserver.HttpServer;
import de.bsommerfeld.tinyfetch.api.BrowserEngine;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.HostPolicy;
import de.bsommerfeld.tinyfetch.api.TinyFetch;
import de.bsommerfeld.tinyfetch.api.Wall;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole chain for real: TinyFetch starts TinyBrowser, which opens hidden
 * Chromium tabs and fetches through them - first against a local server,
 * then against Reddit's JSON and Yahoo's search, which must answer.
 *
 * <p>Opt-in: {@code mvn test -pl tinybrowser -am -Dtest=BrowserLiveTest
 * -Dtest.excludedGroups=visual -Dsurefire.failIfNoSpecifiedTests=false}. The
 * first run installs Chromium into {@code target/chromium}; the profile stays
 * in {@code target/live-profile}, so the runs are one returning visitor.
 */
@Tag("live")
class BrowserLiveTest {

    private static final HostPolicy LOCAL = HostPolicy.defaults().withMinInterval(Duration.ZERO);

    private static HttpServer server;
    private static String base;
    private static final Map<String, String> userAgents = new ConcurrentHashMap<>();
    private static TinyFetch fetch;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            userAgents.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            switch (path) {
                case "/" -> send(exchange, 200, "text/html", "<html><title>anchor</title></html>".getBytes());
                case "/echo" -> send(exchange, 200, "application/octet-stream", requestBody);
                case "/binary" -> {
                    byte[] bytes = new byte[300_000];
                    for (int i = 0; i < bytes.length; i++) {
                        bytes[i] = (byte) i;
                    }
                    send(exchange, 200, "application/octet-stream", bytes);
                }
                case "/redirect" -> {
                    exchange.getResponseHeaders().add("location", "/target");
                    send(exchange, 302, null, new byte[0]);
                }
                case "/missing" -> send(exchange, 404, "text/plain", "nicht da".getBytes(StandardCharsets.UTF_8));
                default -> send(exchange, 200, "text/plain; charset=utf-8",
                        ("hallo " + path).getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        Path target = Path.of(System.getProperty("tinybrowser.chromium", "target/chromium")).toAbsolutePath().getParent();
        List<Path> classPath = Arrays.stream(System.getProperty("surefire.test.class.path",
                        System.getProperty("java.class.path")).split(System.getProperty("path.separator")))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .toList();
        TinyFetch.Builder builder = TinyFetch.builder()
                .engine(BrowserEngine.of(classPath, target.resolve("chromium"), target.resolve("live-profile")))
                .policy("127.0.0.1", LOCAL);
        fetch = builder.build();
    }

    @AfterAll
    static void stop() {
        if (fetch != null) {
            fetch.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
        if (type != null) {
            exchange.getResponseHeaders().add("content-type", type);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private static FetchResponse get(String url) throws Exception {
        return fetch.fetch(FetchRequest.of(url).timeout(Duration.ofSeconds(60)));
    }

    @Test
    void localServerThroughTheTab() throws Exception {
        FetchResponse plain = get(base + "/hello");
        assertEquals(200, plain.status());
        assertEquals("hallo /hello", plain.text());
        assertTrue(userAgents.get("/hello").contains("Chrome/"), userAgents.get("/hello"));

        FetchResponse redirected = get(base + "/redirect");
        assertEquals("hallo /target", redirected.text());
        assertEquals(base + "/target", redirected.url().toString());

        assertEquals(404, get(base + "/missing").status());

        byte[] binary = get(base + "/binary").body();
        assertEquals(300_000, binary.length);
        assertEquals((byte) 299_999, binary[299_999]);

        byte[] body = {0, 1, 2, (byte) 0xff, 'x'};
        FetchResponse echoed = fetch.fetch(FetchRequest.of(base + "/echo")
                .post("application/octet-stream", body)
                .header("user-agent", "java:de.bsommerfeld.test:1.0 (by /u/tester)"));
        assertArrayEquals(body, echoed.body());
        assertEquals("java:de.bsommerfeld.test:1.0 (by /u/tester)", userAgents.get("/echo"),
                "a caller's user agent replaces the browser's");
    }

    @Test
    void redditJson() throws Exception {
        FetchResponse listing = get("https://www.reddit.com/r/wallstreetbetsGER/new.json?limit=5&raw_json=1");
        System.out.println("Reddit: " + listing + " " + listing.text().substring(0, Math.min(200, listing.text().length())));
        assertEquals(Wall.NONE, listing.wall(), listing.toString());
        assertEquals(200, listing.status());
        assertTrue(listing.header("content-type").orElse("").contains("json"), listing.headers().toString());
        assertTrue(listing.text().contains("\"kind\":\"Listing\"") || listing.text().contains("\"kind\": \"Listing\""),
                listing.text().substring(0, Math.min(300, listing.text().length())));
    }

    @Test
    void yahooSearch() throws Exception {
        FetchResponse search = get("https://query2.finance.yahoo.com/v1/finance/search?q=SAP&quotesCount=3&newsCount=3");
        System.out.println("Yahoo: " + search + " " + search.text().substring(0, Math.min(200, search.text().length())));
        assertEquals(Wall.NONE, search.wall(), search.toString());
        assertEquals(200, search.status());
        assertTrue(search.text().contains("\"quotes\""), search.text().substring(0, Math.min(300, search.text().length())));
    }
}
