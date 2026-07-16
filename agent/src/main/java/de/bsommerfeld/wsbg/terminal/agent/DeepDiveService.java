package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveFinishedEvent;
import de.bsommerfeld.wsbg.terminal.briefing.CentralBankCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.EconCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.TradingViewCalendarClient;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveProgressEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveStartedEvent;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystView;
import de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive;
import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealings;
import de.bsommerfeld.wsbg.terminal.core.price.ShortInterest;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActions;
import de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularity;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveArchive;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The KI-DD tool: on demand, ONE comprehensive due-diligence report for ONE
 * subject — a FIXED, dated snapshot (unlike the watchlist's continuously revised
 * dossier), archived permanently and exportable. Pulls EVERY data leg the
 * terminal has at generation time: identity via the pipeline's own resolver,
 * price (L&S), venue depth (Tradegate), profile facts (onvista), the deep
 * company record incl. official website, key-figure estimates, balance sheets,
 * boards, chart-technical read and peers (Consorsbank), analyst opinions +
 * corporate events (Consorsbank), insider dealings (BaFin), disclosed short
 * positions (Bundesanzeiger), triangulated news (Yahoo + WSO + Google News +
 * Fool) — plus the room's evidence from the feed-wide {@link SubjectRegistry}.
 *
 * <p><b>The report is produced by a WORKSPACE of sections, not by editing one
 * growing text (rebuild 2026-07-13, "die Redaktion"):</b> the old edit-script
 * loop (verbatim anchors + sentinel protocol against a standing report) broke
 * three ways at once — the parser passed sentinels through as prose, substring
 * anchors spliced sentences mid-figure, and the growing report saturated
 * num_ctx so the late passes (the room) died silently. Now the report is a set
 * of eight section states assembled DETERMINISTICALLY; the model only ever
 * writes or rewrites ONE bounded section, never patches, never sees the whole
 * report. The roles mirror a real research desk:
 * <ul>
 *   <li><b>Triage</b> — a judge call decides for every pooled news item whether
 *       it is substantively about the subject (Yahoo's symbol search returns an
 *       unrelated firehose for suffix tickers — live-observed 'SAP.DE') and
 *       which section it serves; only relevant items get source numbers. When
 *       the pool is thin, ONE alias follow-up asks the model for alternative
 *       press names and re-queries — the desk requests, we deliver.</li>
 *   <li><b>Author</b> — writes one section from THAT section's material,
 *       hard-budgeted against the context window (never silently truncated).</li>
 *   <li><b>Examiner</b> — {@link DeepDiveFactCheck}, deterministic: every
 *       figure and date must exist in the material, only the section's source
 *       markers, no protocol residue, no torn sentences, no repeats.</li>
 *   <li><b>Challenger</b> — the adversarial pass a real desk runs per note
 *       (the Supervisory-Analyst review): objections against the DRAFT strictly
 *       from the material; the author revises; unresolved FIGURE objections
 *       cost the sentence (a lost sentence beats a false statement).</li>
 *   <li><b>Editor</b> — writes "These" LAST from the standing sections' claim
 *       sentences plus the key data (the page-1 read), challenged like any
 *       section.</li>
 *   <li><b>Typesetter</b> — deterministic assembly: headings, honest literals
 *       for empty sections, cross-section dedupe, marker scrub, the appended
 *       source register, the figure layer.</li>
 * </ul>
 *
 * <p><b>Ist and Soll:</b> the eight-section skeleton carries a dedicated
 * "Ausblick" — the anchored expectation: what the market measures next (dated
 * events), what the street expects (consensus target and estimate path,
 * attributed), and what would change the read. Every forward-looking sentence
 * must hang on an anchor IN the material (date, consensus figure, guidance,
 * announced event, observed chart mark) — enforced by prompt AND challenger;
 * probabilities are never invented.
 *
 * <p>All model calls ride the shared {@link LlmGate} on {@code deepDiveModel}
 * (triage/alias on the JSON {@code agentModel}) — on-demand only, one
 * generation at a time on a single daemon worker; progress is posted as
 * {@link DeepDiveProgressEvent}s so the UI can narrate the stages.
 */
@Singleton
public class DeepDiveService {

    private static final Logger LOG = LoggerFactory.getLogger(DeepDiveService.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Newest news that ride the material — the DD must be CURRENT (watchlist rule). */
    private static final long NEWS_MAX_AGE_DAYS = 30;
    /**
     * News ceilings are RUNAWAY BACKSTOPS ONLY since 2026-07-14 (user mandate
     * "ALLES was das Instrument umtreibt, egal wie lange es dauert"): every
     * triage-relevant article earns a source number, a digest and a weave
     * step. The TRIAGE judge and the same-story arbiter are the quality
     * filters — never a seat count. Hitting a backstop is LOGGED (no silent
     * caps), and the numbers sit far above any organic pool.
     */
    private static final int MAX_NEWS = 120;
    private static final int MAX_NEWS_RESEARCHED = 150;
    /** How many targeted research queries one run may fire. */
    private static final int MAX_RESEARCH_QUERIES = 4;
    /** Per research query: how many finds it may contribute to the candidate set. */
    private static final int RESEARCH_QUERY_LIMIT = 25;
    /** Pre-triage CANDIDATE pool backstop — the judge sorts, the pool stays open. */
    private static final int MAX_NEWS_CANDIDATES = 300;
    /**
     * Digest backstop: EVERY triage-relevant article is read + distilled
     * inline (one small model call each, body capped by the reader) — after
     * triage, so no read is wasted on a rejected item.
     */
    private static final int MAX_DIGESTED_ARTICLES = 150;
    /** Per-article digest cap inside a material block (mirrors the wire brief's cap). */
    private static final int MAX_DIGEST_CHARS_BASE = 500;
    private static final int MAX_INSIDER_DEALS = 8;
    private static final int MAX_SHORT_POSITIONS = 8;
    /** NASDAQ tab caps — material stays model-sized, the tabs carry hundreds of rows. */
    private static final int MAX_US_INSIDER_TRADES = 8;
    private static final int MAX_US_SHORT_POINTS = 6;
    private static final int MAX_US_HOLDERS = 5;
    private static final int MAX_US_SURPRISES = 4;
    private static final int MAX_ANALYST_ACTIONS = 10;
    private static final int MAX_ACTION_TABLE_ROWS = 8;
    /** First-party IR-archive entries kept (reports, calls, calendar). */
    private static final int MAX_IR_ENTRIES = 12;
    /** The thesis' machine ceiling - its 400-900 prompt contract plus slack. */
    private static final int THESIS_MAX_CHARS = 1000;
    /** Past calendar years the press-history leg sweeps (one window each). */
    private static final int PRESS_HISTORY_YEARS = 3;
    /** Headlines kept per history year - an arc marker, never a second firehose. */
    private static final int PRESS_HISTORY_PER_YEAR = 6;
    private static final int MAX_MACRO_ACTUALS = 4;
    private static final int MAX_MACRO_DOCKET = 4;
    /** Wire-archive lines fed to the room shelf: the first N + the newest M. */
    private static final int WIRE_HISTORY_HEAD = 2;
    private static final int WIRE_HISTORY_TAIL = 6;
    private static final int MAX_KEY_FIGURE_YEARS = 6;
    private static final int MAX_BALANCE_YEARS = 4;
    private static final int MAX_EVENTS = 4;
    private static final int MAX_WIRE_LINES = 6;
    /** Base char budget for the room's evidence block (window-scaled, newest kept). */
    private static final int ROOM_PACKET_CHARS_BASE = 2400;
    /** Base char budget for the OUTSIDE ROOMS block (forum/social chatter, window-scaled). */
    private static final int SENTIMENT_PACKET_CHARS_BASE = 2200;
    /** Fetch cap for the sentiment fan (the char budget is the real gate). */
    private static final int MAX_SENTIMENT_ITEMS = 60;
    /**
     * The TradingCentral comment prose is attributed context, not the star —
     * capped, but at a SENTENCE boundary: a mid-sentence cut leaves the
     * comment's figures (moving averages, RSI) half in the model's head and
     * half out of the material, and the examiner then rightly kills the
     * sentence every single run (live: "138,01" recurred in three runs).
     */
    private static final int MAX_TECHNICAL_COMMENT_CHARS_BASE = 700;
    /** Hard cap on ONE section's material — must fit an author call beside the prompt. */
    private static final int SECTION_MATERIAL_CHARS_BASE = 6200;

    /**
     * The material char budgets scale with the resolved context window — the
     * BASE values above are the 8k arithmetic (16 GB end-user floor), and a
     * machine that resolves a bigger window gets proportionally fuller
     * shelves, digests, room packets and chart-technical prose instead of
     * pure headroom (user mandate 2026-07-16 "vollere Regale": mechanical
     * compression only where the window forces it). Static because the shelf
     * builders are static; the ONE service instance stamps the resolved
     * window at construction, tests keep the deterministic 8k floor.
     */
    static volatile int windowTokens = 8192;

    /** A base char budget scaled by the window (1x at 8k, 2x at 16k, capped 3x). */
    static int scaled(int base) {
        int factor = Math.max(1, Math.min(3, windowTokens / 8192));
        return base * factor;
    }

    private static int sectionMaterialChars() { return scaled(SECTION_MATERIAL_CHARS_BASE); }
    private static int maxDigestChars() { return scaled(MAX_DIGEST_CHARS_BASE); }
    private static int roomPacketChars() { return scaled(ROOM_PACKET_CHARS_BASE); }
    private static int sentimentPacketChars() { return scaled(SENTIMENT_PACKET_CHARS_BASE); }
    private static int maxTechnicalCommentChars() { return scaled(MAX_TECHNICAL_COMMENT_CHARS_BASE); }
    /**
     * Runaway backstop on the archived report — NOT a working budget. Section
     * sizes are bounded upstream ({@link DeepDiveFactCheck#MAX_SECTION_CHARS}
     * per section), so this only ever fires on a logic error.
     */
    private static final int MAX_REPORT_CHARS = 30000;
    /**
     * Mirrors the deep-dive model's numPredict in {@link OllamaModelFactory} —
     * the output half of the context-window budget every pass must respect.
     */
    private static final int DD_NUM_PREDICT = 3584;
    /** Conservative chars-per-token estimate for German prose (gemma tokenizer). */
    private static final double CHARS_PER_TOKEN = 3.0;
    /**
     * NO fixed round cap on the grilling (user mandate 2026-07-13
     * "Iterationscap entfernen"): the loop runs until the challenger says the
     * section STANDS or provably stops converging (identical objections twice
     * in a row / a revision that changes nothing). This is the pure runaway
     * backstop, never a working limit.
     */
    private static final int CHALLENGE_ROUNDS_BACKSTOP = 10;
    /** Runaway net for the whole-report final instance - never a working limit. */
    private static final int CONSISTENCY_ROUNDS_BACKSTOP = 4;
    /** Below this many relevant news the alias follow-up re-queries the aggregator. */
    private static final int THIN_NEWS_THRESHOLD = 3;
    /**
     * A weave block this token-similar to an ALREADY WOVEN one is a
     * SUSPICION, never a verdict — the arbiter (AI, "Konfliktlöser") then
     * reads both sources and decides same-story vs own news value. Low on
     * purpose: the mechanical check is the smoke detector, the AI the judge.
     */
    private static final double WEAVE_SUSPICION_SIMILARITY = 0.35;

    private final SubjectRegistry subjectRegistry;
    private final AgentBrain brain;
    private final ChatGateway chatGateway;
    private final DeepDiveArchive archive;
    private final ApplicationEventBus eventBus;

    // -- optional data legs (production-wired via Guice, absent in tests) --
    private volatile EditorialAgent editorialAgent;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.PriceSource priceSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.VenueStatsSource venueStatsSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.InstrumentFactsSource factsSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.AnalystViewSource analystSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDiveSource deepDiveSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.ShortInterestSource shortInterestSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.InsiderDealingsSource insiderSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.UsListingStatsSource usStatsSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularitySource hedgeFundSource;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.AnalystActionsSource analystActionsSource;
    private volatile YahooFinanceClient yahooClient;
    private volatile de.bsommerfeld.wsbg.terminal.core.price.OrderBookSource orderBookSource;
    private volatile de.bsommerfeld.wsbg.terminal.db.MarketEventArchive marketEventArchive;
    private volatile de.bsommerfeld.wsbg.terminal.db.FearGreedHistoryArchive fearGreedHistory;
    private volatile TradingViewCalendarClient tvCalendar;
    private volatile EconCalendarClient econCalendar;
    private volatile CentralBankCalendarClient cbCalendar;
    private volatile HeadlineArchive headlineArchive;
    private volatile de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator newsAggregator;
    private volatile de.bsommerfeld.wsbg.terminal.briefing.FnRssClient fnRssClient;
    private volatile de.bsommerfeld.wsbg.terminal.briefing.EarningsWhispersClient earningsWhispers;
    private volatile CompanyPressScout pressScout;
    private volatile de.bsommerfeld.wsbg.terminal.websearch.BingWebSearchClient webSearch;
    private volatile de.bsommerfeld.wsbg.terminal.briefing.GlobalHazardsClient hazardsClient;
    // The fishing-net's ONE collection point (2026-07-15) — the full catch,
    // judged per subject, never pre-filtered.
    private volatile WorldSignalsCollector worldCollector;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "deepdive");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public DeepDiveService(SubjectRegistry subjectRegistry, AgentBrain brain, LlmGate llmGate,
            DeepDiveArchive archive, ApplicationEventBus eventBus) {
        this.subjectRegistry = subjectRegistry;
        this.brain = brain;
        this.chatGateway = new ChatGateway(brain, llmGate);
        this.archive = archive;
        this.eventBus = eventBus;
        // Stamp the resolved window for the static shelf builders — the ONE
        // service instance owns the material arithmetic; without a service
        // (plain unit tests) the deterministic 8k floor stays in force.
        windowTokens = brain.contextTokens();
    }

    @com.google.inject.Inject(optional = true)
    void setEditorialAgent(EditorialAgent editorialAgent) {
        this.editorialAgent = editorialAgent;
    }

