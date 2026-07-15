package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.briefing.ApeWisdomClient;
import de.bsommerfeld.wsbg.terminal.briefing.BundYieldClient;
import de.bsommerfeld.wsbg.terminal.briefing.EcbFeedsClient;
import de.bsommerfeld.wsbg.terminal.briefing.CboePutCallClient;
import de.bsommerfeld.wsbg.terminal.briefing.CentralBankCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.CoinGeckoClient;
import de.bsommerfeld.wsbg.terminal.briefing.CryptoDerivsClient;
import de.bsommerfeld.wsbg.terminal.briefing.CuriositiesClient;
import de.bsommerfeld.wsbg.terminal.briefing.EarningsWhispersClient;
import de.bsommerfeld.wsbg.terminal.briefing.EconCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.EqsEventsClient;
import de.bsommerfeld.wsbg.terminal.briefing.FinraShortVolumeClient;
import de.bsommerfeld.wsbg.terminal.briefing.FnRssClient;
import de.bsommerfeld.wsbg.terminal.briefing.GlobalHazardsClient;
import de.bsommerfeld.wsbg.terminal.briefing.MacroPressClient;
import de.bsommerfeld.wsbg.terminal.briefing.MarketPressClient;
import de.bsommerfeld.wsbg.terminal.briefing.MoonPhase;
import de.bsommerfeld.wsbg.terminal.briefing.NasdaqCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.PolymarketClient;
import de.bsommerfeld.wsbg.terminal.briefing.RhinePegelClient;
import de.bsommerfeld.wsbg.terminal.briefing.TagesschauClient;
import de.bsommerfeld.wsbg.terminal.briefing.TradingViewCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.WikipediaCurrentEventsClient;
import de.bsommerfeld.wsbg.terminal.briefing.WorldWeatherClient;
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
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldSignals;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.BetStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CbDateStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CryptoStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.EconOutcomeStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.EventReviewStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DepthStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ExchangeWeatherStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MacroStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoonStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoverStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.NewsStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PegelStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PutCallStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RateStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RoomPulse;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentComponent;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ShortVolStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SocialStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.StreetActionStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TopNewsStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TrendingCoin;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WatchlistStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldEventStat;
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
import java.util.stream.Collectors;
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
            new MarketDef("DX-Y.NYB", "US-Dollar-Index", "PTS"),
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
    private static final int MAX_STREET_ACTIONS = 15;
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
    private static final int MAX_OUTLOOK_CORP = 4;
    private static final int MAX_ECON_OUTCOMES = 8;
    private static final int WORLD_EVENTS_PER_CATEGORY = 2;
    private static final int MAX_WORLD_EVENTS = 8;
    private static final int MAX_TOP_NEWS = 8;
    // Fishing-net freeze budgets live in WorldSignalsCollector (2026-07-15).
    /** Runaway backstop only (user mandate: no information lost to fixed caps). */
    private static final int MAX_PRESS_REVIEW = 400;
    private static final int MAX_HAZARDS = 10;
    /** Fresh triangulated press per top ticker — a catalyst feed, not an archive. */
    private static final int MAX_TICKER_NEWS_PER_TICKER = 4;
    /** General web-sweep results per top ticker (Bing, crawl-dated → untimed). */
    private static final int MAX_TICKER_WEB_NEWS = 2;
    private static final int MAX_EVENT_REVIEWS = 2;
    private static final int REVIEW_HEADLINES = 3;
    private static final int CB_LOOKAHEAD_DAYS = 90;
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
    private volatile MarketPressClient marketPressClient;
    private volatile WorldWeatherClient worldWeatherClient;
    private volatile GlobalHazardsClient globalHazardsClient;
    // The KI-DD's source park, ridden ERGÄNZEND (user mandate 2026-07-14
    // "dieselben Quellen wie für DD"): the aggregator is the same @Singleton
    // the DD and the wire query — its per-source politeness caches are shared,
    // so a story pulled for the DD is never fetched twice for the evening.
    private volatile de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator newsAggregator;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.AnalystActionsSource analystActionsSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.UsListingStatsSource usListingStatsSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularitySource hedgeFundSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.InstrumentFactsSource instrumentFactsSource;
    private volatile BundYieldClient bundYieldClient;
    private volatile ApeWisdomClient apeWisdomClient;
    private volatile CoinGeckoClient coinGeckoClient;
    private volatile PolymarketClient polymarketClient;
    private volatile CboePutCallClient cboePutCallClient;
    private volatile FinraShortVolumeClient finraClient;
    private volatile NasdaqCalendarClient nasdaqCalendarClient;
    private volatile RhinePegelClient rhinePegelClient;
    private volatile TradingViewCalendarClient tradingViewClient;
    private volatile EqsEventsClient eqsEventsClient;
    private volatile EarningsWhispersClient earningsWhispersClient;
    private volatile CentralBankCalendarClient centralBankCalendarClient;
    private volatile WikipediaCurrentEventsClient wikipediaClient;
    private volatile TagesschauClient tagesschauClient;
    private volatile de.bsommerfeld.wsbg.terminal.websearch.BingWebSearchClient webSearchClient;
    private volatile CryptoDerivsClient cryptoDerivsClient;
    private volatile CuriositiesClient curiositiesClient;
    private volatile AnalystViewSource analystViewSource;
    private volatile ShortInterestSource shortInterestSource;
    private volatile InsiderDealingsSource insiderDealingsSource;
    // The fishing-net world layer (2026-07-15) — collected by the ONE shared
    // WorldSignalsCollector (the DD reads the same catch); the ECB client
    // stays injected here separately for the rates section.
    private volatile WorldSignalsCollector worldSignalsCollector;
    private volatile EcbFeedsClient ecbFeedsClient;
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
    void setMarketPressClient(MarketPressClient client) {
        this.marketPressClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setWorldWeatherClient(WorldWeatherClient client) {
        this.worldWeatherClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setGlobalHazardsClient(GlobalHazardsClient client) {
        this.globalHazardsClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setNewsAggregator(de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator aggregator) {
        this.newsAggregator = aggregator;
    }

    @com.google.inject.Inject(optional = true)
    void setAnalystActionsSource(
            de.bsommerfeld.wsbg.terminal.core.price.AnalystActionsSource source) {
        this.analystActionsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setUsListingStatsSource(
            de.bsommerfeld.wsbg.terminal.core.price.UsListingStatsSource source) {
        this.usListingStatsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setHedgeFundSource(
            de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularitySource source) {
        this.hedgeFundSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setInstrumentFactsSource(
            de.bsommerfeld.wsbg.terminal.core.price.InstrumentFactsSource source) {
        this.instrumentFactsSource = source;
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
    void setTradingViewClient(TradingViewCalendarClient client) {
        this.tradingViewClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setEqsEventsClient(EqsEventsClient client) {
        this.eqsEventsClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setEarningsWhispersClient(EarningsWhispersClient client) {
        this.earningsWhispersClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setCentralBankCalendarClient(CentralBankCalendarClient client) {
        this.centralBankCalendarClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setWikipediaClient(WikipediaCurrentEventsClient client) {
        this.wikipediaClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setTagesschauClient(TagesschauClient client) {
        this.tagesschauClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setWebSearchClient(de.bsommerfeld.wsbg.terminal.websearch.BingWebSearchClient client) {
        this.webSearchClient = client;
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
    void setWorldSignalsCollector(WorldSignalsCollector collector) {
        this.worldSignalsCollector = collector;
    }

    @com.google.inject.Inject(optional = true)
    void setEcbFeedsClient(EcbFeedsClient client) {
        this.ecbFeedsClient = client;
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
        // One TradingView fetch spans today AND tomorrow — the outcomes leg and
        // the outlook fallback read the same response.
        List<TradingViewCalendarClient.TvEvent> tvEvents =
                guarded("tv calendar", List.of(), () -> tvEvents(today, zone));
        List<EconOutcomeStat> econOutcomes =
                guarded("econ outcomes", List.of(), () -> econOutcomes(tvEvents, today, zone));
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat> outlook =
                guarded("outlook", List.of(),
                        () -> outlook(today, zone, tvEvents, isinToTicker));

        WorldStats world = new WorldStats(
                guarded("sectors", List.of(), () -> tiles(SECTORS)),
                guarded("overnight", List.of(), () -> tiles(OVERNIGHT)),
                guarded("rates", List.of(), this::rates),
                guarded("pulse", null, () -> pulse(todaysHeadlines, zone)),
                guarded("adhocs", List.of(), () -> adhocs(dayStart, isinToTicker)),
                guarded("analyst actions", List.of(), () -> {
                    // Each half is capped alone, so the union could reach 2×
                    // the cap — re-cap the MERGED list, cage-joined US actions
                    // first (they are wire-ticker-filtered by construction),
                    // and log what the cap cuts (no silent caps).
                    List<AnalystActionStat> merged = new ArrayList<>(guarded(
                            "us analyst actions", List.of(),
                            () -> usAnalystActions(wireTickers)));
                    merged.addAll(analystActions(dayStart));
                    if (merged.size() > MAX_ANALYST_ACTIONS) {
                        LOG.info("[WEATHER] analyst actions: merged {} over cap {}, "
                                        + "dropping {} FN tail line(s)",
                                merged.size(), MAX_ANALYST_ACTIONS,
                                merged.size() - MAX_ANALYST_ACTIONS);
                    }
                    return cap(merged, MAX_ANALYST_ACTIONS);
                }),
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
                guarded("dayparts", List.of(), () -> dayparts(todaysHeadlines, zone, outlook)),
                econOutcomes,
                guarded("world events", List.of(), () -> worldEvents(today)),
                guarded("event reviews", List.of(), () -> eventReviews(econOutcomes)),
                guarded("cb dates", List.of(), () -> cbDates(today)),
                guarded("top news", List.of(), () -> topNews(dayStart)),
                guarded("press review", List.of(), () -> pressReview(dayStart, zone)),
                guarded("world weather", List.of(), this::worldWeather),
                guarded("hazards", List.of(), this::hazards),
                guarded("ticker news", List.of(),
                        () -> tickerNews(tickers, todaysHeadlines, dayStart)),
                guarded("street actions", List.of(), () -> streetActions(wireTickers)),
                guarded("world signals", null, () -> worldSignals(today, zone, dayStart)));

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
     * is DEFINED as the 10y Treasury yield ×10 (a CBOE quirk), but Yahoo's
     * chart feed has been observed serving the plain yield instead
     * (live 2026-07-13: raw 4.61 → the old unconditional ÷10 froze "0,46 %"
     * into the record) — {@link #normalizeTnx} handles both encodings. The
     * Bund side comes from the Bundesbank's daily Svensson curve (T+1 — the
     * latest available fixing, dated).
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
            Double prev = Double.isFinite(s.previousClose())
                    ? normalizeTnx(s.previousClose()) : null;
            out.add(new RateStat("10J US-Treasury", normalizeTnx(s.price()), prev, null));
        }
        // The ECB's own policy anchors (SDMX, T+1) — the deposit facility rate
        // IS the euro money market's floor, €STR the overnight fixing.
        EcbFeedsClient ecb = ecbFeedsClient;
        if (ecb != null) {
            guardedRun("ecb rates", () -> {
                EcbFeedsClient.Observation dfr = ecb.depositFacilityRate();
                if (dfr != null) {
                    out.add(new RateStat("EZB-Einlagensatz", dfr.value(), null,
                            dfr.isoPeriod()));
                }
                EcbFeedsClient.Observation estr = ecb.estr();
                if (estr != null) {
                    out.add(new RateStat("€STR", estr.value(), null, estr.isoPeriod()));
                }
                // Inflation beside the yields it prices — desks read the pair.
                EcbFeedsClient.Observation hicp = ecb.hicpFlash();
                if (hicp != null) {
                    out.add(new RateStat(hicp.estimate()
                            ? "Inflation Euroraum (HICP-Flash, Schätzung)"
                            : "Inflation Euroraum (HICP)",
                            hicp.value(), null, hicp.isoPeriod()));
                }
            });
        }
        return out;
    }

    /**
     * {@code ^TNX} raw level → yield percent, whichever encoding Yahoo serves:
     * a yield×10 reading (≥ 20 — any 2%+ world reads 20+) is divided back, a
     * plain-yield reading (single digits) passes through. Package-private for
     * tests.
     */
    static double normalizeTnx(double raw) {
        return raw >= 20 ? raw / 10.0 : raw;
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
            if (("ECON".equals(o.kind()) || "CB".equals(o.kind()))
                    && "High".equalsIgnoreCase(o.impact())) {
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

    /**
     * A human-readable protagonist for the forecast tiles — never an internal
     * key: a {@code name:<norm>} unit key loses its prefix, a bare cluster id
     * (digits) yields no note at all (live-observed 2026-07-13: the evening
     * tile read "name:donald trump").
     */
    private static String subjectDisplay(HeadlineRecord r, String fallback) {
        for (HeadlineSubject s : r.subjects()) {
            if (s.name() != null && !s.name().isBlank()) return s.name();
        }
        if (fallback == null) return null;
        String f = fallback.strip();
        if (f.regionMatches(true, 0, "name:", 0, 5)) return f.substring(5).strip();
        return f.chars().allMatch(Character::isDigit) ? null : f;
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
        List<AnalystActionStat> out = new ArrayList<>();
        FnRssClient client = fnRssClient;
        if (client != null) {
            for (FnRssClient.AnalystAction a : client.analystActions(30)) {
                if (a.publishedAt() == null || a.publishedAt().isBefore(dayStart)) continue;
                out.add(new AnalystActionStat(a.title(), localTime(a.publishedAt())));
            }
        }
        return cap(out, MAX_ANALYST_ACTIONS);
    }

    /**
     * The day's US analyst actions from the KI-DD's dated table (MarketBeat's
     * daily ratings, ~490 rows), FILTERED to the papers the cage actually
     * discussed today — the house angle, not a firehose. Complements the
     * dpa-AFX titles (German houses on German/EU names) with the US street's
     * moves incl. old→new targets. Untimed rows route to their home window.
     */
    private List<AnalystActionStat> usAnalystActions(Set<String> wireTickers) {
        de.bsommerfeld.wsbg.terminal.core.price.AnalystActionsSource source = analystActionsSource;
        if (source == null || wireTickers.isEmpty()) return List.of();
        List<AnalystActionStat> out = new ArrayList<>();
        for (de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.Action a
                : source.todaysActions("US")) {
            if (a.symbol() == null
                    || !wireTickers.contains(a.symbol().toUpperCase(Locale.ROOT))) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (a.brokerage() != null) sb.append(a.brokerage()).append(": ");
            sb.append(a.companyName() == null || a.companyName().isBlank()
                    ? a.symbol() : a.companyName());
            if (a.actionType() != null) sb.append(" — ").append(a.actionType());
            if (a.ratingNew() != null && !a.ratingNew().isBlank()) {
                sb.append(" '").append(a.ratingNew()).append('\'');
            }
            if (Double.isFinite(a.targetNew())) {
                sb.append(", Ziel ");
                if (Double.isFinite(a.targetOld())) {
                    sb.append(fmtTarget(a.targetOld())).append("→");
                }
                sb.append(fmtTarget(a.targetNew()));
                if (a.targetCurrency() != null) sb.append(' ').append(a.targetCurrency());
            }
            sb.append(" [MarketBeat]");
            out.add(new AnalystActionStat(sb.toString(), null));
            if (out.size() >= MAX_ANALYST_ACTIONS) break;
        }
        return out;
    }

    private static String fmtTarget(double v) {
        return String.format(Locale.GERMANY, v == Math.rint(v) ? "%,.0f" : "%,.2f", v);
    }

    /**
     * The day's US street actions, WHOLE-table view (MarketBeat's daily
     * ratings, ~350-490 rows) — unlike {@link #usAnalystActions} (which folds
     * the cage-joined rows into the analyst TEXT lines) this freezes a
     * structured list of the SUBSTANTIVE moves for the record/UI and the
     * evening shelf: up-/downgrades and initiations always, pure target moves
     * only when both halves are present (a target-only row without the prior
     * is noise). Rows carry no time — the page is strictly today.
     */
    private List<StreetActionStat> streetActions(Set<String> wireTickers) {
        de.bsommerfeld.wsbg.terminal.core.price.AnalystActionsSource source = analystActionsSource;
        if (source == null) return List.of();
        return streetActions(source.todaysActions("US"), wireTickers);
    }

    /**
     * Filter + join + priority cap over the raw daily table. Package-private
     * for tests. Priority: cage-discussed papers first, then rating moves
     * (up-/downgrades), then the rest — stable within each tier; capped at
     * {@link #MAX_STREET_ACTIONS} with the drop logged (no silent caps).
     */
    static List<StreetActionStat> streetActions(
            List<de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.Action> rows,
            Set<String> wireTickers) {
        List<StreetActionStat> substantive = new ArrayList<>();
        int skipped = 0;
        for (de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.Action a : rows) {
            if (a.symbol() == null || a.symbol().isBlank() || !substantiveAction(a)) {
                skipped++;
                continue;
            }
            String symbol = a.symbol().toUpperCase(Locale.ROOT);
            substantive.add(new StreetActionStat(symbol,
                    a.companyName() == null || a.companyName().isBlank() ? null : a.companyName(),
                    a.actionType(), a.brokerage(), a.ratingOld(), a.ratingNew(),
                    Double.isFinite(a.targetOld()) ? a.targetOld() : null,
                    Double.isFinite(a.targetNew()) ? a.targetNew() : null,
                    a.targetCurrency(), wireTickers.contains(symbol)));
        }
        // Stable two-tier sort: the cage's papers lead, rating moves outrank
        // target moves; original (provider) order survives within a tier.
        substantive.sort(Comparator
                .comparing((StreetActionStat s) -> !s.inKaefig())
                .thenComparing(s -> !isRatingMove(s.action())));
        if (substantive.size() > MAX_STREET_ACTIONS) {
            List<StreetActionStat> dropped =
                    substantive.subList(MAX_STREET_ACTIONS, substantive.size());
            LOG.info("Wetterbericht street actions: {} substantive of {} rows"
                            + " ({} filtered), kept {}, dropped beyond cap: {}",
                    substantive.size(), rows.size(), skipped, MAX_STREET_ACTIONS,
                    dropped.stream().map(StreetActionStat::symbol).toList());
        } else if (skipped > 0) {
            LOG.debug("Wetterbericht street actions: {} substantive of {} rows"
                    + " ({} filtered as non-substantive)", substantive.size(), rows.size(),
                    skipped);
        }
        return cap(substantive, MAX_STREET_ACTIONS);
    }

    /**
     * Up-/downgrades and initiations count regardless of targets; anything
     * else (reiterations, target sets) only when BOTH target halves arrived —
     * an old→new target is a statement, a lone number is noise.
     */
    static boolean substantiveAction(
            de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.Action a) {
        String type = a.actionType() == null ? "" : a.actionType().toLowerCase(Locale.ROOT);
        if (type.contains("upgrad") || type.contains("downgrad") || type.contains("initiat")) {
            return true;
        }
        return Double.isFinite(a.targetOld()) && Double.isFinite(a.targetNew());
    }

    /** The provider's verbatim label names the rating move itself. */
    private static boolean isRatingMove(String actionType) {
        if (actionType == null) return false;
        String type = actionType.toLowerCase(Locale.ROOT);
        return type.contains("upgrad") || type.contains("downgrad");
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
        // US parity (2026-07-14, user mandate "dieselben Quellen wie für DD"):
        // top tickers WITHOUT an ISIN anchor but with a US shape get their
        // street line from the KI-DD's US legs — NASDAQ (targets, rating
        // panel, Form-4 aggregate), MarketBeat (float-short percent) and
        // Insider Monkey (13F hedge-fund count). The German ISIN path above
        // stays untouched; both together mirror the DD's Bewertung/
        // Katalysatoren shelves in one dense line each.
        for (TickerStat t : topTickers) {
            if (out.size() >= MAX_DEPTH) break;
            if (tickerToIsin.containsKey(t.ticker())) continue;
            if (!US_DEPTH_SYMBOL.matcher(t.ticker()).matches()) continue;
            DepthStat us = guarded("us depth " + t.ticker(), null, () -> usDepth(t.ticker()));
            if (us != null) out.add(us);
        }
        return out;
    }

    /** Bare US listing shapes only — suffixed/caret/future symbols never reach the US legs. */
    private static final java.util.regex.Pattern US_DEPTH_SYMBOL =
            java.util.regex.Pattern.compile("[A-Z]{1,5}(\\.[A-Z])?");

    /** One US ticker's street line from the DD's US legs; null when every leg is empty. */
    private DepthStat usDepth(String ticker) {
        de.bsommerfeld.wsbg.terminal.core.price.UsListingStatsSource usStats = usListingStatsSource;
        de.bsommerfeld.wsbg.terminal.core.price.AnalystActionsSource mb = analystActionsSource;
        de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularitySource hf = hedgeFundSource;
        Double target = null, shortPct = null;
        Integer buy = null, hold = null, sell = null;
        StringBuilder insider = new StringBuilder();
        if (usStats != null) {
            var stats = guarded("us listing " + ticker,
                    Optional.<de.bsommerfeld.wsbg.terminal.core.price.UsListingStats>empty(),
                    () -> usStats.statsFor(ticker));
            if (stats.isPresent()) {
                var s = stats.get();
                if (s.analystRatings() != null) {
                    var r = s.analystRatings();
                    if (Double.isFinite(r.meanPriceTargetUsd())) target = r.meanPriceTargetUsd();
                    if (r.buy() >= 0) {
                        buy = r.buy();
                        hold = Math.max(r.hold(), 0);
                        sell = Math.max(r.sell(), 0);
                    }
                }
                if (s.insiderActivity() != null) {
                    var ia = s.insiderActivity();
                    if (ia.buys3m() >= 0 || ia.sells3m() >= 0) {
                        insider.append("Form-4 3M: ").append(Math.max(ia.buys3m(), 0))
                                .append(" Käufe / ").append(Math.max(ia.sells3m(), 0))
                                .append(" Verkäufe");
                    }
                }
            }
        }
        if (mb != null) {
            var actions = guarded("marketbeat " + ticker,
                    Optional.<de.bsommerfeld.wsbg.terminal.core.price.AnalystActions>empty(),
                    () -> mb.actionsFor(ticker));
            if (actions.isPresent() && actions.get().shortStats() != null
                    && Double.isFinite(actions.get().shortStats().percentOfFloat())) {
                shortPct = actions.get().shortStats().percentOfFloat();
            }
            if (target == null && actions.isPresent()
                    && Double.isFinite(actions.get().consensusTarget())) {
                target = actions.get().consensusTarget();
            }
        }
        if (hf != null) {
            var pop = guarded("hedge funds " + ticker,
                    Optional.<de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularity>empty(),
                    () -> hf.popularityFor(ticker));
            if (pop.isPresent() && !pop.get().quarters().isEmpty()) {
                var latest = pop.get().quarters().get(pop.get().quarters().size() - 1);
                if (latest.funds() >= 0) {
                    if (insider.length() > 0) insider.append("; ");
                    insider.append(latest.funds()).append(" Hedgefonds (13F, ")
                            .append(latest.quarterLabel()).append(')');
                }
            }
        }
        if (target == null && buy == null && shortPct == null && insider.length() == 0) {
            return null;
        }
        // shortPct here is the US percent-of-float (MarketBeat), semantically
        // the same "disclosed shorts" slot the German register fills.
        return new DepthStat(ticker, target, target == null ? null : "USD", null,
                buy, hold, sell, null, null, shortPct, null,
                insider.length() == 0 ? null : insider.toString());
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
                    finiteOrNull(s.dayChangePercent()), finiteOrNull(s.price()), s.currency(),
                    e.tldr() == null || e.tldr().isBlank() ? null : e.tldr().strip()));
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
        Set<String> seenSubjects = new HashSet<>();
        for (DeepDiveRecord r : archive.recent(10)) {
            if (r.createdAtEpoch() < dayStart.getEpochSecond()) continue;
            String name = r.canonicalName() != null && !r.canonicalName().isBlank()
                    ? r.canonicalName() : r.subject();
            // Two runs over the same subject in one day (the live 14.07 report:
            // "Outlook Therapeutics, Inc., Outlook Therapeutics, Inc.") are one
            // house finding — the newest report speaks.
            if (!seenSubjects.add(name.toLowerCase(Locale.ROOT))) continue;
            String thesis = thesisSentence(r.report());
            out.add(thesis == null ? name : name + ": " + thesis);
        }
        return out;
    }

    /**
     * The first sentence of a deep-dive report's "These"/"Thesis" section —
     * the house's page-1 read, so the evening edition can say WHAT the desk
     * concluded today, not merely that a report exists. Null when the report
     * carries no recognizable thesis section.
     */
    static String thesisSentence(String report) {
        if (report == null || report.isBlank()) return null;
        for (String heading : List.of("## These", "## Thesis")) {
            int at = report.indexOf(heading + "\n");
            if (at < 0) continue;
            int from = at + heading.length() + 1;
            int end = report.indexOf("\n## ", from);
            String body = (end < 0 ? report.substring(from) : report.substring(from, end)).strip();
            if (body.isEmpty()) continue;
            List<String> sentences = DeepDiveFactCheck.sentences(body.split("\n")[0]);
            if (sentences.isEmpty()) continue;
            String s = sentences.get(0).strip();
            if (s.length() < 20) continue;
            return s.length() > 220 ? s.substring(0, 217) + "…" : s;
        }
        return null;
    }

    // --- outlook -------------------------------------------------------------------------

    private List<OutlookStat> outlook(LocalDate today, ZoneId zone,
            List<TradingViewCalendarClient.TvEvent> tvEvents,
            Map<String, String> isinToTicker) {
        LocalDate tomorrow = today.plusDays(1);
        List<OutlookStat> out = new ArrayList<>();
        int econ = 0;
        EconCalendarClient calendar = econCalendarClient;
        if (calendar != null) {
            for (EconCalendarClient.EconEvent e
                    : guarded("ff this week",
                            List.<EconCalendarClient.EconEvent>of(), calendar::thisWeek)) {
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
        // Only ForexFactory's this-week file still exists (_nextweek 404s since
        // mid-2026), so a Saturday evening sees an empty FF docket for tomorrow —
        // TradingView (already fetched, spans tomorrow) fills that hole.
        if (econ == 0) {
            for (TradingViewCalendarClient.TvEvent e : tvEvents) {
                if (!e.when().atZone(zone).toLocalDate().equals(tomorrow)
                        || !relevantTvEvent(e) || econ >= MAX_OUTLOOK_ECON) {
                    continue;
                }
                out.add(new OutlookStat(e.title(), e.country(), impactWord(e.importance()),
                        LocalTime.ofInstant(e.when(), zone).format(HOUR_MINUTE), "ECON"));
                econ++;
            }
        }
        // The street's numbers for tomorrow's reports (EarningsWhispers, US) —
        // joined by symbol onto the NASDAQ rows so the docket line carries the
        // revenue consensus beside NASDAQ's EPS figure.
        Map<String, EarningsWhispersClient.EarningsEstimate> estimates = new HashMap<>();
        EarningsWhispersClient whispers = earningsWhispersClient;
        if (whispers != null) {
            for (EarningsWhispersClient.EarningsEstimate est : guarded("earnings whispers",
                    List.<EarningsWhispersClient.EarningsEstimate>of(),
                    () -> whispers.estimatesOn(tomorrow))) {
                estimates.putIfAbsent(est.ticker().toUpperCase(Locale.ROOT), est);
            }
        }
        NasdaqCalendarClient nasdaq = nasdaqCalendarClient;
        if (nasdaq != null) {
            for (NasdaqCalendarClient.EarningsEntry e : guarded("nasdaq earnings",
                    List.<NasdaqCalendarClient.EarningsEntry>of(),
                    () -> nasdaq.earningsOn(tomorrow, MAX_OUTLOOK_EARNINGS))) {
                StringBuilder detail = new StringBuilder(e.symbol());
                if (e.epsForecast() != null && !e.epsForecast().isBlank()) {
                    detail.append(", erw. EPS ").append(e.epsForecast());
                }
                EarningsWhispersClient.EarningsEstimate est =
                        estimates.get(e.symbol().toUpperCase(Locale.ROOT));
                if (est != null && est.revenueEstimate() != null) {
                    detail.append(", erw. Umsatz ")
                            .append(WeatherMaterial.compact(Math.round(est.revenueEstimate())))
                            .append(" $");
                }
                out.add(new OutlookStat(e.name() == null || e.name().isBlank()
                        ? e.symbol() : e.name(), detail.toString(), null,
                        slotLabel(e.slot()), "EARNINGS"));
            }
        }
        // German corporate dates (EQS register), only where the ISIN joins a
        // paper the room actually discussed today — the register itself is 600+
        // events deep and would drown the docket.
        EqsEventsClient eqs = eqsEventsClient;
        if (eqs != null && !isinToTicker.isEmpty()) {
            int corp = 0;
            for (EqsEventsClient.CorporateEvent e : guarded("eqs events",
                    List.<EqsEventsClient.CorporateEvent>of(), eqs::upcoming)) {
                if (corp >= MAX_OUTLOOK_CORP) break;
                if (e.isin() == null
                        || !e.startDate().atZone(zone).toLocalDate().equals(tomorrow)) {
                    continue;
                }
                String kaefigTicker = isinToTicker.get(e.isin());
                if (kaefigTicker == null) continue;
                out.add(new OutlookStat(e.companyName() + ": " + e.headline(),
                        kaefigTicker, null, null, "CORP"));
                corp++;
            }
        }
        // A rate decision tomorrow outranks everything on the docket.
        CentralBankCalendarClient cb = centralBankCalendarClient;
        if (cb != null) {
            for (CentralBankCalendarClient.CbMeeting m : guarded("cb tomorrow",
                    List.<CentralBankCalendarClient.CbMeeting>of(),
                    () -> cb.upcomingDecisions(tomorrow, 1))) {
                if (!m.date().equals(tomorrow)) continue;
                out.add(new OutlookStat(m.title(), m.bank(), "High", null, "CB"));
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

    // --- the calendar's outcome side (TradingView) + world events -------------------------

    /** ONE TradingView fetch spanning today and tomorrow (local calendar days). */
    private List<TradingViewCalendarClient.TvEvent> tvEvents(LocalDate today, ZoneId zone) {
        TradingViewCalendarClient client = tradingViewClient;
        if (client == null) return List.of();
        return client.events(today.atStartOfDay(zone).toInstant(),
                today.plusDays(2).atStartOfDay(zone).toInstant());
    }

    /**
     * Today's macro releases WITH their outcome — the "wie ist es ausgegangen"
     * the forecast-only docket can't answer. Only events that actually carry
     * an actual value; relevance mirrors the docket rule (high anywhere,
     * medium for the rooms the cage trades).
     */
    private List<EconOutcomeStat> econOutcomes(List<TradingViewCalendarClient.TvEvent> tvEvents,
            LocalDate today, ZoneId zone) {
        List<EconOutcomeStat> out = new ArrayList<>();
        for (TradingViewCalendarClient.TvEvent e : tvEvents) {
            if (e.actual() == null || !relevantTvEvent(e)) continue;
            if (!e.when().atZone(zone).toLocalDate().equals(today)) continue;
            out.add(new EconOutcomeStat(e.title(), e.country(),
                    LocalTime.ofInstant(e.when(), zone).format(HOUR_MINUTE),
                    impactWord(e.importance()), e.actual(), e.forecast(), e.previous(),
                    e.unit()));
            if (out.size() >= MAX_ECON_OUTCOMES) break;
        }
        return out;
    }

    /** The docket rule on the TradingView scale: high anywhere, medium for USD/EUR rooms. */
    private static boolean relevantTvEvent(TradingViewCalendarClient.TvEvent e) {
        if (e.importance() >= 1) return true;
        return e.importance() == 0 && ("US".equals(e.country()) || "DE".equals(e.country())
                || "EU".equals(e.country()));
    }

    /** TradingView's -1/0/1 → the ForexFactory impact words the record already speaks. */
    private static String impactWord(int importance) {
        if (importance >= 1) return "High";
        return importance == 0 ? "Medium" : "Low";
    }

    /**
     * The day's world log (Wikipedia Current Events, EN — attributed): what
     * happened outside the tape, categorized with a press citation each. The
     * portal's TODAY page fills through the day; when it is still empty at
     * freeze time, yesterday's completed page carries the block.
     */
    private List<WorldEventStat> worldEvents(LocalDate today) {
        WikipediaCurrentEventsClient client = wikipediaClient;
        if (client == null) return List.of();
        List<WikipediaCurrentEventsClient.WorldEvent> events =
                client.eventsOn(today, WORLD_EVENTS_PER_CATEGORY);
        if (events.isEmpty()) {
            events = client.eventsOn(today.minusDays(1), WORLD_EVENTS_PER_CATEGORY);
        }
        List<WorldEventStat> out = new ArrayList<>();
        for (WikipediaCurrentEventsClient.WorldEvent e : events) {
            out.add(new WorldEventStat(e.category(), e.text(), e.source()));
            if (out.size() >= MAX_WORLD_EVENTS) break;
        }
        return out;
    }

    /**
     * The ARD desk's top news of the day (Tagesschau api2u, attributed press):
     * the homepage ranking leads — that IS the "Top News des Tages" — then the
     * Wirtschaft ressort tops the list up. Today's stories only, deduped by
     * title (a Wirtschaft story often sits on the homepage too).
     */
    private List<TopNewsStat> topNews(Instant dayStart) {
        TagesschauClient client = tagesschauClient;
        if (client == null) return List.of();
        Map<String, TopNewsStat> out = new LinkedHashMap<>();
        for (TagesschauClient.Article a : client.topNews(10)) {
            addTopNews(out, a, dayStart);
        }
        for (TagesschauClient.Article a : client.wirtschaft(20)) {
            if (out.size() >= MAX_TOP_NEWS) break;
            addTopNews(out, a, dayStart);
        }
        return cap(new ArrayList<>(out.values()), MAX_TOP_NEWS);
    }

    private static void addTopNews(Map<String, TopNewsStat> out, TagesschauClient.Article a,
            Instant dayStart) {
        if (a.publishedAt() == null || a.publishedAt().isBefore(dayStart)) return;
        if (a.title() == null || a.title().isBlank()) return;
        out.putIfAbsent(a.title().toLowerCase(Locale.ROOT).strip(),
                new TopNewsStat(a.topline(), a.title(), a.firstSentence(),
                        localTime(a.publishedAt()), a.ressort(), a.breaking()));
    }

    /**
     * Common calendar country/currency codes spelled out for a search query;
     * an unknown code is dropped rather than passed raw (a two-letter code
     * matches everything from CNN to a Cuxhaven local paper).
     */
    static String countryName(String code) {
        if (code == null || code.isBlank()) return null;
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "US", "USD" -> "US";
            case "CN", "CNY" -> "China";
            case "DE" -> "Germany";
            case "EU", "EUR" -> "Eurozone";
            case "GB", "GBP", "UK" -> "UK";
            case "JP", "JPY" -> "Japan";
            case "CH", "CHF" -> "Switzerland";
            case "CA", "CAD" -> "Canada";
            case "AU", "AUD" -> "Australia";
            case "NZ", "NZD" -> "New Zealand";
            case "FR" -> "France";
            case "IT" -> "Italy";
            case "ES" -> "Spain";
            default -> null;
        };
    }

    /**
     * Words a review headline must carry at least one of to count as press ON
     * the event: the event title's significant words (calendar shorthand like
     * YoY/MoM excluded) plus the spelled-out country.
     */
    static Set<String> reviewRelevanceWords(String eventTitle, String country) {
        Set<String> out = new HashSet<>();
        if (eventTitle != null) {
            for (String w : eventTitle.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (w.length() >= 4 && !"rate".equals(w)) out.add(w);
            }
        }
        if (country != null) out.add(country.toLowerCase(Locale.ROOT));
        return out;
    }

    /** True when the headline names the event (or its country) at all. */
    static boolean reviewTitleRelevant(String title, Set<String> eventWords) {
        if (eventWords.isEmpty()) return true;
        String t = title.toLowerCase(Locale.ROOT);
        for (String w : eventWords) {
            if (t.contains(w)) return true;
        }
        return false;
    }

    /**
     * The general market press review (CNBC/MarketWatch/WSJ/Investing +
     * n-tv/Spiegel/Handelsblatt/WiWo): the day's timed press headlines, so a
     * sector or index move can be attached to its reported cause inside the
     * right day-part window — and so the report carries a day even when the
     * cage was silent (the Wetterbericht's Reddit-independence leg).
     */
    private List<PressReviewStat> pressReview(Instant dayStart, ZoneId zone) {
        MarketPressClient client = marketPressClient;
        if (client == null) return List.of();
        List<PressReviewStat> out = new ArrayList<>();
        for (MarketPressClient.PressHeadline h : client.headlinesSince(dayStart, MAX_PRESS_REVIEW)) {
            out.add(new PressReviewStat(h.title(), h.teaser(), h.source(), h.category(),
                    localTime(h.publishedAt()), h.link()));
        }
        return out;
    }

    /**
     * Fresh press on the day's TOP tickers through the house's FULL news
     * triangulation — the same 7-source aggregator (Yahoo, wallstreet-online,
     * Google News, Fool, PR Newswire, finanznachrichten, Nasdaq RSS) the KI-DD
     * and the wire read, same singleton, same caches (a story pulled for the
     * DD is never fetched twice). Today-only, timestamped items route into
     * their day-part window.
     */
    private List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerNewsStat> tickerNews(
            List<TickerStat> topTickers, List<HeadlineRecord> headlines, Instant dayStart) {
        de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator aggregator = newsAggregator;
        if (aggregator == null) return List.of();
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerNewsStat> out =
                new ArrayList<>();
        Set<String> seenTitles = new HashSet<>();
        for (TickerStat t : topTickers) {
            String isin = isinForTicker(headlines, t.ticker());
            List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> items =
                    guarded("ticker news " + t.ticker(), List.of(),
                            () -> aggregator.newsFor(t.ticker(), t.name(), isin,
                                    MAX_TICKER_NEWS_PER_TICKER * 2));
            int taken = 0;
            for (var item : items) {
                if (item.publishedAt() == null || item.publishedAt().isBefore(dayStart)) continue;
                if (item.title() == null || item.title().isBlank()) continue;
                if (!seenTitles.add(item.title().toLowerCase(Locale.ROOT).strip())) continue;
                out.add(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerNewsStat(
                        t.ticker(), item.title(),
                        item.publisher() == null || item.publisher().isBlank()
                                ? null : item.publisher(),
                        localTime(item.publishedAt())));
                if (++taken >= MAX_TICKER_NEWS_PER_TICKER) break;
            }
            // The GENERAL web sweep per highlighted instrument (the KI-DD's
            // Bing leg, '<Name> News'): what the feed-shaped sources miss —
            // blog posts, regional outlets, direct article URLs. Bing's
            // pubDate is the crawl date and deliberately null, so these ride
            // UNTIMED (home window: the evening wrap-up).
            de.bsommerfeld.wsbg.terminal.websearch.BingWebSearchClient web = webSearchClient;
            if (web != null && t.name() != null && !t.name().isBlank()
                    && !t.name().equalsIgnoreCase(t.ticker())) {
                for (var item : guarded("web news " + t.ticker(),
                        List.<de.bsommerfeld.wsbg.terminal.source.RawNewsItem>of(),
                        () -> web.newsForName(t.name(), MAX_TICKER_WEB_NEWS))) {
                    if (item.title() == null || item.title().isBlank()) continue;
                    if (!seenTitles.add(item.title().toLowerCase(Locale.ROOT).strip())) continue;
                    out.add(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerNewsStat(
                            t.ticker(), item.title(),
                            item.publisher() == null || item.publisher().isBlank()
                                    ? null : item.publisher(),
                            null));
                }
            }
        }
        return out;
    }

    /** The literal sky over the market-relevant places (Open-Meteo, one call). */
    private List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PlaceWeatherStat> worldWeather() {
        WorldWeatherClient client = worldWeatherClient;
        if (client == null) return List.of();
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PlaceWeatherStat> out =
                new ArrayList<>();
        for (WorldWeatherClient.PlaceWeather w : client.worldWeather()) {
            out.add(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PlaceWeatherStat(
                    w.place(), w.role(), w.tempC(), w.word(), w.windKmh(),
                    w.tomorrowMaxC(), w.tomorrowMinC(), w.tomorrowWord(),
                    w.lat(), w.lon()));
        }
        return out;
    }

    /** Tropical storms, significant quakes, US aviation disruptions (NHC/USGS/FAA). */
    private List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HazardStat> hazards() {
        GlobalHazardsClient client = globalHazardsClient;
        if (client == null) return List.of();
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HazardStat> out =
                new ArrayList<>();
        for (GlobalHazardsClient.Hazard h : client.hazards()) {
            Double lat = h.lat();
            Double lon = h.lon();
            if (lat == null && "AVIATION".equals(h.kind())) {
                // FAA lines lead with the airport code ("EWR: Ground Stop …")
                // — geocode it from the airport fact table.
                int colon = h.text() == null ? -1 : h.text().indexOf(':');
                double[] pos = colon > 0 ? WorldGeo.airport(h.text().substring(0, colon)) : null;
                if (pos != null) {
                    lat = pos[0];
                    lon = pos[1];
                }
            }
            out.add(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HazardStat(
                    h.kind(), h.text(), h.severity(), lat, lon));
            if (out.size() >= MAX_HAZARDS) break;
        }
        return out;
    }

    /**
     * The Ereignis-Nachlese loop: for the day's top released numbers, ask the
     * web how the press read them — headline titles only, attributed, never a
     * conclusion of our own. High-impact outcomes first; two events, one Bing
     * query each (the 15-min client cache absorbs re-runs).
     */
    private List<EventReviewStat> eventReviews(List<EconOutcomeStat> outcomes) {
        de.bsommerfeld.wsbg.terminal.websearch.BingWebSearchClient search = webSearchClient;
        if (search == null || outcomes.isEmpty()) return List.of();
        List<EconOutcomeStat> ranked = new ArrayList<>(outcomes);
        ranked.sort(Comparator.comparing((EconOutcomeStat o) -> !"High".equals(o.impact())));
        List<EventReviewStat> out = new ArrayList<>();
        for (EconOutcomeStat o : ranked) {
            if (out.size() >= MAX_EVENT_REVIEWS) break;
            // The country code spelled OUT — the raw code poisoned the query
            // (live 14.07: "CN Imports YoY" matched the CNN homepage and the
            // Cuxhavener Nachrichten), and the search engine knows "China".
            String country = countryName(o.country());
            String query = (country == null ? "" : country + " ") + o.title();
            Set<String> eventWords = reviewRelevanceWords(o.title(), country);
            List<String> headlines = new ArrayList<>();
            for (var item : guarded("event review " + o.title(),
                    List.<de.bsommerfeld.wsbg.terminal.source.RawNewsItem>of(),
                    () -> search.newsForName(query, REVIEW_HEADLINES * 2))) {
                if (item.title() == null || item.title().isBlank()) continue;
                if (!reviewTitleRelevant(item.title(), eventWords)) continue;
                headlines.add(item.publisher() == null || item.publisher().isBlank()
                        ? item.title() : item.title() + " [" + item.publisher() + "]");
                if (headlines.size() >= REVIEW_HEADLINES) break;
            }
            if (!headlines.isEmpty()) {
                out.add(new EventReviewStat(o.title() + " (" + o.country() + ")", headlines));
            }
        }
        return out;
    }

    /** The next rate decisions (EZB + Fed) inside the lookahead — the Ausblick's hard anchors. */
    private List<CbDateStat> cbDates(LocalDate today) {
        CentralBankCalendarClient client = centralBankCalendarClient;
        if (client == null) return List.of();
        List<CbDateStat> out = new ArrayList<>();
        for (CentralBankCalendarClient.CbMeeting m : client.upcomingDecisions(today, 1)) {
            if (m.date().isAfter(today.plusDays(CB_LOOKAHEAD_DAYS))) continue;
            out.add(new CbDateStat(m.bank(), m.title(), m.date().toString()));
        }
        return out;
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
            List<TickerStat> result = new ArrayList<>(top.size());
            for (Ranked r : top) {
                result.add(enrichWithSector(enrichWithVenueStats(
                        refreshYahooShapedQuote(r.stat()), r.snap()), r.snap()));
            }
            return result;
        } catch (Exception e) {
            LOG.warn("Wetterbericht ticker stats failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Refreshes a Yahoo-shaped symbol's quote (index {@code ^…}, future
     * {@code =F}, FX {@code =X}, crypto {@code -USD}) at freeze time — the
     * frozen wire snapshot can be hours old (the live 14.07 report showed
     * "Gold −0,0 %" beside the market tile's "+1,59 %" because the wire's
     * last Gold line was written at 03:00), and these shapes are exactly the
     * ones the market tiles already re-quote via Yahoo, so the two views must
     * agree. Equities deliberately keep the wire's own frozen quote (their
     * price source is L&S, not reachable from here). Best-effort.
     */
    private TickerStat refreshYahooShapedQuote(TickerStat stat) {
        String sym = stat.ticker();
        boolean yahooShaped = sym != null && (sym.startsWith("^")
                || sym.endsWith("=F") || sym.endsWith("=X") || sym.endsWith("-USD"));
        if (!yahooShaped) return stat;
        try {
            Optional<MarketSnapshot> snap = yahoo.fetchChart(sym);
            if (snap.isEmpty() || !snap.get().hasPrice()) return stat;
            MarketSnapshot s = snap.get();
            return new TickerStat(stat.ticker(), stat.name(), stat.headlineCount(),
                    stat.importantCount(), finiteOrNull(s.price()),
                    s.currency() == null || s.currency().isBlank() ? stat.currency() : s.currency(),
                    finiteOrNull(s.dayChangePercent()),
                    s.volume() < 0 ? stat.volume() : s.volume(), stat.turnoverEur());
        } catch (Exception e) {
            LOG.debug("Wetterbericht quote refresh for {} failed: {}", sym, e.getMessage());
            return stat;
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

    /**
     * Tags a top ticker with its SECTOR/BRANCH (onvista, the KI-DD's
     * instrument-facts leg — session-cached, ISIN-addressed): the bridge
     * between the day's instruments and the sector table, so the report can
     * say deterministically WHERE in the rotation the room's papers sit.
     * ISIN-anchored papers only; a fund/crypto/index keeps null.
     */
    private TickerStat enrichWithSector(TickerStat stat, MarketSnapshot snap) {
        de.bsommerfeld.wsbg.terminal.core.price.InstrumentFactsSource facts =
                instrumentFactsSource;
        if (facts == null || snap == null || !looksLikeIsin(snap.symbol())
                || stat.sector() != null) {
            return stat;
        }
        try {
            var f = facts.factsByIsin(snap.symbol());
            if (f.isEmpty()) return stat;
            String sector = f.get().sector();
            String branch = f.get().branch();
            String tag = sector == null || sector.isBlank() ? branch
                    : (branch == null || branch.isBlank() || branch.equalsIgnoreCase(sector)
                            ? sector : sector + "/" + branch);
            if (tag == null || tag.isBlank()) return stat;
            return new TickerStat(stat.ticker(), stat.name(), stat.headlineCount(),
                    stat.importantCount(), stat.price(), stat.currency(),
                    stat.changePercent(), stat.volume(), stat.turnoverEur(), tag);
        } catch (Exception e) {
            LOG.debug("Wetterbericht sector tag for {} failed: {}", stat.ticker(), e.getMessage());
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

    /**
     * Freezes the fishing-net world layer beside the day — delegated to the
     * ONE shared {@link WorldSignalsCollector} (the KI-DD reads the same
     * catch). Which of these signals reaches the PROSE is decided by the
     * report service's AI relevance triage; this only ingests.
     */
    private de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldSignals worldSignals(
            LocalDate today, ZoneId zone, Instant dayStart) {
        WorldSignalsCollector collector = worldSignalsCollector;
        return collector == null ? null : collector.collect(today, zone, dayStart);
    }


    /** {@link #guarded} for side-effecting sub-legs without a return value. */
    private void guardedRun(String what, Runnable leg) {
        guarded(what, null, () -> {
            leg.run();
            return null;
        });
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
