package de.bsommerfeld.wsbg.terminal.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.InstrumentFacts;
import de.bsommerfeld.wsbg.terminal.core.price.InstrumentFactsSource;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * onvista company-profile client — the watchlist's "what IS this" source:
 * sector/branch, market capitalization (EUR, even for US names), P/E and
 * dividend yield with their fiscal-year labels, workforce, and the ~30-day
 * average daily volume that makes a venue's day volume readable. Keyless, no
 * bot wall (probed 2026-07-12).
 *
 * <p>Two calls per ISIN: {@code api.onvista.de/api/v1/instruments/query?searchValue=<ISIN>}
 * resolves the entity ({@code entityType} + {@code entityValue}), then
 * {@code /api/v1/stocks/<entityValue>/snapshot} carries the whole profile.
 * STOCK entities only — a FUND/ETF/crypto ISIN resolves cleanly to empty and
 * that miss is cached (an ETF doesn't become a stock mid-session); network
 * failures are NOT cached so an outage heals itself.
 *
 * <p>Fundamentals come as one row per fiscal year, actuals first, then
 * estimates (label with a trailing {@code e}, e.g. {@code "2026e"} or
 * {@code "26/27e"}). Valuation figures prefer the latest ACTUAL row and fall
 * back to the nearest estimate — always carrying the row's label so an
 * estimate is never presented as an actual.
 */
@Singleton
public class OnvistaClient implements InstrumentFactsSource,
        de.bsommerfeld.wsbg.terminal.source.NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(OnvistaClient.class);

    private static final String QUERY_URL =
            "https://api.onvista.de/api/v1/instruments/query?searchValue=";
    private static final String SNAPSHOT_URL_FMT =
            "https://api.onvista.de/api/v1/stocks/%s/snapshot";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** ISIN (UPPER) → facts or a cached definite miss (non-stock / unknown ISIN). */
    private final Map<String, Optional<InstrumentFacts>> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public OnvistaClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public OnvistaClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    // ---- multi-year press ARCHIVE (NewsSource window leg, 2026-07-16) ----

    private static final String ARTICLES_URL_FMT =
            "https://api.onvista.de/api/v1/articles/finder/configuration_query"
                    + "?application=WEBSITE&calculateTotal=true&device=DESKTOP"
                    + "&entityType=STOCK&entityValue=%s&page=%d&perPage=100";
    /** The finder paginates to ~1,000 articles per instrument (probed 2026-07-16). */
    private static final int MAX_ARTICLE_PAGES = 10;

    @Override
    public String sourceName() {
        return "onvista";
    }

    /** The archive is entity-addressed - a bare symbol answers nothing here. */
    @Override
    public java.util.List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> newsFor(
            String symbol, int limit) {
        return java.util.List.of();
    }

    /**
     * The per-instrument news ARCHIVE, windowed: onvista's articles finder
     * serves the dated, attributed press history (dpa-AFX, EQS/DGAP corporate
     * news) as plain JSON - for small caps and pennystocks the COMPLETE
     * multi-year history sits inside the ~1,000-article pagination cap
     * (probed 2026-07-16: artec technologies = 57 articles back 4.7 years in
     * one request; SAP total 3,499, cap reaches ~14 months). ISIN-addressed
     * first (never a same-named twin), company name as the fallback.
     */
    @Override
    public java.util.List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> newsForNameWindow(
            String companyName, String isin, String fromIsoDate, String toIsoDateExclusive,
            int limit) {
        if (limit <= 0) return java.util.List.of();
        try {
            String entity = archiveEntity(isin, companyName);
            if (entity == null) return java.util.List.of();
            java.time.Instant from = java.time.LocalDate.parse(fromIsoDate)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            java.time.Instant to = java.time.LocalDate.parse(toIsoDateExclusive)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            java.util.List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> out =
                    new java.util.ArrayList<>();
            for (int page = 0; page < MAX_ARTICLE_PAGES && out.size() < limit; page++) {
                WebResponse resp = get(String.format(ARTICLES_URL_FMT, entity, page));
                if (resp == null || resp.status() != 200) break;
                JsonNode items = articleArray(JSON.readTree(resp.body()));
                if (items == null || items.isEmpty()) break;
                boolean pageOlderThanWindow = true;
                for (JsonNode item : items) {
                    java.time.Instant at = parseArticleInstant(
                            item.path("datetimePublication").asText(null));
                    if (at == null) continue;
                    if (!at.isBefore(from)) pageOlderThanWindow = false;
                    if (at.isBefore(from) || !at.isBefore(to)) continue;
                    String headline = item.path("headline").asText(null);
                    if (headline == null || headline.isBlank()) continue;
                    JsonNode pub = item.path("publisher");
                    String publisher = pub.isTextual() ? pub.asText()
                            : pub.path("name").asText("onvista");
                    // Live shape (probed 2026-07-16): urls = {"WEBSITE": "<link>"}.
                    String url = item.path("urls").path("WEBSITE").asText(null);
                    if (url == null) url = firstText(item, "urlArticle", "url", "link");
                    out.add(new de.bsommerfeld.wsbg.terminal.source.RawNewsItem(
                            "onvista-" + entity + "-" + at.getEpochSecond()
                                    + "-" + Math.abs(headline.hashCode()),
                            headline.strip(), publisher, url, at, java.util.List.of()));
                    if (out.size() >= limit) break;
                }
                // Newest-first pagination: a page entirely older than the
                // window means everything after it is older too.
                if (pageOlderThanWindow) break;
            }
            if (!out.isEmpty()) {
                LOG.info("[onvista] archive {}..{} for {} → {} headline(s)",
                        fromIsoDate, toIsoDateExclusive,
                        isin != null ? isin : companyName, out.size());
            }
            return out;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[onvista] archive window failed: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    /** Entity for the archive: ISIN lookup first (exact), name as fallback. */
    private String archiveEntity(String isin, String companyName) throws Exception {
        for (String key : new String[]{isin, companyName}) {
            if (key == null || key.isBlank()) continue;
            WebResponse resp = get(QUERY_URL
                    + URLEncoder.encode(key.trim(), StandardCharsets.UTF_8));
            if (resp == null) continue;
            EntityLookup lookup = lookupEntity(resp.body(), key, "STOCK");
            if (lookup.isHit()) return lookup.entityValue();
        }
        return null;
    }

    /** The finder's article array - field name parsed defensively. */
    private static JsonNode articleArray(JsonNode root) {
        for (String field : new String[]{"list", "articles", "items", "data"}) {
            JsonNode n = root.path(field);
            if (n.isArray()) return n;
        }
        java.util.Iterator<JsonNode> it = root.elements();
        while (it.hasNext()) {
            JsonNode n = it.next();
            if (n.isArray() && n.size() > 0 && n.get(0).has("headline")) return n;
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static java.time.Instant parseArticleInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(raw).toInstant();
        } catch (Exception e) {
            try {
                return java.time.Instant.parse(raw);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    @Override
    public Optional<InstrumentFacts> factsByIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        Optional<InstrumentFacts> cached = cache.get(key);
        if (cached != null) return cached;
        try {
            WebResponse queryResp = get(QUERY_URL + URLEncoder.encode(key, StandardCharsets.UTF_8));
            if (queryResp == null) return Optional.empty();
            EntityLookup lookup = lookupEntity(queryResp.body(), key, "STOCK");
            if (!lookup.isHit()) {
                if (lookup.structuredMiss()) {
                    // Definite non-stock / unknown — cache the miss for the session.
                    cache.put(key, Optional.empty());
                    LOG.info("[onvista] {} → no STOCK entity (fund/crypto/unknown)", key);
                } else {
                    // Garbled 200 body — NOT a miss; a later call may heal.
                    LOG.warn("[onvista] {} → unparseable query reply, not cached", key);
                }
                return Optional.empty();
            }
            WebResponse snapResp = get(String.format(SNAPSHOT_URL_FMT, lookup.entityValue()));
            if (snapResp == null) return Optional.empty();
            Optional<InstrumentFacts> facts = parseSnapshot(snapResp.body());
            facts.ifPresentOrElse(
                    f -> LOG.info("[onvista] {} → '{}' {}/{} mcap={} pe={}({}) avgVol30={}",
                            key, f.companyName(), f.sector(), f.branch(),
                            fmtBillions(f.marketCapEur()), fmt(f.peRatio()), f.peLabel(),
                            fmt(f.avgVolume30d())),
                    () -> LOG.info("[onvista] {} → snapshot unparseable", key));
            facts.ifPresent(f -> cache.put(key, Optional.of(f)));
            return facts;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[onvista] facts for {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    // ---- the fund/ETF leg (fundFactsByIsin) ----

    private static final String FUND_SNAPSHOT_URL_FMT =
            "https://api.onvista.de/api/v1/funds/%s/snapshot";

    /** ISIN (UPPER) → fund facts or a cached definite miss (non-fund / unknown ISIN). */
    private final Map<String, Optional<de.bsommerfeld.wsbg.terminal.core.price.FundFacts>> fundCache =
            new ConcurrentHashMap<>();

    /** How many top holdings ride along — enough to characterize the fund, not the full list. */
    private static final int MAX_HOLDINGS = 10;

    @Override
    public Optional<de.bsommerfeld.wsbg.terminal.core.price.FundFacts> fundFactsByIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        var cached = fundCache.get(key);
        if (cached != null) return cached;
        try {
            WebResponse queryResp = get(QUERY_URL + URLEncoder.encode(key, StandardCharsets.UTF_8));
            if (queryResp == null) return Optional.empty();
            EntityLookup lookup = lookupEntity(queryResp.body(), key, "FUND");
            if (!lookup.isHit()) {
                if (lookup.structuredMiss()) {
                    fundCache.put(key, Optional.empty());
                    LOG.info("[onvista] {} → no FUND entity (stock/crypto/unknown)", key);
                } else {
                    LOG.warn("[onvista] {} → unparseable query reply, not cached", key);
                }
                return Optional.empty();
            }
            WebResponse snapResp = get(String.format(FUND_SNAPSHOT_URL_FMT, lookup.entityValue()));
            if (snapResp == null) return Optional.empty();
            var facts = parseFundSnapshot(snapResp.body());
            facts.ifPresentOrElse(
                    f -> LOG.info("[onvista] {} → fund '{}' TER={} vol={} benchmark={} holdings={}",
                            key, f.name(), fmt(f.terPercent()), fmtBillions(f.volumeEur()),
                            f.benchmark(), f.topHoldings().size()),
                    () -> LOG.info("[onvista] {} → fund snapshot unparseable", key));
            facts.ifPresent(f -> fundCache.put(key, Optional.of(f)));
            return facts;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[onvista] fund facts for {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** Extracts the ETF profile from a funds snapshot. Package-private, network-free. */
    Optional<de.bsommerfeld.wsbg.terminal.core.price.FundFacts> parseFundSnapshot(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode base = root.path("fundsBaseData");
            String name = text(root.path("instrument").path("name"));
            double ter = base.path("ongoingCharges").asDouble(Double.NaN);
            double volumeEur = base.path("volumeFundEuro").asDouble(Double.NaN);
            if (name == null && !Double.isFinite(volumeEur)) return Optional.empty();

            String benchmark = null;
            JsonNode benchList = root.path("fundsBenchmarkList").path("list");
            if (benchList.isArray() && benchList.size() > 0) {
                benchmark = text(benchList.get(0).path("instrument").path("name"));
            }
            int morningstar = -1;
            String rating = text(root.path("fundsEvaluation").path("morningstarRating"));
            if (rating != null && rating.matches("\\d")) morningstar = Integer.parseInt(rating);

            String description = null;
            JsonNode bg = root.path("background");
            if (bg.isArray() && bg.size() > 0) description = text(bg.get(0).path("value"));

            java.util.List<de.bsommerfeld.wsbg.terminal.core.price.FundFacts.Holding> holdings =
                    new java.util.ArrayList<>();
            JsonNode hl = root.path("fundsHoldingList").path("list");
            if (hl.isArray()) {
                for (JsonNode h : hl) {
                    if (holdings.size() >= MAX_HOLDINGS) break;
                    String hn = text(h.path("instrument").path("name"));
                    if (hn == null) continue;
                    holdings.add(new de.bsommerfeld.wsbg.terminal.core.price.FundFacts.Holding(
                            hn, h.path("investmentPct").asDouble(Double.NaN)));
                }
            }
            return Optional.of(new de.bsommerfeld.wsbg.terminal.core.price.FundFacts(
                    name, ter, volumeEur, benchmark, morningstar, description,
                    holdings, System.currentTimeMillis() / 1000));
        } catch (Exception e) {
            LOG.warn("[onvista] fund snapshot parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Tri-state entity lookup result: a hit carries the {@code entityValue};
     * a STRUCTURED miss (the documented shape — the reply parses and its
     * {@code list} simply lacks a matching entity of the wanted type) is
     * cacheable for the session; a garbled/malformed body is NOT a miss and
     * must never be cached (an outage or a changed response shape heals).
     */
    record EntityLookup(String entityValue, boolean structuredMiss) {
        static EntityLookup hit(String v) { return new EntityLookup(v, false); }
        static EntityLookup miss() { return new EntityLookup(null, true); }
        static EntityLookup garbled() { return new EntityLookup(null, false); }
        boolean isHit() { return entityValue != null; }
    }

    /**
     * Picks the query hit whose ISIN matches AND whose entityType is STOCK,
     * returning its entityValue — or null (miss/garbled). Package-private,
     * network-free convenience over {@link #lookupEntity}.
     */
    String parseStockEntity(String body, String isin) {
        return lookupEntity(body, isin, "STOCK").entityValue();
    }

    /** Same pick with a caller-chosen entity type ("STOCK" / "FUND"). */
    EntityLookup lookupEntity(String body, String isin, String entityType) {
        try {
            JsonNode list = JSON.readTree(body).path("list");
            // A parseable body WITHOUT the documented list array is a changed
            // or error shape, not a documented miss — don't cache it.
            if (!list.isArray()) return EntityLookup.garbled();
            for (JsonNode n : list) {
                if (!isin.equalsIgnoreCase(n.path("isin").asText(""))) continue;
                if (!entityType.equalsIgnoreCase(n.path("entityType").asText(""))) continue;
                String v = n.path("entityValue").asText("");
                if (!v.isBlank()) return EntityLookup.hit(v);
            }
            return EntityLookup.miss();
        } catch (Exception e) {
            LOG.warn("[onvista] query parse failure: {}", e.getMessage());
            return EntityLookup.garbled();
        }
    }

    /** Extracts the profile facts from a stocks snapshot. Package-private, network-free. */
    Optional<InstrumentFacts> parseSnapshot(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode company = root.path("company");
            String name = text(company.path("name"));
            String country = text(company.path("nameCountry"));
            JsonNode branchNode = company.path("branch");
            String branch = text(branchNode.path("name"));
            String sector = text(branchNode.path("sector").path("name"));
            if (name == null && sector == null) return Optional.empty();

            JsonNode figure = root.path("stocksFigure");
            double marketCap = "EUR".equalsIgnoreCase(figure.path("isoCurrency").asText("EUR"))
                    ? figure.path("marketCapCompany").asDouble(Double.NaN) : Double.NaN;

            double avgVol30 = root.path("cnPerformance").path("averageVolumeD30").asDouble(Double.NaN);

            JsonNode rows = root.path("stocksCnFundamentalList").path("list");
            FigureWithLabel pe = pickValuation(rows, "cnPer");
            FigureWithLabel div = pickValuation(rows, "cnDivYield");
            long employees = -1;
            String employeesLabel = null;
            if (rows.isArray()) {
                for (JsonNode r : rows) { // ascending years — keep the LAST actual figure
                    if (r.path("employees").isNumber()) {
                        employees = r.path("employees").asLong();
                        employeesLabel = text(r.path("label"));
                    }
                }
            }

            return Optional.of(new InstrumentFacts(name, country, sector, branch,
                    marketCap > 0 ? marketCap : Double.NaN,
                    employees, employeesLabel,
                    pe.value, pe.label, div.value, div.label, avgVol30,
                    System.currentTimeMillis() / 1000));
        } catch (Exception e) {
            LOG.warn("[onvista] snapshot parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private record FigureWithLabel(double value, String label) {}

    /**
     * Selects a valuation figure from the fiscal-year rows: the latest ACTUAL
     * row (label without a trailing {@code e}) carrying the field wins; a name
     * without actuals falls back to the NEAREST estimate row. The row's label
     * always rides along.
     */
    private static FigureWithLabel pickValuation(JsonNode rows, String field) {
        double actual = Double.NaN, estimate = Double.NaN;
        String actualLabel = null, estimateLabel = null;
        if (rows.isArray()) {
            for (JsonNode r : rows) {
                if (!r.path(field).isNumber()) continue;
                String label = r.path("label").asText("");
                boolean isEstimate = label.endsWith("e");
                if (isEstimate) {
                    if (Double.isNaN(estimate)) { // rows ascend — keep the NEAREST estimate
                        estimate = r.path(field).asDouble();
                        estimateLabel = label;
                    }
                } else { // keep the LATEST actual
                    actual = r.path(field).asDouble();
                    actualLabel = label;
                }
            }
        }
        return Double.isNaN(actual)
                ? new FigureWithLabel(estimate, estimateLabel)
                : new FigureWithLabel(actual, actualLabel);
    }

    private WebResponse get(String url) throws Exception {
        WebResponse resp = fetcher.fetch(url,
                Map.of("User-Agent", userAgent, "Accept", "application/json"),
                requestTimeout);
        if (resp.status() != 200) {
            LOG.warn("[onvista] HTTP {} for {}", resp.status(), url);
            return null;
        }
        return resp;
    }

    private static String text(JsonNode n) {
        String s = n.asText("");
        return s.isBlank() ? null : s;
    }

    private static String fmt(double d) {
        return Double.isFinite(d) ? String.format(Locale.ROOT, "%.2f", d) : "n/a";
    }

    private static String fmtBillions(double d) {
        return Double.isFinite(d) ? String.format(Locale.ROOT, "%.1fB", d / 1e9) : "n/a";
    }
}