    @com.google.inject.Inject(optional = true)
    void setPriceSource(de.bsommerfeld.wsbg.terminal.core.price.PriceSource source) {
        this.priceSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setVenueStatsSource(de.bsommerfeld.wsbg.terminal.core.price.VenueStatsSource source) {
        this.venueStatsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setInstrumentFactsSource(de.bsommerfeld.wsbg.terminal.core.price.InstrumentFactsSource source) {
        this.factsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setAnalystViewSource(de.bsommerfeld.wsbg.terminal.core.price.AnalystViewSource source) {
        this.analystSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setCompanyDeepDiveSource(de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDiveSource source) {
        this.deepDiveSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setShortInterestSource(de.bsommerfeld.wsbg.terminal.core.price.ShortInterestSource source) {
        this.shortInterestSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setInsiderDealingsSource(de.bsommerfeld.wsbg.terminal.core.price.InsiderDealingsSource source) {
        this.insiderSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setUsListingStatsSource(de.bsommerfeld.wsbg.terminal.core.price.UsListingStatsSource source) {
        this.usStatsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setHedgeFundPopularitySource(
            de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularitySource source) {
        this.hedgeFundSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setAnalystActionsSource(
            de.bsommerfeld.wsbg.terminal.core.price.AnalystActionsSource source) {
        this.analystActionsSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setYahooFinanceClient(YahooFinanceClient client) {
        this.yahooClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setTradingViewCalendar(TradingViewCalendarClient client) {
        this.tvCalendar = client;
    }

    @com.google.inject.Inject(optional = true)
    void setEconCalendar(EconCalendarClient client) {
        this.econCalendar = client;
    }

    @com.google.inject.Inject(optional = true)
    void setCentralBankCalendar(CentralBankCalendarClient client) {
        this.cbCalendar = client;
    }

    @com.google.inject.Inject(optional = true)
    void setHeadlineArchive(HeadlineArchive archive) {
        this.headlineArchive = archive;
    }

    @com.google.inject.Inject(optional = true)
    void setNewsAggregator(de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator aggregator) {
        this.newsAggregator = aggregator;
    }

    @com.google.inject.Inject(optional = true)
    void setFnRssClient(de.bsommerfeld.wsbg.terminal.briefing.FnRssClient client) {
        this.fnRssClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setEarningsWhispers(de.bsommerfeld.wsbg.terminal.briefing.EarningsWhispersClient client) {
        this.earningsWhispers = client;
    }

    @com.google.inject.Inject(optional = true)
    void setWebSearch(de.bsommerfeld.wsbg.terminal.websearch.BingWebSearchClient client) {
        this.webSearch = client;
    }

    @com.google.inject.Inject(optional = true)
    void setGlobalHazardsClient(de.bsommerfeld.wsbg.terminal.briefing.GlobalHazardsClient client) {
        this.hazardsClient = client;
    }

    @com.google.inject.Inject(optional = true)
    void setWorldCollector(WorldSignalsCollector collector) {
        this.worldCollector = collector;
    }

    @com.google.inject.Inject(optional = true)
    void setOrderBookSource(de.bsommerfeld.wsbg.terminal.core.price.OrderBookSource source) {
        this.orderBookSource = source;
    }

    @com.google.inject.Inject(optional = true)
    void setMarketEventArchive(de.bsommerfeld.wsbg.terminal.db.MarketEventArchive archive) {
        this.marketEventArchive = archive;
    }

    @com.google.inject.Inject(optional = true)
    void setFearGreedHistoryArchive(de.bsommerfeld.wsbg.terminal.db.FearGreedHistoryArchive archive) {
        this.fearGreedHistory = archive;
    }

    /** Browser UA for the first-party press scout's plain page fetches. */
    private static final String SCOUT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    @com.google.inject.Inject(optional = true)
    void setDirectFetcher(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst de.bsommerfeld.wsbg.terminal.source.net.WebFetcher fetcher) {
        this.pressScout = new CompanyPressScout(fetcher, SCOUT_USER_AGENT);
    }

    /** Test seam — the smoke wires a scout with a plain direct fetcher. */
    void setPressScout(CompanyPressScout scout) {
        this.pressScout = scout;
    }

    // -- public API (bridge) --

    public boolean isBusy() {
        return busy.get();
    }

    public List<DeepDiveRecord> recent(int limit) {
        return archive.recent(limit);
    }

    public Optional<DeepDiveRecord> byId(String id) {
        return archive.byId(id);
    }

    /** Deletes one archived report — an explicit user action from the widget. */
    public boolean delete(String id) {
        return archive.delete(id);
    }

    /**
     * Kicks one report generation. The input is a TICKER, never a free name
     * (user mandate 2026-07-13 "ausschließlich Ticker akzeptieren") — the whole
     * collect keys on it: local lists first, the resolver as dynamic fallback,
     * and the canonical name is LOOKED UP from the ticker for the name-addressed
     * legs (Google News, WSO). Returns {@code false} (and does nothing) when a
     * generation is already running or the input is not ticker-shaped — one DD
     * at a time, the passes are heavyweight model calls.
     */
    public boolean generate(String rawTicker) {
        String ticker = rawTicker == null ? "" : rawTicker.strip().toUpperCase(Locale.ROOT);
        if (!looksLikeTicker(ticker)) {
            LOG.info("[DEEPDIVE] rejected non-ticker input: '{}'", rawTicker);
            return false;
        }
        if (!busy.compareAndSet(false, true)) return false;
        cancelRequested = false;
        worker.execute(() -> {
            try {
                run(ticker);
            } catch (java.util.concurrent.CancellationException e) {
                LOG.info("[DEEPDIVE] generation for '{}' cancelled by the user.", ticker);
                finish(ticker, false, null);
            } catch (Exception e) {
                LOG.warn("[DEEPDIVE] generation for '{}' failed: {}", ticker, e.getMessage());
                finish(ticker, false, null);
            } finally {
                busy.set(false); // safety net — finish() already dropped it
            }
        });
        return true;
    }

    /** A user-requested abort of the RUNNING generation (checked between steps). */
    private volatile boolean cancelRequested;

    /**
     * Requests the running generation to stop — honored at the next step
     * boundary (a model call in flight finishes first, so the abort lands
     * within seconds). No-op when idle.
     */
    public boolean cancelCurrent() {
        if (!busy.get()) return false;
        cancelRequested = true;
        LOG.info("[DEEPDIVE] cancel requested for the running generation.");
        return true;
    }

    private void checkCancelled() {
        if (cancelRequested) throw new java.util.concurrent.CancellationException();
    }

    /**
     * Ticker shape: no whitespace, ≤15 chars, only symbol alphabet (letters,
     * digits, and the venue/index/futures/FX markers {@code . - ^ =}). Covers
     * RHM.DE, 005930.KS, BRK-B, ^GDAXI, CC=F, BTC-USD — and rejects free names.
     */
    static boolean looksLikeTicker(String s) {
        if (s == null || s.isEmpty() || s.length() > 15) return false;
        boolean alnum = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                alnum = true;
                continue;
            }
            if (c != '.' && c != '-' && c != '^' && c != '=') return false;
        }
        return alnum;
    }

    /**
     * Ends a generation: {@code busy} falls BEFORE the finished event goes out,
     * so the UI's final state push (the bridge reads {@link #isBusy()} on it)
     * already sees idle — otherwise the progress display would linger until the
     * next unrelated push (user report 2026-07-13).
     */
    private void finish(String subject, boolean success, String reportId) {
        busy.set(false);
        eventBus.post(new DeepDiveFinishedEvent(subject, success, reportId));
    }

    // -- the generation pipeline --

    /** The section indices (fixed skeleton order — the DD's red thread). */
    static final int SEC_ABOUT = 0;
    static final int SEC_THESIS = 1;
    static final int SEC_SITUATION = 2;
    static final int SEC_FUNDAMENTALS = 3;
    static final int SEC_VALUATION = 4;
    static final int SEC_CATALYSTS = 5;
    static final int SEC_OUTLOOK = 6;
    static final int SEC_ROOM = 7;
    static final int SECTION_COUNT = 8;

    /**
     * The report's canonical section skeleton, shaped like a professional
     * research note: description, thesis (page-1 read, written LAST), situation,
     * financial trend, valuation vs peers, catalysts/risks, the ANCHORED outlook
     * (Ist → Soll), and the house's own room section. The literals are pinned in
     * the prompts AND set deterministically at assembly.
     */
    static final List<String> SECTIONS_DE = List.of(
            "Worum es geht", "These", "Lage", "Fundamentale Entwicklung",
            "Bewertung und Wettbewerb", "Katalysatoren und Risiken", "Ausblick", "Der Raum");
    static final List<String> SECTIONS_EN = List.of(
            "What it is about", "Thesis", "Situation", "Fundamental development",
            "Valuation and competition", "Catalysts and risks", "Outlook", "The room");

    /** The loaded prompt set of one run. */
    private record Prompts(String section, String these, String revise, String challenge,
            String weave, String polish, String arbiter, String samestory,
            String consistency, String diffcheck, String chronicle, String covered,
            String reclaim) {
    }

    /**
     * One section's shelf: the BASE material (the data legs' blocks) and the
     * news blocks as SEPARATE steps — the author writes from the base, then
     * WEAVES the articles in one at a time, each held against the standing
     * text (the single source of truth). Tiny steps, never a bulk context.
     */
    record Shelf(String base, List<String> newsBlocks) {
        boolean isEmpty() {
            return (base == null || base.isBlank()) && newsBlocks.isEmpty();
        }

        /** The full admissible evidence — what the challenger and final checks see. */
        String combined() {
            if (newsBlocks.isEmpty()) return base;
            StringBuilder sb = new StringBuilder(base == null ? "" : base);
            sb.append("NEWS (verified, last ").append(NEWS_MAX_AGE_DAYS).append(" days):\n");
            for (String b : newsBlocks) sb.append(b);
            return sb.toString();
        }
    }

    private void run(String subject) {
        long t0 = System.currentTimeMillis();
        // The whole run rides the gate's INTERACTIVE lane: a human visibly
        // waits on this generation, so its calls overtake the background wire
        // at the next free permit (the gate's anti-starvation quota keeps the
        // wire publishing). Measured: Abendausgabe-hour contention queued
        // every DD call ~16 s and roughly doubled the SAP run.
        ChatGateway.INTERACTIVE.set(Boolean.TRUE);
        try {
            runInteractive(subject, t0);
        } finally {
            ChatGateway.INTERACTIVE.remove();
        }
    }

    private void runInteractive(String subject, long t0) {
        eventBus.post(new DeepDiveStartedEvent(subject));
        eventBus.post(new DeepDiveProgressEvent(subject, "collect"));

        Material m = collect(subject);
        String lang = brain.getUserLanguage().code();
        boolean de = "de".equalsIgnoreCase(lang);
        String langName = brain.getUserLanguage().displayName();

        ChatModel model = brain.getDeepDiveModel() != null
                ? brain.getDeepDiveModel() : brain.getProseModel();
        if (model == null) {
            finish(subject, false, null);
            return;
        }

        // -- triage: relevance judge + alias/research follow-ups + article reads --
        eventBus.post(new DeepDiveProgressEvent(subject, "triage"));
        checkCancelled();
        pressSweep(subject, m);
        checkCancelled();
        triageNews(subject, m, lang, langName);
        checkCancelled();
        aliasFollowUp(subject, m, lang, langName);
        checkCancelled();
        researchFollowUp(subject, m, lang, langName);
        checkCancelled();
        digestArticles(subject, m);
        checkCancelled();
        // World signals are judged LAST in the triage phase, when the report's
        // THEME LANDSCAPE (the accepted press titles) is known — a wild-but-
        // real connection (a satellite deal in the news pool licensing space
        // weather) is only visible from here, never at collect time.
        judgeWorldSignals(subject, m, lang);
        checkCancelled();

        // Figures are built BEFORE the prose (they are a pure function of the
        // collected material): every figure gets a positional ID (A1, A2, ...)
        // and each section's shelf lists its figures as ID + caption, so the
        // prose can POINT at a figure like a paper does ("Abbildung A3") -
        // IDs copy-only, the register of the figure layer (user mandate
        // 2026-07-16 "Visuals referieren mit IDs").
        try {
            DeepDiveCharts chartBuilder = new DeepDiveCharts(lang);
            List<DeepDiveRecord.ChartFigure> figs = new ArrayList<>(chartBuilder.build(
                    m.snapshot, m.deepDive, m.analystView,
                    m.shortInterest, m.insiderDealings, m.venueStats, m.usStats,
                    m.analystActions, m.hedgeFunds, m.pressTimeline, m.worldSignalKeep,
                    m.volumeProfile, m.orderBook, m.memoryEvents));
            DeepDiveRecord.ChartFigure signalBoard =
                    chartBuilder.signalsFigure(SignalDesk.values(m.signalReadings));
            if (signalBoard != null) figs.add(signalBoard);
            m.charts = figs;
            Map<Integer, List<String>> captions = new LinkedHashMap<>();
            for (int ci = 0; ci < m.charts.size(); ci++) {
                DeepDiveRecord.ChartFigure fig = m.charts.get(ci);
                captions.computeIfAbsent(fig.section(), k -> new ArrayList<>())
                        .add("A" + (ci + 1) + ": " + fig.title());
            }
            m.figureCaptions = captions;
        } catch (Exception e) {
            LOG.warn("[DEEPDIVE] '{}' figure build failed - report continues without "
                    + "figure pointers: {}", subject, e.getMessage());
        }

        Map<String, Integer> nums = sourceNumbers(m);
        Set<Integer> validNums = new HashSet<>(nums.values());
        // A third UI language writes its prose in that language but keeps the
        // English heading literals — the skeleton is ours, not the model's.
        List<String> headings = de ? SECTIONS_DE : SECTIONS_EN;
        String header = header(subject, m);
        Shelf[] shelves = sectionShelves(m);
        LOG.info("[DEEPDIVE] '{}' material collected: {} chars across {} sections "
                        + "(isin={}, ticker={}, news={}, evidence={})",
                subject,
                java.util.Arrays.stream(shelves)
                        .map(Shelf::combined).filter(java.util.Objects::nonNull)
                        .mapToInt(String::length).sum(),
                SECTION_COUNT, m.isin, m.ticker, m.news.size(), m.evidenceCount);
        if (shelves[SEC_ABOUT].isEmpty() && shelves[SEC_SITUATION].isEmpty()
                && shelves[SEC_FUNDAMENTALS].isEmpty() && shelves[SEC_ROOM].isEmpty()) {
            LOG.warn("[DEEPDIVE] '{}' nothing to write from — no leg delivered.", subject);
            finish(subject, false, null);
            return;
        }

        Prompts prompts = new Prompts(
                PromptLoader.loadLocalized("deepdive-section", lang).replace("{{LANGUAGE}}", langName),
                PromptLoader.loadLocalized("deepdive-these", lang).replace("{{LANGUAGE}}", langName),
                PromptLoader.loadLocalized("deepdive-revise", lang).replace("{{LANGUAGE}}", langName),
                PromptLoader.loadLocalized("deepdive-challenge", lang).replace("{{LANGUAGE}}", langName),
                PromptLoader.loadLocalized("deepdive-weave", lang).replace("{{LANGUAGE}}", langName),
                PromptLoader.loadLocalized("deepdive-polish", lang).replace("{{LANGUAGE}}", langName),
                PromptLoader.loadLocalized("deepdive-arbiter", lang),
                PromptLoader.loadLocalized("deepdive-samestory", lang),
                PromptLoader.loadLocalized("deepdive-consistency", lang),
                PromptLoader.loadLocalized("deepdive-diffcheck", lang)
                        .replace("{{LANGUAGE}}", langName),
                PromptLoader.loadLocalized("deepdive-chronicle", lang)
                        .replace("{{LANGUAGE}}", langName),
                PromptLoader.loadLocalized("deepdive-covered", lang),
                PromptLoader.loadLocalized("deepdive-reclaim", lang));

        // -- the section workspace: author → weave sources one at a time →
        // examine → challenge → revise, one section at a time; "These" (the
        // editor's page-1 read) comes last. --
        String[] bodies = new String[SECTION_COUNT];
        int[] order = {SEC_ABOUT, SEC_SITUATION, SEC_FUNDAMENTALS, SEC_VALUATION,
                SEC_CATALYSTS, SEC_OUTLOOK, SEC_ROOM};
        int written = 0;
        String previousThought = null;
        Set<String> dropWords = storyDropWords(m.canonicalName, m.ticker);
        for (int idx : order) {
            checkCancelled();
            String label = (idx + 1) + "/" + SECTION_COUNT + " · " + headings.get(idx);
            eventBus.post(new DeepDiveProgressEvent(subject, "sections", label));
            long tSec = System.currentTimeMillis();
            bodies[idx] = writeSection(model, prompts, subject,
                    headerWithThought(header, previousThought), headings.get(idx),
                    shelves[idx], de, label, dropWords, m, idx == SEC_SITUATION);
            LOG.info("[DEEPDIVE] '{}' section '{}' {} in {} s.", subject, headings.get(idx),
                    bodies[idx] != null ? "stands" : "empty (honest literal)",
                    (System.currentTimeMillis() - tSec) / 1000);
            if (bodies[idx] != null) {
                written++;
                // The red thread: the next section's author sees where the
                // previous one ENDED and can pick the narrative up.
                previousThought = lastSentence(bodies[idx]);
            }
        }
        if (written == 0) {
            LOG.warn("[DEEPDIVE] '{}' every section whiffed — aborting.", subject);
            finish(subject, false, null);
            return;
        }

        eventBus.post(new DeepDiveProgressEvent(subject, "these"));
        // The thesis' length contract (400-900) lived only in its prompt - with
        // full-section input the editor provably overflows it (live 2026-07-16:
        // an 8-paragraph thesis opening with a copied profile). One condense
        // call, then the house scissors, both loud.
        bodies[SEC_THESIS] = writeSection(model, new Prompts(prompts.these(), prompts.these(),
                        prompts.revise(), prompts.challenge(), prompts.weave(), prompts.polish(),
                        prompts.arbiter(), prompts.samestory(), prompts.consistency(),
                        prompts.diffcheck(), prompts.chronicle(), prompts.covered(),
                        prompts.reclaim()),
                subject, header,
                headings.get(SEC_THESIS), new Shelf(thesisMaterial(headings, bodies, m), List.of()),
                de, (SEC_THESIS + 1) + "/" + SECTION_COUNT + " · " + headings.get(SEC_THESIS),
                dropWords, m, false);
        if (bodies[SEC_THESIS] != null) {
            written++;
            String thesis = bodies[SEC_THESIS].strip();
            if (thesis.length() > THESIS_MAX_CHARS) {
                String condensed = condense(model, prompts, header,
                        headings.get(SEC_THESIS), thesis);
                if (!isBlank(condensed) && condensed.strip().length() < thesis.length()) {
                    thesis = condensed.strip();
                }
                if (thesis.length() > THESIS_MAX_CHARS) {
                    LOG.warn("[DEEPDIVE] '{}' thesis over its contract ({} chars) - house cut.",
                            subject, thesis.length());
                    thesis = DeepDiveFactCheck.cutToLength(thesis, THESIS_MAX_CHARS);
                }
                bodies[SEC_THESIS] = thesis;
            }
        }

        // -- the RECLAIM pass (user design 2026-07-16): the report stands -
        // re-judge the triage-discarded articles against ITS picture. An
        // article with no visible relevance at triage time may be the cause,
        // consequence or missing piece of a thread the report now carries
        // ("um 2 Ecken denken"); the reclaimed few are digested and woven
        // into their sections BEFORE the final instance reviews the whole. --
        checkCancelled();
        try {
            reclaimPass(model, prompts, subject, headings, bodies, m, de, header);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("[DEEPDIVE] '{}' reclaim pass failed: {}", subject, e.getMessage());
        }

        // -- cross-section consistency: the ONE review that sees the whole
        // report at once (live finding run 10: Bewertung said "no revisions"
        // while Ausblick correctly carried 0 up / 1 down — a per-section
        // challenger can never catch that). One bounded round: objections map
        // to their owning section, which gets one revision against ITS shelf.
        checkCancelled();
        eventBus.post(new DeepDiveProgressEvent(subject, "finish",
                de ? "Konsistenz" : "consistency"));
        try {
            // The FINAL INSTANCE (user design 2026-07-16): the whole-report
            // review re-runs after its own fixes until it stands - demand as
            // many corrections as it takes; the backstop is the runaway net,
            // never a working limit.
            for (int round = 1; round <= CONSISTENCY_ROUNDS_BACKSTOP; round++) {
                if (!crossSectionConsistency(model, prompts, subject, headings, bodies,
                        shelves, m, de, header)) {
                    break;
                }
                if (round == CONSISTENCY_ROUNDS_BACKSTOP) {
                    LOG.warn("[DEEPDIVE] '{}' consistency review hit the runaway backstop ({}).",
                            subject, CONSISTENCY_ROUNDS_BACKSTOP);
                }
            }
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("[DEEPDIVE] '{}' consistency review failed: {}", subject, e.getMessage());
        }

        // -- typesetting: deterministic assembly, scrub, register, figures --
        eventBus.post(new DeepDiveProgressEvent(subject, "finish"));
        // Typesetter tables (BestStocks doctrine: prose-table-chart oscillation):
        // built deterministically from the verified legs AFTER every model pass
        // — the model never writes these, so they need no examination.
        Map<String, Integer> tableNums = sourceNumbers(m);
        appendTypesetTable(bodies, SEC_VALUATION, actionsTable(m, tableNums, de));
        appendTypesetTable(bodies, SEC_VALUATION, peerTable(m, tableNums, de));
        appendTypesetTable(bodies, SEC_OUTLOOK, scenarioTable(m, tableNums, de));
        String report = assemble(headings, bodies, de);
        report = scrubUnknownSourceMarkers(report, validNums);
        report = scrubUnknownFigureRefs(report, m.charts.size());
        if (!looksLikeReport(report, headings)) {
            // By construction this cannot fail — if it does, a logic error upstream.
            LOG.warn("[DEEPDIVE] '{}' assembled report failed the skeleton sanity gate.", subject);
        }
        if (report.length() > MAX_REPORT_CHARS) {
            LOG.warn("[DEEPDIVE] '{}' report hit the runaway backstop ({} > {} chars) "
                    + "— truncating.", subject, report.length(), MAX_REPORT_CHARS);
            report = report.substring(0, MAX_REPORT_CHARS - 1).stripTrailing() + "…";
        }
        Set<Integer> cited = markersIn(report);
        Set<Integer> uncited = new HashSet<>(validNums);
        uncited.removeAll(cited);
        if (!uncited.isEmpty()) {
            LOG.info("[DEEPDIVE] '{}' delivered source(s) never cited in the text: {}",
                    subject, uncited);
        }
        // The source register: OURS, deterministic, appended AFTER every model
        // pass — the model never writes, edits or renumbers it (user mandate
        // 2026-07-13, Wikipedia-style footnotes). Wikipedia lists only what is
        // CITED: an uncited news article stays out (triage bycatch or an
        // article the author judged not worth a line must not decorate the
        // register); the data legs stay listed — they feed the figures.
        String sources = sourcesSection(m, de, cited);
        if (!sources.isEmpty()) {
            report = report.stripTrailing() + "\n\n" + sources;
        }
        // Figures: deterministic SVG from the verified material (the model never
        // draws), built BEFORE the prose so the sections could point at them
        // by ID; frozen into the record so UI and PDF show the same picture.
        List<DeepDiveRecord.ChartFigure> charts = m.charts;
        DeepDiveRecord record = new DeepDiveRecord(
                "dd-" + UUID.randomUUID().toString().substring(0, 8),
                subject, m.canonicalName, m.ticker, m.isin,
                Instant.now().getEpochSecond(), report,
                m.snapshot != null && m.snapshot.hasPrice() ? m.snapshot.price() : null,
                m.snapshot != null && m.snapshot.hasPrice() ? m.snapshot.currency() : null,
                m.evidenceCount, m.news.size(),
                System.currentTimeMillis() - t0,
                charts,
                SignalDesk.values(m.signalReadings));
        archive.append(record);
        LOG.info("[DEEPDIVE] '{}' report {} archived: {} chars, {} figure(s), {} ms "
                        + "({} of {} sections written).",
                subject, record.id(), report.length(), charts.size(),
                record.durationMs(), written, SECTION_COUNT);
        finish(subject, true, record.id());
    }

    // -- the per-section writing loop (author → weave → examiner → challenger) --

    /**
     * Produces one section body through the desk's full loop, or {@code null}
     * when the shelf carries nothing (the typesetter then sets the honest
     * literal — no model call ever writes ON nothing, which is what used to
     * hallucinate a room discussion out of an empty unit).
     *
     * <p>The flow keeps every step TINY (user mandate): the author writes from
     * the BASE blocks, then each STORY (the routed articles grouped by
     * normalized-title similarity — one story often arrives via many re-spins)
     * is WOVEN in as its own small call — the standing text is the single
     * source of truth, and every new source is held against it (confirm → add
     * marker; contradict → source against source; new → integrate). Only then
     * the challenger grills the section against the FULL shelf.
     */
    private String writeSection(ChatModel model, Prompts prompts, String subject, String header,
            String heading, Shelf shelf, boolean de, String progressLabel,
            Set<String> storyDropWords, Material m, boolean chronicleSeed) {
        if (shelf == null || shelf.isEmpty()) return null;
        String newsHeader = "NEWS (verified, last " + NEWS_MAX_AGE_DAYS + " days):\n";

        // STORY GROUPING (deterministic, before any model call): the routed
        // sources are largely re-spins of the SAME story — German content
        // farms re-report one event daily under fresh titles (live OTLK run:
        // 50 routed sources on Lage, most the same FDA date) — and each
        // re-spin used to cost a full weave re-emission or at least an
        // arbiter call. One weave step per STORY now; every member's source
        // line (and marker) still rides into the step, so no source is ever
        // silently dropped (user mandate: every source contributes).
        List<List<String>> groups = groupStoryBlocks(shelf.newsBlocks(), storyDropWords);
        if (groups.size() < shelf.newsBlocks().size()) {
            LOG.info("[DEEPDIVE] '{}' section '{}': {} routed source(s) grouped into {} "
                    + "story step(s).", subject, heading, shelf.newsBlocks().size(),
                    groups.size());
        }

        // Seed: the base blocks — or, base-less, the first story group.
        String seed = shelf.base();
        List<String> seedGroup = null;
        // THE PRESS CHRONICLE (scratchpad pilot, user mandate 2026-07-16):
        // for the chronicle-seeded section the desk first DISTILLS the whole
        // routed press into a dated fact note - facts as facts, nothing
        // dropped, no relevance filter - and the author writes the section's
        // ARC from it instead of discovering the story weave by weave (the
        // litany's root). The EXAMINER'S yardstick stays the RAW press: a
        // figure the chronicle invented dies at the first inspection, so the
        // note can never launder into the material. Fail-open: a whiffed
        // chronicle falls back to the classic seed path.
        String chronicleNote = chronicleSeed && !shelf.newsBlocks().isEmpty()
                ? chronicleNote(model, prompts, subject, header, shelf.newsBlocks(),
                        m, progressLabel, de)
                : null;
        boolean rawFed = !isBlank(chronicleNote);
        String fed;
        if (rawFed) {
            String raw = newsHeader + String.join("", shelf.newsBlocks());
            fed = isBlank(seed) ? raw : seed + "\n" + raw;
            seed = (isBlank(seed) ? "" : seed)
                    + "PRESS CHRONICLE (the desk's own dated fact note, distilled from the "
                    + "routed press - the section's ARC; every line carries its markers):\n"
                    + chronicleNote;
        } else {
            if (isBlank(seed) && !groups.isEmpty()) {
                seedGroup = groups.remove(0);
                seed = newsHeader + String.join("", seedGroup);
            }
            fed = seed;
        }

        // Author. One retry — an aborted section over a single hiccup is waste.
        String body = null;
        for (int attempt = 1; attempt <= 2 && body == null; attempt++) {
            String raw = cleanReport(chatGateway.chat(model, prompts.section(),
                    authorMessage(header, heading, seed, prompts.section().length())));
            if (raw != null && raw.strip().length() >= DeepDiveFactCheck.MIN_SECTION_CHARS / 2) {
                body = raw.strip();
            } else {
                LOG.warn("[DEEPDIVE] '{}' section '{}' author attempt {} whiffed ({} chars).",
                        subject, heading, attempt, raw == null ? 0 : raw.length());
            }
        }
        if (body == null) return null;
        body = examineAndRepair(model, prompts, subject, header, heading, fed,
                markersIn(fed), de, body).body();
        // The desk journal shows every mutation as a sentence diff against
        // what the pane last showed — pure effect, never narration.
        String shown = "";
        journalDiff(subject, shown, body);
        shown = body;

        // The weave loop: EVERY story its own step, held against the standing
        // text — no bundling cap (user mandate 2026-07-13 "Iterationscap
        // entfernen"); the queue is naturally bounded by the triaged pool. A
        // step's material carries ALL of its story's source lines, so every
        // marker can be cited.
        int total = groups.size();
        int step = 0;
        int coveredSteps = 0;
        ChatModel arbiter = brain.getAgentModel();
        List<String> wovenBlocks = new ArrayList<>();
        if (seedGroup != null) wovenBlocks.add(String.join("", seedGroup));
        for (List<String> group : groups) {
            checkCancelled();
            String block = String.join("", group);
            step++;
            // Same-story gate, AI-first (user mandate 2026-07-13 "Konflikt-
            // löser"): token similarity is only the SUSPICION trigger — the
            // arbiter reads both sources and decides whether the candidate is
            // the same story re-spun (content farms mint fresh titles over
            // identical auto-notes) or carries its own news value. A skipped
            // group earns no marker, so the register never lists its members.
            String suspect = mostSimilarWoven(block, wovenBlocks);
            if (suspect != null && sameStoryVerdict(arbiter, prompts.samestory(), suspect, block)) {
                LOG.info("[DEEPDIVE] '{}' section '{}' weave step {}: arbiter ruled the story "
                        + "group ({} source(s)) a re-spin of an already woven one — skipped.",
                        subject, heading, step, group.size());
                // The re-spin's SUBSTANCE stands via its twin - its CITATION
                // must too (2026-07-16: the skip used to drop the markers, so
                // the register omitted a source whose story the report tells).
                String cited = attachMarkers(body, block);
                if (!cited.equals(body)) {
                    body = cited;
                    journalDiff(subject, shown, body);
                    shown = body;
                }
                journalNotes(subject, group.stream().map(DeepDiveService::firstLine).toList());
                continue;
            }
            // MATERIALITY SHORT-CUT (user decision 2026-07-16): the length
            // contract already forces the section to carry only the load-
            // bearing ~20 statements - weaving all 70+ stories pays the full
            // call for substance the fold then discards anyway. A tiny judge
            // asks the honest early question instead: would this story CHANGE
            // the section's read? Immaterial stories are skipped WITHOUT a
            // text citation (no fake markers) and listed in the register
            // under their own sighted-only label - read, chronicled, visible,
            // just not load-bearing. Doubt or whiff = material = full weave.
            if (rawFed && !storyMaterial(arbiter, prompts.covered(), body, block)) {
                m.sightedOnly.addAll(markersIn(block));
                journalNotes(subject, List.of((de
                        ? "Gesichtet, ändert die Lesart nicht: "
                        : "Sighted, does not change the read: ") + firstLine(block)));
                wovenBlocks.add(block);
                coveredSteps++;
                continue;
            }
            eventBus.post(new DeepDiveProgressEvent(subject, "sections",
                    progressLabel + " · " + (de ? "Quelle " : "source ") + step + "/" + total));
            if (!rawFed) fed = fed + "\n" + newsHeader + block;
            String woven = cleanReport(chatGateway.chat(model, prompts.weave(),
                    weaveMessage(header, heading, body, block, prompts.weave().length(),
                            group.size())));
            if (isBlank(woven)) {
                LOG.warn("[DEEPDIVE] '{}' section '{}' weave step {} whiffed — story skipped.",
                        subject, heading, step);
                continue;
            }
            // The UNCHANGED sentinel (2026-07-15): a pass-case answers one
            // word instead of re-emitting the whole section, and an unchanged
            // body needs no examiner pass — the single biggest weave saving.
            if (WeatherReportService.isUnchangedSentinel(woven)
                    || woven.strip().equals(body.strip())) {
                continue;
            }
            String beforeStep = body;
            RepairOutcome out = examineAndRepair(model, prompts, subject, header, heading, fed,
                    markersIn(fed), de, woven.strip());
            body = out.body();
            // DIFF-JUDGE (user design 2026-07-16): the step examiner reads
            // ONLY the delta this weave produced beside the ONE new source -
            // concentrated attention instead of a whole-section pass (a
            // one-word direction inversion provably slipped the full-view
            // challenger, live smoke 4: a BaFin SALE narrated as a buy).
            body = diffJudgeStep(model, prompts, subject, header, heading, beforeStep,
                    body, block, fed, de);
            // RE-KNOCK (user mandate 2026-07-14): a HARD finding on a group
            // step whose members were never full-text-read means the desk may
            // be missing exactly the source that carries the figure — the
            // group digest only read the representative. Fetch the missing
            // digests NOW (session cache makes repeats free) and weave the
            // story ONCE more with the enriched material. Bounded: one
            // re-knock per group, at most MAX_REKNOCK_DIGESTS reads.
            if (out.hadHard()) {
                String extra = reknockDigests(subject, group, m);
                if (!extra.isEmpty()) {
                    fed = fed + "\n" + extra;
                    String rewoven = cleanReport(chatGateway.chat(model, prompts.weave(),
                            weaveMessage(header, heading, body, block + extra,
                                    prompts.weave().length(), group.size())));
                    if (!isBlank(rewoven)) {
                        body = examineAndRepair(model, prompts, subject, header, heading,
                                fed, markersIn(fed), de, rewoven.strip()).body();
                    }
                }
            }
            wovenBlocks.add(block);
            journalDiff(subject, shown, body);
            shown = body;
        }
        if (coveredSteps > 0) {
            LOG.info("[DEEPDIVE] '{}' section '{}': materiality short-cut skipped {} of {} "
                    + "story step(s) - sighted-only, weave calls saved.",
                    subject, heading, coveredSteps, total);
        }

        // Challenger (the desk's grilling) against the FULL shelf, each round
        // answered by a revision. NO fixed round count — the loop runs until
        // the section STANDS, the ARBITER rules the grilling repetitive, or a
        // revision no longer changes the text. Convergence is an AI judgment
        // (user mandate 2026-07-13 "Konfliktlöser", AI-first): the arbiter
        // reads the objection history — problems only, since the quote half
        // changes with every revision by construction — and decides whether
        // the newest round still carries new substance. Fail-open: an arbiter
        // whiff lets the grilling continue; the backstop stays the runaway net.
        String material = shelf.combined();
        Set<Integer> allowedMarkers = markersIn(material);
        List<String> objectionHistory = new ArrayList<>();
        for (int round = 1; round <= CHALLENGE_ROUNDS_BACKSTOP; round++) {
            checkCancelled();
            eventBus.post(new DeepDiveProgressEvent(subject, "sections",
                    progressLabel + " · " + (de ? "Anfechtung" : "challenge") + " " + round));
            List<String> objections = challenge(model, prompts, header, heading, body, material);
            if (objections.isEmpty()) {
                LOG.info("[DEEPDIVE] '{}' section '{}' stands after {} challenge round(s).",
                        subject, heading, round - 1);
                break;
            }
            String problems = objections.stream()
                    .map(DeepDiveService::objectionProblem)
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (!objectionHistory.isEmpty()
                    && arbiterSaysRepetitive(arbiter, prompts.arbiter(), heading,
                            objectionHistory, problems)) {
                LOG.info("[DEEPDIVE] '{}' section '{}': arbiter ruled round {} repetitive — "
                        + "no new substance, section stands as reviewed.",
                        subject, heading, round);
                break;
            }
            objectionHistory.add(problems);
            journalNotes(subject, objections);
            LOG.info("[DEEPDIVE] '{}' section '{}' challenged ({} objection(s), round {}).",
                    subject, heading, objections.size(), round);
            eventBus.post(new DeepDiveProgressEvent(subject, "sections",
                    progressLabel + " · " + (de ? "Revision" : "revision")));
            String revised = revise(model, prompts, header, heading, body, material,
                    String.join("\n", objections));
            if (isBlank(revised)) {
                LOG.warn("[DEEPDIVE] '{}' section '{}' revision whiffed — keeping the "
                        + "standing draft.", subject, heading);
                break;
            }
            String before = body.strip();
            body = examineAndRepair(model, prompts, subject, header, heading, material,
                    allowedMarkers, de, revised.strip()).body();
            journalDiff(subject, shown, body);
            shown = body;
            if (body.strip().equals(before)) {
                LOG.info("[DEEPDIVE] '{}' section '{}': revision changed nothing — "
                        + "converged after {} round(s).", subject, heading, round);
                break;
            }
            if (round == CHALLENGE_ROUNDS_BACKSTOP) {
                LOG.warn("[DEEPDIVE] '{}' section '{}' hit the challenge runaway backstop ({}).",
                        subject, heading, CHALLENGE_ROUNDS_BACKSTOP);
            }
        }

        // The copy editor (user mandate 2026-07-13 STRONG PRIORITY): one pass
        // for rhythm, emphasis and scanability — the content is mechanically
        // frozen by the acceptance gate below. The number wall is ITS brief
        // (not the revision loop's — a revision provably never reduces the
        // count): over the density line the message says so explicitly, and
        // the gate permits cutting figures while forbidding any alteration.
        eventBus.post(new DeepDiveProgressEvent(subject, "sections",
                progressLabel + " · " + (de ? "Lektorat" : "copy edit")));
        int figures = DeepDiveFactCheck.figureCount(body, de);
        String densityNote = figures > DeepDiveFactCheck.MAX_FIGURES_PER_SECTION
                ? "\n\nDENSITY: the text carries " + figures + " figures — a number wall. "
                        + "Cut the recited series down to the load-bearing two or three "
                        + "values per paragraph (drop the whole clause with its figure); "
                        + "the charts carry the series."
                : "";
        // The gate's rejection reason goes BACK to the editor for one retry —
        // the desk-realistic feedback loop (live: half of all copy edits died
        // at the gate, mostly a lost marker or an over-cut, and with them the
        // report's typography).
        String rejection = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String feedback = rejection == null ? ""
                    : "\n\nREJECTED PREVIOUS EDIT (" + rejection + "): every source marker "
                            + "and every kept figure must survive VERBATIM, and the section "
                            + "must not shrink below half its length. Edit more conservatively.";
            String polished = cleanReport(chatGateway.chat(model, prompts.polish(),
                    header + "SECTION: ## " + heading + densityNote + feedback
                            + "\n\nTEXT:\n" + body));
            rejection = polishRejection(body, polished, de);
            if (rejection == null) {
                body = DeepDiveFactCheck.splitLongParagraphs(
                        DeepDiveFactCheck.scrubResidue(polished.strip()));
                journalDiff(subject, shown, body);
                break;
            }
            LOG.info("[DEEPDIVE] '{}' section '{}': copy edit attempt {} rejected ({}){}",
                    subject, heading, attempt, rejection,
                    attempt == 2 ? " — keeping the reviewed draft." : " — one retry with the reason.");
        }

        if (body.strip().length() < DeepDiveFactCheck.MIN_SECTION_CHARS) {
            LOG.warn("[DEEPDIVE] '{}' section '{}' ended below substance minimum — honest literal.",
                    subject, heading);
            return null;
        }
        return body;
    }

    /**
     * The copy editor's UNTOUCHABILITY gate: every figure that SURVIVES must
     * already exist in the original (subset — cutting a recited series is the
     * editor's densest tool, per the polish prompt's own rules; altering or
     * inventing one is content drift), the source markers must be IDENTICAL
     * (attribution never thins), no protocol residue sneaked in, and the
     * length stayed within cutting-not-gutting bounds.
     */
    static boolean polishAcceptable(String before, String after, boolean de) {
        return polishRejection(before, after, de) == null;
    }

    /** The gate's verdict with its reason — null when the polish is acceptable. */
    static String polishRejection(String before, String after, boolean de) {
        if (after == null || after.isBlank() || before == null) return "empty reply";
        String a = after.strip();
        String b = before.strip();
        if (a.length() < b.length() * 0.5) {
            return "gutted: " + b.length() + " -> " + a.length() + " chars";
        }
        if (a.length() > b.length() * 1.35) {
            return "bloated: " + b.length() + " -> " + a.length() + " chars";
        }
        if (a.contains("## ") || a.contains("<<<") || a.contains("```")) {
            return "protocol residue";
        }
        Set<Double> beforeNums = DeepDiveFactCheck.numberSet(b, de);
        Set<Double> afterNums = DeepDiveFactCheck.numberSet(a, de);
        if (!beforeNums.containsAll(afterNums)) {
            afterNums.removeAll(beforeNums);
            return "figure(s) not in the reviewed draft: " + afterNums;
        }
        if (!markersIn(b).equals(markersIn(a))) {
            return "marker set changed: " + markersIn(b) + " -> " + markersIn(a);
        }
        return null;
    }

    /**
     * The PROBLEM half of a challenge line ({@code E: "<Zitat>" — <Problem>}):
     * everything after the last dash separator outside the quote. Falls back
     * to the whole line when the format is loose.
     */
    static String objectionProblem(String line) {
        if (line == null) return "";
        int quoteEnd = Math.max(line.lastIndexOf('"'), line.lastIndexOf('“'));
        String tail = quoteEnd >= 0 && quoteEnd < line.length() - 1
                ? line.substring(quoteEnd + 1) : line;
        for (String dash : new String[]{" — ", " – ", " - "}) {
            int i = tail.indexOf(dash);
            if (i >= 0) return tail.substring(i + dash.length()).strip();
        }
        return tail.strip();
    }

    /**
     * The already-woven block most similar to the candidate — null when none
     * crosses the suspicion threshold (then no arbiter call is spent).
     */
    private static String mostSimilarWoven(String candidate, List<String> wovenBlocks) {
        String best = null;
        double bestScore = 0;
        for (String prior : wovenBlocks) {
            double s = tokenSimilarity(candidate, prior);
            if (s > bestScore) {
                bestScore = s;
                best = prior;
            }
        }
        return bestScore >= WEAVE_SUSPICION_SIMILARITY ? best : null;
    }

    /**
     * The arbiter's same-story verdict on a suspicious weave candidate.
     * Fail-open: a whiffed call weaves the source (losing coverage is the
     * worse error).
     */
    private boolean sameStoryVerdict(ChatModel arbiter, String prompt, String woven,
            String candidate) {
        if (arbiter == null) return false;
        try {
            String reply = chatGateway.chat(arbiter, prompt,
                    "WOVEN SOURCE:\n" + woven + "\n\nNEW CANDIDATE:\n" + candidate);
            return reply != null && DUPLICATE_TRUE.matcher(reply).find();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The arbiter's repetition verdict on the newest challenge round against
     * the round history. Fail-open: a whiff lets the grilling continue (the
     * runaway backstop stays the net).
     */
    private boolean arbiterSaysRepetitive(ChatModel arbiter, String prompt, String heading,
            List<String> history, String newest) {
        if (arbiter == null) return false;
        StringBuilder sb = new StringBuilder(1024);
        sb.append("SECTION: ").append(heading).append("\n\nEARLIER ROUNDS:\n");
        for (int i = 0; i < history.size(); i++) {
            sb.append("Round ").append(i + 1).append(":\n").append(history.get(i)).append('\n');
        }
        sb.append("\nNEWEST ROUND:\n").append(newest);
        try {
            String reply = chatGateway.chat(arbiter, prompt, sb.toString());
            return reply != null && REPETITIVE_TRUE.matcher(reply).find();
        } catch (Exception e) {
            return false;
        }
    }

    private static final Pattern DUPLICATE_TRUE =
            Pattern.compile("\"duplicate\"\\s*:\\s*true");
    private static final Pattern REPETITIVE_TRUE =
            Pattern.compile("\"repetitive\"\\s*:\\s*true");

    // ---- the desk journal: the live review pane's diff stream (user mandate
    // 2026-07-14 "nur DIFF — rot, grün, orange"; effect, never narration) ----

    private static final int JOURNAL_LINE_CHARS = 240;

    /**
     * The desk journal's display language - the journal is user-facing UI, so
     * its composed notes follow {@code user.language} like the examiner texts
     * (German when de, English otherwise).
     */
    private boolean journalGerman() {
        return "de".equalsIgnoreCase(brain.getUserLanguage().code());
    }

    private void journalNotes(String subject, List<String> notes) {
        List<de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveJournalEvent.Line> lines =
                notes.stream()
                        .filter(n -> n != null && !n.isBlank())
                        .map(n -> new de.bsommerfeld.wsbg.terminal.agent.event
                                .DeepDiveJournalEvent.Line("note", clipJournal(n)))
                        .toList();
        if (!lines.isEmpty()) {
            eventBus.post(new de.bsommerfeld.wsbg.terminal.agent.event
                    .DeepDiveJournalEvent(subject, lines));
        }
    }

    private void journalDiff(String subject, String before, String after) {
        var lines = sentenceDiff(before, after);
        if (!lines.isEmpty()) {
            eventBus.post(new de.bsommerfeld.wsbg.terminal.agent.event
                    .DeepDiveJournalEvent(subject, lines));
        }
    }

    /**
     * Sentence-level LCS diff between two section states, rendered as ONE
     * GitHub-style hunk: {@code del}/{@code add} lines carry the change,
     * {@code ctx} lines the ONE unchanged sentence around each edit run,
     * {@code gap} lines ("⋯") elide longer unchanged stretches. Old/new
     * 1-based sentence numbers ride every line (the dual gutter). Empty when
     * nothing changed. Deterministic before/after comparison, never model
     * self-report.
     */
    static List<de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveJournalEvent.Line>
            sentenceDiff(String before, String after) {
        List<String> a = before == null || before.isBlank()
                ? List.of() : DeepDiveFactCheck.sentences(before);
        List<String> b = after == null || after.isBlank()
                ? List.of() : DeepDiveFactCheck.sentences(after);
        String[] an = a.stream().map(s -> s.strip().replaceAll("\\s+", " ")).toArray(String[]::new);
        String[] bn = b.stream().map(s -> s.strip().replaceAll("\\s+", " ")).toArray(String[]::new);
        int n = an.length, m = bn.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = an[i].equals(bn[j]) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        // Op stream: eq/del/add with 1-based old/new sentence numbers.
        record Op(String kind, String text, int oldNo, int newNo) {}
        List<Op> ops = new ArrayList<>();
        int i = 0, j = 0;
        boolean changed = false;
        while (i < n && j < m) {
            if (an[i].equals(bn[j])) {
                ops.add(new Op("ctx", b.get(j), i + 1, j + 1));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op("del", a.get(i), i + 1, 0));
                i++;
                changed = true;
            } else {
                ops.add(new Op("add", b.get(j), 0, j + 1));
                j++;
                changed = true;
            }
        }
        while (i < n) {
            ops.add(new Op("del", a.get(i), i + 1, 0));
            i++;
            changed = true;
        }
        while (j < m) {
            ops.add(new Op("add", b.get(j), 0, j + 1));
            j++;
            changed = true;
        }
        if (!changed) return List.of();
        // Keep ctx only DIRECTLY beside a change; longer unchanged runs elide
        // to one gap marker (the hunk stays a focused container).
        boolean[] keep = new boolean[ops.size()];
        for (int k = 0; k < ops.size(); k++) {
            if (!ops.get(k).kind().equals("ctx")) {
                keep[k] = true;
                if (k > 0) keep[k - 1] = true;
                if (k + 1 < ops.size()) keep[k + 1] = true;
            }
        }
        List<de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveJournalEvent.Line> out =
                new ArrayList<>();
        boolean inGap = false;
        for (int k = 0; k < ops.size(); k++) {
            if (!keep[k]) {
                if (!inGap) {
                    out.add(new de.bsommerfeld.wsbg.terminal.agent.event
                            .DeepDiveJournalEvent.Line("gap", "⋯"));
                    inGap = true;
                }
                continue;
            }
            inGap = false;
            Op op = ops.get(k);
            out.add(new de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveJournalEvent.Line(
                    op.kind(), clipJournal(op.text()), op.oldNo(), op.newNo()));
        }
        return out;
    }

    private static String clipJournal(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= JOURNAL_LINE_CHARS
                ? t : t.substring(0, JOURNAL_LINE_CHARS - 1).stripTrailing() + "…";
    }

    /** The first line of a material block — its "SOURCE [n] publisher — title" head. */
    private static String firstLine(String block) {
        if (block == null) return "";
        int nl = block.indexOf('\n');
        return (nl < 0 ? block : block.substring(0, nl)).strip();
    }

    /** Token-set Jaccard similarity of two texts — the rephrase detector. */
    static double tokenSimilarity(String a, String b) {
        Set<String> ta = tokenSet(a);
        Set<String> tb = tokenSet(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(ta);
        intersection.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenSet(String s) {
        Set<String> out = new HashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }

    // -- story grouping (the weave-churn brake) --

    /** Title token-Jaccard at or above this = the same story re-reported. */
    private static final double STORY_GROUP_SIMILARITY = 0.5;
    /** Tokens every finance title carries — they say nothing about the story. */
    private static final Set<String> GENERIC_STORY_TOKENS = Set.of("aktie", "aktien");

    /**
     * Deterministic STORY GROUPING of routed news blocks by normalized-title
     * similarity: German content farms re-report the same event daily under
     * fresh titles (live OTLK run: 50 routed sources on one section, most the
     * same FDA story — each a full weave re-emission or an arbiter call).
     * Greedy with transitive chaining: a block joins the first group where any
     * member's significant-title-token Jaccard reaches
     * {@value #STORY_GROUP_SIMILARITY}. Order is preserved (oldest-first block
     * order; a group sits at its first member's position) and NO member is
     * ever dropped — every source line rides into its group's weave step so
     * its marker can be cited (user mandate: every source contributes).
     */
    static List<List<String>> groupStoryBlocks(List<String> blocks, Set<String> dropWords) {
        List<List<String>> groups = new ArrayList<>();
        List<List<Set<String>>> groupTokens = new ArrayList<>();
        for (String block : blocks) {
            Set<String> tokens = storyTokens(block, dropWords);
            int home = -1;
            outer:
            for (int g = 0; g < groups.size(); g++) {
                for (Set<String> member : groupTokens.get(g)) {
                    if (setJaccard(tokens, member) >= STORY_GROUP_SIMILARITY) {
                        home = g;
                        break outer;
                    }
                }
            }
            if (home < 0) {
                groups.add(new ArrayList<>(List.of(block)));
                groupTokens.add(new ArrayList<>(List.of(tokens)));
            } else {
                groups.get(home).add(block);
                groupTokens.get(home).add(tokens);
            }
        }
        return groups;
    }

    /**
     * The significant title tokens of one news block: the head line minus its
     * bullet, markers, timestamp bracket and "· Publisher" tail — lowercased,
     * umlauts/diacritics stripped, punctuation split, words shorter than three
     * chars, the subject's own name words and generic finance tokens dropped
     * (they would glue EVERY title about the subject together).
     */
    static Set<String> storyTokens(String block, Set<String> dropWords) {
        String line = firstLine(block)
                .replaceFirst("^[-–—•]\\s*", "")
                .replaceAll("\\[[^\\]]*]", " ");
        int pub = line.lastIndexOf(" · ");
        if (pub > 0) line = line.substring(0, pub);
        Set<String> out = new HashSet<>();
        for (String t : normalizeStoryText(line).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 3 && !dropWords.contains(t) && !GENERIC_STORY_TOKENS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** The subject's own name/ticker words — dropped from story tokens. */
    static Set<String> storyDropWords(String canonicalName, String ticker) {
        Set<String> out = new HashSet<>(GENERIC_STORY_TOKENS);
        if (ticker != null) {
            for (String t : normalizeStoryText(ticker).split("[^\\p{L}\\p{N}]+")) {
                if (!t.isEmpty()) out.add(t);
            }
        }
        if (canonicalName != null) {
            for (String t : normalizeStoryText(canonicalName).split("[^\\p{L}\\p{N}]+")) {
                if (t.length() >= 3) out.add(t);
            }
        }
        return out;
    }

    /** Lowercase + diacritics stripped (ä→a) — the story tokens' normal form. */
    private static String normalizeStoryText(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static double setJaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /**
     * Distills the routed press of one section into the dated FACT CHRONICLE
     * - the scratchpad note the author writes the section's arc from. Pure
     * restatement (facts as facts, no relevance judgment, nothing dropped),
     * batched under the context budget when the press outsizes one call.
     * Returns {@code null} on a whiff - the caller falls back to the classic
     * seed path, never blocks on the note.
     */
    private String chronicleNote(ChatModel model, Prompts prompts, String subject,
            String header, List<String> blocks, Material m, String progressLabel, boolean de) {
        try {
            eventBus.post(new DeepDiveProgressEvent(subject, "sections",
                    progressLabel + " · " + (de ? "Chronik" : "chronicle")));
            // The chronicle spans the WHOLE press picture, not just the routed
            // 30-day pool (zoom-out 2026-07-16: timeline, multi-year history
            // and routed news were three parallel headline lists for one
            // section - the chronicle is where they become ONE arc). The
            // archive lines ride as additional dated sources into the first
            // batch; the shelf keeps the raw blocks as the examiner's
            // yardstick.
            StringBuilder archive = new StringBuilder(1024);
            Map<String, Integer> nums = sourceNumbers(m);
            appendPressTimeline(archive, m.pressTimeline, nums);
            appendPressHistory(archive, m, nums);
            if (archive.length() > 0) {
                blocks = new ArrayList<>(blocks);
                blocks.add(0, archive.toString());
            }
            int budget = Math.max(2000, inputBudgetChars() - prompts.chronicle().length()
                    - header.length() - 400);
            List<String> batches = new ArrayList<>();
            StringBuilder batch = new StringBuilder();
            for (String b : blocks) {
                if (batch.length() > 0 && batch.length() + b.length() > budget) {
                    batches.add(batch.toString());
                    batch.setLength(0);
                }
                batch.append(b);
            }
            if (batch.length() > 0) batches.add(batch.toString());
            StringBuilder note = new StringBuilder();
            for (String b : batches) {
                checkCancelled();
                String reply = cleanReport(chatGateway.chat(model, prompts.chronicle(),
                        header + "SOURCES:\n" + b));
                if (!isBlank(reply)) note.append(reply.strip()).append('\n');
            }
            if (note.length() == 0) return null;
            LOG.info("[DEEPDIVE] '{}' press chronicle: {} source block(s) distilled to "
                            + "{} chars in {} batch(es).", subject, blocks.size(),
                    note.length(), batches.size());
            return note.toString();
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("[DEEPDIVE] '{}' press chronicle failed - classic seed path: {}",
                    subject, e.getMessage());
            return null;
        }
    }

    /**
     * The coverage judge of one weave step: does the STANDING TEXT already
     * carry this story's load-bearing substance? One tiny JSON verdict -
     * skipping the weave is an AI judgment (never a similarity threshold),
     * and a whiff answers false so the story weaves normally (fail-open,
     * nothing is ever lost to a broken call).
     */
    private boolean storyMaterial(ChatModel judge, String prompt, String body, String block) {
        try {
            String reply = chatGateway.chat(judge, prompt,
                    "STANDING TEXT:\n" + body + "\n\nSTORY:\n" + block);
            if (reply == null) return true;
            Matcher m = MATERIAL_VERDICT.matcher(reply);
            // Fail-open: no parseable verdict means the story weaves normally
            // - a whiffed judge must never cost substance.
            return !m.find() || Boolean.parseBoolean(m.group(1));
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] materiality judge whiffed: {}", e.getMessage());
            return true;
        }
    }

    private static final Pattern MATERIAL_VERDICT =
            Pattern.compile("\"material\"\\s*:\\s*(true|false)");

    /**
     * Attaches a covered story's markers to the paragraph most similar to it
     * - the substance already stands there, so the citation belongs there
     * (deterministic; markers the body already cites are not repeated).
     */
    static String attachMarkers(String body, String block) {
        Set<Integer> already = markersIn(body);
        List<Integer> fresh = new ArrayList<>();
        for (Integer n : markersIn(block)) {
            if (!already.contains(n)) fresh.add(n);
        }
        if (fresh.isEmpty()) return body;
        String[] paras = body.strip().split("\n\\s*\n");
        int bestIdx = 0;
        double best = -1;
        for (int i = 0; i < paras.length; i++) {
            double sim = tokenSimilarity(block, paras[i]);
            if (sim > best) {
                best = sim;
                bestIdx = i;
            }
        }
        StringBuilder marks = new StringBuilder();
        for (Integer n : fresh) marks.append('[').append(n).append(']');
        paras[bestIdx] = paras[bestIdx].stripTrailing() + " " + marks;
        return String.join("\n\n", paras);
    }

    /** The letterhead plus the previous section's closing thought (the red thread). */
    private static String headerWithThought(String header, String previousThought) {
        if (previousThought == null || previousThought.isBlank()) return header;
        return header + "PREVIOUS SECTION ENDED WITH: " + previousThought.strip() + "\n\n";
    }

    /** The final sentence of a body — where the narrative currently stands. */
    static String lastSentence(String body) {
        if (body == null || body.isBlank()) return "";
        String[] paras = body.strip().split("\n\\s*\n");
        List<String> sentences = DeepDiveFactCheck.sentences(paras[paras.length - 1]);
        return sentences.isEmpty() ? "" : sentences.get(sentences.size() - 1).strip();
    }

    /**
     * The weaver's user message: standing text + exactly ONE new STORY — a
     * single source, or a grouped bundle of several sources reporting the same
     * event (then the label says so and demands EVERY marker be cited on the
     * statements it supports; no member may silently vanish).
     */
    private String weaveMessage(String header, String heading, String body, String block,
            int promptChars, int groupSize) {
        String label = groupSize > 1
                ? "NEW SOURCES (" + groupSize + " reports of ONE story - the same event "
                        + "may arrive via several sources; work the story in ONCE and cite "
                        + "EVERY marker below on the statements it supports):\n"
                : "NEW SOURCE:\n";
        // Prevention beats scissors (user mandate 2026-07-15 "NICHTS darf
        // wegfallen"): once the standing text nears its contract, every
        // further weave must integrate by TIGHTENING — merge and compress the
        // weaker sentences to make room — instead of appending; the house cut
        // stays the last resort, not the plan.
        String budgetNote = "";
        if (body != null && body.length() > WEAVE_TIGHTEN_FROM) {
            budgetNote = "\n\nLENGTH BUDGET: the standing text has " + body.length()
                    + " of at most " + DeepDiveFactCheck.MAX_SECTION_CHARS
                    + " characters. Integrate the new material by TIGHTENING - merge "
                    + "overlapping statements, compress wordy ones, keep every fact, "
                    + "figure and marker. The result must NOT be longer than the "
                    + "standing text.";
        }
        String fixed = header + "SECTION: ## " + heading + "\n\nSTANDING TEXT:\n" + body
                + budgetNote + "\n\n" + label;
        return fixed + budgeted(block, promptChars + fixed.length());
    }

    /** Weave calls carry the tighten instruction from this standing-text length on. */
    private static final int WEAVE_TIGHTEN_FROM =
            (int) (DeepDiveFactCheck.MAX_SECTION_CHARS * 0.8);

    /**
     * The deterministic examiner cycle: inspect, let the author fix, re-inspect,
     * and SURGICALLY remove what still fails the hard figure/date checks — the
     * fact-checking end state is "every claim sourced or out", never "archived
     * anyway". Residue lines (protocol tokens) are scrubbed mechanically.
     */
    /** The examiner pass's outcome: the repaired body plus whether HARD findings fell. */
    private record RepairOutcome(String body, boolean hadHard) {
    }

    /** At most this many full texts are fetched when the desk re-knocks on a group. */
    private static final int MAX_REKNOCK_DIGESTS = 3;

    /**
     * The desk knocks again (user mandate 2026-07-14): fetches the full-text
     * digests of a story group's members that were NOT read by the group
     * digest (only the representative was). Returns a material block with the
     * new digests, or {@code ""} when nothing was missing or readable.
     */
    private String reknockDigests(String subject, List<String> group, Material m) {
        EditorialAgent agent = editorialAgent;
        if (agent == null || m == null || m.newsLinksByBlock.isEmpty()) return "";
        StringBuilder extra = new StringBuilder(256);
        int fetched = 0;
        for (String memberBlock : group) {
            if (fetched >= MAX_REKNOCK_DIGESTS) break;
            String link = m.newsLinksByBlock.get(memberBlock);
            if (link == null || !isBlank(m.digests.get(link))) continue;
            checkCancelled();
            String digest = agent.newsDigester().digestNow(link);
            if (isBlank(digest)) continue;
            if (!(m.digests instanceof LinkedHashMap)) m.digests = new LinkedHashMap<>(m.digests);
            m.digests.put(link.trim(), digest);
            extra.append("  - ").append(digest.replace('\n', ' ').strip()).append('\n');
            fetched++;
        }
        if (extra.length() == 0) return "";
        journalNotes(subject, List.of(journalGerman()
                ? "Digest nachgefordert — " + fetched + " Volltext(e) für diese Story gelesen"
                : "Digest requested — " + fetched + " full text(s) read for this story"));
        LOG.info("[DEEPDIVE] '{}' re-knock: {} missing full text(s) fetched for a story group.",
                subject, fetched);
        return "REQUESTED FULL-TEXT DIGESTS (the desk asked for the missing sources):\n" + extra;
    }

    private RepairOutcome examineAndRepair(ChatModel model, Prompts prompts, String subject,
            String header, String heading, String material, Set<Integer> allowedMarkers,
            boolean de, String body) {
        // Raw source-list lines ("- [19] [2026-07-12 09:26] Titel · Börse
        // Express", "[17] [17] Titel.") and sentence-leading markers are
        // scrubbed MECHANICALLY before any objection is raised — digest-less
        // sources make the weave model paste its input list, and each
        // INTEGRITY round used to cost two model calls (live OTLK run).
        // List lines first: the timestamp brackets they are recognized by are
        // exactly what the bracket scrub below would strip.
        body = DeepDiveFactCheck.scrubSourceListLines(body);
        // Pseudo-citations ("[2026-07-13 18:51]") are scrubbed MECHANICALLY
        // before any model call — a reviser provably re-emits its own bracket
        // habit round after round (live: five identical RESIDUE objections in
        // Der Raum), and the typesetter scrubs them at assembly anyway.
        body = DeepDiveFactCheck.scrubNonMarkerBrackets(body);
        // Length pipeline (2026-07-15, user mandate "NICHTS darf wegfallen"):
        // over-max first gets ONE dedicated CONDENSE call — compress, merge,
        // keep every fact/figure/marker (a generic LENGTH revision provably
        // GREW the text; a condense instruction is a different job). Only
        // what still overflows after that meets the house scissors, loudly —
        // the block/sentence-boundary cut is the last resort, never the plan.
        if (body != null && body.strip().length() > DeepDiveFactCheck.MAX_SECTION_CHARS) {
            String condensed = condense(model, prompts, header, heading, body);
            if (!isBlank(condensed) && condensed.strip().length() < body.strip().length()) {
                LOG.info("[DEEPDIVE] '{}' section '{}': condense pass {} -> {} chars.",
                        subject, heading, body.strip().length(), condensed.strip().length());
                body = condensed.strip();
            }
        }
        if (body != null && body.strip().length() > DeepDiveFactCheck.MAX_SECTION_CHARS) {
            String cut = DeepDiveFactCheck.cutToLength(body,
                    DeepDiveFactCheck.MAX_SECTION_CHARS);
            LOG.warn("[DEEPDIVE] '{}' section '{}': house length cut ({} -> {} chars) "
                    + "— tail content LOST despite the condense pass.", subject, heading,
                    body.strip().length(), cut.strip().length());
            body = cut;
        }
        // Density is DELIBERATELY not enforced here: a revision provably
        // never reduces the figure count (live: "18 figures" stood identical
        // across ten challenge rounds — pure churn). The number wall is the
        // copy editor's job — its message carries the density note and its
        // gate allows CUTTING figures, never altering them.
        List<DeepDiveFactCheck.Objection> objections =
                DeepDiveFactCheck.inspect(body, material, allowedMarkers, de).stream()
                        .filter(o -> o.kind() != DeepDiveFactCheck.Objection.Kind.DENSITY)
                        .toList();
        if (objections.isEmpty()) return new RepairOutcome(body, false);
        boolean hadHard = objections.stream().anyMatch(DeepDiveFactCheck.Objection::hard);
        LOG.info("[DEEPDIVE] '{}' section '{}': examiner raised {} objection(s): {}",
                subject, heading, objections.size(),
                objections.stream().map(o -> o.kind() + " " + o.problem()).toList());
        journalNotes(subject, objections.stream()
                .map(o -> "\"" + o.quote() + "\" — " + o.problem()).toList());
        String revised = revise(model, prompts, header, heading, body, material,
                renderObjections(objections));
        if (!isBlank(revised)) body = revised.strip();
        // Surgery with RE-CHECK: one cut can expose (or miss) further hard
        // findings — the pass ends only when a re-inspect comes back clean
        // (bounded; live run 9: a twin sentence survived a single-pass cut).
        for (int pass = 0; pass < 3; pass++) {
            List<DeepDiveFactCheck.Objection> hard =
                    DeepDiveFactCheck.inspect(body, material, allowedMarkers, de).stream()
                            .filter(DeepDiveFactCheck.Objection::hard).toList();
            if (hard.isEmpty()) break;
            hadHard = true;
            String cut = DeepDiveFactCheck.removeOffendingSentences(body, hard);
            LOG.info("[DEEPDIVE] '{}' section '{}': {} unverifiable figure sentence(s) "
                            + "removed after revision ({} -> {} chars).",
                    subject, heading, hard.size(), body.length(), cut.length());
            if (cut.equals(body)) break; // nothing removable — avoid a spin
            body = cut;
        }
        // Paragraph shape is TYPESET, not objected (the retired STRUCTURE
        // objection never converged — every weave re-emission flattened the
        // breaks again): walls of text split deterministically at sentence
        // boundaries.
        return new RepairOutcome(
                DeepDiveFactCheck.splitLongParagraphs(DeepDiveFactCheck.scrubResidue(body)),
                hadHard);
    }

    /**
     * ONE dedicated condense call for an over-budget section: the reviser gets
     * a single objection whose repair is COMPRESSION — merge overlapping
     * statements, cut filler, keep every fact, figure and marker. Returns the
     * model's text (caller keeps it only when it actually shrank).
     */
    private String condense(ChatModel model, Prompts prompts, String header,
            String heading, String body) {
        String bodyHead = body.strip().substring(0, Math.min(60, body.strip().length()));
        String objection = "E: \"" + bodyHead + "\" - der Abschnitt "
                + "überschreitet den Längenvertrag (" + body.strip().length() + " von maximal "
                + DeepDiveFactCheck.MAX_SECTION_CHARS + " Zeichen). Repariere durch "
                + "VERDICHTEN, nicht durch Streichen: verschmelze überlappende Aussagen, "
                + "kürze Füllwörter und Umwege - JEDER Fakt, jede Zahl und jeder "
                + "Quellenmarker bleibt erhalten.";
        try {
            String reply = chatGateway.chat(model, prompts.revise(),
                    header + "SECTION: ## " + heading + "\n\nSTANDING TEXT:\n" + body
                            + "\n\nOBJECTIONS:\n" + objection);
            return reply == null ? null : cleanReport(reply);
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] condense pass failed: {}", e.getMessage());
            return null;
        }
    }

    /** The author's user message, material hard-budgeted against the window. */
    private String authorMessage(String header, String heading, String material, int promptChars) {
        String fixed = header + "SECTION TO WRITE: ## " + heading
                + "\n\nMATERIAL (verified blocks; [n] = source markers to copy):\n";
        return fixed + budgeted(material, promptChars + fixed.length());
    }

    /** Reclaimed articles woven per run - a runaway net, never a working limit. */
    private static final int MAX_RECLAIMED = 8;

    /**
     * The RECLAIM pass: the triage's set-aside pool, judged ONCE MORE against
     * the STANDING report - the finished picture licenses two-corner
     * connections the blind first triage could not see. Reclaimed articles
     * are digested and woven into their target sections through the normal
     * gauntlet (weave + examiner + diff-judge), and they join the news pool
     * APPENDED so every already-assigned source number stays stable.
     */
    private void reclaimPass(ChatModel model, Prompts prompts, String subject,
            List<String> headings, String[] bodies, Material m, boolean de, String header) {
        if (m.newsDiscarded.isEmpty()) return;
        eventBus.post(new DeepDiveProgressEvent(subject, "finish",
                de ? "Nachlese" : "reclaim"));
        StringBuilder report = new StringBuilder(8192);
        for (int i = 0; i < SECTION_COUNT; i++) {
            if (bodies[i] == null) continue;
            report.append("## ").append(headings.get(i)).append('\n')
                    .append(bodies[i]).append("\n\n");
        }
        if (report.isEmpty()) return;
        StringBuilder list = new StringBuilder(1024);
        List<RawNewsItem> pool = m.newsDiscarded;
        for (int i = 0; i < pool.size(); i++) {
            RawNewsItem item = pool.get(i);
            list.append(i + 1).append(". ").append(item.title());
            if (item.publisher() != null && !item.publisher().isEmpty()) {
                list.append(" · ").append(item.publisher());
            }
            list.append('\n');
        }
        String fixed = header + "REPORT:\n";
        String message = fixed + budgeted(report.toString(),
                prompts.reclaim().length() + fixed.length() + list.length() + 16)
                + "\nITEMS:\n" + list;
        String reply = chatGateway.chat(model, prompts.reclaim(), message);
        if (reply == null) return;
        // Same verdict grammar as the triage - i, relevant, quoted section tokens.
        List<int[]> reclaimed = new ArrayList<>(); // [poolIdx] with targets resolved below
        Map<Integer, String> targetsByIdx = new LinkedHashMap<>();
        Matcher obj = TRIAGE_OBJ.matcher(reply);
        while (obj.find()) {
            String o = obj.group();
            Matcher iM = TRIAGE_I.matcher(o);
            if (!iM.find()) continue;
            int i = Integer.parseInt(iM.group(1));
            if (i < 1 || i > pool.size()) continue;
            Matcher relM = TRIAGE_REL.matcher(o);
            if (!relM.find() || !Boolean.parseBoolean(relM.group(1))) continue;
            StringBuilder targets = new StringBuilder();
            Matcher tM = TRIAGE_TARGET.matcher(o);
            while (tM.find()) {
                String t = tM.group(1).toUpperCase(Locale.ROOT);
                String norm = t.startsWith("KATALYSATOR") || t.startsWith("CATALYST")
                        ? "KATALYSATOR"
                        : t.startsWith("AUSBLICK") || t.startsWith("OUTLOOK") ? "AUSBLICK"
                        : t.startsWith("BEWERTUNG") || t.startsWith("VALUATION") ? "BEWERTUNG"
                        : "LAGE";
                if (targets.indexOf(norm) < 0) {
                    if (targets.length() > 0) targets.append(',');
                    targets.append(norm);
                }
            }
            targetsByIdx.put(i - 1, targets.length() == 0 ? "LAGE" : targets.toString());
        }
        if (targetsByIdx.isEmpty()) {
            LOG.info("[DEEPDIVE] '{}' reclaim pass: none of {} set-aside item(s) earned a "
                    + "window - the first triage stands.", subject, pool.size());
            return;
        }
        int woven = 0;
        EditorialAgent digestAgent = editorialAgent;
        for (Map.Entry<Integer, String> entry : targetsByIdx.entrySet()) {
            if (woven >= MAX_RECLAIMED) {
                LOG.warn("[DEEPDIVE] '{}' reclaim hit the runaway net ({}).", subject,
                        MAX_RECLAIMED);
                break;
            }
            checkCancelled();
            RawNewsItem item = pool.get(entry.getKey());
            // Join the pool APPENDED - source numbering is order-stable.
            List<RawNewsItem> news = new ArrayList<>(m.news);
            news.add(item);
            m.news = news;
            Map<String, String> targets = new LinkedHashMap<>(m.newsTargets);
            targets.put(newsKey(item), entry.getValue());
            m.newsTargets = targets;
            if (digestAgent != null && item.link() != null && !item.link().isBlank()
                    && isBlank(m.digests.get(item.link().trim()))) {
                String digest = digestAgent.newsDigester().digestNow(item.link());
                if (!isBlank(digest)) {
                    if (!(m.digests instanceof LinkedHashMap)) {
                        m.digests = new LinkedHashMap<>(m.digests);
                    }
                    m.digests.put(item.link().trim(), digest);
                }
            }
            Map<String, Integer> nums = sourceNumbers(m);
            String block = newsItemBlock(item, m.digests,
                    mark(nums, "news:" + (m.news.size() - 1)));
            for (String target : entry.getValue().split(",")) {
                int sec = switch (target.trim()) {
                    case "KATALYSATOR" -> SEC_CATALYSTS;
                    case "AUSBLICK" -> SEC_OUTLOOK;
                    case "BEWERTUNG" -> SEC_VALUATION;
                    default -> SEC_SITUATION;
                };
                if (bodies[sec] == null) continue;
                String wovenBody = cleanReport(chatGateway.chat(model, prompts.weave(),
                        weaveMessage(header, headings.get(sec), bodies[sec], block,
                                prompts.weave().length(), 1)));
                if (isBlank(wovenBody) || WeatherReportService.isUnchangedSentinel(wovenBody)) {
                    continue;
                }
                String before = bodies[sec];
                String repaired = examineAndRepair(model, prompts, subject, header,
                        headings.get(sec), before + "\n" + block, markersIn(before + block),
                        de, wovenBody.strip()).body();
                repaired = diffJudgeStep(model, prompts, subject, header, headings.get(sec),
                        before, repaired, block, before + "\n" + block, de);
                journalDiff(subject, before, repaired);
                bodies[sec] = repaired;
            }
            woven++;
            journalNotes(subject, List.of((de ? "Nachlese: " : "Reclaimed: ") + item.title()));
        }
        LOG.info("[DEEPDIVE] '{}' reclaim pass: {} of {} set-aside item(s) earned a window "
                + "and were woven.", subject, woven, pool.size());
    }

    /**
     * The cross-section consistency review: the arbiter-grade pass that sees
     * the WHOLE report beside the verified KEY DATA and hunts exclusively the
     * conflicts a per-section challenger is blind to (section A vs section B,
     * section vs page-1 figures). Each objection is routed to the section
     * that carries the quoted claim; that section gets ONE revision against
     * its OWN shelf, re-examined. Returns {@code true} when it applied fixes
     * (the caller re-runs the review until the report stands - the final
     * instance loops, backstopped).
     */
    private boolean crossSectionConsistency(ChatModel model, Prompts prompts, String subject,
            List<String> headings, String[] bodies, Shelf[] shelves, Material m, boolean de,
            String header) {
        StringBuilder report = new StringBuilder(8192);
        for (int i = 0; i < SECTION_COUNT; i++) {
            if (bodies[i] == null) continue;
            report.append("## ").append(headings.get(i)).append('\n')
                    .append(bodies[i]).append("\n\n");
        }
        if (report.isEmpty()) return false;
        Map<String, Integer> nums = sourceNumbers(m);
        StringBuilder keyData = new StringBuilder(512);
        appendKeyData(keyData, m, nums);
        String fixed = "KEY DATA (verified):\n" + keyData + "\nREPORT:\n";
        String reply = chatGateway.chat(model, prompts.consistency(),
                fixed + budgeted(report.toString(),
                        prompts.consistency().length() + fixed.length()));
        List<String> objections = new ArrayList<>();
        if (reply != null) {
            for (String line : reply.split("\n")) {
                String s = line.strip();
                if (s.startsWith("E:")) objections.add(s);
                if (objections.size() >= 4) break;
            }
        }
        if (objections.isEmpty()) {
            LOG.info("[DEEPDIVE] '{}' cross-section review: consistent.", subject);
            return false;
        }
        journalNotes(subject, objections);
        Map<Integer, List<String>> bySection = new LinkedHashMap<>();
        for (String objection : objections) {
            int idx = owningSection(bodies, quotedSpan(objection));
            if (idx >= 0) bySection.computeIfAbsent(idx, k -> new ArrayList<>()).add(objection);
        }
        LOG.info("[DEEPDIVE] '{}' cross-section review: {} objection(s) across {} section(s).",
                subject, objections.size(), bySection.size());
        boolean applied = false;
        for (Map.Entry<Integer, List<String>> entry : bySection.entrySet()) {
            int idx = entry.getKey();
            checkCancelled();
            String material = idx == SEC_THESIS
                    ? thesisMaterial(headings, bodies, m)
                    : shelves[idx].combined();
            if (material == null || material.isBlank()) continue;
            String revised = revise(model, prompts, header, headings.get(idx), bodies[idx],
                    material, String.join("\n", entry.getValue()));
            if (isBlank(revised)) continue;
            String repaired = examineAndRepair(model, prompts, subject, header,
                    headings.get(idx), material, markersIn(material), de, revised.strip()).body();
            if (!repaired.strip().equals(bodies[idx].strip())) applied = true;
            journalDiff(subject, bodies[idx], repaired);
            bodies[idx] = repaired;
        }
        return applied;
    }

    /**
     * Removes prose pointers to figure IDs the figure layer does not carry -
     * the same copy-only contract as source markers: a 4B must never mint its
     * own cross-references ("Abbildung A9" with eight figures standing).
     */
    static String scrubUnknownFigureRefs(String report, int figureCount) {
        java.util.regex.Matcher mRef = java.util.regex.Pattern
                .compile("\\s?\\b(?:Abbildung|Figure)\\s+A(\\d+)\\b").matcher(report);
        StringBuilder sb = new StringBuilder(report.length());
        while (mRef.find()) {
            int n = Integer.parseInt(mRef.group(1));
            mRef.appendReplacement(sb, n >= 1 && n <= figureCount
                    ? java.util.regex.Matcher.quoteReplacement(mRef.group()) : "");
        }
        mRef.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]{2,}", " ");
    }

    /** The quoted span of an E-line ({@code E: "<Zitat>" — <Problem>}), or empty. */
    static String quotedSpan(String line) {
        if (line == null) return "";
        int open = line.indexOf('"');
        if (open < 0) return "";
        int close = line.indexOf('"', open + 1);
        return close > open ? line.substring(open + 1, close).strip() : "";
    }

    /** The section whose body carries the quote (whitespace-normalized), or -1. */
    static int owningSection(String[] bodies, String quote) {
        if (quote == null || quote.length() < 12) return -1;
        String needle = quote.replaceAll("\\s+", " ");
        for (int i = 0; i < bodies.length; i++) {
            if (bodies[i] == null) continue;
            if (bodies[i].replaceAll("\\s+", " ").contains(needle)) return i;
        }
        return -1;
    }

    /**
     * The DIFF-JUDGE of one weave step (user design 2026-07-16): the examiner
     * sees ONLY the sentences this step added or reworked, beside the step's
     * ONE source block - the concentrated view in which a direction inversion
     * or a mislabeled value cannot hide. At most one revision per step; the
     * section-end challenge and the cross-section review stay the final
     * instances for everything a delta view cannot see.
     */
    private String diffJudgeStep(ChatModel model, Prompts prompts, String subject,
            String header, String heading, String before, String after, String block,
            String fed, boolean de) {
        List<String> delta = addedSentences(before, after);
        if (delta.isEmpty()) return after;
        String message = header + "SECTION: ## " + heading
                + "\n\nNEW SOURCE (this step's only material):\n" + block
                + "\nDELTA (only the sentences this step added or reworked):\n  - "
                + String.join("\n  - ", delta);
        String reply = chatGateway.chat(model, prompts.diffcheck(),
                budgeted(message, prompts.diffcheck().length()));
        List<String> objections = new ArrayList<>();
        if (reply != null) {
            String deltaNorm = String.join(" ", delta);
            for (String line : reply.split("\n")) {
                String s = line.strip();
                if (!s.startsWith("E:")) continue;
                // Judge-output validity: an objection whose quote does not
                // appear in the delta is the judge hallucinating a defect -
                // it verifies nothing and would only churn a revision (live
                // smoke 2026-07-16: 53 objections over ~16 steps, Lage alone
                // 22 minutes). ONE valid objection max - the step examiner is
                // a tripwire, not a second challenger.
                String quote = quotedSpan(s).replaceAll("\\s+", " ");
                if (quote.length() < 12 || !deltaNorm.contains(quote)) {
                    LOG.debug("[DEEPDIVE] diff-judge objection dropped (quote not in delta).");
                    continue;
                }
                objections.add(s);
                break;
            }
        }
        if (objections.isEmpty()) return after;
        journalNotes(subject, objections);
        LOG.info("[DEEPDIVE] '{}' section '{}': diff-judge raised {} objection(s) on the step.",
                subject, heading, objections.size());
        String revised = revise(model, prompts, header, heading, after, fed,
                String.join("\n", objections));
        if (isBlank(revised)) return after;
        return examineAndRepair(model, prompts, subject, header, heading, fed,
                markersIn(fed), de, revised.strip()).body();
    }

    /** The sentences of {@code after} that {@code before} does not carry (normalized). */
    static List<String> addedSentences(String before, String after) {
        Set<String> old = new java.util.HashSet<>();
        if (before != null && !before.isBlank()) {
            for (String s : DeepDiveFactCheck.sentences(before)) {
                old.add(s.strip().replaceAll("\\s+", " "));
            }
        }
        List<String> added = new ArrayList<>();
        if (after != null && !after.isBlank()) {
            for (String s : DeepDiveFactCheck.sentences(after)) {
                String norm = s.strip().replaceAll("\\s+", " ");
                if (!norm.isEmpty() && !old.contains(norm)) added.add(norm);
            }
        }
        return added;
    }

    private List<String> challenge(ChatModel model, Prompts prompts, String header,
            String heading, String body, String material) {
        String fixed = header + "SECTION: ## " + heading + "\n\nDRAFT:\n" + body
                + "\n\nMATERIAL (the only admissible evidence):\n";
        String reply = chatGateway.chat(model, prompts.challenge(),
                fixed + budgeted(material, prompts.challenge().length() + fixed.length()));
        List<String> out = new ArrayList<>();
        if (reply == null) return out;
        for (String line : reply.split("\n")) {
            String s = line.strip();
            if (s.startsWith("E:")) out.add(s);
            if (out.size() >= 4) break;
        }
        return out;
    }

    private String revise(ChatModel model, Prompts prompts, String header, String heading,
            String body, String material, String objections) {
        String fixed = header + "SECTION: ## " + heading + "\n\nCURRENT TEXT:\n" + body
                + "\n\nOBJECTIONS (fix each from the material or REMOVE the claim):\n"
                + objections + "\n\nMATERIAL:\n";
        return cleanReport(chatGateway.chat(model, prompts.revise(),
                fixed + budgeted(material, prompts.revise().length() + fixed.length())));
    }

    private static String renderObjections(List<DeepDiveFactCheck.Objection> objections) {
        StringBuilder sb = new StringBuilder(256);
        for (DeepDiveFactCheck.Objection o : objections) {
            sb.append("E: \"").append(o.quote()).append("\" — ").append(o.problem()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Enforces the context budget HARD: the material is trimmed at a line
     * boundary to what actually fits beside the fixed parts — a pass is never
     * sent over num_ctx (Ollama truncates silently, which is what starved the
     * old late passes).
     */
    private String budgeted(String material, int fixedChars) {
        int budget = inputBudgetChars() - fixedChars - 200;
        if (material.length() <= budget) return material;
        int cut = Math.max(budget, 800);
        int nl = material.lastIndexOf('\n', cut);
        if (nl > cut / 2) cut = nl;
        LOG.warn("[DEEPDIVE] material over the context budget ({} > {} chars) — trimmed.",
                material.length(), budget);
        return material.substring(0, cut) + "\n  (material trimmed to the context budget)\n";
    }

    /**
     * How many input chars a pass may spend: the model's context window minus
     * its own output reservation, char-estimated conservatively. DD passes must
     * budget against this — Ollama TRUNCATES a longer prompt silently.
     */
    private int inputBudgetChars() {
        return (int) ((brain.contextTokens() - DD_NUM_PREDICT) * CHARS_PER_TOKEN);
    }

    // -- news triage (the relevance judge) and the alias follow-up --

    private static final Pattern TRIAGE_OBJ = Pattern.compile("\\{[^{}]*}");
    private static final Pattern TRIAGE_I = Pattern.compile("\"i\"\\s*:\\s*(\\d+)");
    private static final Pattern TRIAGE_REL = Pattern.compile("\"relevant\"\\s*:\\s*(true|false)");
    /**
     * A quoted section token anywhere in the verdict object — the triage may
     * route one article to SEVERAL shelves ("targets" array, 2026-07-16 user
     * mandate: a single-label route hid the guidance article from the outlook
     * forever); matching the tokens instead of the key shape also swallows the
     * legacy single-"target" form.
     */
    private static final Pattern TRIAGE_TARGET = Pattern.compile(
            "\"(LAGE|SITUATION|KATALYSATOR\\w*|CATALYST\\w*|AUSBLICK|OUTLOOK|BEWERTUNG|VALUATION)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIAS_ITEM = Pattern.compile("\"([^\"]{2,40})\"");
    private static final int TRIAGE_BATCH = 6;

    /**
     * The relevance judge: every pooled item is judged "substantively about the
     * subject?" BEFORE it can earn a source number, a digest or a packet — the
     * aggregator ranks but never discards, and Yahoo's symbol search returns an
     * unrelated firehose for suffix tickers (live-verified: {@code q=SAP.DE}
     * answers Montenegro resorts and wildfires). No curated lists — the judge
     * is gemma. Relevant items are ROUTED to the section they serve.
     */
    private void triageNews(String subject, Material m, String lang, String langName) {
        if (m.news.isEmpty()) return;
        ChatModel judge = brain.getAgentModel();
        if (judge == null) return;
        Map<String, String> verdicts =
                judgeNews(m.news, aboutLine(m), lang, langName, judge);
        if (verdicts.isEmpty()) return; // judge whiffed entirely — keep the pool
        List<RawNewsItem> kept = new ArrayList<>();
        Map<String, String> targets = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        List<RawNewsItem> droppedItems = new ArrayList<>();
        List<String> unjudged = new ArrayList<>();
        for (RawNewsItem item : m.news) {
            String verdict = verdicts.get(newsKey(item));
            if (verdict == null || !verdict.isEmpty()) {
                kept.add(item);
                targets.put(newsKey(item), verdict == null || verdict.isEmpty()
                        ? "LAGE" : verdict);
                if (verdict == null) unjudged.add(item.title());
            } else {
                dropped.add(item.title());
                droppedItems.add(item);
            }
        }
        if (!dropped.isEmpty()) {
            LOG.info("[DEEPDIVE] '{}' triage dropped {} of {} news item(s) as off-subject: {}",
                    subject, dropped.size(), m.news.size(), dropped);
            journalNotes(subject, dropped);
        }
        if (!unjudged.isEmpty()) {
            LOG.warn("[DEEPDIVE] '{}' triage kept {} item(s) WITHOUT a verdict (fail-open): {}",
                    subject, unjudged.size(), unjudged);
        }
        if (kept.size() > MAX_NEWS) {
            LOG.warn("[DEEPDIVE] '{}' post-triage pool hit the runaway backstop ({} > {}).",
                    subject, kept.size(), MAX_NEWS);
            // Judged-relevant items with a verdict outrank fail-open survivors
            // when the ceiling cuts.
            kept.sort((a, b) -> Boolean.compare(
                    verdicts.get(newsKey(b)) != null, verdicts.get(newsKey(a)) != null));
            for (RawNewsItem lost : kept.subList(MAX_NEWS, kept.size())) {
                targets.remove(newsKey(lost));
                droppedItems.add(lost);
            }
            kept = new ArrayList<>(kept.subList(0, MAX_NEWS));
        }
        m.news = kept;
        m.newsTargets = targets;
        // Nothing leaves for good: the set-aside pool waits for the RECLAIM
        // pass, where the STANDING report may open a window the first triage
        // could not see (user mandate 2026-07-16 "um 2 Ecken denken").
        m.newsDiscarded = droppedItems;
    }

    /**
     * One judge sweep over a news list. Returns per item key: the target
     * section ("LAGE"/"KATALYSATOR"/"AUSBLICK") when relevant, {@code ""} when
     * judged off-subject, no entry when the judge never mentioned it (treated
     * as relevant — fail-open, a lost judgement must not lose a real story).
     */
    private Map<String, String> judgeNews(List<RawNewsItem> items, String about,
            String lang, String langName, ChatModel judge) {
        // Concurrent: the batches run two-wide through the gate.
        Map<String, String> out = new java.util.concurrent.ConcurrentHashMap<>();
        String prompt = PromptLoader.loadLocalized("deepdive-triage", lang)
                .replace("{{LANGUAGE}}", langName);
        judgeBatches(items, about, prompt, judge, out);
        // The judge routinely SKIPS single items inside an otherwise-parsed
        // batch (live: 4-10 items per run passed fail-open with no verdict) —
        // one follow-up sweep over exactly the unmentioned items closes it.
        List<RawNewsItem> unjudged = new ArrayList<>();
        for (RawNewsItem item : items) {
            if (!out.containsKey(newsKey(item))) unjudged.add(item);
        }
        if (!unjudged.isEmpty() && unjudged.size() < items.size()) {
            judgeBatches(unjudged, about, prompt, judge, out);
        }
        return out;
    }

    private void judgeBatches(List<RawNewsItem> items, String about, String prompt,
            ChatModel judge, Map<String, String> out) {
        // The batches are INDEPENDENT judge calls — run them two-wide, matching
        // Ollama's NUM_PARALLEL (2026-07-15 performance pass; verdicts land in a
        // concurrent map, quality untouched). The gate still governs every call.
        List<Runnable> tasks = new ArrayList<>();
        for (int from = 0; from < items.size(); from += TRIAGE_BATCH) {
            List<RawNewsItem> batch = items.subList(from, Math.min(items.size(), from + TRIAGE_BATCH));
            tasks.add(() -> judgeOneBatch(batch, about, prompt, judge, out));
        }
        runTwoWide(tasks);
    }

    private void judgeOneBatch(List<RawNewsItem> batch, String about, String prompt,
            ChatModel judge, Map<String, String> out) {
        {
            // An uncapped pool means up to ~50 judge batches — cancel bites here too.
            checkCancelled();
            StringBuilder list = new StringBuilder(1024);
            for (int i = 0; i < batch.size(); i++) {
                RawNewsItem item = batch.get(i);
                list.append(i + 1).append(". ").append(item.title());
                if (item.publisher() != null && !item.publisher().isEmpty()) {
                    list.append(" · ").append(item.publisher());
                }
                if (item.summary() != null && !item.summary().isBlank()) {
                    String s = item.summary().replace('\n', ' ').strip();
                    list.append(" — ").append(s, 0, Math.min(s.length(), 160));
                }
                list.append('\n');
            }
            // A batch whose reply parses to NOTHING is a whiffed judge call, not
            // a verdict — one retry before the fail-open lets it all through
            // (live-observed: batch 2 whiffed and five off-subject items kept
            // their source numbers).
            String lastReply = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                int parsed = 0;
                try {
                    String reply = chatGateway.chat(judge, prompt,
                            "SUBJECT: " + about + "\n\nITEMS:\n" + list);
                    lastReply = reply;
                    if (reply == null) continue;
                    Matcher obj = TRIAGE_OBJ.matcher(reply);
                    while (obj.find()) {
                        String o = obj.group();
                        Matcher iM = TRIAGE_I.matcher(o);
                        if (!iM.find()) continue;
                        int i = Integer.parseInt(iM.group(1));
                        if (i < 1 || i > batch.size()) continue;
                        Matcher relM = TRIAGE_REL.matcher(o);
                        boolean relevant = !relM.find() || Boolean.parseBoolean(relM.group(1));
                        StringBuilder targets = new StringBuilder();
                        Matcher tM = TRIAGE_TARGET.matcher(o);
                        while (tM.find()) {
                            String t = tM.group(1).toUpperCase(Locale.ROOT);
                            String norm = t.startsWith("KATALYSATOR") || t.startsWith("CATALYST")
                                    ? "KATALYSATOR"
                                    : t.startsWith("AUSBLICK") || t.startsWith("OUTLOOK")
                                            ? "AUSBLICK"
                                            : t.startsWith("BEWERTUNG") || t.startsWith("VALUATION")
                                                    ? "BEWERTUNG" : "LAGE";
                            if (targets.indexOf(norm) < 0) {
                                if (targets.length() > 0) targets.append(',');
                                targets.append(norm);
                            }
                        }
                        if (targets.length() == 0) targets.append("LAGE");
                        out.put(newsKey(batch.get(i - 1)), relevant ? targets.toString() : "");
                        parsed++;
                    }
                } catch (Exception e) {
                    LOG.debug("[DEEPDIVE] triage batch failed: {}", e.getMessage());
                }
                if (parsed > 0) break;
                LOG.warn("[DEEPDIVE] triage batch yielded no parseable verdicts (attempt {}){}",
                        attempt, attempt == 1 ? " — retrying" : " — items pass fail-open");
                if (attempt == 2 && lastReply != null) {
                    // The whiff is reproducible across runs — surface the raw
                    // reply head so the parse gap can be found.
                    String head = lastReply.strip();
                    LOG.warn("[DEEPDIVE] triage whiff raw reply head: {}",
                            head.substring(0, Math.min(head.length(), 400)));
                }
            }
        }
    }

    /**
     * Runs independent judge tasks two-wide (Ollama's NUM_PARALLEL) on daemon
     * workers. The submitting thread's INTERACTIVE lane rides into every task;
     * a cancellation inside a task cancels the whole set.
     */
    private void runTwoWide(List<Runnable> tasks) {
        if (tasks.isEmpty()) return;
        if (tasks.size() == 1) {
            tasks.get(0).run();
            return;
        }
        boolean interactive = Boolean.TRUE.equals(ChatGateway.INTERACTIVE.get());
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "dd-judge");
            t.setDaemon(true);
            return t;
        });
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    if (interactive) ChatGateway.INTERACTIVE.set(Boolean.TRUE);
                    try {
                        task.run();
                    } finally {
                        ChatGateway.INTERACTIVE.remove();
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof java.util.concurrent.CancellationException c) throw c;
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The desk's ONE material request: when triage left a thin pool, the model
     * names up to two alternative press names (brand, short form, former name)
     * and the aggregator is queried once per alias — "wir legen auf den Tisch,
     * was die KI anfragt".
     */
    private void aliasFollowUp(String subject, Material m, String lang, String langName) {
        if (m.news.size() >= THIN_NEWS_THRESHOLD || newsAggregator == null) return;
        ChatModel judge = brain.getAgentModel();
        if (judge == null || m.canonicalName == null
                || m.canonicalName.equalsIgnoreCase(m.ticker)) {
            return;
        }
        try {
            String prompt = PromptLoader.loadLocalized("deepdive-alias", lang)
                    .replace("{{LANGUAGE}}", langName);
            String reply = chatGateway.chat(judge, prompt, "SUBJECT: " + aboutLine(m));
            // The canonical name itself always rides: the initial aggregator
            // sweep may have missed the press leg entirely (live-observed: the
            // Google News anchor took 45 s to warm and the pool was built
            // before the German press arrived) — a second query hits warm
            // transports. The model's press-name variants join it.
            Set<String> aliases = new java.util.LinkedHashSet<>();
            aliases.add(m.canonicalName);
            int arr = reply == null ? -1 : reply.indexOf('[');
            if (arr >= 0) {
                Matcher am = ALIAS_ITEM.matcher(reply.substring(arr));
                while (am.find() && aliases.size() < 3) {
                    String alias = am.group(1).strip();
                    if (alias.length() < 3 || alias.equalsIgnoreCase(m.canonicalName)
                            || alias.equalsIgnoreCase(m.ticker)) {
                        continue;
                    }
                    aliases.add(alias);
                }
            }
            LOG.info("[DEEPDIVE] '{}' thin news pool — alias follow-up: {}", subject, aliases);
            Set<String> seen = new HashSet<>();
            for (RawNewsItem item : m.news) seen.add(newsKey(item));
            List<RawNewsItem> fresh = new ArrayList<>();
            Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(NEWS_MAX_AGE_DAYS));
            for (String alias : aliases) {
                for (RawNewsItem item : newsAggregator.newsFor(null, alias,
                        RESEARCH_QUERY_LIMIT)) {
                    if (item.publishedAt() != null && item.publishedAt().isBefore(cutoff)) continue;
                    if (seen.add(newsKey(item))) fresh.add(item);
                }
            }
            if (fresh.isEmpty()) return;
            Map<String, String> verdicts = judgeNews(fresh, aboutLine(m), lang, langName, judge);
            List<RawNewsItem> merged = new ArrayList<>(m.news);
            Map<String, String> targets = new LinkedHashMap<>(m.newsTargets);
            for (RawNewsItem item : fresh) {
                if (merged.size() >= MAX_NEWS) break;
                String verdict = verdicts.get(newsKey(item));
                if (verdict != null && verdict.isEmpty()) continue; // judged off-subject
                merged.add(item);
                targets.put(newsKey(item), verdict == null ? "LAGE" : verdict);
            }
            LOG.info("[DEEPDIVE] '{}' alias follow-up added {} relevant item(s).",
                    subject, merged.size() - m.news.size());
            m.news = merged;
            m.newsTargets = targets;
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] alias follow-up failed: {}", e.getMessage());
        }
    }

    /**
     * The desk's RESEARCH round (user mandate: "wir legen die Informationen auf
     * den Tisch, die die KI anfragt"): a big name's daily feed is mostly
     * mechanical price notes, so the generic name query drowns the week's
     * load-bearing stories (live-observed SAP: the AI restructuring and the
     * antitrust story, both 3-4 days old, never surfaced while "SAP-Aktie:
     * Gleichbleibend" filled the pool). Epistemics matter here: the model can
     * NOT know which stories are missing — the table is its whole world — so
     * this is a STANDARD SWEEP over fixed question categories (reason behind
     * the price path, strategy/restructuring, regulation, product, leadership),
     * with the model only instantiating the company's short form and German
     * search terms (no curated query lists — the house rule). Each query runs
     * through the aggregator — specific terms dodge the same-day spam
     * naturally — and the finds are triage-judged before they earn seats.
     */
    private void researchFollowUp(String subject, Material m, String lang, String langName) {
        if (newsAggregator == null || m.canonicalName == null) return;
        ChatModel judge = brain.getAgentModel();
        if (judge == null) return;
        try {
            String prompt = PromptLoader.loadLocalized("deepdive-research", lang)
                    .replace("{{LANGUAGE}}", langName);
            StringBuilder brief = new StringBuilder(1024);
            brief.append("SUBJECT: ").append(aboutLine(m)).append('\n');
            brief.append("SITUATION:");
            if (m.snapshot != null && m.snapshot.hasPrice()) {
                brief.append(" price ").append(fmt2(m.snapshot.price()));
            }
            CompanyDeepDive.PerformanceStats p =
                    m.deepDive != null ? m.deepDive.performance() : null;
            if (p != null && Double.isFinite(p.perf52w())) {
                brief.append(String.format(Locale.ROOT, ", 52w %+.1f%%", p.perf52w()));
            }
            if (snapshotComparableToEurStats(m) && p != null
                    && Double.isFinite(p.high52w()) && p.high52w() > 0) {
                brief.append(String.format(Locale.ROOT, ", %.1f%% below the 52w high",
                        Math.abs((m.snapshot.price() - p.high52w()) / p.high52w() * 100.0)));
            }
            AnalystView av = m.analystView;
            if (av != null) {
                AnalystView.CorporateEvent next = av.nextEvent(Instant.now().getEpochSecond());
                if (next != null && next.title() != null) {
                    brief.append("; next event ").append(next.title());
                }
            }
            brief.append('\n').append("POOL:\n");
            for (RawNewsItem item : m.news) {
                brief.append("  - ").append(item.title()).append('\n');
            }
            String reply = chatGateway.chat(judge, prompt, brief.toString());
            List<String> queries = new ArrayList<>();
            int arr = reply == null ? -1 : reply.indexOf('[');
            if (arr >= 0) {
                Matcher qm = ALIAS_ITEM.matcher(reply.substring(arr));
                while (qm.find() && queries.size() < MAX_RESEARCH_QUERIES) {
                    String q = qm.group(1).strip();
                    if (q.length() >= 5 && !q.equalsIgnoreCase(m.canonicalName)) queries.add(q);
                }
            }
            if (queries.isEmpty()) {
                LOG.info("[DEEPDIVE] '{}' research round: no open questions named.", subject);
                return;
            }
            LOG.info("[DEEPDIVE] '{}' research round — targeted queries: {}", subject, queries);
            Set<String> seen = new HashSet<>();
            for (RawNewsItem item : m.news) seen.add(newsKey(item));
            List<RawNewsItem> fresh = new ArrayList<>();
            Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(NEWS_MAX_AGE_DAYS));
            de.bsommerfeld.wsbg.terminal.websearch.BingWebSearchClient web = webSearch;
            for (String query : queries) {
                try {
                    for (RawNewsItem item : newsAggregator.newsFor(null, query,
                            RESEARCH_QUERY_LIMIT)) {
                        if (item.publishedAt() != null && item.publishedAt().isBefore(cutoff)) {
                            continue;
                        }
                        if (seen.add(newsKey(item))) fresh.add(item);
                    }
                    // The open web answers the same query — IR pages and
                    // articles the news indexes never carry.
                    if (web != null) {
                        for (RawNewsItem item : web.newsForName(query, RESEARCH_QUERY_LIMIT)) {
                            if (seen.add(newsKey(item))) fresh.add(item);
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("[DEEPDIVE] research query '{}' failed: {}", query, e.getMessage());
                }
            }
            if (fresh.isEmpty()) {
                LOG.info("[DEEPDIVE] '{}' research round found nothing new.", subject);
                return;
            }
            Map<String, String> verdicts = judgeNews(fresh, aboutLine(m), lang, langName, judge);
            // Research finds ride FIRST: they are the substance carriers the
            // firehose drowned — they must reach the article-read slots.
            List<RawNewsItem> merged = new ArrayList<>();
            Map<String, String> targets = new LinkedHashMap<>(m.newsTargets);
            int added = 0;
            for (RawNewsItem item : fresh) {
                if (added >= MAX_NEWS_RESEARCHED - Math.min(m.news.size(), MAX_NEWS)) break;
                String verdict = verdicts.get(newsKey(item));
                if (verdict != null && verdict.isEmpty()) continue; // judged off-subject
                merged.add(item);
                targets.put(newsKey(item), verdict == null ? "LAGE" : verdict);
                added++;
            }
            for (RawNewsItem item : m.news) {
                if (merged.size() >= MAX_NEWS_RESEARCHED) break;
                merged.add(item);
            }
            LOG.info("[DEEPDIVE] '{}' research round added {} relevant item(s) "
                            + "({} candidates found).", subject, added, fresh.size());
            m.news = merged;
            m.newsTargets = targets;
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] research round failed: {}", e.getMessage());
        }
    }

    /**
     * The FIRST-PARTY sweep: Consorsbank delivers the company's official
     * website with every profile — its press/IR section carries the
     * announcements the wire services paraphrase. The scout lifts that page's
     * headlines as candidates (publisher = the company's own site); they pass
     * the triage and the article digester like every other source.
     */
    private void pressSweep(String subject, Material m) {
        CompanyPressScout scout = pressScout;
        if (scout == null || m.deepDive == null || m.deepDive.profile() == null) return;
        String website = m.deepDive.profile().website();
        if (website == null || website.isBlank()) return;
        try {
            List<RawNewsItem> found = scout.pressItems(website, 6);
            if (found.isEmpty()) return;
            Set<String> seen = new HashSet<>();
            for (RawNewsItem item : m.news) seen.add(newsKey(item));
            List<RawNewsItem> merged = new ArrayList<>(m.news);
            int added = 0;
            for (RawNewsItem item : found) {
                if (seen.add(newsKey(item))) {
                    merged.add(item);
                    added++;
                }
            }
            if (added > 0) {
                LOG.info("[DEEPDIVE] '{}' press sweep added {} first-party item(s) from {}.",
                        subject, added, website);
                m.news = merged;
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] press sweep failed: {}", e.getMessage());
        }
        // The IR ARCHIVE leg (user mandate 2026-07-16 "Ist, Soll UND
        // vergangener Stand"): the first-party record of reports, calls and
        // calendar dates - its OWN shelf block, never through the news triage
        // (the 30-day freshness cut would behead every past quarter).
        try {
            List<CompanyPressScout.IrEntry> entries = scout.irEntries(website, MAX_IR_ENTRIES);
            if (!entries.isEmpty()) {
                m.irEntries = entries;
                LOG.info("[DEEPDIVE] '{}' IR archive: {} first-party entry(ies) from {}.",
                        subject, entries.size(), website);
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] IR archive failed: {}", e.getMessage());
        }
    }

    /**
     * The finanznachrichten legs: ad-hocs joined EXACTLY on the ISIN (the
     * feed tags every item with {@code <fn:isin>}) and analyst actions matched
     * on the company's significant name words — both merged into the candidate
     * pool, both still subject to triage and the freshness cut like everything
     * else.
     */
    private void appendFnLegs(Material m) {
        de.bsommerfeld.wsbg.terminal.briefing.FnRssClient fn = fnRssClient;
        if (fn == null) return;
        Set<String> seen = new HashSet<>();
        for (RawNewsItem item : m.news) seen.add(newsKey(item));
        List<RawNewsItem> extra = new ArrayList<>();
        try {
            if (m.isin != null && !m.isin.isBlank()) {
                for (var ad : fn.adhocs(80)) {
                    if (!m.isin.equalsIgnoreCase(ad.isin())) continue;
                    RawNewsItem item = new RawNewsItem(ad.link() == null ? ad.title() : ad.link(),
                            ad.title(), "EQS/Ad-hoc (finanznachrichten)", ad.link(),
                            ad.publishedAt(), List.of());
                    if (seen.add(newsKey(item))) extra.add(item);
                }
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] fn ad-hoc leg failed: {}", e.getMessage());
        }
        try {
            Set<String> nameWords = NameMatcher.significantWords(m.canonicalName);
            if (!nameWords.isEmpty()) {
                for (var action : fn.analystActions(80)) {
                    if (action.title() == null) continue;
                    String lower = action.title().toLowerCase(Locale.ROOT);
                    boolean hit = false;
                    for (String w : nameWords) {
                        if (lower.contains(w)) {
                            hit = true;
                            break;
                        }
                    }
                    if (!hit) continue;
                    RawNewsItem item = new RawNewsItem(action.title(), action.title(),
                            "Analysten (finanznachrichten)", null, action.publishedAt(), List.of());
                    if (seen.add(newsKey(item))) extra.add(item);
                }
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] fn analyst leg failed: {}", e.getMessage());
        }
        if (extra.isEmpty()) return;
        LOG.info("[DEEPDIVE] '{}' finanznachrichten legs contributed {} item(s).",
                m.ticker, extra.size());
        // Exact-ISIN disclosures ride FIRST — they must survive the candidate
        // cap and reach the article-read slots.
        List<RawNewsItem> merged = new ArrayList<>(extra);
        merged.addAll(m.news);
        m.news = merged;
    }

    /**
     * The venue-less base symbol ("SAP.DE" → "SAP"), or {@code null} when the
     * ticker has no suffix or is not equity-shaped (indices/futures/FX keep
     * their markers).
     */
    static String baseSymbol(String ticker) {
        if (ticker == null || ticker.indexOf('^') >= 0 || ticker.indexOf('=') >= 0) return null;
        int dot = ticker.indexOf('.');
        if (dot <= 0) return null;
        String base = ticker.substring(0, dot);
        return base.isBlank() ? null : base;
    }

    /** Stable per-item key for triage verdicts (uuid, falling back to link/title). */
    private static String newsKey(RawNewsItem item) {
        if (item.uuid() != null && !item.uuid().isBlank()) return item.uuid();
        if (item.link() != null && !item.link().isBlank()) return item.link().trim();
        return item.title() == null ? "" : item.title();
    }

    /** The one-line subject identity the judges see. */
    private static String aboutLine(Material m) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(m.canonicalName);
        if (m.ticker != null) sb.append(" (ticker ").append(m.ticker).append(')');
        if (m.facts != null && m.facts.sector() != null) {
            sb.append(", sector ").append(m.facts.sector());
            if (m.facts.branch() != null) sb.append(" / ").append(m.facts.branch());
        }
        CompanyDeepDive.Profile p = m.deepDive != null ? m.deepDive.profile() : null;
        if (p != null && p.portrait() != null) {
            String head = p.portrait().replace('\n', ' ').strip();
            sb.append(" — ").append(head, 0, Math.min(head.length(), 220));
        }
        return sb.toString();
    }

    /**
     * Reads the articles BEHIND the relevant headlines, one at a time: each is
     * fetched, capped (~6k chars) and distilled in its own small model call —
     * a long article can never overload the model. Runs AFTER triage, so no
     * read is wasted on rejected items. Cache shared with the wire's
     * background digest lane.
     */
    private void digestArticles(String subject, Material m) {
        EditorialAgent digestAgent = editorialAgent;
        if (digestAgent == null || m.news.isEmpty()) return;
        // GROUP DIGEST (user mandate 2026-07-14 "alles umsetzen"): the weave
        // already works per STORY — the digest now does too. Ten re-spins of
        // one event used to cost ten full-text reads (live SAP run: 133
        // digests, the run\'s largest single cost); now only each story\'s
        // REPRESENTATIVE is read (the earliest publication — usually the
        // original release), the other members keep title + marker and still
        // get cited by the weave. The safety net is the RE-KNOCK: a hard
        // examiner finding on a group whose members went unread fetches the
        // missing digests on demand and re-weaves once.
        Map<String, String> digests = new LinkedHashMap<>();
        List<List<RawNewsItem>> groups = groupStories(m.news,
                storyDropWords(m.canonicalName, m.ticker));
        if (groups.size() < m.news.size()) {
            LOG.info("[DEEPDIVE] '{}' group digest: {} article(s) → {} story "
                    + "representative(s) to read.", subject, m.news.size(), groups.size());
        }
        int total = Math.min(groups.size(), MAX_DIGESTED_ARTICLES);
        int done = 0;
        for (List<RawNewsItem> group : groups) {
            if (done >= MAX_DIGESTED_ARTICLES) break;
            RawNewsItem rep = representativeOf(group);
            if (rep == null) continue;
            // Cancel must bite BETWEEN articles: with the uncapped pool this
            // loop runs for many minutes, and without the check the UI's X
            // waited out the whole digest phase (live 2026-07-14: 20 cancel
            // clicks, the generation kept running).
            checkCancelled();
            done++;
            eventBus.post(new DeepDiveProgressEvent(subject, "triage",
                    "Artikel " + done + "/" + total));
            try {
                String digest = digestAgent.newsDigester().digestNow(rep.link());
                if (!digest.isBlank()) digests.put(rep.link().trim(), digest);
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] article digest failed for {}: {}",
                        rep.link(), e.getMessage());
            }
        }
        m.digests = digests;
    }

    /**
     * Groups the triaged pool into stories with the SAME similarity rule the
     * weave uses — one mechanism, two consumers. Greedy with transitive
     * chaining; order preserved; no item is ever dropped.
     */
    static List<List<RawNewsItem>> groupStories(List<RawNewsItem> items, Set<String> dropWords) {
        List<List<RawNewsItem>> groups = new ArrayList<>();
        List<Set<String>> groupTokens = new ArrayList<>();
        for (RawNewsItem item : items) {
            Set<String> tokens = storyTokens(item.title() == null ? "" : item.title(), dropWords);
            boolean joined = false;
            for (int g = 0; g < groups.size() && !joined; g++) {
                if (setJaccard(tokens, groupTokens.get(g)) >= STORY_GROUP_SIMILARITY) {
                    groups.get(g).add(item);
                    groupTokens.get(g).addAll(tokens);
                    joined = true;
                }
            }
            if (!joined) {
                List<RawNewsItem> fresh = new ArrayList<>();
                fresh.add(item);
                groups.add(fresh);
                groupTokens.add(new HashSet<>(tokens));
            }
        }
        return groups;
    }

    /**
     * The story\'s representative to full-text-read: the EARLIEST publication
     * with a link — re-spins come later than the original release. Undated
     * members lose to dated ones; an all-undated group takes its first.
     */
    static RawNewsItem representativeOf(List<RawNewsItem> group) {
        RawNewsItem best = null;
        for (RawNewsItem item : group) {
            if (item.link() == null || item.link().isBlank()) continue;
            if (best == null) {
                best = item;
                continue;
            }
            if (item.publishedAt() != null
                    && (best.publishedAt() == null || item.publishedAt().isBefore(best.publishedAt()))) {
                best = item;
            }
        }
        return best;
    }

    /** Everything the collect step gathered — nulls/empties where a leg had nothing. */
    /** Package-private for the material-completeness test. */
    static final class Material {
        String canonicalName;
        String ticker;
        String isin;
        MarketSnapshot snapshot;
        de.bsommerfeld.wsbg.terminal.core.price.VenueStats venueStats;
        de.bsommerfeld.wsbg.terminal.core.price.InstrumentFacts facts;
        de.bsommerfeld.wsbg.terminal.core.price.FundFacts fundFacts;
        AnalystView analystView;
        CompanyDeepDive deepDive;
        ShortInterest shortInterest;
        InsiderDealings insiderDealings;
        /** The US listing view (NASDAQ tabs: Form-4 insiders, FINRA shorts, 13F, street). */
        UsListingStats usStats;
        /** The quarterly hedge-fund positioning curve (Insider Monkey, 13F cadence). */
        HedgeFundPopularity hedgeFunds;
        /** The dated analyst-action history + US short stats (MarketBeat). */
        AnalystActions analystActions;
        /** The months-spanning dated press timeline (MarketBeat news tab) — "Was war" context. */
        de.bsommerfeld.wsbg.terminal.core.price.PressTimeline pressTimeline;
        /** House-computed volume profile (Yahoo hourly bars) — the structure layer's traded side. */
        VolumeProfile.Profile volumeProfile;
        /** The Frankfurt floor-book window (10 levels with order counts) — who stands there NOW. */
        de.bsommerfeld.wsbg.terminal.core.price.OrderBookSnapshot orderBook;
        /** The instrument's dated events from the house register (market memory), oldest first. */
        List<de.bsommerfeld.wsbg.terminal.db.MarketEventRecord> memoryEvents = List.of();
        /** Finished base-rate lines (house statistics + attributed literature priors), ROOT locale. */
        List<String> baseRateLines = List.of();
        /** The instrument's US sector proxy (XL* SPDR), day snapshot. Null = no mapping. */
        MarketSnapshot sectorEtf;
        String sectorEtfSymbol;
        String sectorDisplayName;
        /** ALL current world hazards (storm/quake/aviation) — the judge sorts, never a pre-gate. */
        List<de.bsommerfeld.wsbg.terminal.briefing.GlobalHazardsClient.Hazard> allHazards = List.of();
        /** Today's high-impact macro ACTUALS (Ist vs Prognose vs zuvor — the weather pattern). */
        List<TradingViewCalendarClient.TvEvent> macroActualsToday = List.of();
        /**
         * The fishing-net's FULL catch (2026-07-15, user mandate "alles rein,
         * die KI sortiert aus"): every world signal is offered to the
         * subject-scoped relevance judge — the exposure maps survive only as
         * HINTS on the candidate lines, never as pre-filters (a pre-filter
         * would hide the wild-but-real connection from the model forever).
         */
        de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldSignals worldSignals;
        /** The judge's survivors — finished ROOT-locale material lines for the Lage shelf. */
        List<String> worldSignalKeep = List.of();
        /** Upcoming high-impact macro events the sector trades on (Ausblick anchors). */
        List<EconCalendarClient.EconEvent> macroDocket = List.of();
        /** The next central-bank rate decisions (EZB/Fed). */
        List<CentralBankCalendarClient.CbMeeting> cbDecisions = List.of();
        /** The house's OWN published wire lines about this subject — the permanent archive ("Was war"). */
        List<HeadlineRecord> wireHistory = List.of();
        /** The street's consensus for the next report (EarningsWhispers, US names). */
        de.bsommerfeld.wsbg.terminal.briefing.EarningsWhispersClient.EarningsEstimate earningsEstimate;
        /** ISO date the estimate belongs to (the report day the calendar answered for). */
        String earningsEstimateDateIso;
        List<RawNewsItem> news = List.of();
        /** item key -> target section ("LAGE"/"KATALYSATOR"/"AUSBLICK"), set by triage. */
        Map<String, String> newsTargets = Map.of();
        /** Multi-year press HISTORY (headlines only) - the name's longer arc. */
        List<RawNewsItem> pressHistory = List.of();
        /**
         * Forum/social chatter from the sentiment fan (2026-07-16): room
         * opinion, strictly apart from {@link #news} - joins the ROOM shelf
         * as ONE capped aggregate block, never the triage/weave loom.
         */
        List<RawNewsItem> socialSentiment = List.of();
        /** The company's OWN IR archive: dated reports, calls, calendar (first-party). */
        List<CompanyPressScout.IrEntry> irEntries = List.of();
        /** Triage-discarded articles - kept for the RECLAIM pass (re-judged
         * against the STANDING report, where a window may have opened). */
        List<RawNewsItem> newsDiscarded = List.of();
        /** Markers of stories judged IMMATERIAL at the weave (sighted, read,
         * chronicled - but they would not change the section's read; listed
         * honestly in the register under their own label, never as fake
         * in-text citations). */
        Set<Integer> sightedOnly = java.util.Collections.synchronizedSet(new java.util.TreeSet<>());
        /** The figure layer, built BEFORE the prose (pure function of the legs). */
        List<DeepDiveRecord.ChartFigure> charts = List.of();
        /**
         * The quant-signal layer (house statistics kernels over the legs
         * already collected — archive cadence, contagion graph, event-study
         * attribution, regime, room cascade). Each reading carries its number
         * PLUS the reading instruction, finished material lines by contract.
         */
        List<de.bsommerfeld.wsbg.terminal.signals.SignalReading> signalReadings = List.of();
        /** Per section ordinal: the figure IDs + captions the prose may point at. */
        Map<Integer, List<String>> figureCaptions = Map.of();
        /** link -> key-fact digest of the FULL article, read after triage. */
        Map<String, String> digests = Map.of();
        /** exact news-block text -> article link (the re-knock's way back to the URL). */
        Map<String, String> newsLinksByBlock = new HashMap<>();
        SubjectUnit unit;
        /**
         * EVERY registry unit that belongs to this subject — the room speaks
         * name AND ticker (a unit that says "Outlook" without ever writing
         * OTLK belongs to the OTLK DD too); the room material draws from their
         * union. First entry = best-scoring (the primary {@link #unit}).
         */
        List<SubjectUnit> roomUnits = List.of();
        int evidenceCount;
    }

    /**
     * Gathers every leg, each individually guarded — a failing source costs its
     * block, never the report. Identity is TICKER-FIRST (user mandate
     * 2026-07-13): the ticker is checked against the LOCAL lists (the feed-wide
     * {@link SubjectRegistry}) before any network, the pipeline's fully-wired
     * resolver is the dynamic fallback AND the name lookup (the name-addressed
     * news legs need it), and the ISIN is salvaged from the L&S snapshot's
     * symbol when no leg carried it — without an ISIN the five German data legs
     * silently stay dark (live-observed with OTLK 2026-07-13: L&S knew the ISIN,
     * the material never saw it).
     */
    private Material collect(String ticker) {
        Material m = new Material();
        m.ticker = ticker;
        m.canonicalName = ticker;

        // LOCAL lists first: the feed-wide registry already knows most tickers —
        // identity, ISIN, last snapshot and the room's evidence, zero network.
        m.unit = findUnit(ticker, ticker);
        if (m.unit != null) {
            m.evidenceCount = m.unit.evidenceCount();
            if (m.unit.canonicalName() != null && !m.unit.canonicalName().isBlank()) {
                m.canonicalName = m.unit.canonicalName();
            }
            if (m.unit.isin() != null && !m.unit.isin().isBlank()) m.isin = m.unit.isin();
            if (m.unit.snapshot() != null) m.snapshot = m.unit.snapshot();
            LOG.info("[DEEPDIVE] '{}' identity from local registry: '{}' (isin={})",
                    ticker, m.canonicalName, m.isin);
        }

        // Dynamic fallback + refresh via the pipeline's resolver: brings the
        // canonical NAME (Google News/WSO query on it), a fresh snapshot and the
        // Yahoo news leg — also for tickers the registry already knew.
        EditorialAgent agent = editorialAgent;
        if (agent != null) {
            try {
                TickerResolver.ResolvedSubject rs = agent.tickerResolver().resolve(ticker, 0);
                if (rs != null && !rs.unresolved()) {
                    if (rs.canonicalName() != null && !rs.canonicalName().isBlank()) {
                        m.canonicalName = rs.canonicalName();
                    }
                    if (rs.ticker() != null && !rs.ticker().isBlank()) m.ticker = rs.ticker();
                    if (rs.isin() != null && !rs.isin().isBlank()) m.isin = rs.isin();
                    if (rs.snapshot() != null) m.snapshot = rs.snapshot();
                    if (rs.news() != null && !rs.news().isEmpty()) m.news = rs.news();
                }
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] resolve failed for '{}': {}", ticker, e.getMessage());
            }
        }

        // Room binding on NAME *and* TICKER (user mandate 2026-07-13): now that
        // the resolver delivered the canonical name, gather EVERY unit that
        // belongs to this subject — a "name:outlook" unit holding the cage's
        // chatter must not lose to an evidence-less exact-ticker unit, and the
        // room material draws from the union of all of them.
        m.roomUnits = findRoomUnits(m.canonicalName, m.ticker);
        if (!m.roomUnits.isEmpty()) {
            m.unit = m.roomUnits.get(0);
            m.evidenceCount = 0;
            for (SubjectUnit u : m.roomUnits) m.evidenceCount += u.evidenceCount();
            if (m.isin == null) m.isin = m.unit.isin();
            if (m.snapshot == null) m.snapshot = m.unit.snapshot();
            if (m.roomUnits.size() > 1) {
                LOG.info("[DEEPDIVE] '{}' room union: {} unit(s) ({} mentions total)",
                        ticker, m.roomUnits.size(), m.evidenceCount);
            }
        }

        // Fresh price when the resolver pass had none.
        if (m.snapshot == null && priceSource != null && m.ticker != null) {
            try {
                priceSource.snapshot(new de.bsommerfeld.wsbg.terminal.core.price.PriceRef(
                        m.canonicalName, m.ticker, m.isin)).ifPresent(s -> m.snapshot = s);
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] price failed: {}", e.getMessage());
            }
        }

        // ISIN salvage: an L&S snapshot carries the ISIN as its symbol. Without
        // this, a resolver path that never pinned the ISIN (ticker fast-path)
        // skips Tradegate, onvista, Consorsbank, Bundesanzeiger AND BaFin.
        if ((m.isin == null || m.isin.isBlank()) && m.snapshot != null
                && WeatherStatsCollector.looksLikeIsin(m.snapshot.symbol())) {
            m.isin = m.snapshot.symbol();
            LOG.info("[DEEPDIVE] '{}' ISIN salvaged from the price snapshot: {}",
                    m.ticker, m.isin);
        }

        String isin = m.isin;
        if (isin != null && !isin.isBlank()) {
            checkCancelled();
            try {
                if (venueStatsSource != null) {
                    m.venueStats = venueStatsSource.statsByIsin(isin).orElse(null);
                }
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] venue stats failed: {}", e.getMessage());
            }
            checkCancelled();
            try {
                if (factsSource != null) {
                    m.facts = factsSource.factsByIsin(isin).orElse(null);
                    if (m.facts == null) m.fundFacts = factsSource.fundFactsByIsin(isin).orElse(null);
                }
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] facts failed: {}", e.getMessage());
            }
            checkCancelled();
            try {
                // refresh(): a fixed report must carry TODAY's street view, not a session cache.
                if (analystSource != null) m.analystView = analystSource.refresh(isin).orElse(null);
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] analyst view failed: {}", e.getMessage());
            }
            checkCancelled();
            try {
                if (deepDiveSource != null) m.deepDive = deepDiveSource.deepDiveByIsin(isin).orElse(null);
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] company deep dive failed: {}", e.getMessage());
            }
            checkCancelled();
            try {
                if (shortInterestSource != null) {
                    m.shortInterest = shortInterestSource.byIsin(isin).orElse(null);
                }
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] short interest failed: {}", e.getMessage());
            }
            checkCancelled();
            try {
                if (insiderSource != null) m.insiderDealings = insiderSource.byIsin(isin).orElse(null);
            } catch (Exception e) {
                LOG.debug("[DEEPDIVE] insider dealings failed: {}", e.getMessage());
            }
        }
        // The US listing view (NASDAQ tabs): SEC Form-4 insiders, FINRA short
        // interest, 13F holders, street targets, surprise history — TICKER-
        // addressed, so it covers exactly the US names the ISIN-keyed German
        // legs can't see (BaFin covers German issuers only — live-observed
        // with OTLK: "0 Insider-Meldungen" while the press carried director
        // buys). Deliberately the ticker AS RESOLVED, never a stripped base
        // symbol: a German venue form's base (RHM.DE → RHM) can collide with
        // an unrelated US listing, and wrong-twin data is worse than none;
        // the client's own US-shape gate rejects suffixed symbols for free.
        checkCancelled();
        try {
            if (usStatsSource != null && m.ticker != null) {
                m.usStats = usStatsSource.statsFor(m.ticker).orElse(null);
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] US listing stats failed: {}", e.getMessage());
        }
        // The hedge-fund positioning curve (Insider Monkey, CIK-addressed via
        // SEC's ticker map) — same ticker-as-resolved rule as the NASDAQ leg;
        // the client's US-shape gate rejects suffixed symbols without network.
        checkCancelled();
        try {
            if (hedgeFundSource != null && m.ticker != null) {
                m.hedgeFunds = hedgeFundSource.popularityFor(m.ticker).orElse(null);
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] hedge-fund popularity failed: {}", e.getMessage());
        }
        // The dated analyst-action history + percent-of-float shorts
        // (MarketBeat) — ticker as resolved; the client routes venue suffixes
        // itself (.DE→ETR, .L→LON, bare US shape) and gates the rest.
        checkCancelled();
        try {
            if (analystActionsSource != null && m.ticker != null) {
                m.analystActions = analystActionsSource.actionsFor(m.ticker).orElse(null);
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] analyst actions failed: {}", e.getMessage());
        }
        // The dated press timeline (MarketBeat news tab, ticker as resolved):
        // months of dated headlines — how the name got HERE, past the 30-day
        // news window. Context material for the author, deliberately never
        // triaged/digested/woven as sources.
        checkCancelled();
        try {
            if (analystActionsSource != null && m.ticker != null) {
                m.pressTimeline = analystActionsSource.pressTimelineFor(m.ticker).orElse(null);
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] press timeline failed: {}", e.getMessage());
        }
        // Sector & macro context — the Abendausgabe arsenal scoped to THIS
        // instrument (user mandate 2026-07-14): what pressed or carried the
        // sector today (XL* proxy vs the instrument, house arithmetic), the
        // day's high-impact macro ACTUALS in the weather pattern (Ist vs
        // Prognose vs zuvor, comparison computed by us), and the dated macro
        // docket the sector trades on as Ausblick anchors. Woven, never a
        // section of its own — context attaches to the stories.
        checkCancelled();
        try {
            SectorEtf etf = sectorEtfFor(
                    m.facts != null ? m.facts.sector() : null,
                    m.facts != null ? m.facts.branch() : null,
                    m.usStats != null ? m.usStats.sector() : null,
                    m.usStats != null ? m.usStats.industry() : null);
            if (etf != null && yahooClient != null) {
                m.sectorEtf = yahooClient.fetchChart(etf.symbol()).orElse(null);
                if (m.sectorEtf != null) {
                    m.sectorEtfSymbol = etf.symbol();
                    m.sectorDisplayName = etf.name();
                }
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] sector proxy failed: {}", e.getMessage());
        }
        // The fishing-net's FULL catch (2026-07-15, user mandate "alles rein,
        // die KI sortiert aus"): hazards AND world signals are collected
        // unfiltered — the subject-scoped relevance judge decides later, with
        // the report's theme landscape on the table. The old sector-exposure
        // pre-gate is demoted to a candidate-line HINT (a pre-filter would
        // hide the wild-but-real connection from the model forever).
        try {
            de.bsommerfeld.wsbg.terminal.briefing.GlobalHazardsClient hazards = hazardsClient;
            if (hazards != null) m.allHazards = hazards.hazards();
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] world hazards failed: {}", e.getMessage());
        }
        try {
            WorldSignalsCollector collector = worldCollector;
            if (collector != null) {
                java.time.ZoneId zone = java.time.ZoneId.systemDefault();
                java.time.LocalDate today = java.time.LocalDate.now(zone);
                m.worldSignals = collector.collect(today, zone,
                        today.atStartOfDay(zone).toInstant());
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] world signals failed: {}", e.getMessage());
        }
        try {
            if (tvCalendar != null) {
                java.time.ZoneId zone = java.time.ZoneId.systemDefault();
                Instant dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant();
                List<TradingViewCalendarClient.TvEvent> actuals = new ArrayList<>();
                for (TradingViewCalendarClient.TvEvent e : tvCalendar.events(dayStart, Instant.now())) {
                    if (e.actual() == null || !relevantMacro(e.importance(), e.country())) continue;
                    actuals.add(e);
                    if (actuals.size() >= MAX_MACRO_ACTUALS) break;
                }
                m.macroActualsToday = actuals;
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] macro actuals failed: {}", e.getMessage());
        }
        try {
            if (econCalendar != null) {
                long now = Instant.now().getEpochSecond();
                List<EconCalendarClient.EconEvent> docket = new ArrayList<>();
                for (EconCalendarClient.EconEvent e : econCalendar.thisWeek()) {
                    if (e.whenEpochSeconds() <= now) continue;
                    if (e.impact() == null || !e.impact().toLowerCase(Locale.ROOT).contains("high")) continue;
                    docket.add(e);
                    if (docket.size() >= MAX_MACRO_DOCKET) break;
                }
                m.macroDocket = docket;
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] macro docket failed: {}", e.getMessage());
        }
        try {
            if (cbCalendar != null) {
                m.cbDecisions = cbCalendar.upcomingDecisions(java.time.LocalDate.now(), 1);
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] central-bank calendar failed: {}", e.getMessage());
        }
        // "Was war" from the house's own permanent memory: the wire archive
        // holds every line ever published about this ticker — beyond the
        // 30-day news window, with dates. First lines + newest lines carry
        // the story arc; the middle is elided honestly.
        try {
            if (headlineArchive != null && m.ticker != null) {
                m.wireHistory = headlineArchive.byTicker(m.ticker);
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] wire archive failed: {}", e.getMessage());
        }
        // Structure layer (market memory): the house-computed volume profile
        // (where volume TRADED — every level justified by its own volume) and
        // the Frankfurt floor-book window (who is standing there NOW, orders +
        // units per level). Deterministic arithmetic, the model only reads.
        checkCancelled();
        try {
            if (yahooClient != null && m.ticker != null) {
                m.volumeProfile = VolumeProfile.build(
                        yahooClient.fetchHourlyBars(m.ticker, VOLUME_PROFILE_RANGE_DAYS))
                        .orElse(null);
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] volume profile failed: {}", e.getMessage());
        }
        try {
            if (orderBookSource != null && m.isin != null) {
                m.orderBook = orderBookSource.orderBookByIsin(m.isin).orElse(null);
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] order book failed: {}", e.getMessage());
        }
        // Market memory: the instrument's own dated events from the house
        // register plus the class base rates (house statistics gated by N,
        // attributed literature priors as the default layer). The current
        // regime slices the statistics when its cell carries enough events.
        try {
            if (marketEventArchive != null) {
                List<de.bsommerfeld.wsbg.terminal.db.MarketEventRecord> events =
                        new ArrayList<>(m.ticker != null
                                ? marketEventArchive.byInstrument(m.ticker) : List.of());
                if (m.isin != null) {
                    for (var e : marketEventArchive.byInstrument(m.isin)) {
                        if (!events.contains(e)) events.add(e);
                    }
                }
                events.sort(java.util.Comparator.comparing(
                        de.bsommerfeld.wsbg.terminal.db.MarketEventRecord::date));
                m.memoryEvents = events;
                m.baseRateLines = buildBaseRateLines(events, marketEventArchive, currentRegimeBand());
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] market memory failed: {}", e.getMessage());
        }
        journalCollectedSources(ticker, m);

