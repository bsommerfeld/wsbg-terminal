package de.bsommerfeld.wsbg.terminal.boersefrankfurt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.OrderBookSnapshot;
import de.bsommerfeld.wsbg.terminal.core.price.OrderBookSource;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Börse Frankfurt order-book client — the visible Frankfurt floor specialist
 * book (up to 10 price levels per side, orders + units per level) by ISIN,
 * keyless via {@code api.boerse-frankfurt.de/v1/data/bid_ask_overview}.
 *
 * <p><b>Transport carve-out (documented, deliberate):</b> the endpoint answers
 * {@code text/event-stream} (SSE) — the FIRST {@code data:} frame carries the
 * complete book, followed by {@code health_event} keepalives that never end.
 * The house browser joker cannot ride this ({@code WebFetcher.fetch} only
 * delivers complete bodies, and an SSE stream has none), so like
 * {@code InstrumentCorpus} this client is a DIRECT-ONLY carve-out from the
 * joker-first principle: a dedicated JDK {@link HttpClient} streams lines,
 * reads the first book-shaped {@code data:} frame and closes immediately.
 *
 * <p>Endpoint facts (live-probed 2026-07-14): a plain browser-ish
 * {@code User-Agent} suffices; NEVER send an {@code Origin} header — the
 * server then answers "Invalid CORS request". Each frame line carries BOTH
 * sides of one level ({@code {askOffers, askPrice, askUnits, bidOffers,
 * bidPrice, bidUnits}}); fields are null when one side has fewer levels.
 * Outside trading hours (after ~22:00) the same call answers an empty body
 * (content-length 0) — a plain miss, not an error. No caching: the book is
 * live, the consumer calls once per report.
 */
@Singleton
public class OrderBookClient implements OrderBookSource {

    private static final Logger LOG = LoggerFactory.getLogger(OrderBookClient.class);

    private static final String BOOK_URL =
            "https://api.boerse-frankfurt.de/v1/data/bid_ask_overview?isin=";
    /** ISIN shape: 2 letters, 9 alphanumerics, 1 check digit. */
    private static final Pattern ISIN_SHAPE =
            Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(8);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String userAgent = BrowserUserAgent.random();

    @Inject
    public OrderBookClient() {
    }

    @Override
    public Optional<OrderBookSnapshot> orderBookByIsin(String isin) {
        String cleaned = isin == null ? "" : isin.trim().toUpperCase();
        if (!ISIN_SHAPE.matcher(cleaned).matches()) return Optional.empty();
        long deadline = System.nanoTime() + OVERALL_TIMEOUT.toNanos();
        HttpRequest request = HttpRequest.newBuilder(URI.create(BOOK_URL + cleaned))
                .timeout(OVERALL_TIMEOUT)
                // Deliberately NO Origin header — the server rejects it
                // ("Invalid CORS request"). A plain UA is all it wants.
                .header("User-Agent", userAgent)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        try {
            HttpResponse<java.io.InputStream> resp =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                if (resp.statusCode() != 200) {
                    LOG.warn("[BoerseFfm] HTTP {} for isin={}", resp.statusCode(), cleaned);
                    return Optional.empty();
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (System.nanoTime() > deadline) break;
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring("data:".length()).trim();
                    Optional<OrderBookSnapshot> book = parseFrame(payload);
                    if (book.isPresent()) {
                        OrderBookSnapshot b = book.get();
                        LOG.debug("[BoerseFfm] {} → book with {} bid / {} ask levels",
                                cleaned, b.bids().size(), b.asks().size());
                        return book;
                    }
                    // A data frame that isn't the book (keepalive payload) —
                    // keep listening until the deadline.
                }
                // Empty body = closed session / unlisted instrument: a miss.
                LOG.debug("[BoerseFfm] {} → no book frame (session closed or unlisted)", cleaned);
                return Optional.empty();
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[BoerseFfm] fetch for isin={} failed: {}", cleaned, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses one SSE {@code data:} payload into a book, or empty when the JSON
     * is not book-shaped. Each level row carries BOTH sides; a side whose price
     * is missing/null on that row is skipped for that side. {@code *Offers} →
     * orders (missing → 0), {@code *Units} → units (missing → 0). Bids sort
     * best-first descending, asks best-first ascending. Network-free.
     */
    static Optional<OrderBookSnapshot> parseFrame(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isObject()) return Optional.empty();
            JsonNode levels = levelArray(root);
            if (levels == null || levels.isEmpty()) return Optional.empty();
            List<OrderBookSnapshot.Level> bids = new ArrayList<>();
            List<OrderBookSnapshot.Level> asks = new ArrayList<>();
            for (JsonNode row : levels) {
                sideLevel(row, "bidPrice", "bidOffers", "bidUnits").ifPresent(bids::add);
                sideLevel(row, "askPrice", "askOffers", "askUnits").ifPresent(asks::add);
            }
            if (bids.isEmpty() && asks.isEmpty()) return Optional.empty();
            bids.sort(Comparator.comparingDouble(OrderBookSnapshot.Level::price).reversed());
            asks.sort(Comparator.comparingDouble(OrderBookSnapshot.Level::price));
            return Optional.of(new OrderBookSnapshot(
                    text(root, "isin"), text(root, "time"), bids, asks));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The frame's level array, wherever it sits (any array-valued field). */
    private static JsonNode levelArray(JsonNode root) {
        var fields = root.fields();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue();
            if (value.isArray()) return value;
        }
        return null;
    }

    private static Optional<OrderBookSnapshot.Level> sideLevel(
            JsonNode row, String priceField, String offersField, String unitsField) {
        JsonNode price = row.get(priceField);
        if (price == null || price.isNull() || !price.isNumber()) return Optional.empty();
        return Optional.of(new OrderBookSnapshot.Level(
                price.asDouble(), intOrZero(row.get(offersField)), longOrZero(row.get(unitsField))));
    }

    private static int intOrZero(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : 0;
    }

    private static long longOrZero(JsonNode node) {
        return node != null && node.isNumber() ? node.asLong() : 0L;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? "" : n.asText();
    }
}
