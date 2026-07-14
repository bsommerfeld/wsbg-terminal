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
public class OnvistaClient implements InstrumentFactsSource {

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

    @Override
    public Optional<InstrumentFacts> factsByIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        Optional<InstrumentFacts> cached = cache.get(key);
        if (cached != null) return cached;
        try {
            WebResponse queryResp = get(QUERY_URL + URLEncoder.encode(key, StandardCharsets.UTF_8));
            if (queryResp == null) return Optional.empty();
            String entityValue = parseStockEntity(queryResp.body(), key);
            if (entityValue == null) {
                // Definite non-stock / unknown — cache the miss for the session.
                cache.put(key, Optional.empty());
                LOG.info("[onvista] {} → no STOCK entity (fund/crypto/unknown)", key);
                return Optional.empty();
            }
            WebResponse snapResp = get(String.format(SNAPSHOT_URL_FMT, entityValue));
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
            String entityValue = parseEntity(queryResp.body(), key, "FUND");
            if (entityValue == null) {
                fundCache.put(key, Optional.empty());
                LOG.info("[onvista] {} → no FUND entity (stock/crypto/unknown)", key);
                return Optional.empty();
            }
            WebResponse snapResp = get(String.format(FUND_SNAPSHOT_URL_FMT, entityValue));
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
     * Picks the query hit whose ISIN matches AND whose entityType is STOCK,
     * returning its entityValue — or null (a definite miss). Package-private,
     * network-free.
     */
    String parseStockEntity(String body, String isin) {
        return parseEntity(body, isin, "STOCK");
    }

    /** Same pick with a caller-chosen entity type ("STOCK" / "FUND"). */
    String parseEntity(String body, String isin, String entityType) {
        try {
            JsonNode list = JSON.readTree(body).path("list");
            if (!list.isArray()) return null;
            for (JsonNode n : list) {
                if (!isin.equalsIgnoreCase(n.path("isin").asText(""))) continue;
                if (!entityType.equalsIgnoreCase(n.path("entityType").asText(""))) continue;
                String v = n.path("entityValue").asText("");
                if (!v.isBlank()) return v;
            }
            return null;
        } catch (Exception e) {
            LOG.warn("[onvista] query parse failure: {}", e.getMessage());
            return null;
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
