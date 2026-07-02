package de.bsommerfeld.wsbg.terminal.langschwarz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lang &amp; Schwarz <b>Tradecenter</b> (ls-tc.de) price client — the primary price
 * source. Unlike Tradegate it quotes the long German session including the US
 * after-hours spike (e.g. a Micron +1000-style move), in EUR, which is the venue
 * a German retail trader actually sees.
 *
 * <p>Two undocumented-but-stable JSON endpoints (ride the shared browser-joker
 * {@link WebFetcher}; ls-tc bot-walls bare clients):
 * <ul>
 *   <li><b>Search by name</b> {@code …/instrument/search/main?q=<name>} →
 *       {@code [{instrumentId, isin, displayname, categorySymbol:"STK"}]} — so the
 *       resolver's canonical name is enough; the ISIN also feeds the Deutsche Börse fallback.</li>
 *   <li><b>Chart</b> {@code …/chart/dataForInstrument?instrumentId=<id>&series=intraday}
 *       → {@code series.intraday.data} = [ts,price] pairs (the sparkline), the last
 *       price, and {@code info.plotlines} = previous close (for the day move).</li>
 * </ul>
 *
 * <p>Every call returns {@link Optional#empty()} on any failure and logs at WARN;
 * the price-source chain falls through to the next provider.
 */
@Singleton
public class LangSchwarzClient {

    private static final Logger LOG = LoggerFactory.getLogger(LangSchwarzClient.class);

    private static final String SEARCH_URL =
            "https://www.ls-tc.de/_rpc/json/.lstc/instrument/search/main?localeId=2&q=";
    private static final String CHART_URL =
            "https://www.ls-tc.de/_rpc/json/instrument/chart/dataForInstrument"
                    + "?marketId=1&quotetype=mid&series=intraday,history&localeId=2&container=chart&instrumentId=";

    private static final int SPARK_POINTS = 40;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** name (lowercased) → resolved instrument; null sentinel via Optional caching omitted for simplicity. */
    private final Map<String, LsInstrument> instrumentCache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport (hits the bot wall live — joker needed). */
    public LangSchwarzClient() {
        this(new DirectWebFetcher());
    }

    /** Production: rides the shared browser-joker {@link WebFetcher} chain. */
    @Inject
    public LangSchwarzClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Resolves a subject name to its L&amp;S instrument (cached), or empty. L&amp;S's
     * search rejects the full Yahoo name ("Micron Technology, Inc." → empty), so we
     * try progressively simpler queries: the cleaned name (legal suffixes / "The" /
     * comma-tail stripped), then the first word, then the raw name.
     */
    public Optional<LsInstrument> resolveInstrument(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String key = name.trim().toLowerCase(Locale.ROOT);
        LsInstrument cached = instrumentCache.get(key);
        if (cached != null) return Optional.of(cached);
        for (String q : queryCandidates(name)) {
            try {
                WebResponse resp = get(SEARCH_URL + URLEncoder.encode(q, StandardCharsets.UTF_8));
                if (resp == null) continue;
                Optional<LsInstrument> inst = parseSearch(resp.body(), name);
                if (inst.isPresent()) {
                    instrumentCache.put(key, inst.get());
                    LOG.info("[L&S] '{}' (q='{}') → id={} isin={} '{}'", name, q,
                            inst.get().instrumentId(), inst.get().isin(), inst.get().displayName());
                    return inst;
                }
            } catch (Exception e) {
                warn("search", q, e);
            }
        }
        LOG.info("[L&S] '{}' → no instrument (tried {})", name, queryCandidates(name));
        return Optional.empty();
    }

    /**
     * Resolves an instrument by its EXACT ISIN (L&S search accepts an ISIN and returns the
     * one matching listing) — no name fuzz, no wrong-twin risk. Used when an upstream source
     * (wallstreet-online) already pinned the ISIN. Cached, or empty.
     */
    public Optional<LsInstrument> resolveByIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String key = "isin:" + isin.trim().toUpperCase(Locale.ROOT);
        LsInstrument cached = instrumentCache.get(key);
        if (cached != null) return Optional.of(cached);
        try {
            WebResponse resp = get(SEARCH_URL + URLEncoder.encode(isin.trim(), StandardCharsets.UTF_8));
            if (resp == null) return Optional.empty();
            Optional<LsInstrument> inst = parseByIsin(resp.body(), isin.trim());
            inst.ifPresent(i -> {
                instrumentCache.put(key, i);
                LOG.info("[L&S] isin={} → id={} '{}'", isin, i.instrumentId(), i.displayName());
            });
            return inst;
        } catch (Exception e) {
            warn("isin-search", isin, e);
            return Optional.empty();
        }
    }

    /** Picks the search hit whose ISIN equals the query ISIN (exact). Package-private, network-free. */
    Optional<LsInstrument> parseByIsin(String body, String isin) {
        try {
            JsonNode arr = JSON.readTree(body);
            if (!arr.isArray() || arr.isEmpty()) return Optional.empty();
            for (JsonNode n : arr) {
                if (!isin.equalsIgnoreCase(n.path("isin").asText(""))) continue;
                long id = n.path("instrumentId").asLong(n.path("id").asLong(0));
                if (id <= 0) continue;
                return Optional.of(new LsInstrument(id, n.path("isin").asText(""), n.path("displayname").asText("")));
            }
            return Optional.empty();
        } catch (Exception e) {
            LOG.warn("L&S isin parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Search queries to try, simplest-matching-first: cleaned name, first word, raw. */
    static List<String> queryCandidates(String name) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String cleaned = cleanForSearch(name);
        if (!cleaned.isBlank()) out.add(cleaned);
        int sp = cleaned.indexOf(' ');
        if (sp > 0) out.add(cleaned.substring(0, sp)); // first word, e.g. "Micron"
        out.add(name.trim());                          // raw, last resort
        out.removeIf(String::isBlank);
        return new ArrayList<>(out);
    }

    /** Strips a leading "The", a comma tail (", Inc."), and a trailing legal/corporate token. */
    static String cleanForSearch(String name) {
        String s = name == null ? "" : name.trim();
        if (s.regionMatches(true, 0, "The ", 0, 4)) s = s.substring(4).trim();
        int comma = s.indexOf(',');
        if (comma > 0) s = s.substring(0, comma).trim();
        s = s.replaceAll("(?i)\\.com$", "").trim(); // "Amazon.com" → "Amazon" (else L&S search misses it)
        s = s.replaceAll("(?i)\\s+(Inc|Incorporated|Corp|Corporation|Company|Co|Holdings?|Group|"
                + "PLC|Ltd|Limited|AG|SE|NV|N\\.V\\.|A/S|S\\.A\\.|SA|S\\.p\\.A\\.|ADR)\\.?$", "").trim();
        return s.isEmpty() ? (name == null ? "" : name.trim()) : s;
    }

    /** Live EUR snapshot for a subject name (resolve → chart), or empty on any failure. */
    public Optional<MarketSnapshot> fetchSnapshot(String name) {
        Optional<LsInstrument> inst = resolveInstrument(name);
        if (inst.isEmpty()) return Optional.empty();
        return fetchSnapshot(inst.get());
    }

    /** Live EUR snapshot for an already-resolved instrument. */
    public Optional<MarketSnapshot> fetchSnapshot(LsInstrument inst) {
        if (inst == null) return Optional.empty();
        try {
            WebResponse resp = get(CHART_URL + inst.instrumentId());
            if (resp == null) { LOG.info("[L&S] chart id={} → no/blocked response", inst.instrumentId()); return Optional.empty(); }
            Optional<MarketSnapshot> snap = parseChart(resp.body(), inst.isin());
            if (snap.isEmpty()) {
                LOG.info("[L&S] chart id={} → unparseable (body {} chars)", inst.instrumentId(),
                        resp.body() == null ? 0 : resp.body().length());
            }
            return snap;
        } catch (Exception e) {
            warn("chart", String.valueOf(inst.instrumentId()), e);
            return Optional.empty();
        }
    }

    private WebResponse get(String url) throws Exception {
        WebResponse resp = fetcher.fetch(url,
                Map.of("User-Agent", userAgent, "Accept", "application/json"),
                requestTimeout);
        if (resp.status() != 200) {
            LOG.warn("L&S returned HTTP {} for {}", resp.status(), url);
            return null;
        }
        return resp;
    }

    private static void warn(String stage, String what, Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        LOG.warn("L&S {} '{}' failed: {}", stage, what, e.getMessage());
    }

    // ---- parsing (package-private, network-free) ----

    /**
     * Minimum fraction of a subject's significant name tokens a search hit must
     * cover to be trusted. Below this the hit is a same-named <em>twin</em>
     * („Mullen Group" for „Mullen Automotive") and is rejected — better no L&S
     * price (fall through) than a confident wrong one.
     */
    static final double MIN_NAME_COVERAGE = 0.6;

    /**
     * Noise tokens that carry no identity — issuer/legal/share-class cruft + venue
     * abbreviations. Includes the FULL-word legal/connector forms ("corporation",
     * "company", "and"), not just the abbreviations: a Yahoo subject name carries
     * them ("Microsoft Corporation", "Eli Lilly and Company") while L&S abbreviates
     * ("MICROSOFT", "ELI LILLY"), so without stripping them the coverage gate would
     * wrongly reject the megacap.
     */
    private static final java.util.Set<String> NAME_STOP = java.util.Set.of(
            "etf", "ucits", "the", "and", "und", "com",
            "inc", "incorporated", "corp", "corporation", "co", "company",
            "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international",
            "technology", "technologies", "industries", "industrial",
            "pharmaceutical", "pharmaceuticals", "pharma",
            "ord", "reg", "shs", "share", "shares", "dl", "trn",
            "index", "etr", "acc", "dist", "fund", "trust", "cdr");

    /** One search hit, pre-ranking. */
    private record Cand(long id, String isin, String name, boolean stk) {}

    /**
     * Parses the search array and returns the hit that best matches the FULL
     * subject {@code fullName} (not the truncated query), or empty. Ranking is by
     * prefix-tolerant token <b>coverage</b> of the subject's significant tokens
     * (so „THERAP." covers „Therapeutics") gated at {@link #MIN_NAME_COVERAGE};
     * among qualifiers the tightest wins (coverage, then equity-first, then fewest
     * extra tokens), so „MSCI World" picks a plain World tracker, not „MSCI World
     * Quality Factor" or „MSCI USA", and a wrong twin is dropped rather than taken
     * blindly as the first hit.
     */
    Optional<LsInstrument> parseSearch(String body, String fullName) {
        try {
            JsonNode arr = JSON.readTree(body);
            if (!arr.isArray() || arr.isEmpty()) return Optional.empty();
            List<Cand> cands = new ArrayList<>();
            for (JsonNode n : arr) {
                long id = n.path("instrumentId").asLong(n.path("id").asLong(0));
                if (id <= 0) continue;
                cands.add(new Cand(id, n.path("isin").asText(""), n.path("displayname").asText(""),
                        "STK".equalsIgnoreCase(n.path("categorySymbol").asText(""))));
            }
            return pickBest(fullName, cands);
        } catch (Exception e) {
            LOG.warn("L&S search parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Ranks pre-parsed candidates against {@code fullName}; see {@link #parseSearch}. */
    private Optional<LsInstrument> pickBest(String fullName, List<Cand> cands) {
        List<String> want = nameTokens(fullName);
        Cand best = null;
        double bestCov = 0;
        int bestStk = 0, bestExtra = 0;
        for (Cand c : cands) {
            List<String> have = nameTokens(c.name());
            double cov = coverage(want, have);
            if (cov < MIN_NAME_COVERAGE) continue;
            int stk = c.stk() ? 0 : 1;                       // equities edge out funds on a tie
            int extra = Math.max(0, have.size() - want.size()); // fewest extras = tightest match
            if (best == null || cov > bestCov
                    || (cov == bestCov && stk < bestStk)
                    || (cov == bestCov && stk == bestStk && extra < bestExtra)) {
                best = c; bestCov = cov; bestStk = stk; bestExtra = extra;
            }
        }
        return best == null ? Optional.empty()
                : Optional.of(new LsInstrument(best.id(), best.isin(), best.name()));
    }

    /** Significant (≥3-char, non-stop) lower-case tokens of a name. */
    static List<String> nameTokens(String s) {
        if (s == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")) {
            if (t.length() < 3 || NAME_STOP.contains(t)) continue;
            if (t.chars().allMatch(Character::isDigit)) continue; // share-nominal noise ("001")
            out.add(t);
        }
        return out;
    }

    /** Fraction of {@code want} tokens that prefix-match some {@code have} token. */
    static double coverage(List<String> want, List<String> have) {
        if (want.isEmpty()) return 0.0;
        int hit = 0;
        for (String w : want) {
            for (String h : have) {
                if (prefixMatch(w, h)) { hit++; break; }
            }
        }
        return (double) hit / want.size();
    }

    /**
     * True when one token is a ≥3-char prefix of the other — tolerates the heavy
     * abbreviation L&S uses in its display names ("def"↔"defense", "sec"↔"security",
     * "therap"↔"therapeutics"). The 3-char floor still keeps distinct words apart
     * ("group" ≠ "automotive"), so a wrong same-named twin stays rejected.
     */
    private static boolean prefixMatch(String a, String b) {
        if (a.equals(b)) return true;
        int min = Math.min(a.length(), b.length());
        return min >= 3 && a.regionMatches(0, b, 0, min);
    }

    /**
     * Chart shape: {@code {"series":{"intraday":{"data":[[ts,price],…]}},"info":{"plotlines":…}}}.
     * Builds an EUR {@link MarketSnapshot}: last price, intraday spark (downsampled),
     * previous close from {@code info.plotlines} → day move.
     */
    Optional<MarketSnapshot> parseChart(String body, String isin) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode data = root.path("series").path("intraday").path("data");
            if (!data.isArray() || data.isEmpty()) return Optional.empty();

            List<Double> prices = new ArrayList<>();
            double high = Double.NEGATIVE_INFINITY, low = Double.POSITIVE_INFINITY;
            long lastTsMs = 0;
            for (JsonNode pt : data) {
                if (!pt.isArray() || pt.size() < 2 || !pt.get(1).isNumber()) continue;
                double p = pt.get(1).asDouble();
                if (!Double.isFinite(p)) continue;
                prices.add(p);
                high = Math.max(high, p);
                low = Math.min(low, p);
                lastTsMs = pt.get(0).asLong(lastTsMs);
            }
            if (prices.isEmpty()) return Optional.empty();
            double last = prices.get(prices.size() - 1);

            double prevClose = parsePrevClose(root.path("info").path("plotlines"));
            double dayChange = (Double.isFinite(prevClose) && prevClose > 0)
                    ? (last - prevClose) / prevClose * 100.0 : Double.NaN;

            // series=history rides the SAME call: daily closes back to the listing's
            // start. The tail carries the multi-day context ("runs for days, corrects
            // today") the intraday spark can't, and the last ~252 trading days give an
            // honest 52-week high/low the L&S quote box doesn't expose.
            List<Double> dailyCloses = new ArrayList<>();
            for (JsonNode pt : root.path("series").path("history").path("data")) {
                if (!pt.isArray() || pt.size() < 2 || !pt.get(1).isNumber()) continue;
                double v = pt.get(1).asDouble();
                if (Double.isFinite(v)) dailyCloses.add(v);
            }
            double week52High = Double.NaN, week52Low = Double.NaN;
            if (!dailyCloses.isEmpty()) {
                int yearFrom = Math.max(0, dailyCloses.size() - 252);
                week52High = Double.NEGATIVE_INFINITY;
                week52Low = Double.POSITIVE_INFINITY;
                for (double v : dailyCloses.subList(yearFrom, dailyCloses.size())) {
                    week52High = Math.max(week52High, v);
                    week52Low = Math.min(week52Low, v);
                }
                dailyCloses = new ArrayList<>(dailyCloses.subList(
                        Math.max(0, dailyCloses.size() - 31), dailyCloses.size()));
            }

            long ageMin = (System.currentTimeMillis() - lastTsMs) / 60000;
            LOG.info("[L&S] chart {} → {} EUR, prevClose={}, {} pts, last point {} min old",
                    isin, String.format(Locale.ROOT, "%.2f", last),
                    Double.isFinite(prevClose) ? String.format(Locale.ROOT, "%.2f", prevClose) : "n/a",
                    prices.size(), ageMin);

            return Optional.of(new MarketSnapshot(
                    isin == null ? "" : isin, last,
                    Double.isFinite(prevClose) ? prevClose : Double.NaN, dayChange,
                    high, low, -1, week52High, week52Low,
                    "EUR", "L&S", lastTsMs / 1000, downsample(prices), dailyCloses));
        } catch (Exception e) {
            LOG.warn("L&S chart parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code info.plotlines} is the previous-day reference — an array of objects, an object, or a bare number. */
    private static double parsePrevClose(JsonNode plotlines) {
        if (plotlines == null || plotlines.isMissingNode()) return Double.NaN;
        if (plotlines.isNumber()) return plotlines.asDouble();
        JsonNode node = plotlines.isArray() ? (plotlines.isEmpty() ? null : plotlines.get(0)) : plotlines;
        if (node == null) return Double.NaN;
        for (String field : new String[] {"value", "y", "price", "level"}) {
            if (node.path(field).isNumber()) return node.path(field).asDouble();
        }
        return node.isNumber() ? node.asDouble() : Double.NaN;
    }

    /** Evenly thins the intraday series to at most {@link #SPARK_POINTS} points. */
    private static List<Double> downsample(List<Double> prices) {
        if (prices.size() <= SPARK_POINTS) return prices;
        List<Double> out = new ArrayList<>(SPARK_POINTS);
        double step = (prices.size() - 1) / (double) (SPARK_POINTS - 1);
        for (int i = 0; i < SPARK_POINTS; i++) out.add(prices.get((int) Math.round(i * step)));
        return out;
    }
}
