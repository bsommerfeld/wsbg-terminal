package de.bsommerfeld.wsbg.terminal.consorsbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystView;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystViewSource;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consorsbank financial-info client — the watchlist's analyst leg: the street's
 * rating distribution (five-tier Buy…Sell, current AND three months ago),
 * recent up-/downgrades, the consensus price target (EUR) with implied upside,
 * and the upcoming corporate events (earnings dates ~4 quarters ahead). ONE
 * keyless GET per ISIN, no headers required, no bot wall (probed 2026-07-12).
 *
 * <p>Endpoint: {@code web-financialinfo-service/api/marketdata/stocks?id=<ISIN>
 * &field=RecommendationV1&field=EventsV1}. The response is a root ARRAY with one
 * object per id, carrying {@code RecommendationV1} (flat ALL-CAPS fields; every
 * field {@code null} on a miss), {@code EventsV1.ITEMS[]} and {@code Info}
 * (with {@code Errors[]} on a miss — an ETF/fund answers HTTP 200 with
 * "Missing parameter ID_COMPANY", a clean structured miss like onvista's).
 *
 * <p>Stock-only misses are cached for the session (an ETF doesn't grow analyst
 * coverage mid-session); network failures are NOT cached so an outage heals
 * itself. Freshness is the CALLER's job — the service re-polls on its own
 * cadence via {@link #refresh}; plain {@link #viewByIsin} serves the session
 * cache once loaded.
 */
@Singleton
public class ConsorsbankClient implements AnalystViewSource,
        de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDiveSource {

    private static final Logger LOG = LoggerFactory.getLogger(ConsorsbankClient.class);

    private static final String BASE = "https://www.consorsbank.de/web-financialinfo-service"
            + "/api/marketdata/stocks?id=";
    /** EventsV2 (not V1): same items plus DIVIDENDS payment dates; offset-less datetimes. */
    private static final String URL_FMT = BASE + "%s&field=RecommendationV1&field=EventsV2";
    /**
     * The deep-dive field set — everything a compact research note wants, in ONE
     * call (fields combine freely; an unknown/unlisted block just answers null).
     */
    private static final String DEEP_URL_FMT = BASE + "%s&field=CompanyProfileV1&field=KeyFiguresV1"
            + "&field=BalanceSheetV1&field=BoardMembersV1&field=TradingCentralV2"
            + "&field=AlternativesV1&field=PerformanceV1&field=BasicV1";
    private static final String INDEX_URL_FMT = "https://www.consorsbank.de/web-financialinfo-service"
            + "/api/marketdata/indices?id=%s&field=BasicV1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** ISIN (UPPER) → view or a cached definite miss (non-stock / unknown ISIN). */
    private final Map<String, Optional<AnalystView>> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public ConsorsbankClient() {
        this(new DirectWebFetcher());
    }

    /** Production: rides the direct-first chain (Consorsbank has no bot wall; the joker stays reserve). */
    @Inject
    public ConsorsbankClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Optional<AnalystView> viewByIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        Optional<AnalystView> cached = cache.get(key);
        if (cached != null) return cached;
        return refresh(key);
    }

    /**
     * Fetches a FRESH view past the session cache (the caller's re-poll cadence:
     * ratings move daily). A definite miss still (re)caches as empty; a network
     * failure leaves the previous cache entry standing.
     */
    @Override
    public Optional<AnalystView> refresh(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        try {
            WebResponse resp = fetcher.fetch(
                    String.format(URL_FMT, URLEncoder.encode(key, StandardCharsets.UTF_8)),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("[consorsbank] HTTP {} for {}", resp.status(), key);
                return Optional.empty();
            }
            Optional<AnalystView> view = parse(resp.body());
            view.ifPresentOrElse(
                    v -> LOG.info("[consorsbank] {} → {} analysts ({}/{}/{}/{}/{}), target {} {} ({}%), "
                                    + "{} events",
                            key, v.total(), v.buy(), v.overweight(), v.hold(), v.underweight(), v.sell(),
                            fmt(v.targetPrice()), v.targetCurrency(), fmt(v.expectedUpsidePercent()),
                            v.events().size()),
                    () -> LOG.info("[consorsbank] {} → no analyst coverage (fund/unknown)", key));
            cache.put(key, view);
            return view;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[consorsbank] analyst view for {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses the root-array response into a view. A response whose
     * {@code RecommendationV1} is all-null AND whose events are empty is a
     * definite miss (empty). Ratings-less but events-carrying responses still
     * return a view — the earnings date alone is worth carrying.
     * Package-private, network-free (pinned by tests).
     */
    Optional<AnalystView> parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode item = root.isArray() && root.size() > 0 ? root.get(0) : root;
            JsonNode rec = item.path("RecommendationV1");

            int total = rec.path("TOTAL_RECENT").asInt(-1);
            // EventsV2 is a bare array; the older EventsV1 wraps items in {ITEMS}.
            JsonNode eventsNode = item.path("EventsV2").isArray()
                    ? item.path("EventsV2") : item.path("EventsV1").path("ITEMS");
            List<AnalystView.CorporateEvent> events = parseEvents(eventsNode);
            if (total <= 0 && events.isEmpty()) return Optional.empty();

            return Optional.of(new AnalystView(
                    rec.path("BUY_RECENT").asInt(0),
                    rec.path("OVERWEIGHT_RECENT").asInt(0),
                    rec.path("HOLD_RECENT").asInt(0),
                    rec.path("UNDERWEIGHT_RECENT").asInt(0),
                    rec.path("SELL_RECENT").asInt(0),
                    Math.max(total, 0),
                    rec.path("BUY_M3").asInt(-1),
                    rec.path("OVERWEIGHT_M3").asInt(-1),
                    rec.path("HOLD_M3").asInt(-1),
                    rec.path("UNDERWEIGHT_M3").asInt(-1),
                    rec.path("SELL_M3").asInt(-1),
                    rec.path("UP").asInt(-1),
                    rec.path("DOWN").asInt(-1),
                    rec.path("TARGET_PRICE").asDouble(Double.NaN),
                    text(rec.path("ISO_CURRENCY")),
                    rec.path("EXPECTED_PERFORMANCE_PCT").asDouble(Double.NaN),
                    epoch(text(rec.path("DATETIME_LAST_UPDATE"))),
                    events,
                    System.currentTimeMillis() / 1000));
        } catch (Exception e) {
            LOG.warn("[consorsbank] parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static List<AnalystView.CorporateEvent> parseEvents(JsonNode items) {
        List<AnalystView.CorporateEvent> out = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode ev : items) {
                long at = epoch(text(ev.path("DATETIME_EVENT")));
                if (at <= 0) continue;
                String title = text(ev.path("TITLE"));
                // EventsV2 dividend rows often carry the description in NAME_EVENT only.
                if (title == null) title = text(ev.path("NAME_EVENT"));
                out.add(new AnalystView.CorporateEvent(at, text(ev.path("EVENT_TYPE")), title));
            }
        }
        out.sort(Comparator.comparingLong(AnalystView.CorporateEvent::atEpochSeconds));
        return out;
    }

    /**
     * ISO-8601 → epoch seconds, 0 on blank/garbage. RecommendationV1 stamps carry a
     * colon-less offset ("…+0000"); EventsV2 stamps carry NO offset at all — those
     * are taken as UTC (the date is what matters, not the wall-clock hour).
     */
    private static long epoch(String iso) {
        if (iso == null) return 0;
        try {
            return OffsetDateTime.parse(iso.replace("+0000", "+00:00")).toEpochSecond();
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(iso).toEpochSecond(java.time.ZoneOffset.UTC);
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    // ---- the deep-dive leg (CompanyDeepDiveSource) ----

    /** Deep dives are on-demand (report generation); an hour of caching absorbs quick re-runs. */
    private static final long DEEP_CACHE_MS = 60 * 60 * 1000L;
    private final Map<String, de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive> deepCache =
            new ConcurrentHashMap<>();

    @Override
    public Optional<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive> deepDiveByIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        var cached = deepCache.get(key);
        if (cached != null && System.currentTimeMillis() / 1000 - cached.fetchedAtEpochSeconds()
                < DEEP_CACHE_MS / 1000) {
            return Optional.of(cached);
        }
        try {
            WebResponse resp = fetcher.fetch(
                    String.format(DEEP_URL_FMT, URLEncoder.encode(key, StandardCharsets.UTF_8)),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("[consorsbank] HTTP {} for deep dive {}", resp.status(), key);
                return Optional.empty();
            }
            Optional<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive> dive =
                    parseDeepDive(key, resp.body(), this::resolveIndexName);
            dive.ifPresentOrElse(d -> {
                deepCache.put(key, d);
                LOG.info("[consorsbank] deep dive {} → website={}, {} figure-years, {} balance-years, "
                                + "{} board, {} peers, technicals={}",
                        key, d.profile() != null ? d.profile().website() : null,
                        d.keyFigures().size(), d.balanceSheet().size(), d.board().size(),
                        d.peers().size(), d.technicalView() != null);
            }, () -> LOG.info("[consorsbank] deep dive {} → no company data (fund/unknown)", key));
            return dive;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[consorsbank] deep dive for {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** Resolves a REF_INDEX Consors id ("_20735") to the index display name, null on any miss. */
    private String resolveIndexName(String consorsId) {
        try {
            WebResponse resp = fetcher.fetch(
                    String.format(INDEX_URL_FMT, URLEncoder.encode(consorsId, StandardCharsets.UTF_8)),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) return null;
            JsonNode root = JSON.readTree(resp.body());
            JsonNode item = root.isArray() && root.size() > 0 ? root.get(0) : root;
            return text(item.path("BasicV1").path("NAME_SECURITY"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses the combined deep-dive response. A response with neither a profile
     * nor key figures is a definite miss (fund/unknown). Package-private,
     * network-free — the index resolver is injected so tests pass a stub.
     */
    Optional<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive> parseDeepDive(
            String isin, String body, java.util.function.UnaryOperator<String> indexResolver) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode item = root.isArray() && root.size() > 0 ? root.get(0) : root;

            var profile = parseProfile(item.path("CompanyProfileV1"));
            var keyFigures = parseKeyFigures(item.path("KeyFiguresV1"));
            if (profile == null && keyFigures.isEmpty()) return Optional.empty();

            String indexName = null;
            String refIndexId = text(item.path("BasicV1").path("REF_INDEX").path("CONSORS_ID"));
            if (refIndexId != null && indexResolver != null) indexName = indexResolver.apply(refIndexId);

            return Optional.of(new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive(
                    isin, profile, keyFigures,
                    parseBalanceSheet(item.path("BalanceSheetV1")),
                    parseBoard(item.path("BoardMembersV1")),
                    parseTechnicalView(item.path("TradingCentralV2")),
                    parsePeers(item.path("AlternativesV1")),
                    parsePerformance(item.path("PerformanceV1")),
                    indexName,
                    System.currentTimeMillis() / 1000));
        } catch (Exception e) {
            LOG.warn("[consorsbank] deep-dive parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.Profile parseProfile(JsonNode p) {
        String portrait = stripHtml(text(p.path("PORTRAIT")));
        String website = text(p.path("URL"));
        if (portrait == null && website == null && !p.path("MARKET_CAPITALIZATION").isNumber()) {
            return null;
        }
        return new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.Profile(
                website, portrait,
                text(p.path("HEADQUARTERS_CITY")), text(p.path("HEADQUARTERS_COUNTRY")),
                p.path("MARKET_CAPITALIZATION").asDouble(Double.NaN),
                p.path("NUMBER_SHARES").isNumber() ? p.path("NUMBER_SHARES").asLong() : -1);
    }

    /** FIRST_YEAR…EIGHTH_YEAR ascending; the estimate flag rides the missing-actuals heuristic. */
    private static final String[] YEAR_SLOTS = {"FIRST_YEAR", "SECOND_YEAR", "THIRD_YEAR",
            "FOURTH_YEAR", "FIFTH_YEAR", "SIXTH_YEAR", "SEVENTH_YEAR", "EIGHTH_YEAR"};

    private static List<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.KeyFigureYear>
            parseKeyFigures(JsonNode kf) {
        List<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.KeyFigureYear> out = new ArrayList<>();
        for (String slot : YEAR_SLOTS) {
            JsonNode y = kf.path(slot);
            String label = text(y.path("DATE"));
            if (label == null) continue;
            // Reported years carry the workforce; a consensus-estimate year does not —
            // that asymmetry is the (heuristic) actual-vs-estimate marker.
            boolean estimate = !y.path("EMPLOYEES").isNumber();
            out.add(new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.KeyFigureYear(
                    estimate ? label + "e" : label, estimate,
                    y.path("EARNINGS_PER_SHARE").asDouble(Double.NaN),
                    y.path("DIVIDEND_PER_SHARE").asDouble(Double.NaN),
                    y.path("DIVIDEND_YIELD_PCT").asDouble(Double.NaN),
                    y.path("PRICE_EARNINGS_RATIO").asDouble(Double.NaN),
                    y.path("PRICE_EARNINGS_2_GROWTH_RATIO").asDouble(Double.NaN),
                    y.path("BOOKVALUE_PER_SHARE").asDouble(Double.NaN),
                    y.path("EBIT_MARGE_PCT").asDouble(Double.NaN),
                    y.path("EBITDA_MARGE_PCT").asDouble(Double.NaN),
                    y.path("EQUITY_RATIO_PCT").asDouble(Double.NaN),
                    y.path("EMPLOYEES").isNumber() ? y.path("EMPLOYEES").asLong() : -1));
        }
        return out;
    }

    private static List<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.BalanceSheetYear>
            parseBalanceSheet(JsonNode bs) {
        List<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.BalanceSheetYear> out = new ArrayList<>();
        for (String slot : YEAR_SLOTS) {
            JsonNode y = bs.path(slot);
            String label = text(y.path("DATE"));
            if (label == null) continue;
            out.add(new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.BalanceSheetYear(
                    label,
                    y.path("TURNOVER").asDouble(Double.NaN),
                    y.path("NET_INCOME").asDouble(Double.NaN),
                    y.path("EQUITY_CAPITAL").asDouble(Double.NaN),
                    y.path("LIABILITIES").asDouble(Double.NaN),
                    y.path("TOTAL_ASSETS").asDouble(Double.NaN),
                    y.path("CASHFLOW_NET").asDouble(Double.NaN),
                    y.path("RESEARCH_EXPENSES").asDouble(Double.NaN)));
        }
        return out;
    }

    private static List<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.BoardMember>
            parseBoard(JsonNode b) {
        List<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.BoardMember> out = new ArrayList<>();
        collectBoard(out, b.path("EXECUTIVE_BOARD").path("ITEMS"), "Vorstand");
        collectBoard(out, b.path("SUPERVISORY_BOARD").path("ITEMS"), "Aufsichtsrat");
        return out;
    }

    private static void collectBoard(
            List<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.BoardMember> out,
            JsonNode items, String board) {
        if (!items.isArray()) return;
        for (JsonNode m : items) {
            String first = text(m.path("NAME_FIRST"));
            String last = text(m.path("NAME_LAST"));
            if (last == null) continue;
            String title = text(m.path("TITLE"));
            String name = (title != null ? title + " " : "") + (first != null ? first + " " : "") + last;
            out.add(new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.BoardMember(
                    name, text(m.path("BOARD_ROLE")), board));
        }
    }

    private static de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.TechnicalView
            parseTechnicalView(JsonNode tc) {
        if (!tc.path("PIVOT").isNumber() && !tc.path("SUPPORT_1").isNumber()) return null;
        JsonNode txt = tc.path("TEXT");
        String comment = java.util.stream.Stream.of(
                        stripHtml(text(txt.path("OPINION_TEXT"))),
                        stripHtml(text(txt.path("COMMENT_TEXT"))))
                .filter(java.util.Objects::nonNull)
                .reduce((a, b) -> a + " " + b).orElse(null);
        String asOf = text(tc.path("DATE_ANALYSIS"));
        return new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.TechnicalView(
                tc.path("PIVOT").asDouble(Double.NaN),
                tc.path("SUPPORT_1").asDouble(Double.NaN),
                tc.path("SUPPORT_2").asDouble(Double.NaN),
                tc.path("SUPPORT_3").asDouble(Double.NaN),
                tc.path("RESISTANCE_1").asDouble(Double.NaN),
                tc.path("RESISTANCE_2").asDouble(Double.NaN),
                tc.path("RESISTANCE_3").asDouble(Double.NaN),
                tc.path("OPINION_SHORTTERM").isNumber() ? tc.path("OPINION_SHORTTERM").asInt() : null,
                tc.path("OPINION_MEDIUMTERM").isNumber() ? tc.path("OPINION_MEDIUMTERM").asInt() : null,
                comment, asOf != null && asOf.length() >= 10 ? asOf.substring(0, 10) : asOf);
    }

    private static List<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.Peer>
            parsePeers(JsonNode alts) {
        List<de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.Peer> out = new ArrayList<>();
        if (!alts.isArray()) return out;
        for (JsonNode a : alts) {
            String name = text(a.path("NAME_SECURITY_SHORT"));
            if (name == null) name = text(a.path("NAME_SECURITY"));
            if (name == null) continue;
            out.add(new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.Peer(
                    text(a.path("ISIN")), name,
                    a.path("MARKET_CAPITALIZATION").asDouble(Double.NaN),
                    a.path("PRICE_EARNING_RATIO").asDouble(Double.NaN),
                    a.path("DIV_YIELD_PCT").asDouble(Double.NaN)));
        }
        return out;
    }

    private static de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.PerformanceStats
            parsePerformance(JsonNode pf) {
        if (!pf.path("PERFORMANCE_PCT_W52").isNumber() && !pf.path("CN_VOLA250").isNumber()) return null;
        return new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.PerformanceStats(
                pf.path("PERFORMANCE_PCT_W1").asDouble(Double.NaN),
                pf.path("PERFORMANCE_PCT_W4").asDouble(Double.NaN),
                pf.path("PERFORMANCE_PCT_M3").asDouble(Double.NaN),
                pf.path("PERFORMANCE_PCT_M6").asDouble(Double.NaN),
                pf.path("PERFORMANCE_PCT_W52").asDouble(Double.NaN),
                pf.path("CN_VOLA30").asDouble(Double.NaN),
                pf.path("CN_VOLA250").asDouble(Double.NaN),
                pf.path("PRICE_W52_HIGH").asDouble(Double.NaN),
                isoDate(text(pf.path("DATETIME_W52_HIGH"))),
                pf.path("PRICE_W52_LOW").asDouble(Double.NaN),
                isoDate(text(pf.path("DATETIME_W52_LOW"))));
    }

    private static String isoDate(String iso) {
        return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    /** Portrait/comment fields arrive as HTML paragraphs — reduce to plain prose. */
    private static String stripHtml(String html) {
        if (html == null) return null;
        String s = html.replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("(?i)</p>\\s*<p>", " ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .strip();
        return s.isBlank() ? null : s;
    }

    private static String text(JsonNode n) {
        String s = n.asText("");
        return s.isBlank() || "null".equals(s) ? null : s;
    }

    private static String fmt(double d) {
        return Double.isFinite(d) ? String.format(Locale.ROOT, "%.2f", d) : "n/a";
    }
}
