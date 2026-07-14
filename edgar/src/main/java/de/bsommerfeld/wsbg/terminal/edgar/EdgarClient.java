package de.bsommerfeld.wsbg.terminal.edgar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * SEC EDGAR 8-K event client — dated, house-classified 8-K events per US
 * ticker for the market-memory event register (event-study module). Keyless
 * (live-verified 2026-07-14), two official SEC surfaces:
 *
 * <ul>
 *   <li>Ticker→CIK via {@code https://www.sec.gov/files/company_tickers.json}
 *       (shape {@code {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple
 *       Inc."},…}}) — whole map cached 24 h; a refresh failure keeps serving
 *       the stale map (an outage heals itself).</li>
 *   <li>Filings via {@code https://data.sec.gov/submissions/CIK<10-digit
 *       zero-padded>.json} — {@code filings.recent} is COLUMN-oriented
 *       (index-aligned arrays {@code form[]}, {@code filingDate[]},
 *       {@code items[]}, {@code accessionNumber[]}).</li>
 * </ul>
 *
 * <p>SEC fair-access conventions are MANDATORY (violations earn a ~10 min 403
 * block): a descriptive User-Agent with contact on BOTH hosts, and at most
 * 10 requests per second (this client's per-ticker 24 h cache keeps it far
 * below that, but the ceiling is the contract).
 *
 * <p>Only {@code form == "8-K"} rows are read; {@code "8-K/A"} amendments are
 * skipped (duplicates of the original event). Item codes are mapped to the
 * house event classes via a fixed table; unmapped items — including the
 * pre-~2004 numeric legacy codes like {@code "5"}, and the deliberately
 * excluded 2.02/7.01/8.01/9.01/5.03/5.07 — are silently skipped (earnings
 * arrive signed from a different leg). A filing with several mapped items
 * yields several events (same date, different class).
 *
 * <p>US-shaped symbols only: a venue-suffixed, index, future or crypto symbol
 * returns empty with zero network. Per-ticker events cached 24 h (the list
 * only ever changes at the front); structural misses (unknown ticker) are
 * cached, network failures never.
 */
@Singleton
public class EdgarClient {

    private static final Logger LOG = LoggerFactory.getLogger(EdgarClient.class);

    static final String SEC_TICKERS_URL = "https://www.sec.gov/files/company_tickers.json";
    static final String SUBMISSIONS_URL_FMT = "https://data.sec.gov/submissions/CIK%010d.json";
    private static final String SEC_USER_AGENT =
            "wsbg-terminal edgar (contact: b.sommerfeld2003@gmail.com)";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TICKER_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final long CIK_MAP_TTL_MS = 24 * 60 * 60 * 1000L;

    /**
     * Bare US ticker shape: letters with an optional one-letter dot share-class
     * suffix (BRK.A), ≤5 letters — a venue suffix (RHM.DE), index (^…),
     * future (=F) or crypto pair (-USD) never matches.
     */
    private static final Pattern US_SYMBOL = Pattern.compile("[A-Z]{1,5}(\\.[A-Z])?");

    /** 8-K item code → house event class. ONLY these map; everything else is skipped. */
    static final Map<String, String> ITEM_CLASSES = Map.ofEntries(
            Map.entry("1.01", "VERTRAG"),
            Map.entry("1.03", "INSOLVENZ"),
            Map.entry("1.05", "CYBERVORFALL"),
            Map.entry("2.01", "UEBERNAHME_VOLLZUG"),
            Map.entry("2.05", "RESTRUKTURIERUNG"),
            Map.entry("2.06", "IMPAIRMENT"),
            Map.entry("3.01", "DELISTING_HINWEIS"),
            Map.entry("3.02", "VERWAESSERUNG"),
            Map.entry("4.01", "PRUEFERWECHSEL"),
            Map.entry("4.02", "RESTATEMENT"),
            Map.entry("5.02", "FUEHRUNGSWECHSEL"));

    /**
     * One dated, house-classified 8-K event. {@code items} is the filing's raw
     * item string (e.g. {@code "2.06,9.01"}); {@code eventClass} the mapped
     * house class of ONE of its items.
     */
    public record EdgarEvent(LocalDate date, String ticker, String eventClass, String items) {}

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(30);

    /** Ticker (UPPER) → CIK; null until the first successful SEC fetch. */
    private volatile Map<String, Long> cikBySymbol;
    private volatile long cikMapFetchedAtMs;
    private final Object cikMapLock = new Object();

    private record CacheEntry(List<EdgarEvent> events, long atMs) {}

    /** Ticker (UPPER) → events or a cached structural miss, 24 h TTL. */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public EdgarClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam. */
    @Inject
    public EdgarClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * All mappable 8-K events of {@code ticker} from the RECENT filings,
     * oldest first. Empty for a non-US-shaped symbol, an unknown ticker, or a
     * network failure (the latter never cached).
     */
    public List<EdgarEvent> eightKEvents(String ticker) {
        if (ticker == null || ticker.isBlank()) return List.of();
        String key = ticker.trim().toUpperCase(Locale.ROOT);
        if (key.length() > 7 || !US_SYMBOL.matcher(key).matches()) return List.of();

        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(key);
        if (hit != null && now - hit.atMs() < TICKER_CACHE_TTL_MS) return hit.events();

        Map<String, Long> map = cikMap();
        if (map == null) return List.of(); // SEC map fetch failed — miss, uncached

        Long cik = map.get(key);
        if (cik == null) {
            // Definite unknown ticker — structural miss, cached.
            cache.put(key, new CacheEntry(List.of(), now));
            LOG.info("[edgar] {} → no SEC CIK (not US-listed)", key);
            return List.of();
        }
        try {
            WebResponse resp = fetcher.fetch(String.format(Locale.ROOT, SUBMISSIONS_URL_FMT, cik),
                    Map.of("User-Agent", SEC_USER_AGENT, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("[edgar] HTTP {} for {} (cik {})", resp.status(), key, cik);
                return List.of(); // wall/outage — not cached
            }
            List<EdgarEvent> events = parseEightK(resp.body(), key);
            cache.put(key, new CacheEntry(events, now));
            LOG.info("[edgar] {} → {} classified 8-K events (cik {})", key, events.size(), cik);
            return events;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[edgar] 8-K events for {} failed: {}", key, e.getMessage());
            return List.of();
        }
    }

    // ---- ticker→CIK (SEC company_tickers.json) ----

    /**
     * The 24 h-cached SEC ticker→CIK map. A refresh failure keeps serving the
     * stale map; null only before the first success.
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
                        LOG.info("[edgar] SEC ticker map loaded: {} entries", parsed.size());
                        return parsed;
                    }
                }
                LOG.warn("[edgar] SEC ticker map fetch answered HTTP {}", resp.status());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.warn("[edgar] SEC ticker map fetch failed: {}", e.getMessage());
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

    // ---- submissions parsing ----

    /**
     * Reads {@code filings.recent} (column-oriented, index-aligned arrays) and
     * emits one {@link EdgarEvent} per MAPPED item of every plain {@code 8-K}:
     * amendments ({@code 8-K/A}), other forms, unmapped items (incl. the
     * pre-~2004 numeric legacy codes like {@code "5"}) and unparseable dates
     * are silently skipped. Result oldest first. Package-private, network-free.
     */
    static List<EdgarEvent> parseEightK(String json, String ticker) throws Exception {
        JsonNode recent = JSON.readTree(json).path("filings").path("recent");
        JsonNode forms = recent.path("form");
        JsonNode dates = recent.path("filingDate");
        JsonNode items = recent.path("items");
        if (!forms.isArray()) return List.of();

        List<EdgarEvent> out = new ArrayList<>();
        for (int i = 0; i < forms.size(); i++) {
            if (!"8-K".equals(forms.path(i).asText())) continue; // 8-K/A etc. skipped
            String itemString = items.path(i).asText("").trim();
            if (itemString.isEmpty()) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(dates.path(i).asText(""));
            } catch (Exception e) {
                continue; // torn/absent date — skip the filing
            }
            for (String item : itemString.split(",")) {
                String eventClass = ITEM_CLASSES.get(item.trim());
                if (eventClass != null) out.add(new EdgarEvent(date, ticker, eventClass, itemString));
            }
        }
        // recent[] is newest-first; the register wants oldest first (stable).
        out.sort(Comparator.comparing(EdgarEvent::date));
        return out;
    }
}
