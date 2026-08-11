package de.bsommerfeld.wsbg.terminal.minkabu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Minkabu's open chart backend
 * ({@code mkdd.net/api/v1/bar/daily?ric=<T>.T&compress=highcharts}, probed
 * 2026-08-05): ONE keyless call answers roughly TWO DECADES of daily OHLCV
 * bars for a Tokyo listing - the deepest JPX tape this house reaches, since
 * the exchange's own site is closed. Bars arrive NEWEST first as bare
 * six-tuples ({@code [epochMs, open, high, low, close, volume]}); the
 * youngest bar is the RUNNING session (~20 minutes delayed), which is where
 * the current price falls out.
 *
 * <p>A second best-effort call to the assets catalogue
 * ({@code mkdd.net/api/assets?ticker=<T>}) fetches the issuer's native name;
 * when it stays silent the history ships without one - the bars never wait
 * for a label.
 *
 * <p>Japanese RICs only ({@code 6758.T}, letter-suffixed classes included):
 * anything else is a network-free no-op, same gate the NYSE client keeps for
 * its side of the world. Unknown RICs answer 200 with an EMPTY bar array, so
 * emptiness - not status - is the not-found signal.
 */
@Singleton
public class MinkabuClient {

    private static final Logger LOG = LoggerFactory.getLogger(MinkabuClient.class);

    static final String DAILY_URL =
            "https://mkdd.net/api/v1/bar/daily?ric=%s&compress=highcharts&limit=%d";
    static final String ASSETS_URL = "https://mkdd.net/api/assets?ticker=%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A Tokyo RIC: four digits, an optional class letter, the {@code .T} venue. */
    private static final Pattern JP_RIC = Pattern.compile("\\d{4}[A-Z]?\\.T");
    /** Bar timestamps are session midnights UTC; the session's date is Tokyo's. */
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    /** Twenty years of bars is still a small payload - but give the wire room. */
    private final Duration requestTimeout = Duration.ofSeconds(20);

    /** Test/default: plain direct transport. */
    public MinkabuClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public MinkabuClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The daily tape for one Tokyo RIC, up to {@code limit} sessions deep.
     *
     * @return the history, or empty when the RIC is not Tokyo-shaped, unknown
     *         or the endpoint failed - never {@code null}
     */
    public Optional<MinkabuHistory> history(String ric, int limit) {
        if (ric == null || limit <= 0) return Optional.empty();
        String r = ric.trim().toUpperCase(Locale.ROOT);
        if (!JP_RIC.matcher(r).matches()) return Optional.empty();
        try {
            WebResponse resp = fetcher.fetch(String.format(DAILY_URL, r, limit),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[Minkabu] status {} for {}",
                        resp == null ? "null" : resp.status(), r);
                return Optional.empty();
            }
            Optional<MinkabuHistory> history = parseHistory(resp.body(), name(r));
            history.ifPresent(h -> LOG.info(
                    "[Minkabu] {}{} → bars={} latest={} close={}",
                    h.ric(), h.name() == null ? "" : " (" + h.name() + ")",
                    h.bars().size(), h.latestDate(), h.latestClose()));
            return history;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Minkabu] fetch for {} failed: {}", r, e.getMessage());
            return Optional.empty();
        }
    }

    /** The issuer's native name off the assets catalogue - best effort only. */
    private String name(String ric) {
        String ticker = ric.substring(0, ric.length() - 2);
        try {
            WebResponse resp = fetcher.fetch(String.format(ASSETS_URL, ticker),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp == null || resp.status() != 200) return null;
            return parseName(resp.body(), ric);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Minkabu] assets for {} failed: {}", ric, e.getMessage());
            return null;
        }
    }

    /** Parses the bar reply's six-tuples. Package-private for tests. */
    static Optional<MinkabuHistory> parseHistory(String json, String name) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(json);
            String ric = text(root, "ric");
            JsonNode data = root.path("data");
            if (ric == null || !data.isArray()) return Optional.empty();
            List<MinkabuHistory.MinkabuBar> bars = new ArrayList<>();
            for (JsonNode b : data) {
                if (!b.isArray() || b.size() < 6) continue;
                LocalDate date = barDate(b.get(0));
                double close = num(b.get(4));
                if (date == null || !Double.isFinite(close) || close <= 0) continue;
                bars.add(new MinkabuHistory.MinkabuBar(date,
                        num(b.get(1)), num(b.get(2)), num(b.get(3)), close,
                        num(b.get(5))));
            }
            if (bars.isEmpty()) return Optional.empty();
            bars.sort((a, b) -> a.date().compareTo(b.date()));
            return Optional.of(new MinkabuHistory(ric, name, List.copyOf(bars)));
        } catch (Exception e) {
            LOG.warn("[Minkabu] unparseable payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses the assets reply's matching row. Package-private for tests. */
    static String parseName(String json, String ric) {
        if (json == null || json.isBlank()) return null;
        try {
            for (JsonNode item : MAPPER.readTree(json).path("items")) {
                if (ric.equals(text(item, "ric"))) return text(item, "name");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate barDate(JsonNode epochMs) {
        if (epochMs == null || !epochMs.canConvertToLong()) return null;
        long ms = epochMs.asLong();
        if (ms <= 0) return null;
        return Instant.ofEpochMilli(ms).atZone(TOKYO).toLocalDate();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** A number-typed cell, {@code NaN} when absent/unreadable. */
    private static double num(JsonNode v) {
        return v != null && v.isNumber() ? v.asDouble() : Double.NaN;
    }
}
