package de.bsommerfeld.wsbg.terminal.insidermonkey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularity;
import de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularity.InsiderRow;
import de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularity.QuarterPoint;
import de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularitySource;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Insider Monkey hedge-fund popularity client — the 13F "how many hedge funds
 * hold X" quarterly curve plus recent Form-4 insider rows, keyless (probed
 * 2026-07-14). Company pages answer plain HTTP 200 under a browser UA; search
 * and tag feeds are AWS-WAF-walled and robots-disallowed — this client NEVER
 * touches them.
 *
 * <p>Addressing is the SEC CIK: {@code /insider-trading/company/<slug>/<CIK>/}
 * where the slug is ignored (live-verified: {@code /company/x/1649989/} = OTLK).
 * Ticker→CIK resolves via the SEC's official {@code company_tickers.json}
 * (fair-access policy: descriptive User-Agent with contact), whole map cached
 * 24 h in memory, fetched lazily; a fetch failure is a resolution miss and is
 * NOT cached. The page embeds the curve INLINE as JSON (no XHR) in four
 * period-keyed arrays ({@code nPositions}, {@code totalNShares},
 * {@code nNewPositions}, {@code nClosedPositions}) that are NOT index-aligned —
 * the source omits zero quarters, so the arrays are joined by
 * {@code filingPeriod}, never zipped by index (live-verified: OTLK's
 * {@code nClosedPositions} starts a quarter late).
 *
 * <p>US-listed names only: a non-US-shaped symbol returns empty with zero
 * network. Per-symbol cache 1 h; a structural miss (unknown ticker, page
 * without data) is cached, a network failure is not.
 */
@Singleton
public class InsiderMonkeyClient implements HedgeFundPopularitySource {

    private static final Logger LOG = LoggerFactory.getLogger(InsiderMonkeyClient.class);

    static final String SEC_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json";
    private static final String SEC_USER_AGENT =
            "wsbg-terminal insidermonkey (contact: b.sommerfeld2003@gmail.com)";
    static final String PAGE_URL_FMT = "https://www.insidermonkey.com/insider-trading/company/x/%d/";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SYMBOL_CACHE_TTL_MS = 60 * 60 * 1000L;
    private static final long CIK_MAP_TTL_MS = 24 * 60 * 60 * 1000L;
    static final int MAX_INSIDER_ROWS = 10;

    /**
     * Bare US ticker shape: letters with an optional one-letter dot share-class
     * suffix (BRK.A), ≤5 chars total — a venue suffix (RHM.DE), index (^…),
     * future (=F) or crypto pair (-USD) never matches.
     */
    private static final Pattern US_SYMBOL = Pattern.compile("[A-Z]{1,5}(\\.[A-Z])?");

    private static final Pattern SECTION_HEADER =
            Pattern.compile("Insider Trading:\\s*([^<]+)</span>");
    private static final Pattern TABLE_ROW =
            Pattern.compile("<tr class=\"[^\"]*\">(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern ROW_NAME =
            Pattern.compile("title=\"Click to See Insider Details\">([^<]+)</a>");
    private static final Pattern ROW_CELL =
            Pattern.compile("<td><span>([^<]*)</span></td>");

    private final WebFetcher fetcher;
    private final String browserUserAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);

    /** Ticker (UPPER) → CIK; null until the first successful SEC fetch. */
    private volatile Map<String, Long> cikBySymbol;
    private volatile long cikMapFetchedAtMs;
    private final Object cikMapLock = new Object();

    private record CacheEntry(Optional<HedgeFundPopularity> value, long atMs) {}

    /** Symbol (UPPER) → popularity or a cached structural miss, 1 h TTL. */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public InsiderMonkeyClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam. */
    @Inject
    public InsiderMonkeyClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Optional<HedgeFundPopularity> popularityFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        String key = symbol.trim().toUpperCase(Locale.ROOT);
        if (key.length() > 5 || !US_SYMBOL.matcher(key).matches()) return Optional.empty();

        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(key);
        if (hit != null && now - hit.atMs() < SYMBOL_CACHE_TTL_MS) return hit.value();

        Map<String, Long> map = cikMap();
        if (map == null) return Optional.empty(); // SEC fetch failed — miss, uncached

