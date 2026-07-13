package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.briefing.ApeWisdomClient;
import de.bsommerfeld.wsbg.terminal.briefing.BundYieldClient;
import de.bsommerfeld.wsbg.terminal.briefing.CboePutCallClient;
import de.bsommerfeld.wsbg.terminal.briefing.CoinGeckoClient;
import de.bsommerfeld.wsbg.terminal.briefing.CryptoDerivsClient;
import de.bsommerfeld.wsbg.terminal.briefing.CuriositiesClient;
import de.bsommerfeld.wsbg.terminal.briefing.EconCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.FinraShortVolumeClient;
import de.bsommerfeld.wsbg.terminal.briefing.FnRssClient;
import de.bsommerfeld.wsbg.terminal.briefing.MacroPressClient;
import de.bsommerfeld.wsbg.terminal.briefing.MoonPhase;
import de.bsommerfeld.wsbg.terminal.briefing.NasdaqCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.PolymarketClient;
import de.bsommerfeld.wsbg.terminal.briefing.RhinePegelClient;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystView;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystViewSource;
import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealings;
import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealingsSource;
import de.bsommerfeld.wsbg.terminal.core.price.ShortInterest;
import de.bsommerfeld.wsbg.terminal.core.price.ShortInterestSource;
import de.bsommerfeld.wsbg.terminal.core.price.VenueStats;
import de.bsommerfeld.wsbg.terminal.core.price.VenueStatsSource;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveArchive;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSubject;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.AdhocStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.AnalystActionStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.BetStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CryptoStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DepthStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ExchangeWeatherStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MacroStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoonStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoverStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.NewsStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PegelStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PutCallStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RateStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RoomPulse;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentComponent;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ShortVolStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SocialStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TrendingCoin;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WatchlistStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldStats;
import de.bsommerfeld.wsbg.terminal.feargreed.CryptoFearGreedClient;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedClient;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedIndex;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Freezes the Wetterbericht's day view at generation time — since the
 * Abendausgabe cut (2026-07-13) that is far more than the four original
 * sections: markets/sectors/overnight/rates, the room pulse, German ad-hocs
 * and analyst actions, macro actuals + calendar, US movers and put/call,
 * the neighbour-cage social pulse, crypto incl. derivatives, prediction
 * markets, FINRA short volume, street depth on the top tickers, watchlist
 * day moves, the day's deep dives, tomorrow's docket — and the colour
 * (Rhine gauge, US debt, exchange weather, moon).
 *
 * <p>EVERY leg is individually guarded and best-effort (the DeepDive
 * collect() pattern): a failed source costs its block, never the report.
 * All source clients are optional injects — present in prod, absent in unit
 * tests. One collect per day, so the call budget (~30 HTTP requests) is
 * irrelevant.
 */
