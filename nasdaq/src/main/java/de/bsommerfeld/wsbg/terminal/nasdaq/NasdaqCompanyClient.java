package de.bsommerfeld.wsbg.terminal.nasdaq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.AnalystRatings;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.EarningsSurprise;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.InsiderActivity;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.InsiderTrade;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.InstitutionalOwnership;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.ShortInterestPoint;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStatsSource;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NASDAQ's own company-data API ({@code api.nasdaq.com}) — the US listing view
 * behind a ticker page's tabs: short interest (FINRA settlement history),
 * insider trades (SEC Form 4 with 3/12-month aggregate), institutional
 * ownership (13F), analyst consensus + price targets, and earnings surprises.
 * Keyless, live-probed 2026-07-14; the Akamai edge answers plain HTTP as long
 * as the request carries browser-shaped {@code Accept}/{@code Origin}/{@code
 * Referer} headers — without them it simply never answers, so the timeout
 * matters more than any status (the {@code NasdaqCalendarClient} recipe).
 *
 * <p>US-shaped bare tickers only (letters, optionally one share-class dot
 * letter — {@code BRK.A}): a venue-suffixed / index / future / crypto symbol
 * returns empty WITHOUT any network call. An unknown symbol answers HTTP 200
 * with {@code data:null, status.rCode:400} ("Symbol not exists.") — a definite
 * miss, cached like a success (1 h); network failures are NOT cached so an
 * outage heals itself. A covered symbol with a missing tab is present-with-empty.
 *
 * <p>Parser quirks pinned by probe: numbers arrive as strings with {@code $},
 * {@code %} and thousand-commas mixed with raw JSON numbers in the SAME
 * document; dates are {@code M/d/yyyy} (single-digit days occur: "6/02/2026");
 * absent values read "N/A"/"NA", a new 13F position's change-% reads "New".
 */
@Singleton
public class NasdaqCompanyClient implements UsListingStatsSource {

    private static final Logger LOG = LoggerFactory.getLogger(NasdaqCompanyClient.class);

    // Casing is verbatim from the site's own calls: quote endpoints take
    // lowercase "assetclass", short-interest takes camelCase "assetClass".
    private static final String INFO_URL = "https://api.nasdaq.com/api/quote/%s/info?assetclass=stocks";
    private static final String SUMMARY_URL = "https://api.nasdaq.com/api/quote/%s/summary?assetclass=stocks";
    private static final String SHORT_URL = "https://api.nasdaq.com/api/quote/%s/short-interest?assetClass=stocks";
    private static final String INSIDER_URL = "https://api.nasdaq.com/api/company/%s/insider-trades"
            + "?limit=20&type=ALL&sortColumn=lastDate&sortOrder=DESC";
    private static final String INST_URL = "https://api.nasdaq.com/api/company/%s/institutional-holdings"
            + "?limit=20&type=TOTAL&sortColumn=marketValue&sortOrder=DESC";
    private static final String RATINGS_URL = "https://api.nasdaq.com/api/analyst/%s/ratings";
    private static final String TARGET_URL = "https://api.nasdaq.com/api/analyst/%s/targetprice";
    private static final String EARNINGS_URL = "https://api.nasdaq.com/api/company/%s/earnings-surprise";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/uuuu");

    /** Bare US ticker: 1-5 letters, optionally one share-class letter after a dot. */
    private static final Pattern US_TICKER = Pattern.compile("[A-Z]{1,5}(\\.[A-Z])?");

