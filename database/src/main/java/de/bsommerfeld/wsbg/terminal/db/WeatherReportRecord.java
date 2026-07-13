package de.bsommerfeld.wsbg.terminal.db;

import java.util.List;

/**
 * One archived daily Wetterbericht: the AI-written day report plus the market
 * stats frozen at generation time. The stats are captured WITH the report on
 * purpose — a report re-opened days later must show the day it describes, not
 * whatever the market does at viewing time.
 *
 * <p>Plain record, no Jackson annotations — round-trips through the vanilla
 * {@code ObjectMapper} by component name, exactly like {@link HeadlineRecord}.
 * Every field added after v1 is nullable / defaults empty, so pre-existing
 * archive lines keep loading (Jackson maps by name; absent = null).
 *
 * <p>{@code world} is the Abendausgabe extension (2026-07-13): everything the
 * evening collect gathered beyond the original four sections — sectors,
 * overnight, rates, the room pulse, ad-hocs, macro, movers, social, crypto,
 * bets, depth, outlook and the colour (Rhine gauge, US debt, exchange weather,
 * moon). Null on old lines and when the collect was skipped entirely.
 */
public record WeatherReportRecord(
        String date,
        long generatedAt,
        String text,
        String language,
        int headlineCount,
        int importantCount,
        List<IndexStat> indices,
        List<TickerStat> tickers,
        List<NewsStat> news,
        SentimentStat sentiment,
        WorldStats world,
        List<ChartStat> charts) {

    public WeatherReportRecord {
        indices = indices == null ? List.of() : List.copyOf(indices);
        tickers = tickers == null ? List.of() : List.copyOf(tickers);
        news = news == null ? List.of() : List.copyOf(news);
        charts = charts == null ? List.of() : List.copyOf(charts);
    }

    /** Pre-world shape (v1 call sites / tests): everything beyond the four sections absent. */
    public WeatherReportRecord(String date, long generatedAt, String text, String language,
            int headlineCount, int importantCount, List<IndexStat> indices,
            List<TickerStat> tickers, List<NewsStat> news, SentimentStat sentiment) {
        this(date, generatedAt, text, language, headlineCount, importantCount,
                indices, tickers, news, sentiment, null, null);
    }

    /** Pre-chart Abendausgabe shape (2026-07-13 intra-day) — kept for tests. */
    public WeatherReportRecord(String date, long generatedAt, String text, String language,
            int headlineCount, int importantCount, List<IndexStat> indices,
            List<TickerStat> tickers, List<NewsStat> news, SentimentStat sentiment,
            WorldStats world) {
        this(date, generatedAt, text, language, headlineCount, importantCount,
                indices, tickers, news, sentiment, world, null);
    }

    /**
     * One report figure: server-rendered SVG built deterministically from the
     * frozen stats at generation time (the DeepDive {@code ChartFigure}
     * pattern), anchored to the report's `## ` section by 0-based ordinal so
     * the page interleaves prose and figures. Frozen with the record — an old
     * edition always shows its own pictures.
     */
    public record ChartStat(int section, String title, String note, String svg) {
    }

    /**
     * The broad-market mood frozen with the day (CNN Fear &amp; Greed): composite
     * score 0-100, the band name ({@code EXTREME_FEAR} … {@code EXTREME_GREED} —
     * the stable enum token, localized by the UI), and yesterday's score for the
     * delta. {@code components} are CNN's seven sub-indicators (why the gauge
     * reads what it reads); {@code cryptoScore}/{@code cryptoBand} the
     * alternative.me crypto gauge beside it. All nullable/empty on old lines.
     */
    public record SentimentStat(
            Integer score,
            String band,
            Integer previousClose,
            List<SentimentComponent> components,
            Integer cryptoScore,
            String cryptoBand) {

        public SentimentStat {
            components = components == null ? List.of() : List.copyOf(components);
        }

        /** Pre-components shape (v1 call sites / tests). */
        public SentimentStat(Integer score, String band, Integer previousClose) {
            this(score, band, previousClose, null, null, null);
        }
    }

    /** One CNN sub-indicator: the stable key plus its 0-100 score. */
    public record SentimentComponent(String key, int score) {
    }

    /**
     * A broad market at day's end: last level, day move, day volume, intraday spark.
     * {@code currency} says how {@code last} reads: {@code "PTS"} (or {@code null},
     * the pre-currency archive lines) = index points, {@code "FX"} = an exchange
     * rate, {@code "PCT"} = a percentage level (yields), else an ISO currency
     * ({@code "USD"} for the commodity/crypto tiles). Also reused for the sector
     * and overnight tiles inside {@link WorldStats}.
     */
    public record IndexStat(
            String name,
            String symbol,
            Double last,
            Double changePercent,
            Long volume,
            String currency,
            List<Double> spark) {

        public IndexStat {
            spark = spark == null ? List.of() : List.copyOf(spark);
        }
    }

    /**
     * One of the day's most-discussed instruments on the wire, with its frozen
     * quote. {@code volume} (shares) and {@code turnoverEur} are best-effort:
     * from the archived snapshot where it carried one, else from the Tradegate
     * venue stats at generation time (German-listed papers only); null when
     * neither source knows.
     */
    public record TickerStat(
            String ticker,
            String name,
            int headlineCount,
            int importantCount,
            Double price,
            String currency,
            Double changePercent,
            Long volume,
            Long turnoverEur) {
    }

    /** A news item the day's headlines actually leaned on, ranked by citations. */
    public record NewsStat(
            String title,
            String source,
            int citations) {
    }

    /**
     * The Abendausgabe's world block — every additional leg the evening collect
     * froze beside the original four sections. Each list defaults empty, each
     * scalar nullable; a failed leg is simply absent (best-effort per leg, the
     * collect never blocks the report).
     */
    public record WorldStats(
            List<IndexStat> sectors,
            List<IndexStat> overnight,
            List<RateStat> rates,
            RoomPulse pulse,
            List<AdhocStat> adhocs,
            List<AnalystActionStat> analystActions,
            List<MacroStat> macroActuals,
            List<MacroStat> macroEvents,
            String pressDigest,
            List<MoverStat> movers,
            PutCallStat putCall,
            List<SocialStat> social,
            CryptoStat crypto,
            List<BetStat> bets,
            List<ShortVolStat> shortVolume,
            List<DepthStat> depth,
            List<WatchlistStat> watchlist,
            List<String> deepDives,
            List<OutlookStat> outlook,
            PegelStat pegel,
            Double usDebtUsd,
            ExchangeWeatherStat exchangeWeather,
            MoonStat moon,
            List<DaypartStat> dayparts) {

        public WorldStats {
            sectors = sectors == null ? List.of() : List.copyOf(sectors);
            overnight = overnight == null ? List.of() : List.copyOf(overnight);
            rates = rates == null ? List.of() : List.copyOf(rates);
            adhocs = adhocs == null ? List.of() : List.copyOf(adhocs);
            analystActions = analystActions == null ? List.of() : List.copyOf(analystActions);
            macroActuals = macroActuals == null ? List.of() : List.copyOf(macroActuals);
            macroEvents = macroEvents == null ? List.of() : List.copyOf(macroEvents);
            movers = movers == null ? List.of() : List.copyOf(movers);
            social = social == null ? List.of() : List.copyOf(social);
            bets = bets == null ? List.of() : List.copyOf(bets);
            shortVolume = shortVolume == null ? List.of() : List.copyOf(shortVolume);
            depth = depth == null ? List.of() : List.copyOf(depth);
            watchlist = watchlist == null ? List.of() : List.copyOf(watchlist);
            deepDives = deepDives == null ? List.of() : List.copyOf(deepDives);
            outlook = outlook == null ? List.of() : List.copyOf(outlook);
            dayparts = dayparts == null ? List.of() : List.copyOf(dayparts);
        }
    }

    /**
     * One forecast-strip tile — the day told like an actual weather report:
     * {@code key} MORNING / MIDDAY / EVENING for the elapsed day parts (mood
     * computed deterministically from that window's headlines) and TOMORROW
     * for the docket-based warning tile. {@code icon} is a stable token the
     * UI draws (SUNNY / PARTLY / CLOUDY / RAIN / STORM / FOG); {@code note}
     * the window's protagonist (top subject) or tomorrow's headline event.
     */
    public record DaypartStat(String key, String icon, int lines, int bullish, int bearish,
            int red, String note) {
    }

    /** One yield line ("10J Bund"), level in percent plus the prior observation. */
    public record RateStat(String name, Double percent, Double previousPercent, String dateIso) {
    }

    /**
     * The cage's own day, aggregated deterministically from the archived
     * headlines: sentiment split (bullish-leaning / bearish-leaning / the rest),
     * red-flag count, the busiest hour (local, null when no headlines), and
     * how many distinct subjects spoke.
     */
    public record RoomPulse(int bullish, int bearish, int neutral, int redCount,
            Integer busiestHour, int distinctSubjects) {
    }

    /**
     * One EQS ad-hoc disclosure of the day; {@code kaefigTicker} is set when the
     * ISIN joined against a subject the wire actually discussed — an ad-hoc on a
     * cage paper is a lead, not a margin note.
     */
    public record AdhocStat(String title, String isin, String time, String kaefigTicker) {
    }

    /** One analyst action of the day — house, name and rating live in the title. */
    public record AnalystActionStat(String title, String time) {
    }

    /**
     * One macro line. For actuals (Destatis/ifo) the figure sits in the title
     * and impact/forecast/previous stay null; for calendar events (ForexFactory)
     * {@code source} is the currency and the three extras carry the docket.
     */
    public record MacroStat(String title, String source, String time, String impact,
            String forecast, String previous) {
    }

    /** One US mover; {@code kind} = GAINER / LOSER / ACTIVE, {@code inKaefig} = the wire knew it. */
    public record MoverStat(String symbol, String name, Double changePercent, Double price,
            String kind, boolean inKaefig) {
    }

    /** CBOE's day-end put/call ratios (the crowd-positioning gauge). */
    public record PutCallStat(Double total, Double equity, Double index, String dateIso) {
    }

    /** One ticker from the neighbour cages (ApeWisdom): today's rank + the 24h climb. */
    public record SocialStat(String ticker, String name, int mentions, int rank,
            Integer rankClimb) {
    }

    /** The crypto day at a glance: market-wide plus the derivatives temperature. */
    public record CryptoStat(Double marketCapUsd, Double mcapChangePercent, Double btcDominance,
            Integer fearGreedScore, String fearGreedBand, Double fundingRatePercent,
            Double dvol, List<TrendingCoin> trending) {

        public CryptoStat {
            trending = trending == null ? List.of() : List.copyOf(trending);
        }
    }

    /** One trending coin the crowd chases. */
    public record TrendingCoin(String name, String symbol, Double changePercent) {
    }

    /** One prediction-market line: real-money odds on a question of the day. */
    public record BetStat(String question, String outcome, Double probabilityPercent,
            Double volume24hUsd) {
    }

    /** FINRA short-volume ratio of a cage-discussed US ticker on {@code dateIso}. */
    public record ShortVolStat(String symbol, Double shortPercent, String dateIso) {
    }

    /**
     * Street depth for one top ticker, frozen at report time: consensus target
     * with implied move, the rating split, the next corporate event, disclosed
     * shorts and the day's insider note. Every field best-effort nullable.
     */
    public record DepthStat(String ticker, Double targetPrice, String targetCurrency,
            Double upsidePercent, Integer buy, Integer hold, Integer sell,
            String nextEventTitle, String nextEventDate, Double shortPercent,
            String topShortHolder, String insiderNote) {
    }

    /** One watchlist entry's day, frozen beside the report. */
    public record WatchlistStat(String name, String ticker, Double changePercent,
            Double price, String currency) {
    }

    /**
     * One line of tomorrow's docket; {@code kind} = ECON / EARNINGS. For
     * earnings {@code detail} carries the EPS forecast, for macro the currency.
     */
    public record OutlookStat(String title, String detail, String impact, String time,
            String kind) {
    }

    /** The Rhine gauge at Kaub: level in cm plus PEGELONLINE's own state token. */
    public record PegelStat(Double centimeters, String state) {
    }

    /** The literal weather over the Frankfurt exchange at report time. */
    public record ExchangeWeatherStat(Double temperatureCelsius, String icon) {
    }

    /** The moon, because zum Mond: phase token, illumination, days to full. */
    public record MoonStat(String phase, Integer illuminationPercent, Integer daysToFull) {
    }
}