        Long cik = map.get(key);
        if (cik == null) {
            // Definite unknown ticker — structural miss, cached.
            cache.put(key, new CacheEntry(Optional.empty(), now));
            LOG.info("[insidermonkey] {} → no SEC CIK (not US-listed)", key);
            return Optional.empty();
        }
        try {
            WebResponse resp = fetcher.fetch(String.format(Locale.ROOT, PAGE_URL_FMT, cik),
                    Map.of("User-Agent", browserUserAgent,
                            "Accept", "text/html,application/xhtml+xml"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("[insidermonkey] HTTP {} for {} (cik {})", resp.status(), key, cik);
                return Optional.empty(); // wall/outage — not cached
            }
            Optional<HedgeFundPopularity> parsed = parsePage(key, cik, resp.body());
            cache.put(key, new CacheEntry(parsed, now)); // 200 without data = structural miss
            parsed.ifPresentOrElse(
                    p -> LOG.info("[insidermonkey] {} → {} quarters, {} insider rows (cik {})",
                            key, p.quarters().size(), p.recentInsiderRows().size(), cik),
                    () -> LOG.info("[insidermonkey] {} → page carries no data (cik {})", key, cik));
            return parsed;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[insidermonkey] popularity for {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    // ---- ticker→CIK (SEC company_tickers.json) ----

    /**
     * The 24 h-cached SEC ticker→CIK map. A refresh failure keeps serving the
     * stale map (an outage heals itself); null only before the first success.
     */
    private Map<String, Long> cikMap() {
        Map<String, Long> m = cikBySymbol;
        if (m != null && System.currentTimeMillis() - cikMapFetchedAtMs < CIK_MAP_TTL_MS) return m;
        synchronized (cikMapLock) {
            m = cikBySymbol;
            long now = System.currentTimeMillis();
            if (m != null && now - cikMapFetchedAtMs < CIK_MAP_TTL_MS) return m;
            try {
                WebResponse resp = fetcher.fetch(SEC_TICKERS_URL,
                        Map.of("User-Agent", SEC_USER_AGENT, "Accept", "application/json"),
                        Duration.ofSeconds(30));
                if (resp.status() == 200) {
                    Map<String, Long> parsed = parseCikMap(resp.body());
                    if (!parsed.isEmpty()) {
                        cikBySymbol = parsed;
                        cikMapFetchedAtMs = now;
                        LOG.info("[insidermonkey] SEC ticker map loaded: {} entries", parsed.size());
                        return parsed;
                    }
                }
                LOG.warn("[insidermonkey] SEC ticker map fetch answered HTTP {}", resp.status());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.warn("[insidermonkey] SEC ticker map fetch failed: {}", e.getMessage());
            }
            return cikBySymbol; // stale beats none; null when never loaded
        }
    }

    /**
     * Parses {@code company_tickers.json} ({@code {"0":{"cik_str":320193,
     * "ticker":"AAPL","title":"Apple Inc."},…}}) into ticker→CIK. First entry
     * wins on a duplicate ticker (the file lists primary listings first).
     * Package-private, network-free.
     */
    static Map<String, Long> parseCikMap(String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        Map<String, Long> out = new HashMap<>(root.size() * 2);
        for (JsonNode e : root) {
            String ticker = e.path("ticker").asText("").trim().toUpperCase(Locale.ROOT);
            long cik = e.path("cik_str").asLong(-1);
            if (ticker.isEmpty() || cik <= 0) continue;
            out.putIfAbsent(ticker, cik);
        }
        return out;
    }

    // ---- company page parsing ----

    /**
     * Assembles the popularity record from one company page, or empty when the
     * page carries neither a curve nor insider rows. Package-private, network-free.
     */
    static Optional<HedgeFundPopularity> parsePage(String symbol, long cik, String body) {
        List<QuarterPoint> quarters = parseQuarters(body);
        List<InsiderRow> rows = parseInsiderRows(body);
        if (rows.size() > MAX_INSIDER_ROWS) rows = rows.subList(0, MAX_INSIDER_ROWS);
        if (quarters.isEmpty() && rows.isEmpty()) return Optional.empty();
        return Optional.of(new HedgeFundPopularity(symbol, cik, quarters, rows));
    }

    /**
     * Cuts the four inline JSON arrays and joins them BY {@code filingPeriod}
     * (never by index — the source omits zero quarters, so the arrays are not
     * aligned). {@code nPositions} is the master curve; a period absent from
     * {@code totalNShares} is unknown (-1), absent new/closed counts are 0
     * (the omitted-zero convention). Package-private, network-free.
     */
    static List<QuarterPoint> parseQuarters(String body) {
        try {
            JsonNode master = readArray(body, "\"nPositions\":");
            if (master == null) return List.of();
            Map<String, JsonNode> shares = byPeriod(readArray(body, "\"totalNShares\":"));
            Map<String, JsonNode> opened = byPeriod(readArray(body, "\"nNewPositions\":"));
            Map<String, JsonNode> closed = byPeriod(readArray(body, "\"nClosedPositions\":"));

            List<QuarterPoint> out = new ArrayList<>(master.size());
            for (JsonNode p : master) {
                String period = p.path("filingPeriod").asText("");
                if (period.isEmpty()) continue;
                String label = (p.path("qtr").asText("") + " " + p.path("year").asText("")).trim();
                JsonNode sh = shares.get(period);
                long totalShares = sh != null && sh.path("value").isNumber()
                        ? Math.round(sh.path("value").asDouble()) : -1;
                out.add(new QuarterPoint(
                        period,
                        label.isEmpty() ? null : label,
                        p.path("value").asInt(-1),
                        totalShares,
                        intValue(opened.get(period)),
                        intValue(closed.get(period)),
                        p.path("price").isNumber() ? p.path("price").asDouble() : Double.NaN,
                        p.path("isFilingPeriodOngoing").asBoolean(false)));
            }
            return out;
        } catch (Exception e) {
            LOG.warn("[insidermonkey] curve parse failure: {}", e.getMessage());
            return List.of();
        }
    }

    private static int intValue(JsonNode entry) {
        return entry != null ? entry.path("value").asInt(0) : 0;
    }

    private static Map<String, JsonNode> byPeriod(JsonNode array) {
        if (array == null) return Map.of();
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode e : array) {
            String period = e.path("filingPeriod").asText("");
            if (!period.isEmpty()) out.putIfAbsent(period, e);
        }
        return out;
    }

    private static JsonNode readArray(String body, String key) throws Exception {
        String cut = cutJsonArray(body, key);
        if (cut == null) return null;
        JsonNode n = JSON.readTree(cut);
        return n.isArray() ? n : null;
    }

    /**
     * String-scans {@code body} for {@code key} and returns the bracket-matched
     * JSON array that follows it, string-literal-aware (the arrays run long —
     * no regex). Null when the key or a well-formed array is absent.
     * Package-private, network-free.
     */
    static String cutJsonArray(String body, String key) {
        int i = body.indexOf(key);
        if (i < 0) return null;
        int start = body.indexOf('[', i + key.length());
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false, escaped = false;
        for (int j = start; j < body.length(); j++) {
            char c = body.charAt(j);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return body.substring(start, j + 1);
        }
        return null;
    }

    /**
     * Parses the server-rendered insider tables. Each sits under a
     * {@code Insider Trading: <label>} section header whose label is the row's
     * verbatim transaction ("Purchases" / "Sales"); columns are Insider | Price |
     * Amount | Total Value | Remaining Holdings | Date | Form 4. Garbled rows are
     * skipped; the merged result is sorted newest first. Package-private, network-free.
     */
    static List<InsiderRow> parseInsiderRows(String body) {
        List<InsiderRow> out = new ArrayList<>();
        Matcher header = SECTION_HEADER.matcher(body);
        List<int[]> sections = new ArrayList<>(); // [start, labelStart, labelEnd]
        List<String> labels = new ArrayList<>();
        while (header.find()) {
            sections.add(new int[]{header.end()});
            labels.add(header.group(1).trim());
        }
        for (int s = 0; s < sections.size(); s++) {
            int from = sections.get(s)[0];
            int to = s + 1 < sections.size() ? sections.get(s + 1)[0] : body.length();
            int tableStart = body.indexOf("<table", from);
            if (tableStart < 0 || tableStart >= to) continue;
            int tableEnd = body.indexOf("</table>", tableStart);
            if (tableEnd < 0 || tableEnd > to) tableEnd = to;
            String table = body.substring(tableStart, tableEnd);
            Matcher row = TABLE_ROW.matcher(table);
            while (row.find()) {
                InsiderRow parsed = parseRow(row.group(1), labels.get(s));
                if (parsed != null) out.add(parsed);
            }
        }
        out.sort((a, b) -> {
            String da = a.dateIso() == null ? "" : a.dateIso();
            String db = b.dateIso() == null ? "" : b.dateIso();
            return db.compareTo(da); // ISO strings — newest first
        });
        return out;
    }

    /** One table row → InsiderRow, or null when the cells don't line up. */
    private static InsiderRow parseRow(String row, String transaction) {
        Matcher name = ROW_NAME.matcher(row);
        if (!name.find()) return null;
        Matcher cell = ROW_CELL.matcher(row);
        List<String> cells = new ArrayList<>(5);
        while (cell.find()) cells.add(cell.group(1));
        if (cells.size() < 5) return null;
        // Price | Amount | Total Value | Remaining Holdings | Date
        String date = cells.get(4).trim();
        return new InsiderRow(
                date.matches("\\d{4}-\\d{2}-\\d{2}") ? date : null,
                name.group(1).trim(),
                transaction,
                parseLong(cells.get(1)),
                parseDouble(cells.get(0)),
                parseDouble(cells.get(2)),
                parseLong(cells.get(3)));
    }

    private static String cleanNumber(String s) {
        return s.replace("$", "").replace(",", "").replace(" ", "").trim();
    }

    private static long parseLong(String s) {
        try {
            return Math.round(Double.parseDouble(cleanNumber(s)));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(cleanNumber(s));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
