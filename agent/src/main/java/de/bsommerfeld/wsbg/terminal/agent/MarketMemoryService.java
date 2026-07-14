package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.briefing.FnRssClient;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActions;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActionsSource;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStatsSource;
import de.bsommerfeld.wsbg.terminal.core.util.JitteredScheduler;
import de.bsommerfeld.wsbg.terminal.db.AdhocEventArchive;
import de.bsommerfeld.wsbg.terminal.db.AdhocEventRecord;
import de.bsommerfeld.wsbg.terminal.db.FearGreedDayRecord;
import de.bsommerfeld.wsbg.terminal.db.FearGreedHistoryArchive;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.MarketEventArchive;
import de.bsommerfeld.wsbg.terminal.db.MarketEventRecord;
import de.bsommerfeld.wsbg.terminal.edgar.EdgarClient;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.Bar;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The market memory's collection + enrichment loops (user mandates 2026-07-14,
 * docs/recherche-markt-gedaechtnis-2026-07-14.md): everything here is
 * DETERMINISTIC data work — harvest dated events, stamp them with the
 * sentiment regime of the day before, measure the market-adjusted reaction
 * once the window has settled. No model computes anything; the ONE model
 * touchpoint is the {@link AdhocClassifier}'s discrete enum choice over
 * German ad-hoc titles (which have no official category, unlike 8-K items).
 *
 * <p>Harvest legs on one jittered daemon scheduler:
 * <ul>
 *   <li><b>Ad-hoc register</b> — every EQS disclosure the FN feed ships,
 *       frozen forward (no German EDGAR exists; rückwärts unbeschaffbar).</li>
 *   <li><b>Fear&amp;Greed history</b> — CNN's full daily series back-fills in
 *       one fetch (floor 2020-09-21), then tops up; the regime axis.</li>
 *   <li><b>Analyst actions</b> — the day's US up-/downgrades/initiations
 *       (the dated action classes with the strongest literature priors).</li>
 *   <li><b>Per-ticker US legs</b> — a slow rotation over the wire's recent US
 *       names: NASDAQ earnings-surprise history (beat/miss with sign) and
 *       EDGAR 8-K items (the official event taxonomy, back to ~2015 via
 *       {@code filings.recent}).</li>
 *   <li><b>Ad-hoc classification</b> — small batches through the enum judge;
 *       no verdict after {@value #MAX_CLASSIFY_ATTEMPTS} tries or SONSTIGES
 *       → processed without a register entry (a wrong class poisons the
 *       statistics, a lost marginal event costs nothing).</li>
 *   <li><b>Enrichment sweep</b> — pending events past the settle cutoff get
 *       their {@link EventStudy} CARs (Yahoo daily bars vs index benchmark),
 *       the t−1 regime stamp and the confounded flag, persisted via the
 *       archive's atomic {@code enrich}.</li>
 * </ul>
 *
 * <p>Started by {@code AppMain} after the window brought CEF up (fetch chain
 * = browser joker); every collaborator is optional so unit tests and
 * stripped-down injectors run without them.
 */
@Singleton
public class MarketMemoryService {

    private static final Logger LOG = LoggerFactory.getLogger(MarketMemoryService.class);

    /** The hard floor of CNN's historical series (earlier dates answer HTTP 500). */
    static final LocalDate FEAR_GREED_FLOOR = LocalDate.of(2020, 9, 21);

    /** First ad-hoc harvest waits out boot so the fetch chain is settled. */
    private static final long ADHOC_INITIAL_DELAY_SECONDS = 45;
    /** The FN ad-hoc feed carries ~a day of items — 30 min never misses one. */
    private static final long ADHOC_INTERVAL_SECONDS = 1800;
    private static final int ADHOC_FETCH_LIMIT = 100;

    private static final long FEAR_GREED_INITIAL_DELAY_SECONDS = 60;
    /** Top-up cadence; the fetch itself is skipped while the archive is current. */
    private static final long FEAR_GREED_INTERVAL_SECONDS = 6 * 3600;

    private static final long ACTIONS_INITIAL_DELAY_SECONDS = 120;
    /** The daily ratings table changes once per session — 6 h catches it politely. */
    private static final long ACTIONS_INTERVAL_SECONDS = 6 * 3600;

    private static final long US_LEGS_INITIAL_DELAY_SECONDS = 180;
    /** Slow rotation: one US ticker per tick through surprises + 8-K history. */
    private static final long US_LEGS_INTERVAL_SECONDS = 1200;
    /** The rotation universe: distinct US names off the wire's recent output. */
    private static final int UNIVERSE_CAP = 60;
    private static final Duration UNIVERSE_WINDOW = Duration.ofDays(14);

    private static final long CLASSIFY_INITIAL_DELAY_SECONDS = 240;
    private static final long CLASSIFY_INTERVAL_SECONDS = 1800;
    static final int CLASSIFY_BATCH = 8;
    static final int MAX_CLASSIFY_ATTEMPTS = 3;

    private static final long MACRO_INITIAL_DELAY_SECONDS = 360;
    /** High-impact actuals land a handful of times per day — 2 h catches all. */
    private static final long MACRO_INTERVAL_SECONDS = 2 * 3600;
    /** Calendar look-back per sweep; idempotent appends make overlap free. */
    private static final int MACRO_LOOKBACK_DAYS = 3;
    static final int MACRO_CLASSIFY_BATCH = 8;

    private static final long ENRICH_INITIAL_DELAY_SECONDS = 300;
    private static final long ENRICH_INTERVAL_SECONDS = 3600;
    /** CAR(0,+5) needs t+5 TRADING days — 10 calendar days settles any week. */
    static final int SETTLE_CALENDAR_DAYS = 10;
    /**
     * Events measured per hourly sweep. Bar fetches are grouped per symbol, so
     * the Yahoo cost stays around one request per distinct name — the batch
     * paces the EDGAR backfill (hundreds of events) through in ~1-2 days
     * without ever bursting.
     */
    private static final int ENRICH_BATCH = 24;
    /** Regime stamp: walk back from t−1 over weekends/holidays at most this far. */
    private static final int REGIME_LOOKBACK_DAYS = 5;
    /** Two events of one instrument within ±2 days measure a cocktail. */
    static final int CONFOUND_WINDOW_DAYS = 2;

    private static final Pattern US_SYMBOL = Pattern.compile("[A-Z]{1,5}(\\.[A-Z])?");
    /** XETRA close; a German disclosure after this lands on the next session. */
    private static final LocalTime GERMAN_CLOSE = LocalTime.of(17, 35);
    private static final ZoneId GERMAN_ZONE = ZoneId.of("Europe/Berlin");

    private final AdhocEventArchive adhocArchive;
    private final FearGreedHistoryArchive fearGreedArchive;
    private final MarketEventArchive eventArchive;
    private final double pollJitterPercent;

    private volatile FnRssClient fnRssClient;
    private volatile FearGreedClient fearGreedClient;
    private volatile AnalystActionsSource analystActionsSource;
    private volatile UsListingStatsSource usListingStatsSource;
    private volatile EdgarClient edgarClient;
    private volatile YahooFinanceClient yahooClient;
    private volatile HeadlineArchive headlineArchive;
    private volatile AdhocClassifier adhocClassifier;
    private volatile de.bsommerfeld.wsbg.terminal.briefing.TradingViewCalendarClient tvCalendar;
    private volatile MacroClassifier macroClassifier;

    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService scheduler;

    /** Rotation cursor over the US universe (in-memory; restarts just re-walk). */
    private int universeCursor;
    /** Ad-hoc identities already classified/registered this process + attempts. */
    private final Set<String> processedAdhocs = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> classifyAttempts = new ConcurrentHashMap<>();
    /**
     * Enrichment failure memory (in-process): an event whose reaction can't be
     * computed (delisted symbol, series too short, unparseable date) is
     * counted and, after {@value #MAX_ENRICH_ATTEMPTS} attempts, excluded from
     * the pending queue — otherwise a dozen unmeasurable events would livelock
     * the whole sweep forever. A restart grants one fresh round of attempts.
     */
    static final int MAX_ENRICH_ATTEMPTS = 3;
    private final Map<String, Integer> enrichAttempts = new ConcurrentHashMap<>();
    private final Set<String> enrichGivenUp = ConcurrentHashMap.newKeySet();

    @Inject
    public MarketMemoryService(AdhocEventArchive adhocArchive,
            FearGreedHistoryArchive fearGreedArchive, MarketEventArchive eventArchive,
            GlobalConfig config) {
        this.adhocArchive = adhocArchive;
        this.fearGreedArchive = fearGreedArchive;
        this.eventArchive = eventArchive;
        this.pollJitterPercent = config == null || config.getNet() == null
                ? new de.bsommerfeld.wsbg.terminal.core.config.NetConfig().getPollJitterPercent()
                : config.getNet().getPollJitterPercent();
        seedProcessedAdhocs();
    }

    /** EQS events already registered mark their ad-hoc as processed across restarts. */
    private void seedProcessedAdhocs() {
        for (MarketEventRecord r : eventArchive.all()) {
            if ("EQS".equals(r.source()) && r.detail() != null) {
                processedAdhocs.add(r.detail());
            }
        }
    }

    @com.google.inject.Inject(optional = true)
    void setFnRssClient(FnRssClient client) {
        this.fnRssClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setFearGreedClient(FearGreedClient client) {
        this.fearGreedClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setAnalystActionsSource(AnalystActionsSource source) {
        this.analystActionsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setUsListingStatsSource(UsListingStatsSource source) {
        this.usListingStatsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setEdgarClient(EdgarClient client) {
        this.edgarClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setYahooClient(YahooFinanceClient client) {
        this.yahooClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setHeadlineArchive(HeadlineArchive archive) {
        this.headlineArchive = archive;
    }

    @com.google.inject.Inject(optional = true)
    void setAdhocClassifier(AdhocClassifier classifier) {
        this.adhocClassifier = classifier;
    }

    @com.google.inject.Inject(optional = true)
    void setTradingViewCalendarClient(
            de.bsommerfeld.wsbg.terminal.briefing.TradingViewCalendarClient client) {
        this.tvCalendar = client;
    }

    @com.google.inject.Inject(optional = true)
    void setMacroClassifier(MacroClassifier classifier) {
        this.macroClassifier = classifier;
    }

    /** Arms all harvest loops. Idempotent. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-memory");
            t.setDaemon(true);
            return t;
        });
        LOG.info("Market memory armed (ad-hocs {} min, F&G {} h, actions {} h, US legs {} min, "
                        + "classify {} min, enrich {} min; register: {} events).",
                ADHOC_INTERVAL_SECONDS / 60, FEAR_GREED_INTERVAL_SECONDS / 3600,
                ACTIONS_INTERVAL_SECONDS / 3600, US_LEGS_INTERVAL_SECONDS / 60,
                CLASSIFY_INTERVAL_SECONDS / 60, ENRICH_INTERVAL_SECONDS / 60,
                eventArchive.size());
        schedule(this::harvestAdhocs, ADHOC_INITIAL_DELAY_SECONDS, ADHOC_INTERVAL_SECONDS);
        schedule(this::topUpFearGreedHistory, FEAR_GREED_INITIAL_DELAY_SECONDS, FEAR_GREED_INTERVAL_SECONDS);
        schedule(this::harvestAnalystActions, ACTIONS_INITIAL_DELAY_SECONDS, ACTIONS_INTERVAL_SECONDS);
        schedule(this::harvestUsLegs, US_LEGS_INITIAL_DELAY_SECONDS, US_LEGS_INTERVAL_SECONDS);
        schedule(this::classifyAdhocs, CLASSIFY_INITIAL_DELAY_SECONDS, CLASSIFY_INTERVAL_SECONDS);
        schedule(this::harvestMacroSurprises, MACRO_INITIAL_DELAY_SECONDS, MACRO_INTERVAL_SECONDS);
        schedule(this::enrichEvents, ENRICH_INITIAL_DELAY_SECONDS, ENRICH_INTERVAL_SECONDS);
    }

    private void schedule(Runnable task, long initialDelaySeconds, long intervalSeconds) {
        JitteredScheduler.schedule(scheduler, task, initialDelaySeconds, intervalSeconds,
                TimeUnit.SECONDS, pollJitterPercent);
    }

    public void shutdown() {
        ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Harvest: ad-hoc register
    // ------------------------------------------------------------------

    /** One ad-hoc sweep: fetch the feed, append what the register doesn't hold yet. */
    void harvestAdhocs() {
        FnRssClient client = fnRssClient;
        if (client == null) return;
        try {
            int fresh = 0;
            for (FnRssClient.AdhocItem item : client.adhocs(ADHOC_FETCH_LIMIT)) {
                if (item.publishedAt() == null || item.title() == null || item.title().isBlank()) continue;
                if (adhocArchive.append(new AdhocEventRecord(item.publishedAt().toString(),
                        item.isin(), item.title(), item.link()))) {
                    fresh++;
                }
            }
            if (fresh > 0) {
                LOG.info("Ad-hoc register: archived {} new disclosure(s) ({} total).",
                        fresh, adhocArchive.size());
            } else {
                LOG.debug("Ad-hoc register: nothing new ({} total).", adhocArchive.size());
            }
        } catch (Exception e) {
            LOG.warn("Ad-hoc harvest failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Harvest: Fear&Greed history
    // ------------------------------------------------------------------

    /** One history top-up: fetch from the cursor, append every settled day. */
    void topUpFearGreedHistory() {
        FearGreedClient client = fearGreedClient;
        if (client == null) return;
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate since = fearGreedFetchStart(fearGreedArchive.latestDate(), today);
            if (since == null) {
                LOG.debug("Fear&Greed history current through yesterday; no fetch.");
                return;
            }
            List<FearGreedClient.DailyScore> days = client.historySince(since);
            int fresh = 0;
            for (FearGreedClient.DailyScore day : days) {
                if (!day.date().isBefore(today)) continue;
                if (fearGreedArchive.append(new FearGreedDayRecord(
                        day.date().toString(), day.score(), day.rating()))) {
                    fresh++;
                }
            }
            if (fresh > 0) {
                LOG.info("Fear&Greed history: archived {} new day(s) ({} total, since {}).",
                        fresh, fearGreedArchive.size(), since);
            } else if (!days.isEmpty()) {
                LOG.debug("Fear&Greed history: no settled new days ({} total).", fearGreedArchive.size());
            }
        } catch (Exception e) {
            LOG.warn("Fear&Greed history top-up failed: {}", e.getMessage());
        }
    }

    /**
     * Package-private for tests: where the next history fetch starts, or null
     * when the archive already holds everything settled (through yesterday).
     * An empty archive back-fills from the series floor.
     */
    static LocalDate fearGreedFetchStart(Optional<String> latestArchived, LocalDate today) {
        if (latestArchived.isEmpty()) return FEAR_GREED_FLOOR;
        LocalDate latest;
        try {
            latest = LocalDate.parse(latestArchived.get());
        } catch (Exception e) {
            return FEAR_GREED_FLOOR;
        }
        if (!latest.isBefore(today.minusDays(1))) return null;
        return latest.plusDays(1);
    }

    // ------------------------------------------------------------------
    // Harvest: analyst actions (US daily table)
    // ------------------------------------------------------------------

    /** The day's rating actions → dated events (up-/downgrades/initiations only). */
    void harvestAnalystActions() {
        AnalystActionsSource source = analystActionsSource;
        if (source == null) return;
        try {
            int fresh = 0;
            for (AnalystActions.Action action : source.todaysActions("US")) {
                String eventClass = actionClass(action.actionType());
                if (eventClass == null || action.symbol() == null || action.dateIso() == null) continue;
                String detail = describeAction(action);
                if (eventArchive.append(MarketEventRecord.bare(action.dateIso(),
                        action.symbol().toUpperCase(), null, eventClass, "MarketBeat", detail))) {
                    fresh++;
                }
            }
            if (fresh > 0) {
                LOG.info("Event register: {} new analyst action(s) ({} events total).",
                        fresh, eventArchive.size());
            }
        } catch (Exception e) {
            LOG.warn("Analyst-action harvest failed: {}", e.getMessage());
        }
    }

    /** Package-private for tests: provider action label → house class, or null. */
    static String actionClass(String actionType) {
        if (actionType == null) return null;
        String t = actionType.toLowerCase(Locale.ROOT);
        if (t.contains("downgrade")) return "DOWNGRADE";
        if (t.contains("upgrade")) return "UPGRADE";
        if (t.contains("initiat")) return "INITIATION";
        return null; // target moves etc. are not a clean reaction class
    }

    private static String describeAction(AnalystActions.Action action) {
        StringBuilder sb = new StringBuilder();
        if (action.brokerage() != null) sb.append(action.brokerage());
        if (action.ratingOld() != null || action.ratingNew() != null) {
            sb.append(sb.isEmpty() ? "" : " ").append(action.ratingOld() == null ? "?" : action.ratingOld())
                    .append(" -> ").append(action.ratingNew() == null ? "?" : action.ratingNew());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    // ------------------------------------------------------------------
    // Harvest: per-ticker US legs (earnings surprises + EDGAR 8-K)
    // ------------------------------------------------------------------

    /** One rotation step: the next US name's surprise history + 8-K items. */
    void harvestUsLegs() {
        List<String> universe = usUniverse();
        if (universe.isEmpty()) return;
        String symbol = universe.get(Math.floorMod(universeCursor++, universe.size()));
        int fresh = 0;
        UsListingStatsSource stats = usListingStatsSource;
        if (stats != null) {
            try {
                Optional<UsListingStats> s = stats.statsFor(symbol);
                if (s.isPresent()) {
                    for (UsListingStats.EarningsSurprise surprise : s.get().earningsSurprises()) {
                        String eventClass = surpriseClass(surprise.surprisePercent());
                        if (eventClass == null || surprise.reportedDateIso() == null) continue;
                        String detail = String.format(Locale.ROOT, "surprise %+.1f %%",
                                surprise.surprisePercent());
                        if (eventArchive.append(MarketEventRecord.bare(surprise.reportedDateIso(),
                                symbol, null, eventClass, "NASDAQ", detail))) {
                            fresh++;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Surprise harvest '{}' failed: {}", symbol, e.getMessage());
            }
        }
        EdgarClient edgar = edgarClient;
        if (edgar != null) {
            try {
                for (EdgarClient.EdgarEvent event : edgar.eightKEvents(symbol)) {
                    if (eventArchive.append(MarketEventRecord.bare(event.date().toString(),
                            symbol, null, event.eventClass(), "EDGAR", event.items()))) {
                        fresh++;
                    }
                }
            } catch (Exception e) {
                LOG.warn("EDGAR harvest '{}' failed: {}", symbol, e.getMessage());
            }
        }
        if (fresh > 0) {
            LOG.info("Event register: {} new event(s) for {} ({} total).",
                    fresh, symbol, eventArchive.size());
        }
    }

    /** Package-private for tests: surprise sign → class; NaN/zero → null. */
    static String surpriseClass(double surprisePercent) {
        if (!Double.isFinite(surprisePercent) || surprisePercent == 0.0) return null;
        return surprisePercent > 0 ? "EARNINGS_BEAT" : "EARNINGS_MISS";
    }

    /** Distinct US-shaped tickers off the wire's recent weeks, insertion-ordered. */
    private List<String> usUniverse() {
        HeadlineArchive archive = headlineArchive;
        if (archive == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        try {
            for (HeadlineRecord r : archive.recent(UNIVERSE_WINDOW)) {
                String sym = r.tickerSymbol();
                if (sym == null || sym.isBlank()) continue;
                sym = sym.trim().toUpperCase();
                if (US_SYMBOL.matcher(sym).matches()) out.add(sym);
                if (out.size() >= UNIVERSE_CAP) break;
            }
        } catch (Exception e) {
            LOG.debug("US universe read failed: {}", e.getMessage());
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------
    // Classification: German ad-hocs → event classes (the one model touchpoint)
    // ------------------------------------------------------------------

    /** One batch of unclassified ad-hocs through the enum judge. */
    void classifyAdhocs() {
        AdhocClassifier classifier = adhocClassifier;
        if (classifier == null) return;
        try {
            List<AdhocEventRecord> batch = new ArrayList<>(CLASSIFY_BATCH);
            for (AdhocEventRecord adhoc : adhocArchive.recent(200)) {
                if (processedAdhocs.contains(adhoc.title())) continue;
                batch.add(adhoc);
                if (batch.size() >= CLASSIFY_BATCH) break;
            }
            if (batch.isEmpty()) return;

            Map<Integer, String> verdicts =
                    classifier.classify(batch.stream().map(AdhocEventRecord::title).toList());
            int registered = 0;
            for (int i = 0; i < batch.size(); i++) {
                AdhocEventRecord adhoc = batch.get(i);
                String verdict = verdicts.get(i + 1);
                if (verdict == null) {
                    int attempts = classifyAttempts.merge(adhoc.title(), 1, Integer::sum);
                    if (attempts >= MAX_CLASSIFY_ATTEMPTS) {
                        processedAdhocs.add(adhoc.title());
                        classifyAttempts.remove(adhoc.title());
                        LOG.debug("Ad-hoc classification gave up on '{}'.", adhoc.title());
                    }
                    continue;
                }
                processedAdhocs.add(adhoc.title());
                classifyAttempts.remove(adhoc.title());
                if ("SONSTIGES".equals(verdict)) continue;
                String date = effectiveEventDate(adhoc.publishedAt());
                if (date == null) continue;
                String symbol = resolveSymbol(adhoc.isin());
                if (eventArchive.append(new MarketEventRecord(date, symbol, adhoc.isin(),
                        verdict, "EQS", adhoc.title(), null, null, null, null, null, null))) {
                    registered++;
                }
            }
            if (registered > 0) {
                LOG.info("Event register: {} classified ad-hoc(s) ({} events total).",
                        registered, eventArchive.size());
            }
        } catch (Exception e) {
            LOG.warn("Ad-hoc classification sweep failed: {}", e.getMessage());
        }
    }

    /**
     * Package-private for tests: disclosure instant → effective event date. A
     * German disclosure published after the XETRA close belongs to the NEXT
     * session — the most common hand-rolled event-study mistake (the reaction
     * would otherwise be measured on a day that already closed).
     */
    static String effectiveEventDate(String publishedAtIso) {
        try {
            ZonedDateTime berlin = Instant.parse(publishedAtIso).atZone(GERMAN_ZONE);
            LocalDate date = berlin.toLocalDate();
            if (berlin.toLocalTime().isAfter(GERMAN_CLOSE)) date = date.plusDays(1);
            return date.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort ISIN → Yahoo symbol (Yahoo's search resolves ISIN queries). */
    private String resolveSymbol(String isin) {
        YahooFinanceClient yahoo = yahooClient;
        if (yahoo == null || isin == null || isin.isBlank()) return null;
        try {
            for (YahooQuote quote : yahoo.search(isin, 3, 0).quotes()) {
                if (quote.symbol() != null && !quote.symbol().isBlank()) {
                    return quote.symbol().toUpperCase();
                }
            }
        } catch (Exception e) {
            LOG.debug("ISIN {} symbol resolution failed: {}", isin, e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Harvest: macro surprises (world news the register CAN measure)
    // ------------------------------------------------------------------

    /** Indicator title → macro group (titles recur monthly; judged ~once, ever). */
    private final Map<String, String> macroGroupCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> macroClassifyAttempts = new ConcurrentHashMap<>();

    /**
     * One calendar sweep: every settled high-impact actual with a consensus
     * becomes a dated event of class {@code <GROUP>_UEBER/UNTER_PROGNOSE},
     * measured RAW on the country's index — the ONE kind of world news the
     * register can honestly measure (recurring, dated, signed). Singular
     * world events (geopolitics, elections, disasters) deliberately never
     * land here: their treatment is the transmission-channel doctrine in the
     * section prompts, not statistics.
     */
    void harvestMacroSurprises() {
        de.bsommerfeld.wsbg.terminal.briefing.TradingViewCalendarClient calendar = tvCalendar;
        if (calendar == null) return;
        try {
            Instant now = Instant.now();
            List<de.bsommerfeld.wsbg.terminal.briefing.TradingViewCalendarClient.TvEvent> actuals =
                    new ArrayList<>();
            for (var e : calendar.events(now.minus(Duration.ofDays(MACRO_LOOKBACK_DAYS)), now)) {
                if (e.importance() < 1 || e.actual() == null || e.forecast() == null) continue;
                if (macroIndexFor(e.country()) == null) continue;
                actuals.add(e);
            }
            if (actuals.isEmpty()) return;
            resolveMacroGroups(actuals);

            int fresh = 0;
            for (var e : actuals) {
                String group = macroGroupCache.get(e.title());
                String eventClass = macroSurpriseClass(group, e.actual(), e.forecast());
                if (eventClass == null) continue;
                String detail = String.format(Locale.ROOT, "%s (%s): actual %.2f vs forecast %.2f",
                        e.title(), e.country(), e.actual(), e.forecast());
                String date = e.when().atZone(ZoneOffset.UTC).toLocalDate().toString();
                if (eventArchive.append(MarketEventRecord.bare(date,
                        macroIndexFor(e.country()), null, eventClass, "TV-Kalender", detail))) {
                    fresh++;
                }
            }
            if (fresh > 0) {
                LOG.info("Event register: {} new macro surprise(s) ({} events total).",
                        fresh, eventArchive.size());
            }
        } catch (Exception e) {
            LOG.warn("Macro-surprise harvest failed: {}", e.getMessage());
        }
    }

    /** Judges the not-yet-cached titles in small batches; 3 whiffs = SONSTIGES. */
    private void resolveMacroGroups(
            List<de.bsommerfeld.wsbg.terminal.briefing.TradingViewCalendarClient.TvEvent> actuals) {
        MacroClassifier classifier = macroClassifier;
        if (classifier == null) return;
        List<String> unknown = new ArrayList<>();
        for (var e : actuals) {
            if (!macroGroupCache.containsKey(e.title()) && !unknown.contains(e.title())) {
                unknown.add(e.title());
                if (unknown.size() >= MACRO_CLASSIFY_BATCH) break;
            }
        }
        if (unknown.isEmpty()) return;
        Map<Integer, String> verdicts = classifier.classify(unknown);
        for (int i = 0; i < unknown.size(); i++) {
            String title = unknown.get(i);
            String verdict = verdicts.get(i + 1);
            if (verdict != null) {
                macroGroupCache.put(title, verdict);
                macroClassifyAttempts.remove(title);
            } else if (macroClassifyAttempts.merge(title, 1, Integer::sum) >= MAX_CLASSIFY_ATTEMPTS) {
                macroGroupCache.put(title, "SONSTIGES");
                macroClassifyAttempts.remove(title);
            }
        }
    }

    /**
     * Package-private for tests: group + surprise sign → event class. In-line
     * prints and the SONSTIGES bucket carry no measurable class; the sign is
     * relative to CONSENSUS, the economic reading (good/bad) stays with the
     * group — which is exactly why the group matters (an above-consensus CPI
     * and an above-consensus GDP are opposite stories).
     */
    static String macroSurpriseClass(String group, Double actual, Double forecast) {
        if (group == null || "SONSTIGES".equals(group)
                || actual == null || forecast == null || actual.equals(forecast)) {
            return null;
        }
        return group + (actual > forecast ? "_UEBER_PROGNOSE" : "_UNTER_PROGNOSE");
    }

    /** The index a country's macro surprise is measured on; null = not covered. */
    static String macroIndexFor(String country) {
        if (country == null) return null;
        return switch (country) {
            case "US" -> "^GSPC";
            case "DE", "EU" -> "^GDAXI";
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // Enrichment: CARs + regime stamp + confounded flag
    // ------------------------------------------------------------------

    /** One sweep over settled, still-unmeasured events. */
    void enrichEvents() {
        YahooFinanceClient yahoo = yahooClient;
        if (yahoo == null) return;
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            String cutoff = today.minusDays(SETTLE_CALENDAR_DAYS).toString();
            List<MarketEventRecord> pending =
                    eventArchive.pendingEnrichment(cutoff, ENRICH_BATCH, enrichGivenUp);
            if (pending.isEmpty()) return;

            // One bar fetch per symbol/benchmark per sweep, however many events
            // share it. An unparseable event date counts as a failed attempt.
            Map<String, List<Bar>> barsBySymbol = new HashMap<>();
            Map<String, LocalDate> earliest = new LinkedHashMap<>();
            Map<String, LocalDate> eventDates = new HashMap<>();
            for (MarketEventRecord e : pending) {
                LocalDate d;
                try {
                    d = LocalDate.parse(e.date());
                } catch (Exception bad) {
                    noteEnrichFailure(e);
                    continue;
                }
                eventDates.put(e.identity(), d);
                earliest.merge(e.symbol(), d, (a, b) -> a.isBefore(b) ? a : b);
                String bench = benchmarkFor(e.symbol());
                if (bench != null) earliest.merge(bench, d, (a, b) -> a.isBefore(b) ? a : b);
            }
            for (Map.Entry<String, LocalDate> entry : earliest.entrySet()) {
                barsBySymbol.put(entry.getKey(),
                        yahoo.fetchDailyBars(entry.getKey(), entry.getValue().minusDays(40)));
            }

            int enriched = 0;
            for (MarketEventRecord event : pending) {
                try {
                    LocalDate date = eventDates.get(event.identity());
                    if (date == null) continue; // unparseable — already counted
                    String bench = benchmarkFor(event.symbol());
                    Optional<EventStudy.Reaction> reaction = EventStudy.compute(
                            barsBySymbol.getOrDefault(event.symbol(), List.of()),
                            bench == null ? null : barsBySymbol.getOrDefault(bench, List.of()),
                            date);
                    if (reaction.isEmpty()) {
                        noteEnrichFailure(event);
                        continue;
                    }
                    FearGreedDayRecord regime = regimeBefore(date);
                    boolean confounded = isConfounded(event);
                    if (eventArchive.enrich(new MarketEventRecord(event.date(), event.symbol(),
                            event.isin(), event.eventClass(), event.source(), event.detail(),
                            regime == null ? null : bandOf(regime.score()),
                            regime == null ? null : regime.score(),
                            reaction.get().carEventPct(), reaction.get().carShortPct(),
                            bench == null ? "RAW" : bench, confounded))) {
                        enrichAttempts.remove(event.identity());
                        enriched++;
                    }
                } catch (Exception e) {
                    noteEnrichFailure(event);
                    LOG.debug("Enrichment of {} failed: {}", event.identity(), e.getMessage());
                }
            }
            if (enriched > 0) {
                LOG.info("Event register: enriched {} event(s) with reactions ({} total, {} given up).",
                        enriched, eventArchive.size(), enrichGivenUp.size());
            }
        } catch (Exception e) {
            LOG.warn("Enrichment sweep failed: {}", e.getMessage());
        }
    }

    /** Counts one failed measurement; the third failure retires the event from the queue. */
    private void noteEnrichFailure(MarketEventRecord event) {
        int attempts = enrichAttempts.merge(event.identity(), 1, Integer::sum);
        if (attempts >= MAX_ENRICH_ATTEMPTS) {
            enrichGivenUp.add(event.identity());
            enrichAttempts.remove(event.identity());
            LOG.debug("Enrichment gave up on {} after {} attempts.", event.identity(), attempts);
        }
    }

    /**
     * Benchmark index by listing shape: German venue → DAX, everything else →
     * S&P 500 — and {@code null} for an index symbol itself (macro events are
     * measured RAW on the index: subtracting the market from itself would
     * measure zero by construction).
     */
    static String benchmarkFor(String symbol) {
        if (symbol != null && symbol.startsWith("^")) return null;
        return symbol != null && symbol.toUpperCase().endsWith(".DE") ? "^GDAXI" : "^GSPC";
    }

    /** The F&G reading of the last trading day BEFORE the event (t−1 walkback). */
    private FearGreedDayRecord regimeBefore(LocalDate eventDate) {
        for (int i = 1; i <= REGIME_LOOKBACK_DAYS; i++) {
            Optional<FearGreedDayRecord> day = fearGreedArchive.byDate(eventDate.minusDays(i).toString());
            if (day.isPresent()) return day.get();
        }
        return null;
    }

    /** CNN's own band words from the score — robust even if the rating string drifts. */
    static String bandOf(double score) {
        if (score < 25) return "EXTREME_FEAR";
        if (score < 45) return "FEAR";
        if (score <= 55) return "NEUTRAL";
        if (score <= 75) return "GREED";
        return "EXTREME_GREED";
    }

    /** Another registered event of the same instrument within ±2 days → cocktail. */
    private boolean isConfounded(MarketEventRecord event) {
        LocalDate date = LocalDate.parse(event.date());
        String instrument = event.symbol() != null ? event.symbol() : event.isin();
        for (MarketEventRecord other : eventArchive.byInstrument(instrument)) {
            if (other.identity().equals(event.identity())) continue;
            try {
                long gap = Math.abs(date.toEpochDay() - LocalDate.parse(other.date()).toEpochDay());
                if (gap <= CONFOUND_WINDOW_DAYS) return true;
            } catch (Exception ignored) {
                // unparseable neighbour date — ignore
            }
        }
        return false;
    }
}