@Singleton
class WeatherStatsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherStatsCollector.class);

    /** One "Märkte des Tages" tile: Yahoo symbol, display name, and how its level reads. */
    private record MarketDef(String symbol, String name, String currency) {
    }

    /**
     * The broad-market day at a glance — the room's home index first, then the
     * US pair and fear, then the cross-asset tiles the room trades against.
     * All keyless Yahoo {@code v8/chart} symbols; {@code "PTS"} = index points,
     * {@code "FX"} = an exchange rate. TTF trades natively in EUR (the
     * European gas contract — not to be confused with Henry Hub).
     */
    private static final List<MarketDef> MARKETS = List.of(
            new MarketDef("^GDAXI", "DAX", "PTS"),
            new MarketDef("^GSPC", "S&P 500", "PTS"),
            new MarketDef("^NDX", "Nasdaq 100", "PTS"),
            new MarketDef("^VIX", "VIX", "PTS"),
            new MarketDef("EURUSD=X", "EUR/USD", "FX"),
            new MarketDef("GC=F", "Gold", "USD"),
            new MarketDef("SI=F", "Silber", "USD"),
            new MarketDef("HG=F", "Kupfer", "USD"),
            new MarketDef("CL=F", "WTI Öl", "USD"),
            new MarketDef("TTF=F", "TTF Gas", "EUR"),
            new MarketDef("BTC-USD", "Bitcoin", "USD"),
            new MarketDef("ETH-USD", "Ethereum", "USD"));

    /** The eleven S&P sector ETFs — the rotation picture in one glance. */
    private static final List<MarketDef> SECTORS = List.of(
            new MarketDef("XLK", "Tech", "USD"),
            new MarketDef("XLF", "Finanzen", "USD"),
            new MarketDef("XLE", "Energie", "USD"),
            new MarketDef("XLV", "Gesundheit", "USD"),
            new MarketDef("XLI", "Industrie", "USD"),
            new MarketDef("XLY", "Zykl. Konsum", "USD"),
            new MarketDef("XLP", "Basiskonsum", "USD"),
            new MarketDef("XLU", "Versorger", "USD"),
            new MarketDef("XLRE", "Immobilien", "USD"),
            new MarketDef("XLB", "Grundstoffe", "USD"),
            new MarketDef("XLC", "Kommunikation", "USD"));

    /** After the European close the story moves here: US futures + Asia's session. */
    private static final List<MarketDef> OVERNIGHT = List.of(
            new MarketDef("ES=F", "S&P Futures", "PTS"),
            new MarketDef("NQ=F", "Nasdaq Futures", "PTS"),
            new MarketDef("^N225", "Nikkei 225", "PTS"),
            new MarketDef("^HSI", "Hang Seng", "PTS"));

    private static final int MAX_TICKERS = 8;
    private static final int MAX_NEWS = 8;
    private static final int MAX_ADHOCS = 8;
    private static final int MAX_ANALYST_ACTIONS = 8;
    private static final int MAX_MACRO_ACTUALS = 6;
    private static final int MAX_MACRO_EVENTS = 6;
    private static final int MAX_MOVERS_PER_KIND = 5;
    private static final int MAX_SOCIAL = 6;
    private static final int MAX_TRENDING_COINS = 5;
    private static final int MAX_BETS = 3;
    private static final int MAX_SHORT_VOL = 6;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_WATCHLIST = 8;
    private static final int MAX_OUTLOOK_ECON = 6;
    private static final int MAX_OUTLOOK_EARNINGS = 6;
    /** A rank jump on the neighbour boards only counts as a shooter above this. */
    private static final int MIN_RANK_CLIMB = 50;

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final YahooFinanceClient yahoo;

    // Every source below is @Inject(optional = true): present in prod
    // (JIT-constructible or bound in AgentPipelineModule), absent in tests.
    private volatile VenueStatsSource venueStatsSource;
    private volatile FearGreedClient fearGreedClient;
    private volatile CryptoFearGreedClient cryptoFearGreedClient;
    private volatile FnRssClient fnRssClient;
    private volatile EconCalendarClient econCalendarClient;
    private volatile MacroPressClient macroPressClient;
    private volatile BundYieldClient bundYieldClient;
    private volatile ApeWisdomClient apeWisdomClient;
    private volatile CoinGeckoClient coinGeckoClient;
    private volatile PolymarketClient polymarketClient;
    private volatile CboePutCallClient cboePutCallClient;
    private volatile FinraShortVolumeClient finraClient;
    private volatile NasdaqCalendarClient nasdaqCalendarClient;
    private volatile RhinePegelClient rhinePegelClient;
    private volatile CryptoDerivsClient cryptoDerivsClient;
    private volatile CuriositiesClient curiositiesClient;
    private volatile AnalystViewSource analystViewSource;
    private volatile ShortInterestSource shortInterestSource;
    private volatile InsiderDealingsSource insiderDealingsSource;
    private volatile WatchlistService watchlistService;
    private volatile DeepDiveArchive deepDiveArchive;

    record Stats(List<IndexStat> indices, List<TickerStat> tickers, List<NewsStat> news,
            SentimentStat sentiment, WorldStats world) {
    }

    @Inject
    WeatherStatsCollector(YahooFinanceClient yahoo) {
        this.yahoo = yahoo;
    }

    // --- optional wiring (prod only; unit tests construct bare) -----------

    @com.google.inject.Inject(optional = true)
    void setVenueStatsSource(VenueStatsSource source) {
        this.venueStatsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setFearGreedClient(FearGreedClient client) {
        this.fearGreedClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setCryptoFearGreedClient(CryptoFearGreedClient client) {
        this.cryptoFearGreedClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setFnRssClient(FnRssClient client) {
        this.fnRssClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setEconCalendarClient(EconCalendarClient client) {
        this.econCalendarClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setMacroPressClient(MacroPressClient client) {
        this.macroPressClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setBundYieldClient(BundYieldClient client) {
        this.bundYieldClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setApeWisdomClient(ApeWisdomClient client) {
        this.apeWisdomClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setCoinGeckoClient(CoinGeckoClient client) {
        this.coinGeckoClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setPolymarketClient(PolymarketClient client) {
        this.polymarketClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setCboePutCallClient(CboePutCallClient client) {
        this.cboePutCallClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setFinraClient(FinraShortVolumeClient client) {
        this.finraClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setNasdaqCalendarClient(NasdaqCalendarClient client) {
        this.nasdaqCalendarClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setRhinePegelClient(RhinePegelClient client) {
        this.rhinePegelClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setCryptoDerivsClient(CryptoDerivsClient client) {
        this.cryptoDerivsClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setCuriositiesClient(CuriositiesClient client) {
        this.curiositiesClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setAnalystViewSource(AnalystViewSource source) {
        this.analystViewSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setShortInterestSource(ShortInterestSource source) {
        this.shortInterestSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setInsiderDealingsSource(InsiderDealingsSource source) {
        this.insiderDealingsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setWatchlistService(WatchlistService service) {
        this.watchlistService = service;
    }

    @com.google.inject.Inject(optional = true)
    void setDeepDiveArchive(DeepDiveArchive archive) {
        this.deepDiveArchive = archive;
    }

    // --- collection --------------------------------------------------------

    /**
     * The raw dpa-AFX market reports, for the service's press-digest model
     * call (the ONE textual leg that goes through the model before freezing).
     */
    List<FnRssClient.PressItem> pressItems(int limit) {
        FnRssClient client = fnRssClient;
        if (client == null) return List.of();
        return guarded("press items", List.<FnRssClient.PressItem>of(),
                () -> client.marketReports(limit));
    }

    /**
     * Freezes the full day view. {@code pressDigest} is the already-condensed
     * dpa-AFX narrative (may be null/blank — the block is then absent).
     */
    Stats collect(List<HeadlineRecord> todaysHeadlines, String pressDigest) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant dayStart = today.atStartOfDay(zone).toInstant();

        List<TickerStat> tickers = tickers(todaysHeadlines);
        Set<String> wireTickers = wireTickerSymbols(todaysHeadlines);
        Map<String, String> isinToTicker = isinToTicker(todaysHeadlines);
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat> outlook =
                guarded("outlook", List.of(), () -> outlook(today, zone));

        WorldStats world = new WorldStats(
                guarded("sectors", List.of(), () -> tiles(SECTORS)),
                guarded("overnight", List.of(), () -> tiles(OVERNIGHT)),
                guarded("rates", List.of(), this::rates),
                guarded("pulse", null, () -> pulse(todaysHeadlines, zone)),
                guarded("adhocs", List.of(), () -> adhocs(dayStart, isinToTicker)),
                guarded("analyst actions", List.of(), () -> analystActions(dayStart)),
                guarded("macro actuals", List.of(), () -> macroActuals(dayStart)),
                guarded("macro events", List.of(), () -> macroEvents(today, zone)),
                pressDigest == null || pressDigest.isBlank() ? null : pressDigest,
                guarded("movers", List.of(), () -> movers(wireTickers)),
                guarded("put/call", null, () -> putCall(today)),
                guarded("social", List.of(), this::social),
                guarded("crypto", null, this::crypto),
                guarded("bets", List.of(), this::bets),
                guarded("short volume", List.of(), () -> shortVolume(todaysHeadlines, today)),
                guarded("depth", List.of(), () -> depth(tickers, todaysHeadlines)),
                guarded("watchlist", List.of(), this::watchlistMoves),
                guarded("deep dives", List.of(), () -> deepDivesToday(dayStart)),
                outlook,
                guarded("pegel", null, this::pegel),
                guarded("US debt", null, this::usDebt),
                guarded("exchange weather", null, this::exchangeWeather),
                guarded("moon", null, () -> moon(Instant.now())),
                guarded("dayparts", List.of(), () -> dayparts(todaysHeadlines, zone, outlook)));

        return new Stats(indices(), tickers, news(todaysHeadlines), sentiment(), world);
    }

    /** One try/catch per leg — a failing source costs its block, never the report. */
    private <T> T guarded(String what, T fallback, Supplier<T> leg) {
        try {
            return leg.get();
        } catch (Exception e) {
            LOG.warn("Wetterbericht leg '{}' failed: {}", what, e.getMessage());
            return fallback;
        }
    }

    // --- markets / sectors / overnight / rates ------------------------------

    private List<IndexStat> indices() {
        return tiles(MARKETS);
    }

    private List<IndexStat> tiles(List<MarketDef> defs) {
        List<IndexStat> out = new ArrayList<>();
        for (MarketDef m : defs) {
            try {
                Optional<MarketSnapshot> snap = yahoo.fetchChart(m.symbol());
                if (snap.isEmpty() || !snap.get().hasPrice()) continue;
                MarketSnapshot s = snap.get();
                out.add(new IndexStat(m.name(), m.symbol(), finiteOrNull(s.price()),
                        finiteOrNull(s.dayChangePercent()),
                        s.volume() < 0 ? null : s.volume(), m.currency(), s.spark()));
            } catch (Exception e) {
                LOG.warn("Wetterbericht market stat {} failed: {}", m.symbol(), e.getMessage());
            }
        }
        return out;
    }

    /**
     * The two yield lines every professional briefing carries. {@code ^TNX}
     * quotes the 10y Treasury yield ×10 (a CBOE index quirk), so the level is
     * divided back; the Bund side comes from the Bundesbank's daily Svensson
     * curve (T+1 — the latest available fixing, dated).
     */
    private List<RateStat> rates() {
        List<RateStat> out = new ArrayList<>();
        BundYieldClient bund = bundYieldClient;
        if (bund != null) {
            bund.tenYearBund().ifPresent(p -> out.add(new RateStat("10J Bund",
                    p.percent(), p.previousPercent(), p.dateIso())));
        }
        Optional<MarketSnapshot> tnx = yahoo.fetchChart("^TNX");
        if (tnx.isPresent() && tnx.get().hasPrice()) {
            MarketSnapshot s = tnx.get();
            Double prev = Double.isFinite(s.previousClose()) ? s.previousClose() / 10.0 : null;
            out.add(new RateStat("10J US-Treasury", s.price() / 10.0, prev, null));
        }
        return out;
    }

    // --- the cage's own aggregate --------------------------------------------

    /**
     * Deterministic room aggregate over the day's archived headlines — the
     * restored Käfig-Puls: sentiment split, red count, the busiest hour and
     * how many distinct subjects spoke. No model call, pure arithmetic.
     */
    static RoomPulse pulse(List<HeadlineRecord> headlines, ZoneId zone) {
        if (headlines.isEmpty()) return null;
        int bullish = 0, bearish = 0, neutral = 0, red = 0;
        Map<Integer, Integer> byHour = new HashMap<>();
        Set<String> subjects = new HashSet<>();
        for (HeadlineRecord r : headlines) {
            HeadlineSentiment s = r.sentiment();
            if (s == HeadlineSentiment.BULLISH || s == HeadlineSentiment.FOMO
                    || s == HeadlineSentiment.SQUEEZE || s == HeadlineSentiment.BREAKOUT) {
                bullish++;
            } else if (s == HeadlineSentiment.BEARISH || s == HeadlineSentiment.CAPITULATION) {
                bearish++;
            } else {
                neutral++;
            }
            if (r.highlight() == HeadlineHighlight.IMPORTANT) red++;
            byHour.merge(LocalDateTime.ofInstant(Instant.ofEpochSecond(r.createdAt()), zone)
                    .getHour(), 1, Integer::sum);
            subjects.add(r.tickerSymbol() != null && !r.tickerSymbol().isBlank()
                    ? r.tickerSymbol().toUpperCase(Locale.ROOT)
                    : String.valueOf(r.clusterId()));
        }
        Integer busiest = byHour.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return new RoomPulse(bullish, bearish, neutral, red, busiest, subjects.size());
    }

    // --- the forecast strip: the day in weather symbols -------------------------

    /** Morning ends at this local hour, midday at {@link #EVENING_FROM}. */
    private static final int MIDDAY_FROM = 12;
    private static final int EVENING_FROM = 16;

    /**
     * The literal weather-report strip (user mandate 2026-07-13: "wie einen
     * Wetterbericht aufbauen — morgens, mittags, abends, dazu Bildchen, dann
     * die Vorschau"): each elapsed day part gets a deterministic weather icon
     * from ITS window's cage mood — sun when the bulls dominate, rain when the
     * bears do, storm when red flags pile onto a bearish window, fog when the
     * cage was silent — plus the window's protagonist. The TOMORROW tile is
     * docket-based, never a prediction: a High-impact release is a
     * Gewitterwarnung, scheduled earnings are clouds moving in.
     */
    static List<DaypartStat> dayparts(List<HeadlineRecord> headlines, ZoneId zone,
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat> outlook) {
        record Window(String key, int fromHour, int toHourExclusive) {
        }
        List<Window> windows = List.of(
                new Window("MORNING", 0, MIDDAY_FROM),
                new Window("MIDDAY", MIDDAY_FROM, EVENING_FROM),
                new Window("EVENING", EVENING_FROM, 24));
        List<DaypartStat> out = new ArrayList<>();
        for (Window win : windows) {
            int bullish = 0, bearish = 0, neutral = 0, red = 0;
            Map<String, Integer> bySubject = new LinkedHashMap<>();
            Map<String, String> displayBySubject = new LinkedHashMap<>();
            for (HeadlineRecord r : headlines) {
                int hour = LocalDateTime.ofInstant(Instant.ofEpochSecond(r.createdAt()), zone)
                        .getHour();
                if (hour < win.fromHour() || hour >= win.toHourExclusive()) continue;
                HeadlineSentiment s = r.sentiment();
                if (s == HeadlineSentiment.BULLISH || s == HeadlineSentiment.FOMO
                        || s == HeadlineSentiment.SQUEEZE || s == HeadlineSentiment.BREAKOUT) {
                    bullish++;
                } else if (s == HeadlineSentiment.BEARISH || s == HeadlineSentiment.CAPITULATION) {
                    bearish++;
                } else {
                    neutral++;
                }
                if (r.highlight() == HeadlineHighlight.IMPORTANT) red++;
                String key = r.tickerSymbol() != null && !r.tickerSymbol().isBlank()
                        ? r.tickerSymbol().toUpperCase(Locale.ROOT)
                        : String.valueOf(r.clusterId());
                bySubject.merge(key, 1, Integer::sum);
                displayBySubject.putIfAbsent(key, subjectDisplay(r, key));
            }
            String top = bySubject.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> displayBySubject.get(e.getKey()))
                    .orElse(null);
            out.add(new DaypartStat(win.key(), moodIcon(bullish, bearish, neutral, red),
                    bullish + bearish + neutral, bullish, bearish, red, top));
        }
        // Tomorrow: the docket decides the symbol — honest, never a forecast.
        boolean highImpact = false;
        int earnings = 0;
        String note = null;
        for (var o : outlook == null ? List.<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat>of() : outlook) {
            if ("ECON".equals(o.kind()) && "High".equalsIgnoreCase(o.impact())) {
                highImpact = true;
                if (note == null) note = o.title();
            } else if ("EARNINGS".equals(o.kind())) {
                earnings++;
                if (note == null) note = o.title();
            }
        }
        out.add(new DaypartStat("TOMORROW",
                highImpact ? "STORM" : earnings > 0 ? "PARTLY" : "SUNNY",
                0, 0, 0, 0, note));
        return out;
    }

    /** Deterministic mood → weather icon. Package-private for tests. */
    static String moodIcon(int bullish, int bearish, int neutral, int red) {
        int lines = bullish + bearish + neutral;
        if (lines == 0) return "FOG";
        int directional = bullish + bearish;
        if (directional == 0) return "CLOUDY";
        double bullShare = (double) bullish / directional;
        if (bullShare >= 0.65) return "SUNNY";
        if (bullShare >= 0.5) return "PARTLY";
        if (bullShare < 0.35 && red >= 2) return "STORM";
        return "RAIN";
    }

    private static String subjectDisplay(HeadlineRecord r, String fallback) {
        for (HeadlineSubject s : r.subjects()) {
            if (s.name() != null && !s.name().isBlank()) return s.name();
        }
        return fallback;
    }

    // --- Germany: ad-hocs + analyst actions -----------------------------------

    private List<AdhocStat> adhocs(Instant dayStart, Map<String, String> isinToTicker) {
        FnRssClient client = fnRssClient;
        if (client == null) return List.of();
        List<AdhocStat> out = new ArrayList<>();
        for (FnRssClient.AdhocItem item : client.adhocs(40)) {
            if (item.publishedAt() == null || item.publishedAt().isBefore(dayStart)) continue;
            String kaefigTicker = item.isin() == null ? null : isinToTicker.get(item.isin());
            out.add(new AdhocStat(item.title(), item.isin(),
                    localTime(item.publishedAt()), kaefigTicker));
        }
        // Cage-joined disclosures lead — an ad-hoc on a discussed paper is the lead.
        out.sort(Comparator.comparing((AdhocStat a) -> a.kaefigTicker() == null));
        return cap(out, MAX_ADHOCS);
    }

    private List<AnalystActionStat> analystActions(Instant dayStart) {
        FnRssClient client = fnRssClient;
        if (client == null) return List.of();
        List<AnalystActionStat> out = new ArrayList<>();
        for (FnRssClient.AnalystAction a : client.analystActions(30)) {
            if (a.publishedAt() == null || a.publishedAt().isBefore(dayStart)) continue;
            out.add(new AnalystActionStat(a.title(), localTime(a.publishedAt())));
        }
        return cap(out, MAX_ANALYST_ACTIONS);
    }

    // --- macro ------------------------------------------------------------------

    private List<MacroStat> macroActuals(Instant dayStart) {
        MacroPressClient client = macroPressClient;
        if (client == null) return List.of();
        List<MacroStat> out = new ArrayList<>();
        for (MacroPressClient.MacroActual a : client.actualsSince(dayStart, MAX_MACRO_ACTUALS)) {
            out.add(new MacroStat(a.title(), a.source(),
                    a.publishedAt() == null ? null : localTime(a.publishedAt()),
                    null, null, null));
        }
        return out;
    }

    private List<MacroStat> macroEvents(LocalDate today, ZoneId zone) {
        EconCalendarClient client = econCalendarClient;
        if (client == null) return List.of();
        List<MacroStat> out = new ArrayList<>();
        for (EconCalendarClient.EconEvent e : client.thisWeek()) {
            LocalDate day = Instant.ofEpochSecond(e.whenEpochSeconds()).atZone(zone).toLocalDate();
            if (!day.equals(today) || !relevantEvent(e)) continue;
            out.add(new MacroStat(e.title(), e.country(),
                    LocalTime.ofInstant(Instant.ofEpochSecond(e.whenEpochSeconds()), zone)
                            .format(HOUR_MINUTE),
                    e.impact(), e.forecast(), e.previous()));
        }
        return cap(out, MAX_MACRO_EVENTS);
    }

    /** High impact anywhere, medium only for the currencies this room trades. */
    private static boolean relevantEvent(EconCalendarClient.EconEvent e) {
        if ("High".equalsIgnoreCase(e.impact())) return true;
        return "Medium".equalsIgnoreCase(e.impact())
                && ("EUR".equals(e.country()) || "USD".equals(e.country()));
    }

    // --- US movers / options sentiment --------------------------------------------

    private List<MoverStat> movers(Set<String> wireTickers) {
        List<MoverStat> out = new ArrayList<>();
        out.addAll(screener("day_gainers", "GAINER", wireTickers));
        out.addAll(screener("day_losers", "LOSER", wireTickers));
        out.addAll(screener("most_actives", "ACTIVE", wireTickers));
        return out;
    }

    private List<MoverStat> screener(String scrIds, String kind, Set<String> wireTickers) {
        try {
            List<MoverStat> out = new ArrayList<>();
            for (YahooFinanceClient.ScreenerQuote q
                    : yahoo.fetchScreener(scrIds, MAX_MOVERS_PER_KIND).quotes()) {
                out.add(new MoverStat(q.symbol(), q.name(),
                        Double.isFinite(q.changePercent()) ? q.changePercent() : null,
                        Double.isFinite(q.price()) ? q.price() : null,
                        kind, wireTickers.contains(q.symbol().toUpperCase(Locale.ROOT))));
            }
            return cap(out, MAX_MOVERS_PER_KIND);
        } catch (Exception e) {
            LOG.warn("Wetterbericht screener {} failed: {}", scrIds, e.getMessage());
            return List.of();
        }
    }

    private PutCallStat putCall(LocalDate today) {
        CboePutCallClient client = cboePutCallClient;
        if (client == null) return null;
        return client.latest(today)
                .map(r -> new PutCallStat(nanToNull(r.total()), nanToNull(r.equity()),
                        nanToNull(r.index()), r.dateIso()))
                .orElse(null);
    }

    // --- social / crypto / bets ------------------------------------------------------

    /**
     * The neighbour cages' pulse: the top of the board plus the day's shooters
     * (a climb of hundreds of ranks is the pennystock-radar pattern, measured
     * next door). Distinct by ticker, leaders first.
     */
    private List<SocialStat> social() {
        ApeWisdomClient client = apeWisdomClient;
        if (client == null) return List.of();
        List<ApeWisdomClient.SocialTicker> all = client.topTickers();
        if (all.isEmpty()) return List.of();
        Map<String, SocialStat> out = new LinkedHashMap<>();
        all.stream().sorted(Comparator.comparingInt(ApeWisdomClient.SocialTicker::rank))
                .limit(3)
                .forEach(t -> out.put(t.ticker(), toSocial(t)));
        all.stream().filter(t -> t.rankClimb() >= MIN_RANK_CLIMB && t.mentions() >= 5)
                .sorted(Comparator.comparingInt(ApeWisdomClient.SocialTicker::rankClimb).reversed())
                .limit(MAX_SOCIAL)
                .forEach(t -> out.putIfAbsent(t.ticker(), toSocial(t)));
        return cap(new ArrayList<>(out.values()), MAX_SOCIAL);
    }

    private static SocialStat toSocial(ApeWisdomClient.SocialTicker t) {
        return new SocialStat(t.ticker(), t.name(), Math.max(0, t.mentions()), t.rank(),
                t.rankClimb() == 0 ? null : t.rankClimb());
    }

    private CryptoStat crypto() {
        CoinGeckoClient gecko = coinGeckoClient;
        Double mcap = null, mcapChange = null, dominance = null;
        List<TrendingCoin> trending = List.of();
        if (gecko != null) {
            Optional<CoinGeckoClient.CryptoGlobal> global =
                    guarded("coingecko global", Optional.empty(), gecko::global);
            if (global.isPresent()) {
                mcap = nanToNull(global.get().marketCapUsd());
                mcapChange = nanToNull(global.get().mcapChange24hPercent());
                dominance = nanToNull(global.get().btcDominancePercent());
            }
            trending = guarded("coingecko trending", List.<CoinGeckoClient.TrendingCoin>of(),
                    () -> gecko.trending(MAX_TRENDING_COINS)).stream()
                    .map(c -> new TrendingCoin(c.name(), c.symbol(),
                            nanToNull(c.change24hPercent())))
                    .toList();
        }
        Integer fgScore = null;
        String fgBand = null;
        CryptoFearGreedClient cfg = cryptoFearGreedClient;
        if (cfg != null) {
            var idx = guarded("crypto F&G", Optional.<de.bsommerfeld.wsbg.terminal.feargreed.CryptoFearGreedIndex>empty(), cfg::fetch);
            if (idx.isPresent()) {
                fgScore = (int) Math.round(idx.get().score());
                fgBand = idx.get().band().name();
            }
        }
        Double funding = null, dvol = null;
        CryptoDerivsClient derivs = cryptoDerivsClient;
        if (derivs != null) {
            var snap = guarded("crypto derivs",
                    Optional.<CryptoDerivsClient.DerivsSnapshot>empty(), derivs::snapshot);
            if (snap.isPresent()) {
                funding = nanToNull(snap.get().fundingRatePercent());
                dvol = nanToNull(snap.get().dvol());
            }
        }
        if (mcap == null && fgScore == null && funding == null && trending.isEmpty()) return null;
        return new CryptoStat(mcap, mcapChange, dominance, fgScore, fgBand,
                funding, dvol, trending);
    }

    private List<BetStat> bets() {
        PolymarketClient client = polymarketClient;
        if (client == null) return List.of();
        List<BetStat> out = new ArrayList<>();
        for (PolymarketClient.PredictionMarket m : client.topByVolume(MAX_BETS)) {
            out.add(new BetStat(m.question(), m.outcome(),
                    nanToNull(m.probabilityPercent()), nanToNull(m.volume24hUsd())));
        }
        return out;
    }

    // --- FINRA short volume ------------------------------------------------------------

    /**
     * Short-volume ratios for the cage's US-listed day tickers (plain
     * letters-only symbols — ISIN-priced German papers and Yahoo specials
     * never reach FINRA). The interpretation caveat rides in the prompt:
     * short volume ≠ short interest.
     */
    private List<ShortVolStat> shortVolume(List<HeadlineRecord> headlines, LocalDate today) {
        FinraShortVolumeClient client = finraClient;
        if (client == null) return List.of();
        Set<String> usSymbols = new LinkedHashSet<>();
        for (String sym : wireTickerSymbols(headlines)) {
            if (sym.matches("[A-Z]{1,5}")) usSymbols.add(sym);
            if (usSymbols.size() >= MAX_SHORT_VOL) break;
        }
        if (usSymbols.isEmpty()) return List.of();
        List<ShortVolStat> out = new ArrayList<>();
        for (FinraShortVolumeClient.ShortVolume sv
                : client.ratiosFor(usSymbols, today).values()) {
            out.add(new ShortVolStat(sv.symbol(), sv.shortPercent(), sv.dateIso()));
        }
        out.sort(Comparator.comparing(ShortVolStat::shortPercent,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    // --- street depth on the top tickers -------------------------------------------------

    /**
     * Consensus target, rating split, next corporate event, disclosed shorts
     * and the freshest insider note for the top ISIN-carrying tickers —
     * three house sources, one dense line each. Bounded to {@link #MAX_DEPTH}
     * names (≤ ~12 HTTP calls, once a day).
     */
    private List<DepthStat> depth(List<TickerStat> topTickers, List<HeadlineRecord> headlines) {
        AnalystViewSource analysts = analystViewSource;
        ShortInterestSource shorts = shortInterestSource;
        InsiderDealingsSource insiders = insiderDealingsSource;
        if (analysts == null && shorts == null && insiders == null) return List.of();

        Map<String, String> tickerToIsin = new LinkedHashMap<>();
        for (TickerStat t : topTickers) {
            String isin = isinForTicker(headlines, t.ticker());
            if (isin != null) tickerToIsin.put(t.ticker(), isin);
            if (tickerToIsin.size() >= MAX_DEPTH) break;
        }
        List<DepthStat> out = new ArrayList<>();
        long now = Instant.now().getEpochSecond();
        for (Map.Entry<String, String> e : tickerToIsin.entrySet()) {
            String ticker = e.getKey();
            String isin = e.getValue();
            Double target = null, upside = null, shortPct = null;
            String targetCcy = null, nextTitle = null, nextDate = null,
                    topHolder = null, insiderNote = null;
            Integer buy = null, hold = null, sell = null;
            if (analysts != null) {
                Optional<AnalystView> view = guarded("analyst view " + ticker,
                        Optional.empty(), () -> analysts.refresh(isin));
                if (view.isPresent()) {
                    AnalystView v = view.get();
                    target = nanToNull(v.targetPrice());
                    targetCcy = v.targetCurrency();
                    upside = nanToNull(v.expectedUpsidePercent());
                    if (v.hasRatings()) {
                        buy = v.buy() + v.overweight();
                        hold = v.hold();
                        sell = v.underweight() + v.sell();
                    }
                    AnalystView.CorporateEvent next = v.nextEvent(now);
                    if (next != null) {
                        nextTitle = next.title() == null || next.title().isBlank()
                                ? next.type() : next.title();
                        nextDate = LocalDate.ofInstant(
                                Instant.ofEpochSecond(next.atEpochSeconds()),
                                ZoneId.systemDefault()).toString();
                    }
                }
            }
            if (shorts != null) {
                Optional<ShortInterest> si = guarded("short interest " + ticker,
                        Optional.empty(), () -> shorts.byIsin(isin));
                if (si.isPresent() && si.get().totalDisclosedPercent() > 0) {
                    shortPct = si.get().totalDisclosedPercent();
                    List<ShortInterest.ShortPosition> positions = si.get().positions();
                    if (!positions.isEmpty()) topHolder = positions.get(0).holder();
                }
            }
            if (insiders != null) {
                Optional<InsiderDealings> dd = guarded("insider dealings " + ticker,
                        Optional.empty(), () -> insiders.byIsin(isin));
                if (dd.isPresent() && !dd.get().deals().isEmpty()) {
                    insiderNote = insiderNote(dd.get().deals().get(0));
                }
            }
            if (target == null && buy == null && shortPct == null && insiderNote == null
                    && nextTitle == null) {
                continue;
            }
            out.add(new DepthStat(ticker, target, targetCcy, upside, buy, hold, sell,
                    nextTitle, nextDate, shortPct, topHolder, insiderNote));
        }
        return out;
    }

    /** "Kauf 1,2 Mio € (Vorstand Max Mustermann, 2026-07-08)" — or null when stale/empty. */
    private static String insiderNote(InsiderDealings.InsiderDeal deal) {
        if (deal.dealDateIso() == null) return null;
        try {
            if (LocalDate.parse(deal.dealDateIso())
                    .isBefore(LocalDate.now().minusDays(21))) {
                return null; // older than three weeks — not the day's story anymore
            }
        } catch (Exception e) {
            return null;
        }
        StringBuilder sb = new StringBuilder(deal.dealType());
        if (Double.isFinite(deal.volumeEur())) {
            sb.append(' ').append(compactEur(deal.volumeEur()));
        }
        sb.append(" (").append(deal.person());
        if (deal.positionStatus() != null && !deal.positionStatus().isBlank()) {
            sb.append(", ").append(deal.positionStatus());
        }
        sb.append(", ").append(deal.dealDateIso()).append(')');
        return sb.toString();
    }

    private static String compactEur(double v) {
        if (v >= 1_000_000) return String.format(Locale.GERMANY, "%.1f Mio €", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.GERMANY, "%.0f Tsd €", v / 1_000);
        return String.format(Locale.GERMANY, "%.0f €", v);
    }

    // --- house artifacts -------------------------------------------------------------------

    private List<WatchlistStat> watchlistMoves() {
        WatchlistService service = watchlistService;
        if (service == null) return List.of();
        List<WatchlistStat> out = new ArrayList<>();
        for (WatchlistService.EntryView e : service.entries()) {
            MarketSnapshot s = e.snapshot();
            if (s == null || !s.hasPrice()) continue;
            out.add(new WatchlistStat(e.name(), e.ticker(),
                    finiteOrNull(s.dayChangePercent()), finiteOrNull(s.price()), s.currency()));
        }
        out.sort(Comparator.comparing(WatchlistStat::changePercent,
                Comparator.nullsLast(
                        Comparator.comparingDouble((Double d) -> Math.abs(d)).reversed())));
        return cap(out, MAX_WATCHLIST);
    }

    private List<String> deepDivesToday(Instant dayStart) {
        DeepDiveArchive archive = deepDiveArchive;
        if (archive == null) return List.of();
        List<String> out = new ArrayList<>();
        for (DeepDiveRecord r : archive.recent(10)) {
            if (r.createdAtEpoch() < dayStart.getEpochSecond()) continue;
            out.add(r.canonicalName() != null && !r.canonicalName().isBlank()
                    ? r.canonicalName() : r.subject());
        }
        return out;
    }

    // --- outlook -------------------------------------------------------------------------

    private List<OutlookStat> outlook(LocalDate today, ZoneId zone) {
        LocalDate tomorrow = today.plusDays(1);
        List<OutlookStat> out = new ArrayList<>();
        EconCalendarClient calendar = econCalendarClient;
        if (calendar != null) {
            List<EconCalendarClient.EconEvent> week = new ArrayList<>();
            week.addAll(guarded("ff this week", List.of(), calendar::thisWeek));
            week.addAll(guarded("ff next week", List.of(), calendar::nextWeek));
            int econ = 0;
            for (EconCalendarClient.EconEvent e : week) {
                LocalDate day = Instant.ofEpochSecond(e.whenEpochSeconds())
                        .atZone(zone).toLocalDate();
                if (!day.equals(tomorrow) || !relevantEvent(e) || econ >= MAX_OUTLOOK_ECON) {
                    continue;
                }
                out.add(new OutlookStat(e.title(), e.country(), e.impact(),
                        LocalTime.ofInstant(Instant.ofEpochSecond(e.whenEpochSeconds()), zone)
                                .format(HOUR_MINUTE), "ECON"));
                econ++;
            }
        }
        NasdaqCalendarClient nasdaq = nasdaqCalendarClient;
        if (nasdaq != null) {
            for (NasdaqCalendarClient.EarningsEntry e : guarded("nasdaq earnings",
                    List.<NasdaqCalendarClient.EarningsEntry>of(),
                    () -> nasdaq.earningsOn(tomorrow, MAX_OUTLOOK_EARNINGS))) {
                String detail = e.epsForecast() == null || e.epsForecast().isBlank()
                        ? e.symbol() : e.symbol() + ", erw. EPS " + e.epsForecast();
                out.add(new OutlookStat(e.name() == null || e.name().isBlank()
                        ? e.symbol() : e.name(), detail, null, slotLabel(e.slot()), "EARNINGS"));
            }
        }
        return out;
    }

    /** NASDAQ's slot token → a stable short label the UI/prompt can carry. */
    private static String slotLabel(String slot) {
        if (slot == null) return null;
        return switch (slot) {
            case "time-pre-market" -> "pre-market";
            case "time-after-hours" -> "after-hours";
            default -> null;
        };
    }

    // --- colour ---------------------------------------------------------------------------

    private PegelStat pegel() {
        RhinePegelClient client = rhinePegelClient;
        if (client == null) return null;
        return client.kaub()
                .map(r -> new PegelStat(r.centimeters(), r.state()))
                .orElse(null);
    }

    private Double usDebt() {
        CuriositiesClient client = curiositiesClient;
        if (client == null) return null;
        return client.usDebt().map(CuriositiesClient.UsDebt::totalUsd).orElse(null);
    }

    private ExchangeWeatherStat exchangeWeather() {
        CuriositiesClient client = curiositiesClient;
        if (client == null) return null;
        return client.frankfurtWeather()
                .map(w -> new ExchangeWeatherStat(w.temperatureCelsius(), w.icon()))
                .orElse(null);
    }

    static MoonStat moon(Instant now) {
        MoonPhase.MoonInfo info = MoonPhase.at(now);
        return new MoonStat(info.phase(), info.illuminationPercent(), info.daysToFull());
    }

    // --- sentiment ---------------------------------------------------------------------------

    /**
     * The day's market mood, fetched fresh at freeze time (one CNN call per day
     * — reading the poll monitor's cache would couple to its lifecycle for no
     * gain). The band travels as the stable enum token; the UI localizes it.
     * Since the Abendausgabe the seven sub-indicators freeze WITH it (why the
     * gauge reads what it reads), plus the crypto gauge beside it.
     */
    private SentimentStat sentiment() {
        FearGreedClient client = fearGreedClient;
        Integer score = null, prev = null;
        String band = null;
        List<SentimentComponent> components = List.of();
        if (client != null) {
            try {
                Optional<FearGreedIndex> idx = client.fetch();
                if (idx.isPresent()) {
                    FearGreedIndex fg = idx.get();
                    score = (int) Math.round(fg.score());
                    band = fg.band().name();
                    prev = Double.isFinite(fg.previousClose())
                            ? (int) Math.round(fg.previousClose()) : null;
                    components = fg.components().stream()
                            .map(c -> new SentimentComponent(c.key(), (int) Math.round(c.score())))
                            .toList();
                }
            } catch (Exception e) {
                LOG.warn("Wetterbericht sentiment stat failed: {}", e.getMessage());
            }
        }
        Integer cryptoScore = null;
        String cryptoBand = null;
        CryptoFearGreedClient cfg = cryptoFearGreedClient;
        if (cfg != null) {
            try {
                var idx = cfg.fetch();
                if (idx.isPresent()) {
                    cryptoScore = (int) Math.round(idx.get().score());
                    cryptoBand = idx.get().band().name();
                }
            } catch (Exception e) {
                LOG.debug("Wetterbericht crypto sentiment failed: {}", e.getMessage());
            }
        }
        if (score == null && cryptoScore == null) return null;
        return new SentimentStat(score, band, prev, components, cryptoScore, cryptoBand);
    }

    // --- most-discussed tickers (unchanged core) ------------------------------------------

    /**
     * The day's most-discussed instruments, quote frozen from the LATEST archived
     * headline snapshot of each ticker — the wire's own numbers, not a re-fetch.
     * The capped top list is then enriched with Tradegate turnover (shares +
     * EUR) where the snapshot carries an ISIN — L&S publishes no volume, so the
     * "what actually traded" figure has to come from the one venue that does.
     */
    List<TickerStat> tickers(List<HeadlineRecord> headlines) {
        try {
            Map<String, List<HeadlineRecord>> byTicker = new LinkedHashMap<>();
            for (HeadlineRecord r : headlines) {
                if (r.tickerSymbol() == null || r.tickerSymbol().isBlank()) continue;
                byTicker.computeIfAbsent(r.tickerSymbol().toUpperCase(Locale.ROOT),
                        k -> new ArrayList<>()).add(r);
            }
            record Ranked(TickerStat stat, MarketSnapshot snap) {
            }
            List<Ranked> out = new ArrayList<>();
            for (Map.Entry<String, List<HeadlineRecord>> e : byTicker.entrySet()) {
                List<HeadlineRecord> records = e.getValue();
                int important = (int) records.stream()
                        .filter(r -> r.highlight() == HeadlineHighlight.IMPORTANT).count();
                MarketSnapshot snap = records.stream()
                        .sorted(Comparator.comparingLong(HeadlineRecord::createdAt).reversed())
                        .map(HeadlineRecord::snapshot)
                        .filter(s -> s != null && s.hasPrice())
                        .findFirst().orElse(null);
                out.add(new Ranked(new TickerStat(e.getKey(), displayName(records, e.getKey()),
                        records.size(), important,
                        snap == null ? null : finiteOrNull(snap.price()),
                        snap == null ? null : snap.currency(),
                        snap == null ? null : finiteOrNull(snap.dayChangePercent()),
                        snap == null || snap.volume() < 0 ? null : snap.volume(), null), snap));
            }
            out.sort(Comparator.comparingInt((Ranked r) -> r.stat().headlineCount()).reversed()
                    .thenComparing(Comparator.comparingInt((Ranked r) -> r.stat().importantCount()).reversed()));
            List<Ranked> top = out.size() > MAX_TICKERS ? out.subList(0, MAX_TICKERS) : out;
            return top.stream().map(r -> enrichWithVenueStats(r.stat(), r.snap())).toList();
        } catch (Exception e) {
            LOG.warn("Wetterbericht ticker stats failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Fills volume/turnover from Tradegate for a German-listed paper. The ISIN
     * rides the L&S snapshot's {@code symbol} field (the price chain stamps it
     * there); Yahoo-priced snapshots carry a Yahoo symbol instead and are
     * skipped by the shape check. Runs only over the capped top list (≤ 8 HTTP
     * calls, once a day) and is best-effort — a venue miss changes nothing.
     */
    private TickerStat enrichWithVenueStats(TickerStat stat, MarketSnapshot snap) {
        VenueStatsSource source = venueStatsSource;
        if (source == null || snap == null || !looksLikeIsin(snap.symbol())) return stat;
        try {
            Optional<VenueStats> vs = source.statsByIsin(snap.symbol());
            if (vs.isEmpty()) return stat;
            Long shares = vs.get().volumeShares() < 0 ? null : vs.get().volumeShares();
            Long turnover = vs.get().turnoverEur() < 0 ? null : vs.get().turnoverEur();
            if (shares == null && turnover == null) return stat;
            return new TickerStat(stat.ticker(), stat.name(), stat.headlineCount(),
                    stat.importantCount(), stat.price(), stat.currency(), stat.changePercent(),
                    stat.volume() != null ? stat.volume() : shares, turnover);
        } catch (Exception e) {
            LOG.debug("Wetterbericht venue stats for {} failed: {}", stat.ticker(), e.getMessage());
            return stat;
        }
    }

    /** ISIN shape: two country letters, nine alphanumerics, one check digit. */
    static boolean looksLikeIsin(String s) {
        return s != null && s.length() == 12
                && Character.isLetter(s.charAt(0)) && Character.isLetter(s.charAt(1))
                && Character.isDigit(s.charAt(11))
                && s.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128);
    }

    private static String displayName(List<HeadlineRecord> records, String ticker) {
        for (HeadlineRecord r : records) {
            for (HeadlineSubject s : r.subjects()) {
                if (ticker.equalsIgnoreCase(s.ticker()) && s.name() != null && !s.name().isBlank()) {
                    return s.name();
                }
            }
        }
        return ticker;
    }

    /** The news items the day's lines actually leaned on, ranked by how often they were cited. */
    private List<NewsStat> news(List<HeadlineRecord> headlines) {
        try {
            Map<String, NewsStat> byTitle = new LinkedHashMap<>();
            for (HeadlineRecord r : headlines) {
                for (HeadlineNewsRef ref : r.newsRefs()) {
                    if (ref.title() == null || ref.title().isBlank()) continue;
                    String key = ref.title().toLowerCase(Locale.ROOT).strip();
                    NewsStat prior = byTitle.get(key);
                    byTitle.put(key, new NewsStat(ref.title(), ref.publisher(),
                            prior == null ? 1 : prior.citations() + 1));
                }
            }
            return byTitle.values().stream()
                    .sorted(Comparator.comparingInt(NewsStat::citations).reversed())
                    .limit(MAX_NEWS).toList();
        } catch (Exception e) {
            LOG.warn("Wetterbericht news stats failed: {}", e.getMessage());
            return List.of();
        }
    }

    // --- shared helpers --------------------------------------------------------------------

    /** The day's wire ticker symbols (UPPER), first appearance order. */
    private static Set<String> wireTickerSymbols(List<HeadlineRecord> headlines) {
        Set<String> out = new LinkedHashSet<>();
        for (HeadlineRecord r : headlines) {
            if (r.tickerSymbol() != null && !r.tickerSymbol().isBlank()) {
                out.add(r.tickerSymbol().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** ISIN → wire ticker, read off the archived snapshots (the price chain stamps ISINs there). */
    private static Map<String, String> isinToTicker(List<HeadlineRecord> headlines) {
        Map<String, String> out = new HashMap<>();
        for (HeadlineRecord r : headlines) {
            if (r.snapshot() == null || r.tickerSymbol() == null || r.tickerSymbol().isBlank()) {
                continue;
            }
            String symbol = r.snapshot().symbol();
            if (looksLikeIsin(symbol)) {
                out.putIfAbsent(symbol, r.tickerSymbol().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static String isinForTicker(List<HeadlineRecord> headlines, String ticker) {
        for (HeadlineRecord r : headlines) {
            if (r.tickerSymbol() == null || !ticker.equalsIgnoreCase(r.tickerSymbol())) continue;
            if (r.snapshot() != null && looksLikeIsin(r.snapshot().symbol())) {
                return r.snapshot().symbol();
            }
        }
        return null;
    }

    private static String localTime(Instant instant) {
        return LocalTime.ofInstant(instant, ZoneId.systemDefault()).format(HOUR_MINUTE);
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() > max ? List.copyOf(list.subList(0, max)) : list;
    }

    private static Double finiteOrNull(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static Double nanToNull(double v) {
        return Double.isNaN(v) ? null : v;
    }
}