    private static final Pattern ANALYST_COUNT = Pattern.compile("Based on (\\d+) analyst");

    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;
    private static final int MAX_TOP_HOLDERS = 10;

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Symbol (UPPER) → stats or a definite miss, both good for an hour. */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Optional<UsListingStats> value, long atMs) {
    }

    /** Test/default: plain direct transport. */
    public NasdaqCompanyClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public NasdaqCompanyClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Optional<UsListingStats> statsFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        String key = symbol.trim().toUpperCase(Locale.ROOT);
        if (!usTickerShaped(key)) return Optional.empty();

        Cached cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.atMs() < CACHE_TTL_MS) {
            return cached.value();
        }
        try {
            // The info leg anchors: a network failure here is a miss we do NOT
            // cache, a body-level rCode 400/404 is a definite miss we DO cache.
            String infoBody = get(String.format(INFO_URL, key));
            if (infoBody == null) return Optional.empty();
            if (definiteMiss(infoBody)) {
                cache.put(key, new Cached(Optional.empty(), System.currentTimeMillis()));
                LOG.info("[nasdaq] {} → symbol not listed (definite miss)", key);
                return Optional.empty();
            }
            Info info = parseInfo(infoBody);
            if (info == null) return Optional.empty();

            Summary summary = parseSummary(get(String.format(SUMMARY_URL, key)));
            List<ShortInterestPoint> shorts = parseShortInterest(get(String.format(SHORT_URL, key)));
            InsiderLeg insiders = parseInsiders(get(String.format(INSIDER_URL, key)));
            InstitutionalOwnership inst = parseInstitutional(get(String.format(INST_URL, key)));
            AnalystRatings analysts = parseAnalyst(get(String.format(RATINGS_URL, key)),
                    get(String.format(TARGET_URL, key)));
            List<EarningsSurprise> earnings = parseEarnings(get(String.format(EARNINGS_URL, key)));

            UsListingStats stats = new UsListingStats(key, info.companyName(), info.exchange(),
                    summary == null ? null : summary.sector(),
                    summary == null ? null : summary.industry(),
                    summary == null ? Double.NaN : summary.marketCapUsd(),
                    summary == null ? -1 : summary.avgDailyVolume(),
                    summary == null ? Double.NaN : summary.dividendYieldPercent(),
                    shorts,
                    insiders == null ? null : insiders.activity(),
                    insiders == null ? List.of() : insiders.trades(),
                    inst, analysts, earnings,
                    System.currentTimeMillis() / 1000);
            cache.put(key, new Cached(Optional.of(stats), System.currentTimeMillis()));
            LOG.info("[nasdaq] {} → '{}' {} shortPts={} insiderTrades={} instHolders={} analysts={} surprises={}",
                    key, stats.companyName(), stats.exchange(), shorts.size(),
                    stats.insiderTrades().size(),
                    inst == null ? "n/a" : inst.totalHolders(),
                    analysts == null ? "n/a" : analysts.analystCount(),
                    earnings.size());
            return Optional.of(stats);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[nasdaq] stats for {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** One leg's GET: body on HTTP 200, null on anything else (leg stays empty). */
    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/json, text/plain, */*",
                            "Origin", "https://www.nasdaq.com",
                            "Referer", "https://www.nasdaq.com/"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[nasdaq] HTTP {} for {}", resp == null ? "null" : resp.status(), url);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[nasdaq] fetch failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    // ---- parsers (package-private, network-free, shapes pinned 2026-07-14) ----

    record Info(String companyName, String exchange) {
    }

    record Summary(String sector, String industry, double marketCapUsd, long avgDailyVolume,
            double dividendYieldPercent) {
    }

    record InsiderLeg(InsiderActivity activity, List<InsiderTrade> trades) {
    }

    /** True for a symbol NASDAQ's US API can answer: letters + optional ".X" share class. */
    static boolean usTickerShaped(String upper) {
        return US_TICKER.matcher(upper).matches();
    }

    /** Body-level "Symbol not exists." — HTTP is 200, the status object says 400/404. */
    static boolean definiteMiss(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            JsonNode root = JSON.readTree(body);
            return root.path("data").isNull() && root.path("status").path("rCode").asInt(200) >= 400;
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code /quote/<sym>/info}: listing identity. */
    static Info parseInfo(String body) {
        JsonNode data = data(body);
        if (data == null) return null;
        return new Info(text(data.path("companyName")), text(data.path("exchange")));
    }

    /** {@code /quote/<sym>/summary}: label/value grid keyed by field name. */
    static Summary parseSummary(String body) {
        JsonNode data = data(body);
        if (data == null) return null;
        JsonNode grid = data.path("summaryData");
        double avgVol = parseUsNumber(text(grid.path("AverageVolume").path("value")));
        return new Summary(
                text(grid.path("Sector").path("value")),
                text(grid.path("Industry").path("value")),
                parseUsNumber(text(grid.path("MarketCap").path("value"))),
                Double.isNaN(avgVol) ? -1 : (long) avgVol,
                parseUsNumber(text(grid.path("Yield").path("value"))));
    }

    /** {@code /quote/<sym>/short-interest}: settlement rows, newest first as shipped. */
    static List<ShortInterestPoint> parseShortInterest(String body) {
        JsonNode data = data(body);
        if (data == null) return List.of();
        List<ShortInterestPoint> out = new ArrayList<>();
        for (JsonNode r : data.path("shortInterestTable").path("rows")) {
            out.add(new ShortInterestPoint(
                    parseUsDate(text(r.path("settlementDate"))),
                    asLong(parseUsNumber(text(r.path("interest")))),
                    asLong(parseUsNumber(text(r.path("avgDailyShareVolume")))),
                    r.path("daysToCover").asDouble(Double.NaN)));
        }
        return out;
    }

    /** {@code /company/<sym>/insider-trades}: 3/12-month counters + the transaction table. */
    static InsiderLeg parseInsiders(String body) {
        JsonNode data = data(body);
        if (data == null) return null;

        long buys3m = -1, sells3m = -1, buys12m = -1, sells12m = -1;
        for (JsonNode r : data.path("numberOfTrades").path("rows")) {
            String label = r.path("insiderTrade").asText("");
            if (label.startsWith("Number of Open Market Buys")) {
                buys3m = asLong(parseUsNumber(text(r.path("months3"))));
                buys12m = asLong(parseUsNumber(text(r.path("months12"))));
            } else if (label.startsWith("Number of Sells")) {
                sells3m = asLong(parseUsNumber(text(r.path("months3"))));
                sells12m = asLong(parseUsNumber(text(r.path("months12"))));
            }
        }
        long net3m = Long.MIN_VALUE, net12m = Long.MIN_VALUE;
        for (JsonNode r : data.path("numberOfSharesTraded").path("rows")) {
            if (r.path("insiderTrade").asText("").startsWith("Net Activity")) {
                double v3 = parseUsNumber(text(r.path("months3")));
                double v12 = parseUsNumber(text(r.path("months12")));
                if (!Double.isNaN(v3)) net3m = (long) v3;
                if (!Double.isNaN(v12)) net12m = (long) v12;
            }
        }

        List<InsiderTrade> trades = new ArrayList<>();
        for (JsonNode r : data.path("transactionTable").path("table").path("rows")) {
            String transaction = text(r.path("transactionType"));
            trades.add(new InsiderTrade(
                    parseUsDate(text(r.path("lastDate"))),
                    text(r.path("insider")),
                    text(r.path("relation")),
                    transaction,
                    classifyTransaction(transaction),
                    asLong(parseUsNumber(text(r.path("sharesTraded")))),
                    parseUsNumber(text(r.path("lastPrice"))),
                    asLong(parseUsNumber(text(r.path("sharesHeld"))))));
        }
        return new InsiderLeg(new InsiderActivity(buys3m, sells3m, buys12m, sells12m, net3m, net12m),
                trades);
    }

    /** NASDAQ's label → buy/sell/other ("Acquisition (Non Open Market)" is NOT an open-market buy). */
    static String classifyTransaction(String transaction) {
        if (transaction == null) return "other";
        String t = transaction.toLowerCase(Locale.ROOT);
        if (t.startsWith("buy")) return "buy";
        if (t.contains("sell")) return "sell";
        return "other";
    }

    /** {@code /company/<sym>/institutional-holdings}: ownership summary + top positions. */
    static InstitutionalOwnership parseInstitutional(String body) {
        JsonNode data = data(body);
        if (data == null) return null;
        JsonNode summary = data.path("ownershipSummary");
        double pct = parseUsNumber(text(summary.path("SharesOutstandingPCT").path("value")));
        double valueMillions = parseUsNumber(text(summary.path("TotalHoldingsValue").path("value")));

        long holders = -1, sharesHeld = -1;
        for (JsonNode r : data.path("activePositions").path("rows")) {
            if (r.path("positions").asText("").startsWith("Total Institutional Shares")) {
                holders = asLong(parseUsNumber(text(r.path("holders"))));
                sharesHeld = asLong(parseUsNumber(text(r.path("shares"))));
            }
        }

        List<InstitutionalOwnership.Holder> top = new ArrayList<>();
        for (JsonNode r : data.path("holdingsTransactions").path("table").path("rows")) {
            if (top.size() >= MAX_TOP_HOLDERS) break;
            String name = text(r.path("ownerName"));
            if (name == null) continue;
            top.add(new InstitutionalOwnership.Holder(name,
                    asLong(parseUsNumber(text(r.path("sharesHeld")))),
                    parseUsNumber(text(r.path("marketValue"))),
                    parseUsDate(text(r.path("date")))));
        }
        return new InstitutionalOwnership(pct, holders, sharesHeld, valueMillions, top);
    }

    /**
     * {@code /analyst/<sym>/ratings} + {@code /analyst/<sym>/targetprice} folded
     * into one view: the label + analyst count come from ratings, the buy/hold/sell
     * split and targets from the consensus overview (often a SMALLER panel).
     */
    static AnalystRatings parseAnalyst(String ratingsBody, String targetBody) {
        JsonNode ratings = data(ratingsBody);
        JsonNode target = targetBody == null ? null : data(targetBody);
        if (ratings == null && target == null) return null;

        String label = ratings == null ? null : text(ratings.path("meanRatingType"));
        int count = -1;
        if (ratings != null) {
            Matcher m = ANALYST_COUNT.matcher(ratings.path("ratingsSummary").asText(""));
            if (m.find()) count = Integer.parseInt(m.group(1));
        }
        int buy = -1, hold = -1, sell = -1;
        double mean = Double.NaN, high = Double.NaN, low = Double.NaN;
        if (target != null) {
            JsonNode overview = target.path("consensusOverview");
            if (!overview.isMissingNode() && !overview.isNull()) {
                buy = overview.path("buy").asInt(-1);
                hold = overview.path("hold").asInt(-1);
                sell = overview.path("sell").asInt(-1);
                mean = overview.path("priceTarget").asDouble(Double.NaN);
                high = overview.path("highPriceTarget").asDouble(Double.NaN);
                low = overview.path("lowPriceTarget").asDouble(Double.NaN);
            }
        }
        if (label == null && count < 0 && buy < 0 && Double.isNaN(mean)) return null;
        return new AnalystRatings(label, count, buy, hold, sell, mean, high, low);
    }

    /** {@code /company/<sym>/earnings-surprise}: EPS is a raw number, forecast/surprise are strings. */
    static List<EarningsSurprise> parseEarnings(String body) {
        JsonNode data = data(body);
        if (data == null) return List.of();
        List<EarningsSurprise> out = new ArrayList<>();
        for (JsonNode r : data.path("earningsSurpriseTable").path("rows")) {
            out.add(new EarningsSurprise(
                    text(r.path("fiscalQtrEnd")),
                    parseUsDate(text(r.path("dateReported"))),
                    r.path("eps").asDouble(Double.NaN),
                    parseUsNumber(text(r.path("consensusForecast"))),
                    parseUsNumber(text(r.path("percentageSurprise")))));
        }
        return out;
    }

    /** Envelope opener: the {@code data} node of a successful reply, else null. */
    static JsonNode data(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode data = JSON.readTree(body).path("data");
            return data.isMissingNode() || data.isNull() ? null : data;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * NASDAQ's display numbers → double: strips {@code $}, {@code %},
     * thousand-commas and sign-noise; "N/A"/"NA"/"New"/"--"/blank → NaN.
     */
    static double parseUsNumber(String raw) {
        if (raw == null) return Double.NaN;
        String s = raw.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("N/A") || s.equalsIgnoreCase("NA")
                || s.equalsIgnoreCase("New") || s.equals("--")) {
            return Double.NaN;
        }
        s = s.replace("$", "").replace("%", "").replace(",", "").replace(" ", "");
        if (s.startsWith("+")) s = s.substring(1);
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** {@code M/d/yyyy} (single-digit fields occur) → ISO {@code yyyy-MM-dd}, null unparseable. */
    static String parseUsDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim(), US_DATE).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static long asLong(double d) {
        return Double.isNaN(d) ? -1 : (long) d;
    }

    private static String text(JsonNode n) {
        String s = n.asText("");
        return s.isBlank() ? null : s;
    }
}