        // The street's numbers for the NEXT report (EarningsWhispers day calendar,
        // US names): the Consorsbank leg delivers the dated events, the calendar
        // answers per day — query the first future event dates and keep the row
        // matching our base symbol. EPS + revenue consensus anchor the Ausblick.
        try {
            de.bsommerfeld.wsbg.terminal.briefing.EarningsWhispersClient whispers =
                    earningsWhispers;
            String base = baseSymbol(m.ticker) == null ? m.ticker : baseSymbol(m.ticker);
            if (whispers != null && m.analystView != null && base != null
                    && base.matches("[A-Z]{1,5}")) {
                long nowEpoch = Instant.now().getEpochSecond();
                Set<java.time.LocalDate> tried = new java.util.LinkedHashSet<>();
                for (AnalystView.CorporateEvent event : m.analystView.events()) {
                    if (event.atEpochSeconds() < nowEpoch || tried.size() >= 2) continue;
                    java.time.LocalDate day = java.time.LocalDate.ofInstant(
                            Instant.ofEpochSecond(event.atEpochSeconds()),
                            java.time.ZoneId.systemDefault());
                    if (!tried.add(day)) continue;
                    for (var est : whispers.estimatesOn(day)) {
                        if (est.ticker().equalsIgnoreCase(base)) {
                            m.earningsEstimate = est;
                            m.earningsEstimateDateIso = day.toString();
                            break;
                        }
                    }
                    if (m.earningsEstimate != null) break;
                }
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] earnings consensus failed: {}", e.getMessage());
        }

        // Triangulated news (Yahoo + WSO + Google News + Fool), freshness-cut.
        // Relevance is judged SEPARATELY (triage) — the aggregator ranks but
        // never discards.
        try {
            if (newsAggregator != null) {
                // The full triangulation: symbol + name + ISIN per source —
                // the ISIN is the key that never chases a wrong twin and finds
                // the disclosure-grade documents (EQS prints it verbatim).
                List<RawNewsItem> pooled = new ArrayList<>(newsAggregator.newsFor(
                        m.ticker, m.canonicalName, m.isin, MAX_NEWS_CANDIDATES));
                // Yahoo indexes news under the BASE symbol: q=SAP answers the
                // real company stories (restructuring, antitrust — live-probed),
                // q=SAP.DE answers an unrelated firehose. The resolver upgrades
                // the ticker to the German venue form, so the base symbol must
                // ride as a SECOND query or the richest news leg goes dark.
                String base = baseSymbol(m.ticker);
                if (base != null) {
                    Set<String> seen = new HashSet<>();
                    for (RawNewsItem item : pooled) seen.add(newsKey(item));
                    for (RawNewsItem item : newsAggregator.newsFor(base, m.canonicalName,
                            MAX_NEWS_CANDIDATES)) {
                        if (seen.add(newsKey(item))) pooled.add(item);
                    }
                }
                if (!pooled.isEmpty()) m.news = pooled;
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] news failed: {}", e.getMessage());
        }
        // Multi-year press HISTORY (2026-07-16): one windowed archive query
        // per past calendar year - headlines only, never triaged or digested
        // (context for the long-term arc, not weave material; the freshness
        // cut on m.news must not touch it).
        try {
            if (newsAggregator != null && m.canonicalName != null) {
                int year = java.time.LocalDate.now().getYear();
                List<RawNewsItem> history = new ArrayList<>();
                for (int y = year - PRESS_HISTORY_YEARS; y < year; y++) {
                    history.addAll(newsAggregator.historyFor(m.canonicalName, m.isin,
                            y + "-01-01", (y + 1) + "-01-01", PRESS_HISTORY_PER_YEAR));
                }
                if (!history.isEmpty()) {
                    m.pressHistory = history;
                    LOG.info("[DEEPDIVE] '{}' press history: {} headline(s) across {} year(s).",
                            ticker, history.size(), PRESS_HISTORY_YEARS);
                }
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] press history failed: {}", e.getMessage());
        }
        // The OUTSIDE ROOMS (2026-07-16): the aggregator's sentiment fan -
        // forum/social chatter, kept strictly apart from the press pool. The
        // clients cut exchange suffixes themselves, so one triangulated call
        // suffices; the result feeds the ROOM shelf as one capped block.
        try {
            if (newsAggregator != null) {
                List<RawNewsItem> sentiment = newsAggregator.sentimentFor(
                        m.ticker, m.canonicalName, m.isin, MAX_SENTIMENT_ITEMS);
                if (!sentiment.isEmpty()) {
                    m.socialSentiment = sentiment;
                    LOG.info("[DEEPDIVE] '{}' outside rooms: {} forum/social voice(s).",
                            ticker, sentiment.size());
                }
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] sentiment fan failed: {}", e.getMessage());
        }
        // The general web sweep ("<Name> News", user mandate): Bing's search
        // RSS answers the open web — IR pages, publisher articles Google News
        // never indexes — with direct target URLs the digester can read.
        try {
            de.bsommerfeld.wsbg.terminal.websearch.BingWebSearchClient web = webSearch;
            if (web != null && m.canonicalName != null
                    && !m.canonicalName.equalsIgnoreCase(m.ticker)) {
                Set<String> seen = new HashSet<>();
                for (RawNewsItem item : m.news) seen.add(newsKey(item));
                List<RawNewsItem> merged = new ArrayList<>(m.news);
                int added = 0;
                for (RawNewsItem item : web.newsForName(m.canonicalName, 30)) {
                    if (seen.add(newsKey(item))) {
                        merged.add(item);
                        added++;
                    }
                }
                if (added > 0) {
                    LOG.info("[DEEPDIVE] '{}' web sweep added {} candidate(s).",
                            m.ticker, added);
                    m.news = merged;
                }
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] web sweep failed: {}", e.getMessage());
        }
        // First-party disclosures: finanznachrichten's ad-hoc feed is ISIN-
        // tagged — an exact join, zero ambiguity — and the analyst-action feed
        // carries the rating stories WITH their house names.
        appendFnLegs(m);
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(NEWS_MAX_AGE_DAYS));
        m.news = m.news.stream()
                .filter(n -> n.publishedAt() == null || !n.publishedAt().isBefore(cutoff))
                .limit(MAX_NEWS_CANDIDATES)
                .toList();
        if (!m.news.isEmpty()) {
            journalNotes(ticker, List.of(journalGerman()
                    ? "News-Pool — " + m.news.size()
                            + " Kandidaten (Symbol + Name + ISIN + Websuche + Presse)"
                    : "News pool — " + m.news.size()
                            + " candidates (symbol + name + ISIN + web search + press)"));
        }
        // Quant signals LAST — the kernels read the legs collected above
        // (wire archive, room evidence, daily closes, sector proxy, earnings
        // date) and answer as finished reading lines (number + definition +
        // reading instruction). Statistics in code, the model only reads;
        // a data-starved kernel costs its line, never the block.
        try {
            List<HeadlineRecord> recentArchive = headlineArchive != null
                    ? headlineArchive.recent(java.time.Duration.ofDays(60)) : List.of();
            m.signalReadings = SignalDesk.forInstrument(m.ticker, m.wireHistory,
                    recentArchive, m.roomUnits, m.snapshot, m.sectorEtf,
                    m.earningsEstimateDateIso, !m.news.isEmpty(), Instant.now());
            if (!m.signalReadings.isEmpty()) {
                LOG.info("[DEEPDIVE] '{}' quant signals: {} reading(s).",
                        ticker, m.signalReadings.size());
            }
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] quant signals failed: {}", e.getMessage());
        }
        return m;
    }

    /**
     * The review pane's SOURCE containers (user mandate 2026-07-14 "vor den
     * Diffs die Quellen zeigen"): one journal group per delivered data leg,
     * terse outcome only — what was fetched, never narration.
     */
    private void journalCollectedSources(String subject, Material m) {
        boolean de = journalGerman();
        if (m.snapshot != null && m.snapshot.hasPrice()) {
            String venue = m.snapshot.exchangeName() == null || m.snapshot.exchangeName().isBlank()
                    ? (de ? "Kursdaten" : "Price data") : m.snapshot.exchangeName();
            journalNotes(subject, List.of(venue + (de ? " — Kurs " : " — price ")
                    + fmt2(m.snapshot.price())
                    + (m.snapshot.currency() == null ? "" : " " + m.snapshot.currency())));
        }
        if (m.venueStats != null) {
            journalNotes(subject, List.of("Tradegate — "
                    + (de ? "Volumen " : "volume ")
                    + groupedInt(m.venueStats.volumeShares()) + (de ? " Stück, " : " shares, ")
                    + groupedInt(m.venueStats.executions()) + (de ? " Trades" : " trades")));
        }
        if (m.facts != null) {
            journalNotes(subject, List.of("onvista — " + (de ? "Profil" : "profile")
                    + (m.facts.sector() == null ? "" : ": " + m.facts.sector())));
        } else if (m.fundFacts != null) {
            journalNotes(subject, List.of("onvista — "
                    + (de ? "Fondsprofil" : "fund profile")));
        }
        if (m.analystView != null && m.analystView.hasRatings()) {
            journalNotes(subject, List.of("Consorsbank — " + m.analystView.total()
                    + (de ? " Analysten, " : " analysts, ")
                    + m.analystView.events().size() + (de ? " Termine" : " events")));
        }
        if (m.deepDive != null) {
            journalNotes(subject, List.of("Consorsbank — "
                    + (de ? "Kennzahlen: " : "key figures: ")
                    + m.deepDive.keyFigures().size()
                    + (de ? " Geschäftsjahre, Bilanz " : " fiscal years, balance sheet ")
                    + m.deepDive.balanceSheet().size() + (de ? " Jahre" : " years")
                    + (m.deepDive.technicalView() != null
                            ? (de ? ", Charttechnik" : ", technical view") : "")));
        }
        if (m.shortInterest != null) {
            journalNotes(subject, List.of("Bundesanzeiger — "
                    + (m.shortInterest.positions().isEmpty()
                    ? (de ? "keine Shortpositionen ≥ 0,5 %" : "no short positions ≥ 0.5%")
                    : m.shortInterest.positions().size()
                            + (de ? " Shortposition(en), gesamt " : " short position(s), total ")
                            + fmt2(m.shortInterest.totalDisclosedPercent()) + " %")));
        }
        if (m.insiderDealings != null) {
            journalNotes(subject, List.of("BaFin — " + m.insiderDealings.deals().size()
                    + (de ? " Insider-Meldung(en)" : " insider filing(s)")));
        }
        if (m.usStats != null) {
            List<String> bits = new ArrayList<>();
            if (!m.usStats.insiderTrades().isEmpty()) {
                bits.add(m.usStats.insiderTrades().size()
                        + (de ? " Insider-Trades" : " insider trades"));
            }
            if (!m.usStats.shortInterest().isEmpty()) {
                bits.add((de ? "Short Interest " : "short interest ")
                        + m.usStats.shortInterest().size()
                        + (de ? " Stichtage" : " record dates"));
            }
            if (m.usStats.analystRatings() != null) {
                bits.add(de ? "Analysten-Konsens" : "analyst consensus");
            }
            if (m.usStats.institutionalOwnership() != null) {
                bits.add(de ? "13F-Halter" : "13F holders");
            }
            if (!m.usStats.earningsSurprises().isEmpty()) {
                bits.add(m.usStats.earningsSurprises().size()
                        + (de ? " Quartale" : " quarters"));
            }
            journalNotes(subject, List.of("NASDAQ — "
                    + (bits.isEmpty() ? (de ? "US-Listing" : "US listing")
                    : String.join(", ", bits))));
        }
        if (m.hedgeFunds != null && !m.hedgeFunds.quarters().isEmpty()) {
            HedgeFundPopularity.QuarterPoint latest =
                    m.hedgeFunds.quarters().get(m.hedgeFunds.quarters().size() - 1);
            journalNotes(subject, List.of("Insider Monkey — " + latest.funds()
                    + (de ? " Hedgefonds investiert (" : " hedge funds invested (")
                    + latest.quarterLabel() + ")"));
        }
        if (m.analystActions != null) {
            List<String> bits = new ArrayList<>();
            if (!m.analystActions.actions().isEmpty()) {
                bits.add(m.analystActions.actions().size()
                        + (de ? " Analysten-Aktionen" : " analyst actions"));
            }
            if (m.analystActions.shortStats() != null) {
                bits.add(de ? "Short-Quote" : "short ratio");
            }
            journalNotes(subject, List.of("MarketBeat — "
                    + (bits.isEmpty() ? (de ? "Street-Historie" : "street history")
                    : String.join(", ", bits))));
        }
        if (m.pressTimeline != null && !m.pressTimeline.entries().isEmpty()) {
            journalNotes(subject, List.of("MarketBeat — "
                    + (de ? "Presse-Zeitleiste, " : "press timeline, ")
                    + m.pressTimeline.entries().size()
                    + (de ? " Schlagzeilen" : " headlines")));
        }
        if (m.sectorEtf != null || !m.macroActualsToday.isEmpty() || !m.macroDocket.isEmpty()) {
            List<String> bits = new ArrayList<>();
            if (m.sectorEtf != null) {
                bits.add((de ? "Sektor " : "sector ")
                        + m.sectorDisplayName + " (" + m.sectorEtfSymbol + ")");
            }
            if (!m.macroActualsToday.isEmpty()) {
                bits.add(m.macroActualsToday.size()
                        + (de ? " Makro-Ist-Zahl(en) heute" : " macro actual(s) today"));
            }
            if (!m.macroDocket.isEmpty() || !m.cbDecisions.isEmpty()) {
                bits.add((m.macroDocket.size() + m.cbDecisions.size())
                        + (de ? " kommende Termine" : " upcoming events"));
            }
            journalNotes(subject, List.of((de ? "Sektor/Makro — " : "Sector/macro — ")
                    + String.join(", ", bits)));
        }
        if (!m.worldSignalKeep.isEmpty()) {
            journalNotes(subject, List.of((de ? "Weltsignale — " : "World signals — ")
                    + m.worldSignalKeep.size()
                    + (de ? " vom Richter als relevant beurteilt"
                            : " judged relevant by the subject judge")));
        }
        if (!m.wireHistory.isEmpty()) {
            journalNotes(subject, List.of((de ? "Wire-Archiv — " : "Wire archive — ")
                    + m.wireHistory.size()
                    + (de ? " eigene Zeile(n) zu diesem Subjekt"
                            : " own line(s) on this subject")));
        }
        if (!m.roomUnits.isEmpty()) {
            journalNotes(subject, List.of((de ? "Käfig — " : "The room — ")
                    + m.evidenceCount + (de ? " Erwähnung(en) über " : " mention(s) across ")
                    + m.roomUnits.size() + (de ? " Subjekt-Einheit(en)" : " subject unit(s)")));
        }
        if (m.volumeProfile != null || m.orderBook != null) {
            List<String> bits = new ArrayList<>();
            if (m.volumeProfile != null) {
                bits.add(de ? "Volume Profile (POC/Value Area)" : "volume profile (POC/value area)");
            }
            if (m.orderBook != null) {
                bits.add((de ? "Orderbuch " : "order book ")
                        + m.orderBook.bids().size() + "x" + m.orderBook.asks().size()
                        + (de ? " Level" : " levels"));
            }
            journalNotes(subject, List.of((de ? "Struktur — " : "Structure — ")
                    + String.join(", ", bits)));
        }
        if (!m.memoryEvents.isEmpty() || !m.baseRateLines.isEmpty()) {
            journalNotes(subject, List.of((de ? "Markt-Gedächtnis — " : "Market memory — ")
                    + m.memoryEvents.size()
                    + (de ? " registrierte(s) Ereignis(se), " : " registered event(s), ")
                    + m.baseRateLines.size() + (de ? " Basisraten-Zeile(n)" : " base-rate line(s)")));
        }
    }

    /** Deterministic unit lookup: exact ticker/name, then significant-word subset (watchlist rule). */
    private SubjectUnit findUnit(String name, String ticker) {
        List<SubjectUnit> hits = findRoomUnits(name, ticker);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** Sanity cap — the room union never needs more than a handful of units. */
    private static final int MAX_ROOM_UNITS = 5;

    /**
     * EVERY registry unit belonging to the subject, best first: exact
     * ticker/name matches, then significant-word-subset name matches (the
     * watchlist rule) — so the "Outlook" name unit rides along with the OTLK
     * ticker unit and the room's chatter is found under both identities.
     */
    private List<SubjectUnit> findRoomUnits(String name, String ticker) {
        record Scored(SubjectUnit unit, int score) {
        }
        Set<String> nameWords = NameMatcher.significantWords(name);
        List<Scored> hits = new ArrayList<>();
        for (SubjectUnit u : subjectRegistry.all()) {
            int score = -1;
            if (name.equalsIgnoreCase(u.ticker()) || name.equalsIgnoreCase(u.canonicalName())
                    || (ticker != null && ticker.equalsIgnoreCase(u.ticker()))) {
                score = 1_000_000;
            } else if (!nameWords.isEmpty()) {
                Set<String> unitWords = NameMatcher.significantWords(u.canonicalName());
                boolean subset = !unitWords.isEmpty()
                        && (unitWords.containsAll(nameWords) || nameWords.containsAll(unitWords));
                if (subset) score = 0;
            }
            if (score < 0) continue;
            score += (u.isInstrument() ? 100_000 : 0) + Math.min(u.evidenceCount(), 10_000);
            hits.add(new Scored(u, score));
        }
        hits.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<SubjectUnit> out = new ArrayList<>(Math.min(hits.size(), MAX_ROOM_UNITS));
        for (Scored s : hits) {
            if (out.size() >= MAX_ROOM_UNITS) break;
            out.add(s.unit());
        }
        return out;
    }

    // -- per-section materials (the workspace's shelves) --

    /** The compact identity header every pass carries (date, subject, ticker, ISIN, index). */
    static String header(String subject, Material m) {
        StringBuilder sb = new StringBuilder(160);
        // Date only: the vantage the dossier speaks from needs no minutes -
        // and a clock time in the letterhead leaks into prose ("Eine
        // Veröffentlichung vom 16. Juli 2026 um 05:38", live smoke 2026-07-16).
        sb.append("NOW: ").append(STAMP.format(Instant.now()), 0, 10).append('\n');
        sb.append("SUBJECT: ").append(m.canonicalName);
        if (m.ticker != null) sb.append(" (Ticker ").append(m.ticker).append(')');
        if (m.isin != null) sb.append(" [ISIN ").append(m.isin).append(']');
        if (m.deepDive != null && m.deepDive.indexName() != null) {
            sb.append(", member of ").append(m.deepDive.indexName());
        }
        return sb.append('\n').append('\n').toString();
    }

    /**
     * Builds each section's material shelf from the collected legs — the fixed
     * mapping mirrors the skeleton: profile/boards feed "Worum es geht", market/
     * trading/performance plus the LAGE news feed "Lage", key figures/balance
     * feed "Fundamentale Entwicklung", peers/analyst ratings feed "Bewertung",
     * insiders/shorts/chart-technicals plus KATALYSATOR news feed
     * "Katalysatoren", dated events/estimate path/street view plus AUSBLICK
     * news feed "Ausblick" (the anchored Soll-Zustand), and the room union
     * feeds "Der Raum". "These" gets its shelf LAST (the standing sections'
     * claim sentences + key data). Blank shelves mean the honest literal.
     */
    static String[] sectionMaterials(Material m) {
        Shelf[] shelves = sectionShelves(m);
        String[] out = new String[SECTION_COUNT];
        for (int i = 0; i < SECTION_COUNT; i++) {
            String combined = shelves[i] == null ? null : shelves[i].combined();
            out[i] = combined == null || combined.isBlank() ? null : combined;
        }
        return out;
    }

    /** The shelves with the news SEPARATED into weave steps — the prod view. */
    static Shelf[] sectionShelves(Material m) {
        Map<String, Integer> nums = sourceNumbers(m);
        Shelf[] out = new Shelf[SECTION_COUNT];
        StringBuilder sb = new StringBuilder(2048);

        appendProfile(sb, m, nums);
        appendUsListing(sb, m.usStats, nums);
        appendBoard(sb, m.deepDive, nums);
        out[SEC_ABOUT] = new Shelf(take(sb), List.of());

        // NARRATIVE FIRST, tape LAST: a 4B anchors on the material's opening
        // line (the thesis lesson, 2026-07-14), and with the market block up
        // front the Lage opened as a price recitation in every live run
        // despite the section definition ("the event leads, the price
        // reaction follows"). The shelf now leads with the story context -
        // sector, world, press arc - and the trading tape closes as evidence.
        appendSectorContext(sb, m, nums);
        appendWorldSignals(sb, m, nums);
        appendPressTimeline(sb, m.pressTimeline, nums);
        appendPressHistory(sb, m, nums);
        appendPerformance(sb, m.deepDive, nums);
        appendMarket(sb, m.snapshot, nums);
        appendTrading(sb, m.venueStats, m.facts, nums);
        appendStructure(sb, m, nums);
        appendSignals(sb, m, nums, SIGNALS_SITUATION);
        out[SEC_SITUATION] = new Shelf(take(sb), newsBlocksFor(m, "LAGE", nums));

        appendIrArchive(sb, m, nums, false);
        appendFundamentals(sb, m.deepDive, nums);
        appendBalance(sb, m.deepDive, nums);
        appendShareCount(sb, m.deepDive, nums);
        appendUsSurprises(sb, m.usStats, nums);
        out[SEC_FUNDAMENTALS] = new Shelf(take(sb), List.of());

        appendAnalystRatings(sb, m.analystView, nums);
        appendUsAnalysts(sb, m.usStats, nums);
        appendAnalystActions(sb, m.analystActions, nums);
        appendValuationContext(sb, m, nums);
        appendUsInstitutional(sb, m.usStats, nums);
        appendHedgeFunds(sb, m.hedgeFunds, nums);
        appendPeers(sb, m.deepDive, nums);
        out[SEC_VALUATION] = new Shelf(take(sb), newsBlocksFor(m, "BEWERTUNG", nums));

        appendInsider(sb, m.insiderDealings, nums);
        appendUsInsiders(sb, m.usStats, nums);
        appendShorts(sb, m.shortInterest, nums);
        appendUsShortInterest(sb, m.usStats, nums);
        appendUsShortQuote(sb, m.analystActions, nums);
        appendShareCount(sb, m.deepDive, nums);
        appendTechnical(sb, m.deepDive, nums);
        appendMarketMemory(sb, m, nums);
        out[SEC_CATALYSTS] = new Shelf(take(sb), newsBlocksFor(m, "KATALYSATOR", nums));

        appendUpcomingEvents(sb, m.analystView, nums);
        appendIrArchive(sb, m, nums, true);
        appendMacroDocket(sb, m, nums);
        appendEarningsConsensus(sb, m, nums);
        appendEstimatePath(sb, m.deepDive, nums);
        appendAnalystRatings(sb, m.analystView, nums);
        appendUsAnalysts(sb, m.usStats, nums);
        appendValuationContext(sb, m, nums);
        appendSignals(sb, m, nums, SIGNALS_OUTLOOK);
        out[SEC_OUTLOOK] = new Shelf(take(sb), newsBlocksFor(m, "AUSBLICK", nums));

        StringBuilder roomSb = new StringBuilder(256);
        appendWireHistory(roomSb, m, nums);
        String room = roomText(m, nums);
        List<String> roomParts = new ArrayList<>(3);
        if (room != null && !room.isBlank()) roomParts.add(room);
        if (roomSb.length() > 0) roomParts.add(roomSb.toString());
        // The OUTSIDE ROOMS ride the same shelf as one capped aggregate
        // block (empty newsBlocks = loom bypass, exactly like the cage).
        String outsideRooms = sentimentBlock(m, nums);
        if (outsideRooms != null) roomParts.add(outsideRooms);
        StringBuilder roomSignals = new StringBuilder();
        appendSignals(roomSignals, m, nums, SIGNALS_ROOM);
        if (roomSignals.length() > 0) roomParts.add(roomSignals.toString());
        out[SEC_ROOM] = new Shelf(
                roomParts.isEmpty() ? null : String.join("\n", roomParts), List.of());
        out[SEC_THESIS] = new Shelf(null, List.of());
        // Figure pointers ride LAST on every non-empty shelf: the IDs and
        // captions of the section's house figures, so the prose can point at
        // them ("Abbildung A3") instead of retelling their series - copy-only
        // tokens, appended after the cap (a dozen short lines never starve
        // the material). An otherwise-empty shelf stays empty: pointers alone
        // are no reason to write a section.
        for (int i = 0; i < SECTION_COUNT; i++) {
            List<String> captions = m.figureCaptions.get(i);
            if (captions == null || captions.isEmpty() || out[i] == null) continue;
            String base = out[i].base();
            if (isBlank(base) && out[i].newsBlocks().isEmpty()) continue;
            String block = "FIGURES set beside this section (point at one by its ID, "
                    + "e.g. \"Abbildung " + captions.get(0).split(":")[0]
                    + "\"; IDs copy-only, never invented):\n  "
                    + String.join("\n  ", captions) + "\n";
            out[i] = new Shelf(isBlank(base) ? block : base + block, out[i].newsBlocks());
        }
        return out;
    }

    /**
     * The company's OWN IR archive as a dated block - the first-party record
     * the press paraphrases. {@code futureOnly} serves the outlook (coming
     * dates); the fundamentals shelf carries the full archive. Undatable
     * entries ride only the full view, honestly unlabeled.
     */
    private static void appendIrArchive(StringBuilder sb, Material m,
            Map<String, Integer> nums, boolean futureOnly) {
        if (m.irEntries.isEmpty()) return;
        String today = STAMP.format(Instant.now()).substring(0, 10);
        List<String> lines = new ArrayList<>();
        for (CompanyPressScout.IrEntry e : m.irEntries) {
            if (futureOnly && (e.dateIso() == null || e.dateIso().compareTo(today) < 0)) {
                continue;
            }
            lines.add("  - " + (e.dateIso() != null ? "[" + e.dateIso() + "] " : "")
                    + e.title());
        }
        if (lines.isEmpty()) return;
        sb.append(futureOnly
                        ? "IR CALENDAR (first-party, the company's own coming dates)"
                        : "IR ARCHIVE (first-party: the company's own reports, calls and dates)")
                .append(mark(nums, "ir")).append(":\n");
        for (String line : lines) sb.append(line).append('\n');
    }

    /**
     * The multi-year press history as a compact dated block - headlines only,
     * one line per entry, newest years last so the arc reads forward. The
     * long-term dossier's memory of the name beyond the 30-day news window.
     */
    private static void appendPressHistory(StringBuilder sb, Material m,
            Map<String, Integer> nums) {
        if (m.pressHistory.isEmpty()) return;
        sb.append("PRESS HISTORY (multi-year, headlines only - the name's longer arc; ")
                .append("windowed archive search)").append(mark(nums, "history")).append(":\n");
        for (RawNewsItem item : m.pressHistory) {
            sb.append("  - ");
            if (item.publishedAt() != null) {
                sb.append('[').append(STAMP.format(item.publishedAt()), 0, 10).append("] ");
            }
            sb.append(item.title() == null ? "" : item.title().strip());
            if (item.publisher() != null && !item.publisher().isBlank()) {
                sb.append(" · ").append(item.publisher());
            }
            sb.append('\n');
        }
    }

    /** One weave step per routed article: the item block WITH its digest. */
    private static List<String> newsBlocksFor(Material m, String target,
            Map<String, Integer> nums) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < m.news.size(); i++) {
            RawNewsItem item = m.news.get(i);
            String route = m.newsTargets.getOrDefault(newsKey(item), "LAGE");
            if (!routeCovers(route, target)) continue;
            String block = newsItemBlock(item, m.digests, mark(nums, "news:" + i));
            if (item.link() != null && !item.link().isBlank()) {
                m.newsLinksByBlock.put(block, item.link().trim());
            }
            out.add(block);
        }
        return out;
    }

    /** Whether a comma-joined triage route covers the shelf's target section. */
    private static boolean routeCovers(String route, String target) {
        for (String r : route.split(",")) {
            if (r.trim().equals(target)) return true;
        }
        return false;
    }

    private static String take(StringBuilder sb) {
        String text = sb.toString();
        sb.setLength(0);
        if (text.isBlank()) return null;
        int cap = sectionMaterialChars();
        if (text.length() > cap) {
            int nl = text.lastIndexOf('\n', cap);
            text = text.substring(0, nl > cap / 2 ? nl : cap)
                    + "\n  (material trimmed)\n";
        }
        return text;
    }

    /**
     * The editor's shelf for "These": the key data box plus every standing
     * section's claim sentence — the page-1 read is a synthesis of what the
     * report already establishes, never new research.
     */
    static String thesisMaterial(List<String> headings, String[] bodies, Material m) {
        Map<String, Integer> nums = sourceNumbers(m);
        StringBuilder sb = new StringBuilder(8192);
        // The editor reads the STANDING SECTIONS IN FULL (2026-07-16 zoom-out:
        // the old claim-sentence proxy - first sentence per section - bred a
        // whole rabbit hole of layered fixes: price-led claims, primacy
        // reordering, red-thread echoes, verbatim copies. Since the window
        // scales with the machine, the whole report fits the editor's pass;
        // a typical report is ~11k chars, so even the 8k floor's input budget
        // rarely trims - and when it does, the cut takes the tail).
        sb.append("THE STANDING SECTIONS (the report the reader sees below the thesis):\n");
        for (int i = 0; i < SECTION_COUNT; i++) {
            if (i == SEC_THESIS || bodies[i] == null) continue;
            sb.append("## ").append(headings.get(i)).append('\n')
                    .append(bodies[i].strip()).append("\n\n");
        }
        appendKeyData(sb, m, nums);
        return sb.toString();
    }

    /** The key-data box as text (the page-1 numbers, same data the facts figure carries). */
    private static void appendKeyData(StringBuilder sb, Material m, Map<String, Integer> nums) {
        StringBuilder line = new StringBuilder(256);
        MarketSnapshot s = m.snapshot;
        if (s != null && s.hasPrice()) {
            line.append("price ").append(fmt2(s.price()));
            if ("PTS".equals(s.currency())) line.append(" points");
            else if (s.currency() != null) line.append(' ').append(s.currency());
            if (Double.isFinite(s.dayChangePercent())) {
                line.append(String.format(Locale.ROOT, " (day %+.2f%%)", s.dayChangePercent()));
            }
            line.append(mark(nums, "price")).append("; ");
        }
        CompanyDeepDive.Profile p = m.deepDive != null ? m.deepDive.profile() : null;
        if (p != null && Double.isFinite(p.marketCapEur())) {
            line.append(String.format(Locale.ROOT, "market cap %.1fB EUR", p.marketCapEur() / 1e9))
                    .append(mark(nums, "consors")).append("; ");
        }
        if (m.deepDive != null) {
            for (CompanyDeepDive.KeyFigureYear y : m.deepDive.keyFigures()) {
                if (y.estimate() && Double.isFinite(y.peRatio())) {
                    line.append("P/E ").append(y.label()).append(' ').append(fmt2(y.peRatio()))
                            .append(mark(nums, "consors")).append("; ");
                    break;
                }
            }
        }
        AnalystView av = m.analystView;
        if (av != null && av.hasRatings() && Double.isFinite(av.targetPrice())) {
            line.append(String.format(Locale.ROOT, "consensus target %.2f %s",
                    av.targetPrice(), av.targetCurrency() == null ? "" : av.targetCurrency()));
            if (Double.isFinite(av.expectedUpsidePercent())) {
                line.append(String.format(Locale.ROOT, " (%+.1f%%)", av.expectedUpsidePercent()));
            }
            line.append(mark(nums, "consors")).append("; ");
        }
        if (av != null) {
            AnalystView.CorporateEvent next = av.nextEvent(Instant.now().getEpochSecond());
            if (next != null) {
                line.append("next event ")
                        .append(STAMP.format(Instant.ofEpochSecond(next.atEpochSeconds())), 0, 10);
                if (next.title() != null) line.append(' ').append(next.title());
                line.append(mark(nums, "consors")).append("; ");
            }
        }
        if (line.length() > 0) {
            sb.append("KEY DATA (verified): ").append(line).append('\n');
        }
        // The house arithmetic rides into the editor's shelf too — a thesis
        // that may not cite "29.1x the 2026e EPS" cannot anchor its stance
        // (live: the figures got surgically removed because the thesis shelf
        // never carried them).
        String context = valuationContextLine(m);
        if (!context.isEmpty()) {
            sb.append("VALUATION CONTEXT (house arithmetic on the verified figures)")
                    .append(mark(nums, "consors")).append(": ").append(context).append('\n');
        }
    }

    /**
     * The FULL material as one text — header plus every section shelf. Only the
     * completeness test reads this (it asserts every leg lands SOMEWHERE); the
     * model itself always receives one section's shelf at a time.
     */
    static String buildMaterial(String subject, Material m) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append(header(subject, m));
        for (String material : sectionMaterials(m)) {
            if (material != null) sb.append(material);
        }
        return sb.toString();
    }

    // -- deterministic source numbering + material blocks --

    /**
     * Deterministic source numbering — the fixed leg order, numbers assigned only
     * to legs that actually delivered, then one number PER (triage-approved) news
     * article, the room last. Pure function of the material: section markers and
     * the appended register can never disagree.
     */
    static Map<String, Integer> sourceNumbers(Material m) {
        Map<String, Integer> nums = new LinkedHashMap<>();
        int n = 0;
        if (m.snapshot != null && m.snapshot.hasPrice()) nums.put("price", ++n);
        if (m.venueStats != null) nums.put("venue", ++n);
        if (m.facts != null || m.fundFacts != null) nums.put("profile", ++n);
        if (m.deepDive != null || m.analystView != null) nums.put("consors", ++n);
        if (m.deepDive != null && m.deepDive.technicalView() != null) nums.put("tc", ++n);
        if (m.shortInterest != null) nums.put("shorts", ++n);
        if (m.insiderDealings != null) nums.put("insider", ++n);
        if (m.usStats != null) nums.put("nasdaq", ++n);
        if (m.hedgeFunds != null && !m.hedgeFunds.quarters().isEmpty()) nums.put("hedgefunds", ++n);
        if ((m.analystActions != null && (!m.analystActions.actions().isEmpty()
                || m.analystActions.shortStats() != null))
                || (m.pressTimeline != null && !m.pressTimeline.entries().isEmpty())) {
            nums.put("marketbeat", ++n);
        }
        if (m.sectorEtf != null || !m.macroActualsToday.isEmpty()
                || !m.macroDocket.isEmpty() || !m.cbDecisions.isEmpty()) {
            nums.put("sector", ++n);
        }
        if (!m.worldSignalKeep.isEmpty()) nums.put("world", ++n);
        if (m.earningsEstimate != null) nums.put("whispers", ++n);
        if (!m.pressHistory.isEmpty()) nums.put("history", ++n);
        if (!m.irEntries.isEmpty()) nums.put("ir", ++n);
        if (m.volumeProfile != null || m.orderBook != null) nums.put("structure", ++n);
        if (!m.memoryEvents.isEmpty() || !m.baseRateLines.isEmpty()) nums.put("memory", ++n);
        if (!m.signalReadings.isEmpty()) nums.put("signals", ++n);
        for (int i = 0; i < m.news.size(); i++) nums.put("news:" + i, ++n);
        if (roomHasContent(m)) nums.put("room", ++n);
        if (!m.socialSentiment.isEmpty()) nums.put("sentiment", ++n);
        return nums;
    }

    /** A silent room (no mention, no wire line) earns no source number either. */
    private static boolean roomHasContent(Material m) {
        if (!m.wireHistory.isEmpty()) return true;
        List<SubjectUnit> units = !m.roomUnits.isEmpty() ? m.roomUnits
                : m.unit != null ? List.of(m.unit) : List.of();
        for (SubjectUnit u : units) {
            if (u.evidenceCount() > 0 || !u.headlines().isEmpty()) return true;
        }
        return false;
    }

    private static String mark(Map<String, Integer> nums, String key) {
        Integer n = nums.get(key);
        return n == null ? "" : " [" + n + "]";
    }

    /** One news item as a material block: marker, date, title, publisher, and the article's key-fact digest underneath. */
    private static String newsItemBlock(RawNewsItem item, Map<String, String> digests, String mark) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("  -").append(mark).append(' ');
        if (item.publishedAt() != null) sb.append('[').append(STAMP.format(item.publishedAt())).append("] ");
        sb.append(item.title());
        if (item.publisher() != null && !item.publisher().isEmpty()) {
            sb.append(" · ").append(item.publisher());
        }
        sb.append('\n');
        String digest = item.link() == null ? null : digests.get(item.link().trim());
        if (digest != null && !digest.isBlank()) {
            String dg = digest.replace('\n', ' ').strip();
            if (dg.length() > maxDigestChars()) dg = dg.substring(0, maxDigestChars()) + "…";
            sb.append("      ").append(dg).append('\n');
        }
        return sb.toString();
    }

    private static void appendMarket(StringBuilder sb, MarketSnapshot s, Map<String, Integer> nums) {
        if (s == null || !s.hasPrice()) return;
        sb.append("MARKET (verified)").append(mark(nums, "price")).append(": ").append(fmt2(s.price()));
        if ("PTS".equals(s.currency())) sb.append(" points (index)");
        else if (s.currency() != null && !s.currency().isEmpty()) sb.append(' ').append(s.currency());
        if (Double.isFinite(s.dayChangePercent())) {
            sb.append(String.format(Locale.ROOT, ", day %+.2f%%", s.dayChangePercent()));
        }
        if (Double.isFinite(s.dayLow()) && Double.isFinite(s.dayHigh())) {
            sb.append(", day range ").append(fmt2(s.dayLow())).append('-').append(fmt2(s.dayHigh()));
        }
        sb.append('\n');
    }

    private static void appendProfile(StringBuilder sb, Material m, Map<String, Integer> nums) {
        CompanyDeepDive.Profile p = m.deepDive != null ? m.deepDive.profile() : null;
        var facts = m.facts;
        if (p == null && facts == null && m.fundFacts == null) return;
        // The profile line mixes two feeds: Consorsbank profile + onvista facts.
        sb.append("PROFILE (verified)")
                .append(p != null ? mark(nums, "consors") : "")
                .append(facts != null || m.fundFacts != null ? mark(nums, "profile") : "")
                .append(": ");
        if (p != null) {
            if (p.website() != null) sb.append("official website ").append(p.website()).append("; ");
            if (p.hqCity() != null || p.hqCountry() != null) {
                sb.append("HQ ").append(String.join(", ",
                        java.util.stream.Stream.of(p.hqCity(), p.hqCountry())
                                .filter(java.util.Objects::nonNull).toList())).append("; ");
            }
            if (Double.isFinite(p.marketCapEur())) {
                sb.append(String.format(Locale.ROOT, "market cap %.1fB EUR; ", p.marketCapEur() / 1e9));
            }
            if (p.sharesOutstanding() > 0) {
                // Space grouping — comma-grouped counts misread in German prose.
                sb.append(String.format(Locale.ROOT, "%,d shares; ", p.sharesOutstanding())
                        .replace(',', ' '));
            }
        }
        if (m.snapshot != null && venueName(m.snapshot.exchangeName()) != null) {
            sb.append("primary trading venue ").append(venueName(m.snapshot.exchangeName()))
                    .append("; ");
        }
        if (facts != null) {
            if (facts.sector() != null || facts.branch() != null) {
                sb.append("sector ").append(String.join(" / ",
                        java.util.stream.Stream.of(facts.sector(), facts.branch())
                                .filter(java.util.Objects::nonNull).toList())).append("; ");
            }
            if (facts.employees() >= 0) {
                sb.append(groupedInt(facts.employees())).append(" employees");
                if (facts.employeesLabel() != null) sb.append(" (").append(facts.employeesLabel()).append(')');
                sb.append("; ");
            }
            // Valuation fallback: when the Consorsbank deep dive missed (its
            // key-figure years would carry these), the onvista headline figures
            // must not silently vanish from the model's view.
            if (m.deepDive == null) {
                if (Double.isFinite(facts.marketCapEur()) && (p == null || !Double.isFinite(p.marketCapEur()))) {
                    sb.append(String.format(Locale.ROOT, "market cap %.1fB EUR; ",
                            facts.marketCapEur() / 1e9));
                }
                if (Double.isFinite(facts.peRatio())) {
                    sb.append(String.format(Locale.ROOT, "P/E %.1f (%s); ",
                            facts.peRatio(), facts.peLabel()));
                }
                if (Double.isFinite(facts.divYieldPercent())) {
                    sb.append(String.format(Locale.ROOT, "dividend yield %.2f%% (%s); ",
                            facts.divYieldPercent(), facts.divLabel()));
                }
            }
        }
        if (m.fundFacts != null) {
            var f = m.fundFacts;
            sb.append("FUND").append(mark(nums, "profile")).append(": ").append(f.name());
            if (Double.isFinite(f.terPercent())) {
                sb.append(String.format(Locale.ROOT, ", TER %.2f%%", f.terPercent()));
            }
            if (Double.isFinite(f.volumeEur())) {
                sb.append(String.format(Locale.ROOT, ", volume %.1fB EUR", f.volumeEur() / 1e9));
            }
            if (f.benchmark() != null) sb.append(", benchmark ").append(f.benchmark());
            if (f.morningstarRating() > 0) sb.append(", Morningstar ").append(f.morningstarRating()).append("/5");
            if (!f.topHoldings().isEmpty()) {
                sb.append("; top holdings: ");
                for (int i = 0; i < Math.min(5, f.topHoldings().size()); i++) {
                    var h = f.topHoldings().get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(h.name());
                    if (Double.isFinite(h.weightPercent())) {
                        sb.append(String.format(Locale.ROOT, " %.1f%%", h.weightPercent()));
                    }
                }
            }
            sb.append("; ");
        }
        sb.append('\n');
        if (p != null && p.portrait() != null) {
            sb.append("COMPANY PORTRAIT (verified)").append(mark(nums, "consors"))
                    .append(": ").append(p.portrait()).append('\n');
        }
    }

    private static void appendFundamentals(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null || d.keyFigures().isEmpty()) return;
        sb.append("KEY FIGURES BY FISCAL YEAR (verified)")
                .append(mark(nums, "consors")).append(":\n");
        List<CompanyDeepDive.KeyFigureYear> years = d.keyFigures();
        int from = Math.max(0, years.size() - MAX_KEY_FIGURE_YEARS);
        // PER-SHARE values spelled out, never the bare acronym: a 4B unfolds
        // "EPS" from its prior, not the desk's intent — live SAP smoke
        // 2026-07-15 narrated "EPS 6.95" as "Gewinn von 6,95 Millionen Euro",
        // a unit error the verbatim examiner cannot see (the figure matched).
        for (CompanyDeepDive.KeyFigureYear y : years.subList(from, years.size())) {
            sb.append("  ").append(statusLabel(y)).append(':');
            appendFig(sb, " earnings per share ", y.eps());
            appendFig(sb, ", dividend per share ", y.dividendPerShare());
            appendFig(sb, " (yield ", y.dividendYieldPercent());
            if (Double.isFinite(y.dividendYieldPercent())) sb.append("%)");
            appendFig(sb, ", P/E ", y.peRatio());
            appendFig(sb, ", PEG ", y.pegRatio());
            appendFig(sb, ", EBIT margin ", y.ebitMarginPercent());
            if (Double.isFinite(y.ebitMarginPercent())) sb.append('%');
            appendFig(sb, ", equity ratio ", y.equityRatioPercent());
            if (Double.isFinite(y.equityRatioPercent())) sb.append('%');
            if (y.employees() >= 0) sb.append(", ").append(groupedInt(y.employees())).append(" employees");
            sb.append('\n');
        }
    }

    /**
     * A fiscal-year label rendered SELF-DESCRIBING for the material: the
     * reported/estimate status as a word the model can COPY instead of a
     * one-char 'e' convention it must decode — a 4B's training prior (2024/25
     * read as future years) beats a low-salience suffix, and 4 of 6 archived
     * reports narrated reported years as "geschätzt" (live SAP 2026-07-15:
     * "2024 (Schätzung)" table on reported figures). Same lesson as the
     * human-units rendering: the material carries the truth as a literal.
     */
    private static String statusLabel(CompanyDeepDive.KeyFigureYear y) {
        return y.label() + (y.estimate() ? " (consensus estimate)" : " (reported)");
    }

    /**
     * The reported/estimate status word for a BALANCE-SHEET year, joined by
     * year digits against the key-figure flags (the balance leg itself
     * carries no estimate marker). An unmatched year stays bare — honesty
     * over a guessed status.
     */
    private static String balanceStatusLabel(CompanyDeepDive d, String label) {
        for (CompanyDeepDive.KeyFigureYear y : d.keyFigures()) {
            String year = y.estimate() && y.label().endsWith("e")
                    ? y.label().substring(0, y.label().length() - 1)
                    : y.label();
            if (year.equals(label)) {
                return label + (y.estimate() ? " (consensus estimate)" : " (reported)");
            }
        }
        return label;
    }

    /**
     * HOUSE-COMPUTED valuation context — the lines that let the author ANSWER
     * "why does the street call this target" instead of parroting the naked
     * number (user finding, live SAP run 2026-07-13): the target expressed as
     * a multiple of the consensus EPS path, and the price located against its
     * own 52-week range. Computed HERE because the author may never derive a
     * figure himself — the examiner would (rightly) flag a number the material
     * does not carry. Marked as house arithmetic on attributed inputs.
     */
    private static void appendValuationContext(StringBuilder sb, Material m,
            Map<String, Integer> nums) {
        String line = valuationContextLine(m);
        if (line.isEmpty()) return;
        sb.append("VALUATION CONTEXT (house arithmetic on the verified figures)")
                .append(mark(nums, "consors")).append(": ").append(line).append('\n');
    }

    /** The house-arithmetic sentence itself — shared with the editor's key data. */
    private static String valuationContextLine(Material m) {
        AnalystView av = m.analystView;
        StringBuilder line = new StringBuilder(200);
        if (av != null && av.hasRatings() && Double.isFinite(av.targetPrice())
                && m.deepDive != null) {
            for (CompanyDeepDive.KeyFigureYear y : m.deepDive.keyFigures()) {
                if (!y.estimate() || !Double.isFinite(y.eps()) || y.eps() <= 0) continue;
                line.append(String.format(Locale.ROOT,
                        "the consensus target equals %.1fx the %s consensus earnings per share",
                        av.targetPrice() / y.eps(), y.label()));
                break;
            }
        }
        CompanyDeepDive.PerformanceStats p = m.deepDive != null ? m.deepDive.performance() : null;
        if (snapshotComparableToEurStats(m) && p != null
                && Double.isFinite(p.high52w()) && p.high52w() > 0) {
            if (line.length() > 0) line.append("; ");
            line.append(String.format(Locale.ROOT,
                    "the price stands %.1f%% below its 52w high",
                    Math.abs((m.snapshot.price() - p.high52w()) / p.high52w() * 100.0)));
            if (Double.isFinite(p.low52w()) && p.low52w() > 0) {
                line.append(String.format(Locale.ROOT, " and %.1f%% above its 52w low",
                        Math.abs((m.snapshot.price() - p.low52w()) / p.low52w() * 100.0)));
            }
        }
        return line.toString();
    }

    /**
     * Whether the snapshot price may be held against the Consorsbank stats
     * (52-week marks, target) — those are EUR figures, so a USD/foreign-venue
     * snapshot must never enter the house arithmetic (live run 9: "37,7 %
     * unter dem Hoch" computed from a USD price against an EUR high — the
     * real distance was ~46 %). No FX conversion here: cross-currency
     * arithmetic simply does not happen.
     */
    private static boolean snapshotComparableToEurStats(Material m) {
        return m.snapshot != null && m.snapshot.hasPrice()
                && "EUR".equals(m.snapshot.currency());
    }

    /** The consensus estimate path — the Ausblick's anchor for "what the street expects". */
    private static void appendEstimatePath(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null || d.keyFigures().isEmpty()) return;
        StringBuilder path = new StringBuilder(160);
        int n = 0;
        for (CompanyDeepDive.KeyFigureYear y : d.keyFigures()) {
            if (!y.estimate()) continue;
            if (++n > 3) break;
            if (path.length() > 0) path.append("; ");
            path.append(y.label()).append(':');
            appendFig(path, " earnings per share ", y.eps());
            appendFig(path, ", P/E ", y.peRatio());
            appendFig(path, ", dividend per share ", y.dividendPerShare());
        }
        if (path.length() == 0) return;
        sb.append("CONSENSUS ESTIMATE PATH (verified, attributed street expectation)")
                .append(mark(nums, "consors")).append(": ").append(path).append('\n');
    }

    /**
     * SHARES OUTSTANDING per fiscal year — house arithmetic on legs the DD
     * already carries (equity [thousands EUR] ÷ book value per share): the
     * DILUTION series (user finding 2026-07-14 "Verwässerungsgefahr fehlt" —
     * the data was on the table all along). The YoY change rides along so the
     * author can NAME dilution instead of hinting at it.
     */
    static void appendShareCount(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null) return;
        Map<String, Double> bookValueByYear = new LinkedHashMap<>();
        for (CompanyDeepDive.KeyFigureYear y : d.keyFigures()) {
            if (Double.isFinite(y.bookValuePerShare()) && y.bookValuePerShare() > 0) {
                bookValueByYear.put(y.label(), y.bookValuePerShare());
            }
        }
        StringBuilder line = new StringBuilder(200);
        double previous = Double.NaN;
        for (CompanyDeepDive.BalanceSheetYear y : d.balanceSheet()) {
            Double bvps = bookValueByYear.get(y.label());
            if (bvps == null || !Double.isFinite(y.equityCapital()) || y.equityCapital() <= 0) {
                continue;
            }
            double shares = y.equityCapital() * 1000.0 / bvps;
            if (line.length() > 0) line.append("; ");
            line.append(y.label()).append(": ").append(groupedInt(Math.round(shares)))
                    .append(" shares");
            if (Double.isFinite(previous) && previous > 0) {
                line.append(String.format(Locale.ROOT, " (%+.1f%%)",
                        (shares - previous) / previous * 100.0));
            }
            previous = shares;
        }
        if (line.length() == 0) return;
        sb.append("SHARES OUTSTANDING by fiscal year (house arithmetic: equity / book value "
                        + "per share — the dilution series)")
                .append(mark(nums, "consors")).append(": ").append(line).append('\n');
    }

    /**
     * Readable venue name for the handful of exchange codes our snapshots
     * carry — display normalization (the raw Yahoo/L&S code means nothing in
     * report prose), not an entity fix-list.
     */
    static String venueName(String code) {
        if (code == null || code.isBlank()) return null;
        return switch (code) {
            case "NMS", "NGM", "NCM" -> "NASDAQ";
            case "NYQ" -> "NYSE";
            case "ASE" -> "NYSE American";
            case "GER" -> "XETRA";
            case "LSX", "L&S" -> "L&S Exchange";
            default -> code.length() > 3 ? code : null; // a bare cryptic code says nothing
        };
    }

    private static void appendBalance(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null || d.balanceSheet().isEmpty()) return;
        // HUMAN units, not the upstream thousands-EUR raw values: a 4B copies
        // material literals verbatim, and "turnover 30 871 000" (thousands)
        // became "Umsatz stieg von 30 871 000 EUR" in live prose — SAP's
        // revenue misstated by three orders of magnitude, unpuncturable for the
        // examiner because the figure matched the material.
        sb.append("BALANCE SHEET (verified)").append(mark(nums, "consors")).append(":\n");
        List<CompanyDeepDive.BalanceSheetYear> years = d.balanceSheet();
        int from = Math.max(0, years.size() - MAX_BALANCE_YEARS);
        for (CompanyDeepDive.BalanceSheetYear y : years.subList(from, years.size())) {
            sb.append("  ").append(balanceStatusLabel(d, y.label())).append(':');
            appendMoneyThousands(sb, " turnover ", y.turnover());
            appendMoneyThousands(sb, ", net income ", y.netIncome());
            appendMoneyThousands(sb, ", equity ", y.equityCapital());
            appendMoneyThousands(sb, ", cashflow ", y.cashflowNet());
            appendMoneyThousands(sb, ", R&D ", y.researchExpenses());
            sb.append('\n');
        }
    }

    /** A bare count with SPACE grouping — the material's number convention. */
    private static String groupedInt(long v) {
        String s = String.format(Locale.ROOT, "%,d", v);
        return s.replace(',', ' ');
    }

    /** A thousands-EUR upstream value rendered in human units (B/M EUR). */
    private static void appendMoneyThousands(StringBuilder sb, String label, double thousands) {
        if (!Double.isFinite(thousands)) return;
        double eur = thousands * 1000.0;
        if (Math.abs(eur) >= 1e9) {
            sb.append(label).append(String.format(Locale.ROOT, "%.2fB EUR", eur / 1e9));
        } else {
            sb.append(label).append(String.format(Locale.ROOT, "%.1fM EUR", eur / 1e6));
        }
    }

    private static void appendBoard(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null || d.board().isEmpty()) return;
        sb.append("BOARDS (verified)").append(mark(nums, "consors")).append(": ");
        int n = 0;
        for (CompanyDeepDive.BoardMember b : d.board()) {
            if (++n > 8) break;
            if (n > 1) sb.append("; ");
            sb.append(b.name()).append(" (").append(b.board());
            if ("CHAIRMAN".equalsIgnoreCase(b.role())) sb.append(", Vorsitz");
            sb.append(')');
        }
        sb.append('\n');
    }

    /** The analyst RATING view (distribution, trend, target) — the valuation shelf. */
    private static void appendAnalystRatings(StringBuilder sb, AnalystView av, Map<String, Integer> nums) {
        if (av == null || !av.hasRatings()) return;
        sb.append("ANALYSTS (verified)").append(mark(nums, "consors")).append(": ")
                .append(av.total()).append(" covering — ")
                .append(av.buy()).append(" buy / ").append(av.overweight()).append(" overweight / ")
                .append(av.hold()).append(" hold / ").append(av.underweight()).append(" underweight / ")
                .append(av.sell()).append(" sell");
        if (av.buy3m() >= 0) {
            sb.append(String.format(Locale.ROOT, " (3 months ago: %d/%d/%d/%d/%d)",
                    av.buy3m(), av.overweight3m(), av.hold3m(), av.underweight3m(), av.sell3m()));
        }
        if (Double.isFinite(av.targetPrice())) {
            sb.append(String.format(Locale.ROOT, "; consensus target %.2f %s",
                    av.targetPrice(), av.targetCurrency() == null ? "" : av.targetCurrency()));
            if (Double.isFinite(av.expectedUpsidePercent())) {
                sb.append(String.format(Locale.ROOT, " (%+.1f%% vs current)",
                        av.expectedUpsidePercent()));
            }
        }
        if (av.upgrades() >= 0 && av.downgrades() >= 0) {
            sb.append("; recent revisions ").append(av.upgrades()).append(" up / ")
                    .append(av.downgrades()).append(" down");
        }
        sb.append('\n');
    }

    /** The dated corporate events ahead — the Ausblick's hardest anchor. */
    private static void appendUpcomingEvents(StringBuilder sb, AnalystView av, Map<String, Integer> nums) {
        if (av == null) return;
        long nowEpoch = Instant.now().getEpochSecond();
        int n = 0;
        StringBuilder ev = new StringBuilder();
        for (var event : av.events()) {
            if (event.atEpochSeconds() < nowEpoch) continue;
            if (++n > MAX_EVENTS) break;
            if (ev.length() > 0) ev.append("; ");
            ev.append(STAMP.format(Instant.ofEpochSecond(event.atEpochSeconds())), 0, 10);
            if (event.title() != null) ev.append(' ').append(event.title());
        }
        if (ev.length() > 0) {
            sb.append("UPCOMING EVENTS (verified)").append(mark(nums, "consors"))
                    .append(": ").append(ev).append('\n');
        }
    }

    /** The street's consensus for the next report — the Ausblick's Konsensgröße anchor. */
    private static void appendEarningsConsensus(StringBuilder sb, Material m,
            Map<String, Integer> nums) {
        var est = m.earningsEstimate;
        if (est == null || (est.epsEstimate() == null && est.revenueEstimate() == null)) return;
        sb.append("STREET CONSENSUS for the next report (verified, EarningsWhispers)")
                .append(mark(nums, "whispers")).append(": ");
        if (m.earningsEstimateDateIso != null) {
            sb.append(m.earningsEstimateDateIso).append(", ");
        }
        boolean any = false;
        if (est.epsEstimate() != null) {
            sb.append(String.format(Locale.ROOT,
                    "consensus earnings per share %.2f USD", est.epsEstimate()));
            any = true;
        }
        if (est.revenueEstimate() != null) {
            if (any) sb.append(", ");
            sb.append(String.format(Locale.ROOT, "consensus revenue %.2fB USD",
                    est.revenueEstimate() / 1e9));
        }
        if (est.slot() != null) sb.append(" (").append(est.slot()).append(')');
        if (est.confirmed()) sb.append("; report date company-confirmed");
        sb.append('\n');
    }

    private static void appendInsider(StringBuilder sb, InsiderDealings id, Map<String, Integer> nums) {
        if (id == null) return;
        if (id.deals().isEmpty()) {
            sb.append("INSIDER DEALINGS (verified, BaFin)").append(mark(nums, "insider"))
                    .append(": none reported\n");
            return;
        }
        sb.append("INSIDER DEALINGS (verified, BaFin, newest first)")
                .append(mark(nums, "insider")).append(":\n");
        int n = 0;
        for (InsiderDealings.InsiderDeal deal : id.deals()) {
            if (++n > MAX_INSIDER_DEALS) break;
            sb.append("  - ");
            if (deal.dealDateIso() != null) sb.append('[').append(deal.dealDateIso()).append("] ");
            sb.append(deal.person());
            if (deal.positionStatus() != null) sb.append(" (").append(deal.positionStatus()).append(')');
            sb.append(": ").append(deal.dealType());
            if (Double.isFinite(deal.volumeEur())) {
                sb.append(String.format(Locale.ROOT, " %.0f EUR", deal.volumeEur()));
            }
            if (Double.isFinite(deal.avgPrice())) {
                sb.append(String.format(Locale.ROOT, " @ %.2f %s", deal.avgPrice(),
                        deal.currency() == null ? "" : deal.currency()));
            }
            sb.append('\n');
        }
    }

    private static void appendShorts(StringBuilder sb, ShortInterest si, Map<String, Integer> nums) {
        if (si == null) return;
        if (si.positions().isEmpty()) {
            sb.append("SHORT POSITIONS (verified, Bundesanzeiger register)")
                    .append(mark(nums, "shorts")).append(": no disclosed position >= 0.5%\n");
            return;
        }
        sb.append(String.format(Locale.ROOT,
                "SHORT POSITIONS (verified, Bundesanzeiger register, total disclosed %.2f%%)"
                        + mark(nums, "shorts") + ":\n",
                si.totalDisclosedPercent()));
        int n = 0;
        for (ShortInterest.ShortPosition p : si.positions()) {
            if (++n > MAX_SHORT_POSITIONS) break;
            sb.append(String.format(Locale.ROOT, "  - %s: %.2f%% (%s)%n",
                    p.holder(), p.percent(), p.dateIso()));
        }
    }

    /** The US listing identity (venue tier + NASDAQ's sector labels) — Worum es geht. */
    private static void appendUsListing(StringBuilder sb, UsListingStats us, Map<String, Integer> nums) {
        if (us == null || us.exchange() == null) return;
        sb.append("US LISTING (NASDAQ data)").append(mark(nums, "nasdaq"))
                .append(": ").append(us.exchange());
        if (us.sector() != null) {
            sb.append(", sector ").append(us.sector());
            if (us.industry() != null) sb.append(" / ").append(us.industry());
        }
        sb.append('\n');
    }

    /** SEC Form-4 insider trades — the US twin of the BaFin leg (Katalysatoren). */
    private static void appendUsInsiders(StringBuilder sb, UsListingStats us, Map<String, Integer> nums) {
        if (us == null || (us.insiderActivity() == null && us.insiderTrades().isEmpty())) return;
        sb.append("US INSIDER TRADES (SEC Form 4, NASDAQ, newest first)")
                .append(mark(nums, "nasdaq")).append(":\n");
        UsListingStats.InsiderActivity a = us.insiderActivity();
        if (a != null && (a.buys3m() >= 0 || a.sells3m() >= 0)) {
            sb.append("  - last 3 months: ").append(Math.max(a.buys3m(), 0)).append(" buys / ")
                    .append(Math.max(a.sells3m(), 0)).append(" sells");
            if (a.netShares3m() != Long.MIN_VALUE) {
                sb.append(" (net ").append(groupedInt(a.netShares3m())).append(" shares)");
            }
            if (a.buys12m() >= 0 || a.sells12m() >= 0) {
                sb.append("; last 12 months: ").append(Math.max(a.buys12m(), 0)).append(" buys / ")
                        .append(Math.max(a.sells12m(), 0)).append(" sells");
                if (a.netShares12m() != Long.MIN_VALUE) {
                    sb.append(" (net ").append(groupedInt(a.netShares12m())).append(" shares)");
                }
            }
            sb.append('\n');
        }
        int n = 0;
        for (UsListingStats.InsiderTrade t : us.insiderTrades()) {
            if (++n > MAX_US_INSIDER_TRADES) break;
            sb.append("  - ");
            if (t.dateIso() != null) sb.append('[').append(t.dateIso()).append("] ");
            sb.append(t.insider());
            if (t.relation() != null) sb.append(" (").append(t.relation()).append(')');
            sb.append(": ").append(t.transaction());
            if (t.sharesTraded() >= 0) {
                sb.append(' ').append(groupedInt(t.sharesTraded())).append(" shares");
            }
            if (Double.isFinite(t.priceUsd())) {
                sb.append(String.format(Locale.ROOT, " @ %.2f USD", t.priceUsd()));
            }
            sb.append('\n');
        }
    }

    /** FINRA short interest by settlement date — the US twin of the Bundesanzeiger leg. */
    private static void appendUsShortInterest(StringBuilder sb, UsListingStats us,
            Map<String, Integer> nums) {
        if (us == null || us.shortInterest().isEmpty()) return;
        sb.append("US SHORT INTEREST (FINRA settlement points, NASDAQ, newest first)")
                .append(mark(nums, "nasdaq")).append(":\n");
        int n = 0;
        for (UsListingStats.ShortInterestPoint p : us.shortInterest()) {
            if (++n > MAX_US_SHORT_POINTS) break;
            sb.append("  - [").append(p.settlementDateIso()).append("] ");
            if (p.shortInterestShares() >= 0) {
                sb.append(groupedInt(p.shortInterestShares())).append(" shares short");
            }
            if (Double.isFinite(p.daysToCover())) {
                sb.append(String.format(Locale.ROOT, ", days to cover %.1f", p.daysToCover()));
            }
            sb.append('\n');
        }
    }

    /**
     * The US street view — attributed like the Consorsbank consensus. The
     * target panel is honest about its base: NASDAQ's rating count is often
     * BROADER than the buy/hold/sell panel behind the price targets.
     */
    private static void appendUsAnalysts(StringBuilder sb, UsListingStats us, Map<String, Integer> nums) {
        UsListingStats.AnalystRatings r = us == null ? null : us.analystRatings();
        if (r == null) return;
        boolean hasLabel = r.consensusLabel() != null;
        boolean hasTarget = Double.isFinite(r.meanPriceTargetUsd());
        if (!hasLabel && !hasTarget) return;
        sb.append("US STREET VIEW (NASDAQ consensus)").append(mark(nums, "nasdaq")).append(": ");
        if (hasLabel) {
            sb.append("mean rating '").append(r.consensusLabel()).append('\'');
            if (r.analystCount() >= 0) sb.append(" (").append(r.analystCount()).append(" analysts)");
        }
        if (hasTarget) {
            if (hasLabel) sb.append("; ");
            sb.append(String.format(Locale.ROOT, "price target mean %.2f USD", r.meanPriceTargetUsd()));
            if (Double.isFinite(r.highPriceTargetUsd()) && Double.isFinite(r.lowPriceTargetUsd())) {
                sb.append(String.format(Locale.ROOT, " (high %.2f, low %.2f)",
                        r.highPriceTargetUsd(), r.lowPriceTargetUsd()));
            }
            if (r.buy() >= 0 && r.hold() >= 0 && r.sell() >= 0) {
                sb.append(String.format(Locale.ROOT,
                        " from a target panel of buy %d / hold %d / sell %d", r.buy(), r.hold(), r.sell()));
            }
        }
        sb.append('\n');
    }

    /** 13F institutional ownership — positioning context for Bewertung. */
    private static void appendUsInstitutional(StringBuilder sb, UsListingStats us,
            Map<String, Integer> nums) {
        UsListingStats.InstitutionalOwnership o = us == null ? null : us.institutionalOwnership();
        if (o == null) return;
        sb.append("US INSTITUTIONAL OWNERSHIP (13F filings, NASDAQ)")
                .append(mark(nums, "nasdaq")).append(": ");
        if (Double.isFinite(o.ownershipPercent())) {
            sb.append(String.format(Locale.ROOT, "%.2f%% of shares held institutionally", o.ownershipPercent()));
        }
        if (o.totalHolders() >= 0) sb.append(" by ").append(o.totalHolders()).append(" holders");
        if (Double.isFinite(o.totalValueMillionsUsd())) {
            sb.append(String.format(Locale.ROOT, " (value %.1fM USD)", o.totalValueMillionsUsd()));
        }
        sb.append('\n');
        int n = 0;
        for (UsListingStats.InstitutionalOwnership.Holder h : o.topHolders()) {
            if (++n > MAX_US_HOLDERS) break;
            sb.append("  - ").append(h.name());
            if (h.sharesHeld() >= 0) sb.append(": ").append(groupedInt(h.sharesHeld())).append(" shares");
            if (Double.isFinite(h.marketValueThousandsUsd())) {
                sb.append(String.format(Locale.ROOT, " (%.1fM USD)",
                        h.marketValueThousandsUsd() / 1000.0));
            }
            if (h.asOfDateIso() != null) sb.append(", as of ").append(h.asOfDateIso());
            sb.append('\n');
        }
    }

    /**
     * The dated analyst-action history (MarketBeat) — who upgraded, downgraded,
     * initiated or moved a target WHEN; the action history the consensus
     * snapshots (Consorsbank/NASDAQ) structurally do not carry.
     */
    private static void appendAnalystActions(StringBuilder sb, AnalystActions aa,
            Map<String, Integer> nums) {
        if (aa == null || aa.actions().isEmpty()) return;
        sb.append("ANALYST ACTIONS (dated history, MarketBeat, newest first)")
                .append(mark(nums, "marketbeat")).append(":\n");
        int n = 0;
        for (AnalystActions.Action a : aa.actions()) {
            if (++n > MAX_ANALYST_ACTIONS) break;
            sb.append("  - ");
            if (a.dateIso() != null) sb.append('[').append(a.dateIso()).append("] ");
            sb.append(a.brokerage()).append(": ").append(a.actionType());
            String rating = joinOldNew(a.ratingOld(), a.ratingNew());
            if (rating != null) sb.append(", rating ").append(rating);
            String target = joinTargets(a.targetOld(), a.targetNew(), a.targetCurrency());
            if (target != null) sb.append(", target ").append(target);
            sb.append('\n');
        }
    }

    /** "Old→New" for rating halves; single half alone; null when both missing. */
    private static String joinOldNew(String oldV, String newV) {
        boolean hasOld = oldV != null && !oldV.isBlank();
        boolean hasNew = newV != null && !newV.isBlank();
        if (hasOld && hasNew) return oldV.equals(newV) ? newV : oldV + "→" + newV;
        return hasNew ? newV : hasOld ? oldV : null;
    }

    /** "8.00→12.00 USD" for target halves; single value alone; null when none. */
    private static String joinTargets(double oldV, double newV, String currency) {
        boolean hasOld = Double.isFinite(oldV);
        boolean hasNew = Double.isFinite(newV);
        if (!hasOld && !hasNew) return null;
        String cur = currency == null ? "" : " " + currency;
        if (hasOld && hasNew && oldV != newV) {
            return String.format(Locale.ROOT, "%.2f→%.2f%s", oldV, newV, cur);
        }
        return String.format(Locale.ROOT, "%.2f%s", hasNew ? newV : oldV, cur);
    }

    /** MarketBeat's US short stats — the percent-of-float figure (Katalysatoren). */
    private static void appendUsShortQuote(StringBuilder sb, AnalystActions aa,
            Map<String, Integer> nums) {
        AnalystActions.UsShortStats st = aa == null ? null : aa.shortStats();
        if (st == null) return;
        sb.append("US SHORT STATS (MarketBeat)").append(mark(nums, "marketbeat")).append(": ");
        boolean any = false;
        if (Double.isFinite(st.percentOfFloat())) {
            sb.append(String.format(Locale.ROOT, "%.2f%% of float short", st.percentOfFloat()));
            any = true;
        }
        if (st.currentShares() >= 0) {
            if (any) sb.append(", ");
            sb.append(groupedInt(st.currentShares())).append(" shares short");
            if (st.priorShares() >= 0) {
                sb.append(" (prior ").append(groupedInt(st.priorShares())).append(')');
            }
            any = true;
        }
        if (Double.isFinite(st.daysToCover())) {
            if (any) sb.append(", ");
            sb.append(String.format(Locale.ROOT, "days to cover %.1f", st.daysToCover()));
            any = true;
        }
        if (st.settlementDateIso() != null) {
            if (any) sb.append(", ");
            sb.append("record date ").append(st.settlementDateIso());
            any = true;
        }
        if (any) sb.append('\n');
        else sb.setLength(sb.length() - ("US SHORT STATS (MarketBeat)"
                + mark(nums, "marketbeat") + ": ").length());
    }

    /** One US sector SPDR as the instrument's sector proxy. */
    record SectorEtf(String symbol, String name) {
    }

    /**
     * Maps the instrument's sector/industry labels (onvista German, NASDAQ
     * English) to its US sector SPDR. Keyword-based, PRIORITY-ORDERED: health
     * before chemicals ("Chemie / Pharma / Gesundheit" is a health label),
     * tech before industrials. No match = no sector block — a wrong proxy is
     * worse than none.
     */
    static SectorEtf sectorEtfFor(String... labels) {
        StringBuilder hay = new StringBuilder(128);
        for (String l : labels) {
            if (l != null) hay.append(l.toLowerCase(Locale.ROOT)).append(' ');
        }
        String h = hay.toString();
        if (h.isBlank()) return null;
        if (containsAny(h, "gesundheit", "pharma", "biotech", "medizin", "health", "drug")) {
            return new SectorEtf("XLV", "Gesundheit");
        }
        if (containsAny(h, "software", "halbleiter", "semiconductor", "technolog", "computer",
                "hardware", "it-dienst", "informations")) {
            return new SectorEtf("XLK", "Tech");
        }
        if (containsAny(h, "kommunikation", "telekom", "telecom", "medien", "media",
                "entertainment", "communication")) {
            return new SectorEtf("XLC", "Kommunikation");
        }
        if (containsAny(h, "bank", "finanz", "versicher", "financ", "insur", "asset manag")) {
            return new SectorEtf("XLF", "Finanzen");
        }
        if (containsAny(h, "immobilien", "real estate", "reit")) {
            return new SectorEtf("XLRE", "Immobilien");
        }
        if (containsAny(h, "versorger", "utilit")) {
            return new SectorEtf("XLU", "Versorger");
        }
        if (containsAny(h, "energie", "energy", "oil", "erdöl", "erdgas")) {
            return new SectorEtf("XLE", "Energie");
        }
        if (containsAny(h, "nahrung", "lebensmittel", "getränke", "staples", "beverage",
                "food", "tabak", "haushalts")) {
            return new SectorEtf("XLP", "Basiskonsum");
        }
        if (containsAny(h, "auto", "einzelhandel", "retail", "discretionary", "zykl",
                "luxus", "reise", "travel", "freizeit", "apparel")) {
            return new SectorEtf("XLY", "Zykl. Konsum");
        }
        if (containsAny(h, "chemie", "grundstoff", "rohstoff", "material", "bergbau",
                "mining", "stahl", "metall", "papier")) {
            return new SectorEtf("XLB", "Grundstoffe");
        }
        if (containsAny(h, "industrie", "maschinen", "industrial", "rüstung", "defense",
                "verteidigung", "luftfahrt", "aerospace", "transport", "logistik", "bau")) {
            return new SectorEtf("XLI", "Industrie");
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    /**
     * Which hazard classes a sector proxy is EXPOSED to — the conservative
     * gate for the world-context block, keyed on {@code GlobalHazardsClient}'s
     * real kinds (STORM / QUAKE / AVIATION). Deliberately tight: energy trades
     * on Gulf storms (platforms, refineries), insurers on storm AND quake
     * claims, industrials (airlines, logistics) on aviation disruptions and
     * storms, materials/staples (agri, supply chains) and utilities (grid) on
     * storms — everything else has no mapping and gets NOTHING.
     */
    static final Map<String, Set<String>> SECTOR_HAZARD_EXPOSURE = Map.of(
            "XLE", Set.of("STORM"),
            "XLF", Set.of("STORM", "QUAKE"),
            "XLI", Set.of("STORM", "AVIATION"),
            "XLB", Set.of("STORM"),
            "XLP", Set.of("STORM"),
            "XLU", Set.of("STORM", "QUAKE"));

    /**
     * Which fishing-net world signals a sector proxy TRADES ON — the same
     * conservative doctrine as {@link #SECTOR_HAZARD_EXPOSURE}: energy on US
     * oil stocks and tanker routes, industrials/logistics on chokepoints and
     * container freight, materials on chokepoints and power input costs,
     * staples on supply-chain routes, utilities on power and geomagnetic
     * storms (grid), tech on actively exploited vulnerabilities. No mapping,
     * NOTHING.
     */
    static final Map<String, Set<String>> SECTOR_WORLD_EXPOSURE = Map.of(
            "XLE", Set.of("OIL", "CHOKEPOINTS"),
            "XLI", Set.of("CHOKEPOINTS", "FREIGHT"),
            "XLB", Set.of("CHOKEPOINTS", "POWER"),
            "XLP", Set.of("CHOKEPOINTS"),
            "XLU", Set.of("POWER", "SPACEWX"),
            "XLK", Set.of("CYBER"));

    /** The weather docket rule: high impact anywhere, medium only for US/DE/EU. */
    private static boolean relevantMacro(int importance, String country) {
        if (importance >= 1) return true;
        return importance == 0 && ("US".equals(country) || "DE".equals(country)
                || "EU".equals(country));
    }

    /**
     * Sector proxy + today's macro actuals (Lage). Every number arrives
     * EXPLAINED, never naked (user mandate 2026-07-14, the weather pattern):
     * the sector move is paired with the instrument's move and the relative
     * gap (house arithmetic), an actual is paired with its forecast and prior
     * plus the house comparison word.
     */
    private static void appendSectorContext(StringBuilder sb, Material m, Map<String, Integer> nums) {
        boolean hasSector = m.sectorEtf != null && Double.isFinite(m.sectorEtf.dayChangePercent());
        if (!hasSector && m.macroActualsToday.isEmpty()) return;
        sb.append("SECTOR & MACRO CONTEXT (verified)").append(mark(nums, "sector")).append(":\n");
        if (hasSector) {
            sb.append(String.format(Locale.ROOT, "  - US sector proxy %s (%s): %+.2f%% today",
                    m.sectorDisplayName, m.sectorEtfSymbol, m.sectorEtf.dayChangePercent()));
            if (m.snapshot != null && Double.isFinite(m.snapshot.dayChangePercent())) {
                double gap = m.snapshot.dayChangePercent() - m.sectorEtf.dayChangePercent();
                sb.append(String.format(Locale.ROOT,
                        " — the instrument moved %+.2f%%, %.1f points %s its sector "
                                + "(house arithmetic)",
                        m.snapshot.dayChangePercent(), Math.abs(gap),
                        gap >= 0 ? "ahead of" : "behind"));
            }
            sb.append('\n');
        }
        for (TradingViewCalendarClient.TvEvent e : m.macroActualsToday) {
            sb.append("  - ").append(e.title());
            if (e.country() != null) sb.append(" (").append(e.country()).append(')');
            sb.append(String.format(Locale.ROOT, ": actual %.2f%s", e.actual(),
                    e.unit() == null ? "" : e.unit()));
            if (e.forecast() != null) {
                sb.append(String.format(Locale.ROOT, " vs forecast %.2f", e.forecast()));
            }
            if (e.previous() != null) {
                sb.append(String.format(Locale.ROOT, " (prior %.2f)", e.previous()));
            }
            if (e.forecast() != null) {
                double diff = e.actual() - e.forecast();
                sb.append(" — ").append(Math.abs(diff) < 1e-9 ? "in line with"
                        : diff > 0 ? "above" : "below").append(" forecast (house comparison)");
            }
            sb.append('\n');
        }
    }

    /**
     * The full fishing-net catch as judgeable candidate lines — strict ROOT
     * locale for the examiner, every value EXPLAINED (level + comparison, the
     * weather doctrine). The exposure maps ride along as HINTS on the lines
     * they know; nothing is pre-filtered (user mandate 2026-07-15: "alles
     * rein, die KI sortiert aus").
     */
    static List<String> worldSignalCandidateLines(Material m) {
        List<String> out = new ArrayList<>();
        Set<String> worldHints = m.sectorEtfSymbol == null ? Set.of()
                : SECTOR_WORLD_EXPOSURE.getOrDefault(m.sectorEtfSymbol, Set.of());
        Set<String> hazardHints = m.sectorEtfSymbol == null ? Set.of()
                : SECTOR_HAZARD_EXPOSURE.getOrDefault(m.sectorEtfSymbol, Set.of());
        var s = m.worldSignals;
        if (s != null) {
            if (s.oilStocks() != null) {
                var o = s.oilStocks();
                StringBuilder line = new StringBuilder(
                        "US petroleum stocks (EIA weekly report, week ending ")
                        .append(o.weekEnding()).append("):");
                appendOilLine(line, " commercial crude", o.crudeMb(), o.crudeDeltaMb());
                appendOilLine(line, "; SPR", o.sprMb(), o.sprDeltaMb());
                appendOilLine(line, "; gasoline", o.gasolineMb(), o.gasolineDeltaMb());
                appendOilLine(line, "; distillates", o.distillateMb(), o.distillateDeltaMb());
                out.add(line + hint(worldHints.contains("OIL")));
            }
            for (var c : s.chokepoints()) {
                if (c.transits() == null) continue;
                StringBuilder line = new StringBuilder("Maritime chokepoint ").append(c.name())
                        .append(": ").append(c.transits())
                        .append(" vessel transits/day (IMF PortWatch, as of ").append(c.dateIso());
                if (c.weekDeltaPercent() != null) {
                    line.append(String.format(Locale.ROOT, ", %+.1f%% vs one week earlier",
                            c.weekDeltaPercent()));
                }
                line.append(')');
                out.add(line + hint(worldHints.contains("CHOKEPOINTS")));
            }
            if (s.freight() != null && s.freight().harpex() != null) {
                var f = s.freight();
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                        "Container charter rates (Harpex, as of %s): %.0f points",
                        f.dateIso(), f.harpex()));
                if (f.harpexWeekAgo() != null) {
                    line.append(String.format(Locale.ROOT, " (prior week %.0f)",
                            f.harpexWeekAgo()));
                }
                out.add(line + hint(worldHints.contains("FREIGHT")));
            }
            if (s.power() != null && s.power().currentEurMwh() != null) {
                var p = s.power();
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                        "German day-ahead power (Fraunhofer): %.2f EUR/MWh now",
                        p.currentEurMwh()));
                if (p.minEurMwh() != null && p.maxEurMwh() != null) {
                    line.append(String.format(Locale.ROOT, ", day range %.2f to %.2f",
                            p.minEurMwh(), p.maxEurMwh()));
                }
                if (p.avgEurMwh() != null) {
                    line.append(String.format(Locale.ROOT, ", day average %.2f", p.avgEurMwh()));
                }
                out.add(line + hint(worldHints.contains("POWER")));
            }
            var wx = s.spaceWeather();
            if (wx != null && (level(wx.r()) > 0 || level(wx.s()) > 0 || level(wx.g()) > 0
                    || level(wx.forecastMaxG()) > 0)) {
                // A quiet all-zero space-weather day carries no signal at all.
                out.add("Space weather (NOAA): scales R" + level(wx.r()) + "/S" + level(wx.s())
                        + "/G" + level(wx.g()) + " today, 3-day maximum G"
                        + level(wx.forecastMaxG())
                        + " (geomagnetic storms stress power grids and satellites)"
                        + hint(worldHints.contains("SPACEWX")));
            }
            for (var k : s.cyber()) {
                out.add("Actively exploited vulnerability (CISA KEV"
                        + (k.dateAdded() == null ? "" : ", listed " + k.dateAdded()) + "): "
                        + k.cve() + " " + k.vendorProduct()
                        + hint(worldHints.contains("CYBER")));
            }
            for (var p : s.policy()) {
                out.add("Policy wire [" + p.source() + "]"
                        + (p.time() == null ? "" : " " + p.time()) + ": " + p.title());
            }
            for (var p : s.polls()) {
                out.add("German election poll " + p.parliament() + " (" + p.institute()
                        + ", " + p.dateIso() + "): " + p.topline());
            }
            for (var c : s.civic()) {
                out.add("Civic wire [" + c.channel() + "]"
                        + (c.time() == null ? "" : " " + c.time()) + ": "
                        + (c.office() == null || c.office().isBlank() ? "" : c.office() + ": ")
                        + c.title());
            }
            if (s.health() != null) {
                var h = s.health();
                StringBuilder line = new StringBuilder("German public health:");
                boolean any = false;
                if (h.icuOccupancyPercent() != null) {
                    line.append(String.format(Locale.ROOT, " ICU occupancy %.1f%% (DIVI)",
                            h.icuOccupancyPercent()));
                    any = true;
                }
                if (h.areIncidence() != null) {
                    line.append(any ? ";" : "").append(String.format(Locale.ROOT,
                            " ARE consultation incidence %.0f (RKI%s)", h.areIncidence(),
                            h.areWeek() == null ? "" : ", " + h.areWeek()));
                    any = true;
                }
                if (any) out.add(line.toString());
                for (String outbreak : h.outbreaks()) {
                    out.add("WHO disease outbreak notice: " + outbreak);
                }
            }
            for (var c : s.conflicts()) {
                out.add("Armed conflict/attack (Wikipedia Current Events, attributed): "
                        + c.text()
                        + (c.country() == null ? "" : " [region: " + c.country() + "]"));
            }
            for (String fixture : s.sportsTomorrow()) {
                out.add("German football tomorrow: " + fixture);
            }
            if (s.holidays() != null) {
                var h = s.holidays();
                if (h.tomorrowIsHoliday()) {
                    out.add("TOMORROW is a nationwide German public holiday: "
                            + h.nextHolidayName() + " (" + h.nextHolidayDateIso() + ")");
                } else if (h.nextHolidayName() != null) {
                    out.add("Next German public holiday: " + h.nextHolidayName()
                            + " on " + h.nextHolidayDateIso());
                }
                if (!h.schoolHolidayStates().isEmpty()) {
                    out.add("German school holidays currently in "
                            + h.schoolHolidayStates().size() + " states ("
                            + String.join(", ", h.schoolHolidayStates()) + ")");
                }
            }
        }
        for (var h : m.allHazards) {
            String text = h.text() == null ? "" : h.text().strip();
            if (text.length() > 160) text = text.substring(0, 160) + "…";
            out.add("World hazard [" + h.kind() + ", " + h.severity() + "]: " + text
                    + hint(h.kind() != null && hazardHints.contains(h.kind())));
        }
        return out;
    }

    /** The exposure maps as judge context — information, never a filter. */
    private static String hint(boolean exposed) {
        return exposed
                ? " [hint: the instrument's sector proxy is known to trade on this signal class]"
                : "";
    }

    private static int level(Integer scale) {
        return scale == null ? 0 : Math.max(scale, 0);
    }

    /** World-signal judge batch size — the full catch runs to ~60 lines. */
    private static final int WORLD_JUDGE_BATCH = 10;

    /**
     * The subject-scoped relevance judge over the fishing-net's FULL catch —
     * run at the END of the triage phase, when the report's THEME LANDSCAPE
     * (the accepted press titles) is on the table: only from there is the
     * wild-but-real connection visible (a satellite story in the pool
     * licenses space weather for a software name). FAIL-CLOSED per unjudged
     * signal: the catch is broad by mandate, the shelf stays guarded.
     */
    private void judgeWorldSignals(String subject, Material m, String lang) {
        List<String> candidates = worldSignalCandidateLines(m);
        if (candidates.isEmpty()) return;
        ChatModel judge = brain.getAgentModel();
        if (judge == null) return;
        String sys = PromptLoader.loadLocalized("deepdive-worldsignals", lang);
        StringBuilder themes = new StringBuilder();
        int themeCount = 0;
        for (RawNewsItem n : m.news) {
            if ("".equals(m.newsTargets.getOrDefault(newsKey(n), ""))) continue;
            if (n.title() == null || n.title().isBlank()) continue;
            themes.append("- ").append(n.title().strip()).append('\n');
            if (++themeCount >= 30) break;
        }
        String context = "SUBJECT: " + subject
                + (m.ticker == null ? "" : " (" + m.ticker + ")")
                + (m.sectorDisplayName == null ? "" : "\nSECTOR: " + m.sectorDisplayName)
                + (themes.length() == 0 ? ""
                        : "\n\nTHEMES (this report's accepted press titles):\n" + themes);
        // Independent batches, run two-wide (the triage pattern); each batch's
        // survivors land in their own slot so the shelf order stays stable.
        int batchCount = (candidates.size() + WORLD_JUDGE_BATCH - 1) / WORLD_JUDGE_BATCH;
        List<List<String>> keepBySlot = new ArrayList<>();
        for (int i = 0; i < batchCount; i++) {
            keepBySlot.add(java.util.Collections.synchronizedList(new ArrayList<>()));
        }
        List<Runnable> tasks = new ArrayList<>();
        for (int from = 0; from < candidates.size(); from += WORLD_JUDGE_BATCH) {
            final int slot = from / WORLD_JUDGE_BATCH;
            List<String> batch = candidates.subList(from,
                    Math.min(candidates.size(), from + WORLD_JUDGE_BATCH));
            tasks.add(() -> judgeWorldBatch(batch, sys, context, judge, keepBySlot.get(slot)));
        }
        runTwoWide(tasks);
        List<String> keep = new ArrayList<>();
        for (List<String> slot : keepBySlot) keep.addAll(slot);
        m.worldSignalKeep = keep;
        LOG.info("[DEEPDIVE] '{}' world signals: {} candidate(s), {} judged relevant.",
                subject, candidates.size(), keep.size());
    }

    private void judgeWorldBatch(List<String> batch, String sys, String context,
            ChatModel judge, List<String> keep) {
        {
            checkCancelled();
            StringBuilder list = new StringBuilder(768);
            for (int i = 0; i < batch.size(); i++) {
                list.append(i + 1).append(". ").append(batch.get(i)).append('\n');
            }
            // A zero-parse reply is a whiffed judge call, not a verdict — one
            // retry before the batch falls closed (live smoke 2026-07-15: two
            // of six batches whiffed, 20 signals lost — the triage pattern).
            for (int attempt = 1; attempt <= 2; attempt++) {
                int parsed = 0;
                try {
                    String reply = chatGateway.chat(judge, sys,
                            context + "\nSIGNALS:\n" + list);
                    Matcher obj = TRIAGE_OBJ.matcher(reply == null ? "" : reply);
                    while (obj.find()) {
                        String o = obj.group();
                        Matcher iM = TRIAGE_I.matcher(o);
                        if (!iM.find()) continue;
                        int i = Integer.parseInt(iM.group(1));
                        if (i < 1 || i > batch.size()) continue;
                        Matcher relM = TRIAGE_REL.matcher(o);
                        if (relM.find() && Boolean.parseBoolean(relM.group(1))) {
                            keep.add(stripHint(batch.get(i - 1)));
                        }
                        parsed++;
                    }
                } catch (Exception e) {
                    LOG.warn("[DEEPDIVE] world-signal judge batch failed: {}{}",
                            e.getMessage(), attempt == 1 ? " - retrying" : " - fail-closed.");
                }
                if (parsed > 0) break;
                if (attempt == 2) {
                    LOG.warn("[DEEPDIVE] world-signal judge batch yielded no verdicts "
                            + "twice - its {} signal(s) stay off the shelf (fail-closed).",
                            batch.size());
                }
            }
        }
    }

    /** The judge hint never reaches the shelf - it exists for the judge alone. */
    private static String stripHint(String line) {
        int idx = line.indexOf(" [hint:");
        return idx < 0 ? line : line.substring(0, idx);
    }

    /**
     * The judged world signals (Lage) - verified world data the subject-scoped
     * judge let through; any tie to the instrument is the desk's attributed
     * reading, never a fact.
     */
    private static void appendWorldSignals(StringBuilder sb, Material m,
            Map<String, Integer> nums) {
        if (m.worldSignalKeep.isEmpty()) return;
        sb.append("WORLD SIGNALS (verified world data, judged relevant to THIS subject; "
                        + "any tie to the instrument is the desk's attributed reading)")
                .append(mark(nums, "world")).append(":\n");
        for (String line : m.worldSignalKeep) {
            sb.append("  - ").append(line).append('\n');
        }
    }

    private static void appendOilLine(StringBuilder sb, String label, Double level,
            Double delta) {
        if (level == null) return;
        sb.append(label).append(String.format(Locale.ROOT, " %.1f million barrels", level));
        if (delta != null) {
            sb.append(String.format(Locale.ROOT, " (%+.1f week-over-week)", delta));
        }
    }

    /** Volume-profile input horizon (hourly bars) — the position-level class. */
    static final int VOLUME_PROFILE_RANGE_DAYS = 180;
    /** Newest register events carried verbatim into the memory block. */
    private static final int MEMORY_EVENTS_TAIL = 8;

    /** The current F&G band (yesterday's settled reading), or null. */
    private String currentRegimeBand() {
        de.bsommerfeld.wsbg.terminal.db.FearGreedHistoryArchive history = fearGreedHistory;
        if (history == null) return null;
        try {
            return history.latestDate()
                    .flatMap(history::byDate)
                    .map(d -> MarketMemoryService.bandOf(d.score()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The finished base-rate lines for every class the instrument's own events
     * touch: house statistics (regime-conditioned when THAT cell carries
     * {@link BaseRates#MIN_N_FOR_CONDITIONED} events, else unconditioned with
     * the honest fallback note) plus the attributed literature prior. All
     * license gates are code — the model reads finished sentences.
     */
    static List<String> buildBaseRateLines(
            List<de.bsommerfeld.wsbg.terminal.db.MarketEventRecord> instrumentEvents,
            de.bsommerfeld.wsbg.terminal.db.MarketEventArchive archive, String currentBand) {
        List<String> out = new ArrayList<>();
        Set<String> classes = new java.util.LinkedHashSet<>();
        for (var e : instrumentEvents) classes.add(e.eventClass());
        for (String eventClass : classes) {
            List<de.bsommerfeld.wsbg.terminal.db.MarketEventRecord> classEvents =
                    archive.byClass(eventClass);
            java.util.Optional<BaseRates.Stats> conditioned = currentBand == null
                    ? java.util.Optional.empty()
                    : BaseRates.forClass(eventClass, classEvents, currentBand)
                            .filter(s -> s.n() >= BaseRates.MIN_N_FOR_CONDITIONED);
            if (conditioned.isPresent()) {
                out.add(eventClass + " (house register, conditioned on the current regime): "
                        + BaseRates.describe(conditioned.get()));
            } else {
                BaseRates.forClass(eventClass, classEvents, null).ifPresent(s -> out.add(
                        eventClass + " (house register, unconditioned"
                                + (currentBand == null ? "" : " - too few cases in the current regime cell")
                                + "): " + BaseRates.describe(s)));
            }
            LiteraturePriors.priorFor(eventClass)
                    .ifPresent(p -> out.add(eventClass + " (attributed prior): " + p));
        }
        if (!out.isEmpty()) out.add(LiteraturePriors.CROSS_CUTTING);
        return out;
    }

    /**
     * Structure block (Lage): volume profile + order-book window. Every level
     * arrives WITH its justification (traded volume / resting orders) and the
     * depth-honesty note — the base for the "historically X, but the current
     * structure says Y" weighing the user mandated.
     */
    private static void appendStructure(StringBuilder sb, Material m, Map<String, Integer> nums) {
        if (m.volumeProfile == null && m.orderBook == null) return;
        String mark = mark(nums, "structure");
        String unit = m.snapshot != null && m.snapshot.currency() != null
                ? " " + m.snapshot.currency() : "";
        if (m.volumeProfile != null) {
            VolumeProfile.Profile p = m.volumeProfile;
            sb.append("STRUCTURE - VOLUME PROFILE (house-computed from hourly bars, ~")
                    .append(VOLUME_PROFILE_RANGE_DAYS / 30).append(" months)").append(mark).append(":\n");
            sb.append(String.format(Locale.ROOT,
                    "  - point of control (volume-heaviest price): %.2f%s (%s units traded there)\n",
                    p.poc(), unit, grouped(p.pocUnits())));
            sb.append(String.format(Locale.ROOT,
                    "  - value area (70 %% of traded volume): %.2f to %.2f%s\n",
                    p.val(), p.vah(), unit));
            sb.append(String.format(Locale.ROOT,
                    "  - profile range %.2f to %.2f%s, total volume %s units\n",
                    p.low(), p.high(), unit, grouped(p.totalUnits())));
            sb.append("  - reading: high-volume zones are acceptance (price sticks), low-volume "
                    + "zones slip through; a level is justified ONLY by the volume behind it\n");
        }
        if (m.orderBook != null
                && (!m.orderBook.bids().isEmpty() || !m.orderBook.asks().isEmpty())) {
            sb.append("ORDER BOOK (Boerse Frankfurt floor book");
            if (m.orderBook.time() != null && !m.orderBook.time().isBlank()) {
                sb.append(", snapshot ").append(m.orderBook.time());
            }
            sb.append(')').append(mark).append(":\n");
            appendBookSide(sb, "bid (buy) side", m.orderBook.bids(), unit);
            appendBookSide(sb, "ask (sell) side", m.orderBook.asks(), unit);
            sb.append("  - honesty: a keyless WINDOW of the Frankfurt floor book (10 levels), "
                    + "never the consolidated market depth\n");
        }
    }

    private static void appendBookSide(StringBuilder sb,
            String label, List<de.bsommerfeld.wsbg.terminal.core.price.OrderBookSnapshot.Level> levels,
            String unit) {
        if (levels.isEmpty()) return;
        int orders = 0;
        long units = 0;
        de.bsommerfeld.wsbg.terminal.core.price.OrderBookSnapshot.Level strongest = levels.get(0);
        for (var level : levels) {
            orders += level.orders();
            units += level.units();
            if (level.units() > strongest.units()) strongest = level;
        }
        sb.append(String.format(Locale.ROOT,
                "  - %s: %d resting orders / %s units across %.2f-%.2f%s; strongest level %.2f "
                        + "(%d orders, %s units)\n",
                label, orders, grouped(units),
                Math.min(levels.get(0).price(), levels.get(levels.size() - 1).price()),
                Math.max(levels.get(0).price(), levels.get(levels.size() - 1).price()), unit,
                strongest.price(), strongest.orders(), grouped(strongest.units())));
    }

    /**
     * Market-memory block (Katalysatoren): the instrument's own dated events
     * with measured reactions, then the class base rates + priors. The
     * discipline line pins the mandated weighing: base rates are priors,
     * never predictions — the CURRENT material decides.
     */
    private static void appendMarketMemory(StringBuilder sb, Material m, Map<String, Integer> nums) {
        if (m.memoryEvents.isEmpty() && m.baseRateLines.isEmpty()) return;
        sb.append("MARKET MEMORY (house event register)").append(mark(nums, "memory")).append(":\n");
        int from = Math.max(0, m.memoryEvents.size() - MEMORY_EVENTS_TAIL);
        if (from > 0) {
            sb.append("  - ").append(from).append(" older event(s) elided; newest below\n");
        }
        for (int i = from; i < m.memoryEvents.size(); i++) {
            var e = m.memoryEvents.get(i);
            sb.append("  - [").append(e.date()).append("] ").append(e.eventClass())
                    .append(" (").append(e.source()).append(')');
            if (e.carEvent() != null) {
                sb.append(String.format(Locale.ROOT,
                        ": reaction CAR(-1,+1) %+.1f %%, CAR(0,+5) %+.1f %% vs %s",
                        e.carEvent(), e.carShort(), e.benchmark()));
                if (e.regimeBand() != null) sb.append(", regime then ").append(e.regimeBand());
                if (Boolean.TRUE.equals(e.confounded())) {
                    sb.append(" [confounded - another event within 2 days]");
                }
            } else {
                sb.append(": reaction not yet measured");
            }
            sb.append('\n');
        }
        for (String line : m.baseRateLines) {
            sb.append("  - ").append(line).append('\n');
        }
        sb.append("  - discipline: base rates are PRIORS, never predictions - weigh them against "
                + "the current structure and material, and say when they disagree\n");
    }

    /**
     * Quant-signal block: the statistics kernels' finished reading lines
     * (number + definition + reading instruction), FILTERED to the section the
     * shelf belongs to — every section author sees only the signals that
     * answer his question. The discipline line pins the weighing: a signal is
     * a clue, never a verdict.
     */
    private static void appendSignals(StringBuilder sb, Material m,
            Map<String, Integer> nums, Set<String> ids) {
        if (m.signalReadings.isEmpty()) return;
        List<de.bsommerfeld.wsbg.terminal.signals.SignalReading> picked = new ArrayList<>();
        for (var r : m.signalReadings) {
            if (ids.contains(r.id())) picked.add(r);
        }
        if (picked.isEmpty()) return;
        sb.append("QUANT SIGNALS (house-computed in code, never guessed)")
                .append(mark(nums, "signals")).append(":\n");
        for (var r : picked) {
            sb.append("  - ").append(r.toContextLine()).append('\n');
        }
        sb.append("  - discipline: a signal is a clue, never a verdict - cite the number "
                + "when you lean on it, and contradict it explicitly when the material "
                + "says otherwise\n");
    }

    /** Which signal ids ride which section shelf. */
    private static final Set<String> SIGNALS_SITUATION = Set.of(
            "novelty-score", "burstiness", "story-half-life", "co-mention-diffusion",
            "silent-move-detector", "market-regime-hmm");
    private static final Set<String> SIGNALS_OUTLOOK = Set.of("expectation-vacuum");
    private static final Set<String> SIGNALS_ROOM = Set.of("hawkes-endogeneity");

    /** ROOT-locale integer with SPACE grouping (the house material convention). */
    private static String grouped(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', ' ');
    }

    /** Oldest entries kept verbatim at the timeline's start. */
    private static final int PRESS_TIMELINE_HEAD = 4;
    /** Newest entries kept verbatim at the timeline's end. */
    private static final int PRESS_TIMELINE_TAIL = 6;

    /**
     * The dated press timeline (Lage) — "how the name got here". SAMPLED so
     * months of coverage stay model-sized: the oldest {@value
     * #PRESS_TIMELINE_HEAD} entries, then at most one entry per month in
     * between (the arc), then the newest {@value #PRESS_TIMELINE_TAIL}; what
     * the sampling skips is said honestly. Context for the author — these
     * headlines are never triaged, digested or woven as sources.
     */
    static void appendPressTimeline(StringBuilder sb,
            de.bsommerfeld.wsbg.terminal.core.price.PressTimeline t, Map<String, Integer> nums) {
        if (t == null || t.entries().isEmpty()) return;
        List<de.bsommerfeld.wsbg.terminal.core.price.PressTimeline.Entry> chrono =
                new ArrayList<>(t.entries());
        java.util.Collections.reverse(chrono); // delivered newest-first → chronological
        int n = chrono.size();
        int head = Math.min(PRESS_TIMELINE_HEAD, n);
        int tailFrom = Math.max(head, n - PRESS_TIMELINE_TAIL);
        sb.append("PRESS TIMELINE (dated coverage, MarketBeat — how the name got here)")
                .append(mark(nums, "marketbeat")).append(":\n");
        for (int i = 0; i < head; i++) appendTimelineLine(sb, chrono.get(i));
        int skipped = 0;
        Set<String> monthsSeen = new HashSet<>();
        for (int i = head; i < tailFrom; i++) {
            var e = chrono.get(i);
            String month = e.dateIso() != null && e.dateIso().length() >= 7
                    ? e.dateIso().substring(0, 7) : "";
            if (monthsSeen.add(month)) appendTimelineLine(sb, e);
            else skipped++;
        }
        if (skipped > 0) {
            sb.append("  - (").append(skipped)
                    .append(" further headline(s) elided — one per month kept)\n");
        }
        for (int i = tailFrom; i < n; i++) appendTimelineLine(sb, chrono.get(i));
    }

    private static void appendTimelineLine(StringBuilder sb,
            de.bsommerfeld.wsbg.terminal.core.price.PressTimeline.Entry e) {
        sb.append("  - [").append(e.dateIso()).append("] ").append(e.title());
        if (e.publisher() != null && !e.publisher().isBlank()) {
            sb.append(" (").append(e.publisher()).append(')');
        }
        sb.append('\n');
    }

    /** The dated macro docket the sector trades on — Ausblick anchors. */
    private static void appendMacroDocket(StringBuilder sb, Material m, Map<String, Integer> nums) {
        if (m.macroDocket.isEmpty() && m.cbDecisions.isEmpty()) return;
        sb.append("UPCOMING MACRO DOCKET (verified — dated events the sector trades on)")
                .append(mark(nums, "sector")).append(":\n");
        for (CentralBankCalendarClient.CbMeeting cb : m.cbDecisions) {
            sb.append("  - [").append(cb.date()).append("] ").append(cb.bank())
                    .append(": ").append(cb.title()).append('\n');
        }
        for (EconCalendarClient.EconEvent e : m.macroDocket) {
            java.time.LocalDate day = java.time.LocalDate.ofInstant(
                    Instant.ofEpochSecond(e.whenEpochSeconds()), java.time.ZoneId.systemDefault());
            sb.append("  - [").append(day).append("] ").append(e.title());
            if (e.country() != null) sb.append(" (").append(e.country()).append(')');
            sb.append(", high impact");
            if (e.forecast() != null && !e.forecast().isBlank()) {
                sb.append(", forecast ").append(e.forecast());
            }
            sb.append('\n');
        }
    }

    /**
     * "Was war" from the house's own archive: every wire line ever published
     * about this ticker, dated — the first lines and the newest lines carry
     * the story arc, the elided middle is said honestly.
     */
    private static void appendWireHistory(StringBuilder sb, Material m, Map<String, Integer> nums) {
        if (m.wireHistory.isEmpty()) return;
        sb.append("WIRE ARCHIVE (the house's own published lines about this subject, dated)")
                .append(mark(nums, "room")).append(":\n");
        List<HeadlineRecord> all = m.wireHistory;
        int head = Math.min(WIRE_HISTORY_HEAD, all.size());
        int tailFrom = Math.max(head, all.size() - WIRE_HISTORY_TAIL);
        for (int i = 0; i < head; i++) appendWireLine(sb, all.get(i));
        if (tailFrom > head) {
            sb.append("  - (").append(tailFrom - head).append(" further line(s) elided)\n");
        }
        for (int i = tailFrom; i < all.size(); i++) appendWireLine(sb, all.get(i));
    }

    private static void appendWireLine(StringBuilder sb, HeadlineRecord r) {
        sb.append("  - [").append(java.time.LocalDate.ofInstant(
                Instant.ofEpochSecond(r.createdAt()), java.time.ZoneId.systemDefault()))
                .append("] ").append(r.headline()).append('\n');
    }

    /**
     * The hedge-fund positioning curve (Insider Monkey, 13F cadence) — how many
     * funds hold the name per quarter, with the quarter-end price beside it.
     * Positioning context for Bewertung; the last ~6 quarters carry the trend.
     */
    private static void appendHedgeFunds(StringBuilder sb, HedgeFundPopularity hf,
            Map<String, Integer> nums) {
        if (hf == null || hf.quarters().isEmpty()) return;
        sb.append("HEDGE-FUND POSITIONING (13F filings, Insider Monkey, quarterly)")
                .append(mark(nums, "hedgefunds")).append(":\n");
        List<HedgeFundPopularity.QuarterPoint> qs = hf.quarters();
        int from = Math.max(0, qs.size() - 6);
        for (int i = from; i < qs.size(); i++) {
            HedgeFundPopularity.QuarterPoint q = qs.get(i);
            sb.append("  - ").append(q.quarterLabel()).append(": ").append(q.funds())
                    .append(" funds");
            if (q.newPositions() > 0 || q.closedPositions() > 0) {
                sb.append(" (").append(q.newPositions()).append(" new / ")
                        .append(q.closedPositions()).append(" closed)");
            }
            if (Double.isFinite(q.quarterEndPriceUsd())) {
                sb.append(String.format(Locale.ROOT, ", quarter-end price %.2f USD",
                        q.quarterEndPriceUsd()));
            }
            if (q.ongoing()) sb.append(" [quarter still filing]");
            sb.append('\n');
        }
    }

    /** The quarterly beat/miss track record vs consensus — Fundamentale Entwicklung. */
    private static void appendUsSurprises(StringBuilder sb, UsListingStats us, Map<String, Integer> nums) {
        if (us == null || us.earningsSurprises().isEmpty()) return;
        sb.append("US EARNINGS SURPRISES (earnings per share, actual vs consensus, NASDAQ, newest first)")
                .append(mark(nums, "nasdaq")).append(":\n");
        int n = 0;
        for (UsListingStats.EarningsSurprise s : us.earningsSurprises()) {
            if (++n > MAX_US_SURPRISES) break;
            sb.append("  - ").append(s.fiscalQuarter());
            if (s.reportedDateIso() != null) sb.append(" [").append(s.reportedDateIso()).append(']');
            sb.append(':');
            if (Double.isFinite(s.epsActual())) {
                sb.append(String.format(Locale.ROOT, " actual %.2f USD", s.epsActual()));
            }
            if (Double.isFinite(s.epsConsensus())) {
                sb.append(String.format(Locale.ROOT, " vs consensus %.2f USD", s.epsConsensus()));
            }
            if (Double.isFinite(s.surprisePercent())) {
                sb.append(String.format(Locale.ROOT, " (surprise %+.1f%%)", s.surprisePercent()));
            }
            sb.append('\n');
        }
    }

    private static void appendTechnical(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        CompanyDeepDive.TechnicalView t = d != null ? d.technicalView() : null;
        if (t == null) return;
        sb.append("CHART-TECHNICAL READ (TradingCentral, attributed third-party view");
        if (t.asOfIso() != null) sb.append(", as of ").append(t.asOfIso());
        sb.append(')').append(mark(nums, "tc")).append(": ");
        // Level plausibility (supports ascending below resistances): broken
        // ordering is DATA damage and stays out of the material entirely; the
        // opinions still ride. A pivot EQUAL to one of the levels is
        // TradingCentral's own convention (SAP/Rheinmetall both answer
        // pivot == S2) and passes.
        if (DeepDiveCharts.plausibleLevels(t)) {
            appendFig(sb, "pivot ", t.pivot());
            appendFig(sb, "; supports ", t.support1());
            appendFig(sb, " / ", t.support2());
            appendFig(sb, " / ", t.support3());
            appendFig(sb, "; resistances ", t.resistance1());
            appendFig(sb, " / ", t.resistance2());
            appendFig(sb, " / ", t.resistance3());
        }
        if (t.shortTermOpinion() != null) sb.append("; short-term opinion ").append(t.shortTermOpinion());
        if (t.mediumTermOpinion() != null) sb.append("; medium-term opinion ").append(t.mediumTermOpinion());
        sb.append('\n');
        if (t.commentText() != null) {
            String comment = t.commentText();
            int cap = maxTechnicalCommentChars();
            if (comment.length() > cap) {
                // Cut at the last full sentence inside the cap — a torn sentence
                // strands its figures outside the material.
                String head = comment.substring(0, cap);
                int lastStop = Math.max(head.lastIndexOf(". "),
                        Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
                comment = (lastStop > cap / 2
                        ? head.substring(0, lastStop + 1) : head.stripTrailing()) + " …";
            }
            sb.append("  Its comment: ").append(comment).append('\n');
        }
    }

    private static void appendPeers(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        if (d == null || d.peers().isEmpty()) return;
        sb.append("PEERS (verified)").append(mark(nums, "consors")).append(": ");
        int n = 0;
        for (CompanyDeepDive.Peer p : d.peers()) {
            if (++n > 5) break;
            if (n > 1) sb.append("; ");
            sb.append(p.name());
            if (Double.isFinite(p.marketCapEur())) {
                sb.append(String.format(Locale.ROOT, " (mcap %.1fB EUR", p.marketCapEur() / 1e9));
                if (Double.isFinite(p.peRatio())) {
                    sb.append(String.format(Locale.ROOT, ", P/E %.1f", p.peRatio()));
                }
                sb.append(')');
            }
        }
        sb.append('\n');
    }

    private static void appendPerformance(StringBuilder sb, CompanyDeepDive d, Map<String, Integer> nums) {
        CompanyDeepDive.PerformanceStats p = d != null ? d.performance() : null;
        if (p == null) return;
        sb.append("PERFORMANCE (verified)").append(mark(nums, "consors")).append(':');
        appendPct(sb, " 1w ", p.perf1w());
        appendPct(sb, ", 4w ", p.perf4w());
        appendPct(sb, ", 3m ", p.perf3m());
        appendPct(sb, ", 6m ", p.perf6m());
        appendPct(sb, ", 52w ", p.perf52w());
        appendFig(sb, "; volatility 30d ", p.vola30d());
        appendFig(sb, ", 250d ", p.vola250d());
        if (Double.isFinite(p.high52w())) {
            sb.append(String.format(Locale.ROOT, "; 52w high %.2f (%s), low %.2f (%s)",
                    p.high52w(), p.high52wDateIso(), p.low52w(), p.low52wDateIso()));
        }
        sb.append('\n');
    }

    private static void appendTrading(StringBuilder sb,
            de.bsommerfeld.wsbg.terminal.core.price.VenueStats vs,
            de.bsommerfeld.wsbg.terminal.core.price.InstrumentFacts facts,
            Map<String, Integer> nums) {
        if (vs == null) return;
        sb.append("TRADING (Tradegate, verified)").append(mark(nums, "venue")).append(": ");
        if (vs.volumeShares() >= 0) {
            sb.append(vs.volumeShares()).append(" shares");
            if (facts != null && Double.isFinite(facts.avgVolume30d())) {
                sb.append(String.format(Locale.ROOT, " (30d average %.0f)", facts.avgVolume30d()));
            }
        }
        if (vs.turnoverEur() >= 0) {
            sb.append(String.format(Locale.ROOT, " / %.1fM EUR turnover", vs.turnoverEur() / 1_000_000.0));
        }
        if (vs.executions() >= 0) sb.append(", ").append(vs.executions()).append(" executions");
        if (vs.hasQuote()) {
            sb.append(String.format(Locale.ROOT, ", bid/ask %.2f/%.2f", vs.bid(), vs.ask()));
            if (Double.isFinite(vs.spreadPercent())) {
                sb.append(String.format(Locale.ROOT, " (spread %.2f%%)", vs.spreadPercent()));
            }
        }
        sb.append('\n');
    }

    /**
     * The room's material as ONE text ({@link #roomBlock},
     * newest evidence kept): price anchor first, evidence chronological, the
     * already-published wire lines last. Returns {@code null} when the room has
     * genuinely nothing — the section then gets its honest literal WITHOUT any
     * model call, so an empty room can never be hallucinated into a discussion
     * (live-observed SAP 2026-07-13: evidenceCount 0, yet the report claimed a
     * dated debate).
     */
    /**
     * The OUTSIDE ROOMS as ONE capped block (2026-07-16): forum/social
     * chatter from the sentiment fan — every line labeled with its venue
     * (the item's publisher: "Ariva-Forum (name)", "4chan /biz/",
     * "Bluesky (@handle)", …), newest first within the window-scaled budget.
     * Aggregate mood material for the ROOM section, deliberately NOT weave
     * material: the section reads the mood picture, never retells posts.
     * Null when the fan delivered nothing (no empty scaffolding).
     *
     * <p><b>Fair share across venues (2026-07-16):</b> the lines ride
     * venue-INTERLEAVED (each venue's freshest first, round-robin) instead
     * of purely newest-first — a loud venue (4chan's minute-cadence /smg/)
     * must not crowd the quiet ones (Ariva, Lemmy) out of the budget; the
     * mood picture wants as many ROOMS as possible, not one room's stream.
     */
    static String sentimentBlock(Material m, Map<String, Integer> nums) {
        if (m.socialSentiment.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(sentimentPacketChars() + 256);
        sb.append("OUTSIDE ROOMS (forum/social chatter, unverified opinion - an "
                + "aggregate mood signal like the cage, venue labeled per line)")
                .append(mark(nums, "sentiment")).append(":\n");
        int budget = sentimentPacketChars();
        int dropped = 0;
        for (RawNewsItem item : venueInterleaved(m.socialSentiment)) {
            String text = item.summary() == null || item.summary().isBlank()
                    ? item.title() : item.summary();
            if (text == null || text.isBlank()) continue;
            text = text.replaceAll("\\s+", " ").strip();
            if (text.length() > 240) text = text.substring(0, 240).stripTrailing() + "…";
            StringBuilder line = new StringBuilder(280);
            line.append("  - ");
            if (item.publishedAt() != null) {
                line.append('[').append(STAMP.format(item.publishedAt()), 0, 10).append("] ");
            }
            line.append('[')
                    .append(item.publisher() == null || item.publisher().isBlank()
                            ? "Social" : item.publisher())
                    .append("] ").append(text).append('\n');
            if (budget - line.length() < 0) {
                dropped++;
                continue;
            }
            budget -= line.length();
            sb.append(line);
        }
        if (dropped > 0) sb.append("  (").append(dropped).append(" more voices omitted)\n");
        return sb.toString();
    }

    /**
     * Round-robin across venues, each venue's items newest first: pick every
     * venue's freshest voice, then every venue's second-freshest, and so on.
     * The venue is the publisher's stem — "Ariva-Forum (St2023)" and
     * "Ariva-Forum (azulon)" are ONE room, the author suffix is cut.
     */
    static List<RawNewsItem> venueInterleaved(List<RawNewsItem> items) {
        Map<String, List<RawNewsItem>> byVenue = new LinkedHashMap<>();
        for (RawNewsItem item : items) {
            String publisher = item.publisher() == null ? "Social" : item.publisher();
            int paren = publisher.indexOf(" (");
            String venue = paren > 0 ? publisher.substring(0, paren) : publisher;
            byVenue.computeIfAbsent(venue, k -> new ArrayList<>()).add(item);
        }
        for (List<RawNewsItem> perVenue : byVenue.values()) {
            perVenue.sort(java.util.Comparator.comparing(RawNewsItem::publishedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        }
        List<RawNewsItem> out = new ArrayList<>(items.size());
        for (int round = 0; out.size() < items.size(); round++) {
            for (List<RawNewsItem> perVenue : byVenue.values()) {
                if (round < perVenue.size()) out.add(perVenue.get(round));
            }
        }
        return out;
    }

    static String roomText(Material m, Map<String, Integer> nums) {
        if (!roomHasContent(m)) return null;
        List<SubjectUnit> units = !m.roomUnits.isEmpty() ? m.roomUnits
                : m.unit != null ? List.of(m.unit) : List.of();
        // ONE wire representation on the shelf: the house archive when it
        // delivered, the units' session headlines only as its fallback (the
        // two blocks duplicated each other, zoom-out 2026-07-16).
        return roomBlock(units, nums, m.wireHistory.isEmpty());
    }

    /**
     * The room's material as ONE block: the price anchor, the deduped
     * evidence union of every matching unit (chronological, newest kept
     * within the window-scaled budget) and - as archive fallback - the
     * session's published wire lines. The multi-packet chunking retired
     * 2026-07-16 (zoom-out): it served the retold room of the old section
     * definition; the AGGREGATE section reads one shelf, and the only
     * caller re-joined the chunks into one string anyway.
     */
    static String roomBlock(List<SubjectUnit> units, Map<String, Integer> nums,
            boolean includeWireLines) {
        String head = "ROOM EVIDENCE (r/wallstreetbetsGER, unverified)" + mark(nums, "room");
        if (units == null || units.isEmpty()) {
            return head + ":\n  (the room has not taken this subject up)\n";
        }
        SubjectUnit primary = units.get(0);
        StringBuilder cur = new StringBuilder(roomPacketChars() * 2 + 256);
        cur.append(head).append(":\n");
        // The primary unit's price anchor: since-first-mention is the room's own scoreboard.
        if (primary.firstPrice() != null && primary.firstPrice() > 0 && primary.firstPriceAt() != null) {
            cur.append("  First mentioned ").append(STAMP.format(primary.firstPriceAt()), 0, 10)
                    .append(" at ").append(fmt2(primary.firstPrice()));
            MarketSnapshot s = primary.snapshot();
            if (s != null && s.hasPrice()) {
                cur.append(String.format(Locale.ROOT, " (%+.1f%% since)",
                        (s.price() - primary.firstPrice()) / primary.firstPrice() * 100.0));
            }
            cur.append('\n');
        }
        // Union of every unit's mentions, deduped by mention identity — the same
        // comment attributed to both the ticker and the name unit rides once.
        Map<String, SubjectUnit.EvidenceRef> byKey = new LinkedHashMap<>();
        for (SubjectUnit u : units) {
            for (SubjectUnit.EvidenceRef ref : u.evidence()) {
                byKey.putIfAbsent(ref.threadId() + "|" + ref.commentId() + "|" + ref.snippet(), ref);
            }
        }
        List<SubjectUnit.EvidenceRef> evidence = new ArrayList<>(byKey.values());
        evidence.sort(java.util.Comparator.comparingLong(SubjectUnit.EvidenceRef::addedAtEpoch));
        if (evidence.isEmpty()) cur.append("  (none in the current window)\n");
        int start = evidence.size();
        int budget = roomPacketChars() * 2;
        while (start > 0 && budget - evidence.get(start - 1).snippet().length() - 24 >= 0) {
            start--;
            budget -= evidence.get(start).snippet().length() + 24;
        }
        if (start > 0) cur.append("  (").append(start).append(" older mentions omitted)\n");
        for (SubjectUnit.EvidenceRef ref : evidence.subList(start, evidence.size())) {
            // Date-only stamps: the room section is an AGGREGATE - clock
            // times invited the model to retell single posts ("um 03:22 Uhr",
            // live Wave-1 smoke) instead of drawing the mood picture.
            cur.append("  - [").append(STAMP.format(Instant.ofEpochSecond(ref.addedAtEpoch())), 0, 10)
                    .append("] ").append(ref.snippet()).append('\n');
        }
        if (includeWireLines) {
            List<SubjectUnit.UnitHeadline> headlines = new ArrayList<>();
            for (SubjectUnit u : units) headlines.addAll(u.headlines());
            headlines.sort(java.util.Comparator.comparingLong(SubjectUnit.UnitHeadline::atEpoch));
            if (!headlines.isEmpty()) {
                cur.append("WIRE LINES ALREADY PUBLISHED FOR THIS SUBJECT:\n");
                int from = Math.max(0, headlines.size() - MAX_WIRE_LINES);
                for (SubjectUnit.UnitHeadline h : headlines.subList(from, headlines.size())) {
                    cur.append("  - [").append(STAMP.format(Instant.ofEpochSecond(h.atEpoch())), 0, 10)
                            .append("] ").append(h.text()).append('\n');
                }
            }
        }
        return cur.toString();
    }

    // -- the typesetter (deterministic assembly + gates) --

    /** A verbatim sentence this long repeating across sections is duplication, never style. */
    private static final int CROSS_SECTION_SENTENCE_CHARS = 90;

    /**
     * Sets the report from the section bodies: our headings, honest literals
     * for empty sections, cross-section dedupe of whole paragraphs AND long
     * verbatim sentences among the BODY sections (first occurrence wins —
     * assembly is the one place that sees every section at once), plus the
     * deterministic polish belts (non-marker brackets out, ISO dates rendered
     * in the report language). "These" is EXEMPT from the sentence dedupe in
     * both directions: a page-1 summary echoing the body is research-note
     * convention, and the body must never lose its own sentence to the
     * summary (live-observed: the outlook opened with a dangling "Danach
     * folgt…" because These, assembled first, had claimed its anchor
     * sentence).
     */
    static String assemble(List<String> headings, String[] bodies, boolean de) {
        Set<String> seenParagraphs = new HashSet<>();
        Set<String> seenSentences = new HashSet<>();
        // Every non-thesis paragraph/sentence, pre-scanned: the thesis is
        // deduped against the BODY regardless of assembly order.
        Set<String> bodyParagraphNorms = new HashSet<>();
        Set<String> bodySentenceNorms = new HashSet<>();
        for (int i = 0; i < bodies.length; i++) {
            if (i == SEC_THESIS || bodies[i] == null) continue;
            for (String rawPara : bodies[i].strip().split("\n\\s*\n")) {
                String para = rawPara.strip();
                if (para.isEmpty()) continue;
                bodyParagraphNorms.add(para.replaceAll("\\s+", " "));
                for (String sentence : DeepDiveFactCheck.sentences(para)) {
                    bodySentenceNorms.add(sentence.strip().replaceAll("\\s+", " "));
                }
            }
        }
        StringBuilder sb = new StringBuilder(8192);
        for (int i = 0; i < SECTION_COUNT; i++) {
            String body = bodies[i];
            if (body == null || body.isBlank()) {
                sb.append("## ").append(headings.get(i)).append('\n').append('\n');
                sb.append(honestLiteral(i, de)).append('\n').append('\n');
                continue;
            }
            body = DeepDiveFactCheck.scrubNonMarkerBrackets(body);
            if (de) {
                body = germanizeIsoDates(body);
                body = germanizeMoneyUnits(body);
            }
            // Source markers move OUT of the prose and onto the section
            // heading (user mandate 2026-07-13 "nicht an jeder Aussage"):
            // the examiner has already verified per-statement pre-assembly;
            // the READER gets one register per section instead of bracket
            // noise after every paragraph.
            List<Integer> sectionMarkers = new ArrayList<>(markersIn(body));
            java.util.Collections.sort(sectionMarkers);
            sb.append("## ").append(headings.get(i));
            for (Integer n : sectionMarkers) sb.append(" [").append(n).append(']');
            sb.append('\n').append('\n');
            boolean thesis = i == SEC_THESIS;
            // The thesis reads the FULL sections now (2026-07-16) - a copied
            // paragraph must die in the THESIS, never in the body it was
            // copied from. bodyNorms is pre-scanned from every non-thesis
            // section, so the check is order-independent.
            for (String rawPara : body.strip().split("\n\\s*\n")) {
                String para = rawPara.strip();
                if (para.isEmpty()) continue;
                // A paragraph that is ONLY markers/punctuation collapses to a
                // lone comma after the marker lift (live 2026-07-16: ',' rows
                // between sections) - it carries nothing, it dies here.
                if (para.replaceAll("\\[\\d{1,3}]", "")
                        .replaceAll("[ \\t.,;:–—-]+", "").isEmpty()) {
                    continue;
                }
                String norm = para.replaceAll("\\s+", " ");
                if (thesis ? bodyParagraphNorms.contains(norm) : !seenParagraphs.add(norm)) {
                    LOG.info("[DEEPDIVE] assembly dropped a cross-section duplicate paragraph.");
                    continue;
                }
                // A markdown table block is ATOMIC: the sentence machinery
                // would join its rows into one line and destroy it. Rows keep
                // their line structure; markers lift to the heading like
                // everywhere else.
                if (isTableBlock(para)) {
                    StringBuilder table = new StringBuilder(para.length());
                    for (String row : para.split("\n")) {
                        String cleanRow = row.strip().replaceAll(" ?\\[\\d{1,3}]", "");
                        if (!cleanRow.isEmpty()) table.append(cleanRow).append('\n');
                    }
                    sb.append(table).append('\n');
                    continue;
                }
                StringBuilder kept = new StringBuilder(para.length());
                for (String sentence : DeepDiveFactCheck.sentences(para)) {
                    String sNorm = sentence.strip().replaceAll("\\s+", " ");
                    boolean duplicate = thesis
                            ? sNorm.length() >= CROSS_SECTION_SENTENCE_CHARS
                                    && bodySentenceNorms.contains(sNorm)
                            : sNorm.length() >= CROSS_SECTION_SENTENCE_CHARS
                                    && !seenSentences.add(sNorm);
                    if (duplicate) {
                        LOG.info("[DEEPDIVE] assembly dropped a cross-section duplicate sentence.");
                        continue;
                    }
                    if (kept.length() > 0) kept.append(' ');
                    kept.append(sentence.strip());
                }
                if (kept.length() == 0) continue;
                // Lift the in-prose markers — they now live on the heading.
                // Punctuation splice residue (",." after a weave cut) tidied
                // deterministically — live: 'bei,.' survived every pass.
                String clean = kept.toString().replaceAll(" ?\\[\\d{1,3}]", "")
                        .replaceAll(" +([.,;:!?])", "$1").replaceAll("[ \\t]{2,}", " ")
                        .replaceAll(",+\\s*\\.", ".").replaceAll(",{2,}", ",");
                if (clean.isBlank()) continue;
                sb.append(clean).append('\n').append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** Appends a typesetter table to a section body (a table alone is honest content). */
    private static void appendTypesetTable(String[] bodies, int sec, String table) {
        if (table == null) return;
        bodies[sec] = bodies[sec] == null || bodies[sec].isBlank()
                ? table
                : bodies[sec].strip() + "\n\n" + table;
    }

    /**
     * The peer-comparison table (Bewertung und Wettbewerb) — deterministic
     * typesetting from the Consorsbank peers leg; the source marker rides in
     * the header row and lifts to the heading at assembly.
     */
    static String peerTable(Material m, Map<String, Integer> nums, boolean de) {
        if (m.deepDive == null || m.deepDive.peers() == null || m.deepDive.peers().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(320);
        sb.append(de ? "| Unternehmen | Marktkap. | KGV | Dividendenrendite"
                : "| Company | Market cap | P/E | Dividend yield")
                .append(mark(nums, "consors")).append(" |\n");
        sb.append("|---|---|---|---|\n");
        int n = 0;
        for (CompanyDeepDive.Peer p : m.deepDive.peers()) {
            if (p.name() == null || p.name().isBlank()) continue;
            if (++n > 5) break;
            sb.append("| ").append(p.name()).append(" | ")
                    .append(fmtMoneyCompact(p.marketCapEur(), "EUR", de)).append(" | ")
                    .append(fmtCell(p.peRatio(), 1, de)).append(" | ")
                    .append(Double.isFinite(p.dividendYieldPercent())
                            ? fmtCell(p.dividendYieldPercent(), 1, de) + " %" : "-")
                    .append(" |\n");
        }
        return n == 0 ? null : sb.toString().stripTrailing();
    }

    /**
     * The analyst-action table (Bewertung) — the dated street history as
     * deterministic typesetting from the MarketBeat leg (BestStocks pattern:
     * firm, date, action, rating and target old→new).
     */
    static String actionsTable(Material m, Map<String, Integer> nums, boolean de) {
        if (m.analystActions == null || m.analystActions.actions().isEmpty()) return null;
        StringBuilder sb = new StringBuilder(512);
        sb.append(de ? "| Datum | Haus | Aktion | Rating | Kursziel"
                : "| Date | Firm | Action | Rating | Price target")
                .append(mark(nums, "marketbeat")).append(" |\n");
        sb.append("|---|---|---|---|---|\n");
        int n = 0;
        for (AnalystActions.Action a : m.analystActions.actions()) {
            if (++n > MAX_ACTION_TABLE_ROWS) break;
            String rating = joinOldNew(a.ratingOld(), a.ratingNew());
            String target = joinTargets(a.targetOld(), a.targetNew(), a.targetCurrency());
            if (de && target != null) target = target.replace('.', ',');
            sb.append("| ").append(a.dateIso() == null ? "-" : a.dateIso())
                    .append(" | ").append(a.brokerage())
                    .append(" | ").append(a.actionType())
                    .append(" | ").append(rating == null ? "-" : rating)
                    .append(" | ").append(target == null ? "-" : target)
                    .append(" |\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * The scenario-anchor table (Ausblick) — Bull/Base/Bear anchored STRICTLY
     * on the street's own target band (high/mean/low), never on invented
     * probabilities (doctrine 2026-07-14). The distance column appears only
     * when the live price shares the targets' currency — a cross-currency
     * percentage is the figure-corruption class the EUR guards killed.
     */
    static String scenarioTable(Material m, Map<String, Integer> nums, boolean de) {
        UsListingStats.AnalystRatings r = m.usStats == null ? null : m.usStats.analystRatings();
        if (r == null || !Double.isFinite(r.meanPriceTargetUsd())
                || !Double.isFinite(r.highPriceTargetUsd())
                || !Double.isFinite(r.lowPriceTargetUsd())) {
            return null;
        }
        boolean distance = m.snapshot != null && m.snapshot.hasPrice()
                && "USD".equals(m.snapshot.currency()) && m.snapshot.price() > 0;
        StringBuilder sb = new StringBuilder(320);
        sb.append(de ? "| Szenario | Anker | Kursziel" : "| Scenario | Anchor | Price target");
        if (distance) sb.append(de ? " | Abstand" : " | Distance");
        sb.append(mark(nums, "nasdaq")).append(" |\n");
        sb.append(distance ? "|---|---|---|---|\n" : "|---|---|---|\n");
        appendScenarioRow(sb, de ? "Bull" : "Bull", de ? "Street-Hoch" : "street high",
                r.highPriceTargetUsd(), m, distance, de);
        appendScenarioRow(sb, de ? "Basis" : "Base", de ? "Konsens" : "consensus",
                r.meanPriceTargetUsd(), m, distance, de);
        appendScenarioRow(sb, de ? "Bear" : "Bear", de ? "Street-Tief" : "street low",
                r.lowPriceTargetUsd(), m, distance, de);
        return sb.toString().stripTrailing();
    }

    private static void appendScenarioRow(StringBuilder sb, String scenario, String anchor,
            double target, Material m, boolean distance, boolean de) {
        sb.append("| ").append(scenario).append(" | ").append(anchor).append(" | ")
                .append(fmtCell(target, 2, de)).append(" USD");
        if (distance) {
            double pct = (target / m.snapshot.price() - 1) * 100;
            sb.append(" | ").append(String.format(de ? Locale.GERMANY : Locale.ROOT,
                    "%+.1f %%", pct));
        }
        sb.append(" |\n");
    }

    /** A table cell number in display locale; "-" for unknown. */
    private static String fmtCell(double v, int decimals, boolean de) {
        if (!Double.isFinite(v)) return "-";
        return String.format(de ? Locale.GERMANY : Locale.ROOT, "%." + decimals + "f", v);
    }

    /** Money in display units for table cells: "1,5 Mrd. EUR" / "380,0 Mio. EUR". */
    private static String fmtMoneyCompact(double v, String currency, boolean de) {
        if (!Double.isFinite(v) || v <= 0) return "-";
        Locale loc = de ? Locale.GERMANY : Locale.ROOT;
        if (v >= 1e9) {
            return String.format(loc, "%.1f", v / 1e9) + (de ? " Mrd. " : "B ") + currency;
        }
        if (v >= 1e6) {
            return String.format(loc, "%.1f", v / 1e6) + (de ? " Mio. " : "M ") + currency;
        }
        return String.format(loc, "%.0f %s", v, currency);
    }

    /**
     * A paragraph block is a markdown pipe table when every non-blank line is
     * a pipe row and there are at least two of them (header + separator or
     * header + data).
     */
    static boolean isTableBlock(String para) {
        String[] lines = para.split("\n");
        int rows = 0;
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty()) continue;
            if (!s.startsWith("|") || !s.endsWith("|")) return false;
            rows++;
        }
        return rows >= 2;
    }

    /**
     * Renders leftover ISO dates in German prose as dotted German dates — the
     * prompts demand it, a 4B misses some (live: "am 2026-10-22" beside a
     * correctly written "am 24. Juli 2026"). Values are untouched; the
     * examiner has already verified every date against the material.
     */
    static String germanizeIsoDates(String text) {
        if (text == null || text.indexOf('-') < 0) return text;
        Matcher m = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(out, m.group(3) + "." + m.group(2) + "." + m.group(1));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Renders copied material money units in German prose — the material is
     * ROOT-formatted for the examiner ("160.6B EUR"), and a 4B occasionally
     * copies the token verbatim instead of writing "160,6 Mrd. EUR" (live:
     * Worum es geht). The value is untouched, only locale rendering.
     */
    static String germanizeMoneyUnits(String text) {
        if (text == null || (text.indexOf("B EUR") < 0 && text.indexOf("M EUR") < 0
                && text.indexOf("B USD") < 0 && text.indexOf("M USD") < 0)) {
            return text;
        }
        Matcher m = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)([BM]) (EUR|USD)\\b").matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String value = m.group(1).replace('.', ',');
            String unit = "B".equals(m.group(2)) ? "Mrd." : "Mio.";
            m.appendReplacement(out,
                    Matcher.quoteReplacement(value + " " + unit + " " + m.group(3)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The honest empty-section sentence — house text, never model output. */
    static String honestLiteral(int sectionIdx, boolean de) {
        if (sectionIdx == SEC_ROOM) {
            return de ? "Der Käfig hat dieses Subjekt bisher nicht aufgegriffen."
                    : "The room has not taken this subject up yet.";
        }
        return de ? "Hierzu liegen keine verifizierten Daten vor."
                : "No verified data is available for this section.";
    }

    /**
     * The skeleton sanity gate: real length and ALL eight canonical headings,
     * line-leading and in order. Assembly satisfies this by construction — the
     * gate exists as the final belt before the archive.
     */
    static boolean looksLikeReport(String s, List<String> headings) {
        if (s == null || s.length() < 500) return false;
        if (headings == null) {
            int sections = 0;
            for (int i = s.indexOf("## "); i >= 0; i = s.indexOf("## ", i + 1)) {
                if (i == 0 || s.charAt(i - 1) == '\n') sections++;
            }
            return sections >= SECTION_COUNT;
        }
        int from = 0;
        for (String heading : headings) {
            from = indexOfHeading(s, heading, from);
            if (from < 0) return false;
        }
        return true;
    }

    /**
     * Finds a line-leading {@code "## <heading>"} whose rest-of-line is only
     * whitespace, a stray colon, or the section's lifted source markers
     * ({@code [n]…}). Returns the index AFTER the match, or -1.
     */
    private static int indexOfHeading(String s, String heading, int from) {
        String needle = "## " + heading;
        for (int i = s.indexOf(needle, from); i >= 0; i = s.indexOf(needle, i + 1)) {
            if (i > 0 && s.charAt(i - 1) != '\n') continue;
            int j = i + needle.length();
            boolean clean = true;
            while (j < s.length() && s.charAt(j) != '\n') {
                char c = s.charAt(j++);
                if (c != ' ' && c != '\t' && c != ':' && c != '\r'
                        && c != '[' && c != ']' && (c < '0' || c > '9')) {
                    clean = false;
                    break;
                }
            }
            if (clean) return j;
        }
        return -1;
    }

    private static final Pattern MARKER_NUM = Pattern.compile("\\[(\\d{1,3})]");

    /** Every {@code [n]} source marker a text carries. */
    static Set<Integer> markersIn(String text) {
        Set<Integer> out = new HashSet<>();
        if (text == null) return out;
        Matcher mt = MARKER_NUM.matcher(text);
        while (mt.find()) out.add(Integer.parseInt(mt.group(1)));
        return out;
    }

    /**
     * Removes every {@code [n]} marker whose number no source carries — a 4B
     * model must never mint its own footnotes. Valid markers pass untouched.
     */
    static String scrubUnknownSourceMarkers(String report, java.util.Set<Integer> valid) {
        if (report == null || report.isEmpty()) return report;
        Matcher mt = Pattern.compile(" ?\\[(\\d{1,3})]").matcher(report);
        StringBuilder out = new StringBuilder(report.length());
        while (mt.find()) {
            boolean known = valid.contains(Integer.parseInt(mt.group(1)));
            mt.appendReplacement(out,
                    known ? Matcher.quoteReplacement(mt.group()) : "");
        }
        mt.appendTail(out);
        return out.toString();
    }

    /**
     * The rendered source register ("## Quellen"), matching {@link #sourceNumbers}
     * one to one. Deterministic house text — never model output.
     */
    static String sourcesSection(Material m, boolean de) {
        return sourcesSection(m, de, null);
    }

    /**
     * The register with citation discipline: when {@code cited} is given, a
     * NEWS entry no paragraph cites stays out (numbers keep their assignment —
     * gaps are honest). Data legs and the room are always listed; they feed
     * the figure layer and the identity even when no sentence carries their
     * marker.
     */
    static String sourcesSection(Material m, boolean de, Set<Integer> cited) {
        Map<String, Integer> nums = sourceNumbers(m);
        if (nums.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(512);
        sb.append("## ").append(de ? "Quellen" : "Sources").append('\n');
        for (Map.Entry<String, Integer> e : nums.entrySet()) {
            boolean sightedOnly = e.getKey().startsWith("news:")
                    && m.sightedOnly.contains(e.getValue())
                    && (cited == null || !cited.contains(e.getValue()));
            if (sightedOnly) continue; // listed below under their own label
            if (cited != null && e.getKey().startsWith("news:")
                    && !cited.contains(e.getValue())) {
                continue;
            }
            sb.append("- [").append(e.getValue()).append("] ")
                    .append(sourceLabel(m, e.getKey(), de)).append('\n');
        }
        // The sighted-only sources: read and chronicled, judged not to change
        // the section's read - listed honestly under their own label, never
        // as fake in-text citations and never silently dropped.
        StringBuilder sighted = new StringBuilder();
        for (Map.Entry<String, Integer> e : nums.entrySet()) {
            if (!e.getKey().startsWith("news:")) continue;
            if (!m.sightedOnly.contains(e.getValue())) continue;
            if (cited != null && cited.contains(e.getValue())) continue;
            sighted.append("- [").append(e.getValue()).append("] ")
                    .append(sourceLabel(m, e.getKey(), de)).append('\n');
        }
        if (sighted.length() > 0) {
            sb.append(de ? "Gesichtet, ohne eigenständigen Beitrag zur Lesart:\n"
                    : "Sighted, no standalone contribution to the read:\n").append(sighted);
        }
        return sb.toString();
    }

    private static String sourceLabel(Material m, String key, boolean de) {
        switch (key) {
            case "price": {
                String venue = m.snapshot != null && m.snapshot.exchangeName() != null
                        && !m.snapshot.exchangeName().isBlank()
                        ? m.snapshot.exchangeName() : "Yahoo Finance";
                return venue + (de ? " - Kurs- und Handelsdaten" : " - price and market data");
            }
            case "venue":
                return "Tradegate Exchange" + (de ? " - Orderbuch- und Umsatzdaten"
                        : " - order book and turnover data");
            case "profile":
                return "onvista" + (de ? " - Unternehmensprofil und Bewertungskennzahlen"
                        : " - company profile and valuation figures");
            case "consors":
                return "Consorsbank Financial-Info" + (de
                        ? " - Kennzahlen, Bilanz, Analystenschätzungen, Termine"
                        : " - key figures, balance sheet, analyst estimates, corporate dates");
            case "tc":
                return "TradingCentral (via Consorsbank)" + (de
                        ? " - charttechnische Einschätzung" : " - chart-technical view");
            case "shorts":
                return "Bundesanzeiger" + (de ? " - Leerverkaufsregister (Netto-Shortpositionen)"
                        : " - short-selling register (net short positions)");
            case "insider":
                return "BaFin" + (de ? " - Directors' Dealings (§ 19 MAR)"
                        : " - directors' dealings (art. 19 MAR)");
            case "sector": {
                return (de
                        ? "Sektor- und Makro-Kontext - Yahoo Sektor-ETFs, TradingView/ForexFactory-"
                                + "Wirtschaftskalender, Notenbank-Termine"
                        : "Sector and macro context - Yahoo sector ETFs, TradingView/ForexFactory "
                                + "economic calendars, central-bank dates");
            }
            case "world":
                return de
                        ? "Weltsignale - IMF PortWatch, EIA, Harpex, Energy-Charts, NOAA, CISA, "
                                + "Politik-/Zivil-Ticker, Gefahrenlage (NOAA/USGS/FAA); "
                                + "je Subjekt KI-beurteilt"
                        : "World signals - IMF PortWatch, EIA, Harpex, Energy-Charts, NOAA, CISA, "
                                + "policy/civic wires, hazard picture (NOAA/USGS/FAA); "
                                + "AI-judged per subject";
            case "marketbeat":
                return "MarketBeat" + (de
                        ? " - Analysten-Aktionshistorie, US-Short-Quote und Presse-Zeitleiste"
                        : " - analyst action history, US short stats and dated press timeline");
            case "hedgefunds":
                return "Insider Monkey" + (de
                        ? " - Hedgefonds-Positionierung (13F-Quartalskurve)"
                        : " - hedge-fund positioning (quarterly 13F curve)");
            case "nasdaq":
                return "NASDAQ" + (de
                        ? " - US-Listing: Insider-Trades (Form 4), FINRA Short Interest, "
                                + "Analysten-Kursziele, institutionelle Halter (13F), Earnings-Historie"
                        : " - US listing: insider trades (Form 4), FINRA short interest, "
                                + "analyst targets, institutional holders (13F), earnings history");
            case "structure":
                return de
                        ? "Struktur - hausgerechnetes Volume Profile (Yahoo-Stundenkerzen) und "
                                + "Börse-Frankfurt-Orderbuchfenster (10 Level)"
                        : "Structure - house-computed volume profile (Yahoo hourly bars) and "
                                + "Boerse Frankfurt order-book window (10 levels)";
            case "memory":
                return de
                        ? "Markt-Gedächtnis - hauseigenes Ereignisregister (EDGAR, MarketBeat, "
                                + "NASDAQ, EQS-Ad-hocs) mit gemessenen Reaktionen und attribuierten "
                                + "Literatur-Prioren"
                        : "Market memory - house event register (EDGAR, MarketBeat, NASDAQ, EQS "
                                + "ad-hocs) with measured reactions and attributed literature priors";
            case "ir":
                return de
                        ? "Unternehmens-IR - Finanzberichte, Calls und Finanzkalender von der "
                                + "Firmenwebsite (first-party)"
                        : "Company IR - financial reports, calls and calendar from the "
                                + "company website (first-party)";
            case "history":
                return de
                        ? "Google News Archiv - mehrjährige Presse-Historie (Schlagzeilen, "
                                + "Jahresfenster-Suche)"
                        : "Google News archive - multi-year press history (headlines, "
                                + "windowed yearly search)";
            case "whispers":
                return "EarningsWhispers" + (de
                        ? " - Konsensschätzungen zum nächsten Bericht"
                        : " - consensus estimates for the next report");
            case "room":
                return "r/wallstreetbetsGER" + (de ? " - Diskussion im Käfig (unverifiziert)"
                        : " - the room's discussion (unverified)");
            case "sentiment":
                return de
                        ? "Foren & Social Media - Stimmen aus den Außen-Räumen "
                                + "(unverifiziert, aggregiert; Ort je Zeile im Material)"
                        : "Forums & social media - voices from the outside rooms "
                                + "(unverified, aggregated; venue labeled per line)";
            default: {
                int idx = Integer.parseInt(key.substring("news:".length()));
                RawNewsItem item = m.news.get(idx);
                StringBuilder sb = new StringBuilder(96);
                sb.append(item.publisher() == null || item.publisher().isEmpty()
                        ? (de ? "Presse" : "Press") : item.publisher());
                sb.append(" - „").append(item.title()).append('“');
                if (item.publishedAt() != null) {
                    sb.append(" (").append(STAMP.format(item.publishedAt()), 0, 10).append(')');
                }
                return sb.toString();
            }
        }
    }

    // -- small helpers --

    /** Strips code fences a model occasionally wraps its markdown in. */
    private static String cleanReport(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstBreak = s.indexOf('\n');
            if (firstBreak > 0) s = s.substring(firstBreak + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.strip();
        }
        return s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void appendFig(StringBuilder sb, String label, double v) {
        if (Double.isFinite(v)) sb.append(label).append(fmt2(v));
    }

    private static void appendPct(StringBuilder sb, String label, double v) {
        if (Double.isFinite(v)) sb.append(label).append(String.format(Locale.ROOT, "%+.1f%%", v));
    }

    private static String fmt2(double v) {
        // Grouping SPACES, never commas: "36,800" in the material reads as 36.8
        // in German prose, and the model copies literals verbatim (live-observed
        // "Umsatz auf 36,800 Mio. EUR" — ambiguous). "36 800" is unambiguous.
        return String.format(Locale.ROOT, Math.abs(v) >= 1000 ? "%,.0f" : "%.2f", v)
                .replace(',', ' ');
    }
}
