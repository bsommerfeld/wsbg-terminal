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
    private static final int MAX_DIGEST_CHARS = 500;
    private static final int MAX_INSIDER_DEALS = 8;
    private static final int MAX_SHORT_POSITIONS = 8;
    /** NASDAQ tab caps — material stays model-sized, the tabs carry hundreds of rows. */
    private static final int MAX_US_INSIDER_TRADES = 8;
    private static final int MAX_US_SHORT_POINTS = 6;
    private static final int MAX_US_HOLDERS = 5;
    private static final int MAX_US_SURPRISES = 4;
    private static final int MAX_ANALYST_ACTIONS = 10;
    private static final int MAX_ACTION_TABLE_ROWS = 8;
    private static final int MAX_MACRO_ACTUALS = 4;
    private static final int MAX_MACRO_DOCKET = 4;
    /** Wire-archive lines fed to the room shelf: the first N + the newest M. */
    private static final int WIRE_HISTORY_HEAD = 2;
    private static final int WIRE_HISTORY_TAIL = 6;
    private static final int MAX_KEY_FIGURE_YEARS = 6;
    private static final int MAX_BALANCE_YEARS = 4;
    private static final int MAX_EVENTS = 4;
    private static final int MAX_WIRE_LINES = 6;
    /** Room chunking inside {@link #roomBlocks} — two chunks join into ONE author material. */
    private static final int ROOM_PACKET_CHARS = 2400;
    /** How many room chunks ride the section material (newest evidence kept when over). */
    private static final int MAX_ROOM_PACKETS = 2;
    /**
     * The TradingCentral comment prose is attributed context, not the star —
     * capped, but at a SENTENCE boundary: a mid-sentence cut leaves the
     * comment's figures (moving averages, RSI) half in the model's head and
     * half out of the material, and the examiner then rightly kills the
     * sentence every single run (live: "138,01" recurred in three runs).
     */
    private static final int MAX_TECHNICAL_COMMENT_CHARS = 700;
    /** Hard cap on ONE section's material — must fit an author call beside the prompt. */
    private static final int SECTION_MATERIAL_CHARS = 6200;
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
    private volatile TradingViewCalendarClient tvCalendar;
    private volatile EconCalendarClient econCalendar;
    private volatile CentralBankCalendarClient cbCalendar;
    private volatile HeadlineArchive headlineArchive;
    private volatile de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator newsAggregator;
    private volatile de.bsommerfeld.wsbg.terminal.briefing.FnRssClient fnRssClient;
    private volatile de.bsommerfeld.wsbg.terminal.briefing.EarningsWhispersClient earningsWhispers;
    private volatile CompanyPressScout pressScout;
    private volatile de.bsommerfeld.wsbg.terminal.websearch.BingWebSearchClient webSearch;

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
            String consistency) {
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
                PromptLoader.loadLocalized("deepdive-consistency", lang)
                        .replace("{{LANGUAGE}}", langName));

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
                    shelves[idx], de, label, dropWords, m);
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
        bodies[SEC_THESIS] = writeSection(model, new Prompts(prompts.these(), prompts.these(),
                        prompts.revise(), prompts.challenge(), prompts.weave(), prompts.polish(),
                        prompts.arbiter(), prompts.samestory(), prompts.consistency()),
                subject, header,
                headings.get(SEC_THESIS), new Shelf(thesisMaterial(headings, bodies, m), List.of()),
                de, (SEC_THESIS + 1) + "/" + SECTION_COUNT + " · " + headings.get(SEC_THESIS),
                dropWords, m);
        if (bodies[SEC_THESIS] != null) written++;

        // -- cross-section consistency: the ONE review that sees the whole
        // report at once (live finding run 10: Bewertung said "no revisions"
        // while Ausblick correctly carried 0 up / 1 down — a per-section
        // challenger can never catch that). One bounded round: objections map
        // to their owning section, which gets one revision against ITS shelf.
        checkCancelled();
        eventBus.post(new DeepDiveProgressEvent(subject, "finish",
                de ? "Konsistenz" : "consistency"));
        try {
            crossSectionConsistency(model, prompts, subject, headings, bodies, shelves, m, de,
                    header);
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
        // draws), frozen into the record so UI and PDF show the same picture.
        List<DeepDiveRecord.ChartFigure> charts = new DeepDiveCharts(lang)
                .build(m.snapshot, m.deepDive, m.analystView, m.shortInterest, m.insiderDealings,
                        m.venueStats);
        DeepDiveRecord record = new DeepDiveRecord(
                "dd-" + UUID.randomUUID().toString().substring(0, 8),
                subject, m.canonicalName, m.ticker, m.isin,
                Instant.now().getEpochSecond(), report,
                m.snapshot != null && m.snapshot.hasPrice() ? m.snapshot.price() : null,
                m.snapshot != null && m.snapshot.hasPrice() ? m.snapshot.currency() : null,
                m.evidenceCount, m.news.size(),
                System.currentTimeMillis() - t0,
                charts);
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
            Set<String> storyDropWords, Material m) {
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
        if (isBlank(seed) && !groups.isEmpty()) {
            seedGroup = groups.remove(0);
            seed = newsHeader + String.join("", seedGroup);
        }
        String fed = seed;

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
                journalNotes(subject, group.stream().map(DeepDiveService::firstLine).toList());
                continue;
            }
            eventBus.post(new DeepDiveProgressEvent(subject, "sections",
                    progressLabel + " · " + (de ? "Quelle " : "source ") + step + "/" + total));
            fed = fed + "\n" + newsHeader + block;
            String woven = cleanReport(chatGateway.chat(model, prompts.weave(),
                    weaveMessage(header, heading, body, block, prompts.weave().length(),
                            group.size())));
            if (isBlank(woven)) {
                LOG.warn("[DEEPDIVE] '{}' section '{}' weave step {} whiffed — story skipped.",
                        subject, heading, step);
                continue;
            }
            RepairOutcome out = examineAndRepair(model, prompts, subject, header, heading, fed,
                    markersIn(fed), de, woven.strip());
            body = out.body();
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
        String fixed = header + "SECTION: ## " + heading + "\n\nSTANDING TEXT:\n" + body
                + "\n\n" + label;
        return fixed + budgeted(block, promptChars + fixed.length());
    }

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
        journalNotes(subject, List.of("Digest nachgefordert — " + fetched
                + " Volltext(e) für diese Story gelesen"));
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

    /** The author's user message, material hard-budgeted against the window. */
    private String authorMessage(String header, String heading, String material, int promptChars) {
        String fixed = header + "SECTION TO WRITE: ## " + heading
                + "\n\nMATERIAL (verified blocks; [n] = source markers to copy):\n";
        return fixed + budgeted(material, promptChars + fixed.length());
    }

    /**
     * The cross-section consistency review: the arbiter-grade pass that sees
     * the WHOLE report beside the verified KEY DATA and hunts exclusively the
     * conflicts a per-section challenger is blind to (section A vs section B,
     * section vs page-1 figures). Each objection is routed to the section
     * that carries the quoted claim; that section gets ONE revision against
     * its OWN shelf, re-examined. Bounded to a single round.
     */
    private void crossSectionConsistency(ChatModel model, Prompts prompts, String subject,
            List<String> headings, String[] bodies, Shelf[] shelves, Material m, boolean de,
            String header) {
        StringBuilder report = new StringBuilder(8192);
        for (int i = 0; i < SECTION_COUNT; i++) {
            if (bodies[i] == null) continue;
            report.append("## ").append(headings.get(i)).append('\n')
                    .append(bodies[i]).append("\n\n");
        }
        if (report.isEmpty()) return;
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
            return;
        }
        journalNotes(subject, objections);
        Map<Integer, List<String>> bySection = new LinkedHashMap<>();
        for (String objection : objections) {
            int idx = owningSection(bodies, quotedSpan(objection));
            if (idx >= 0) bySection.computeIfAbsent(idx, k -> new ArrayList<>()).add(objection);
        }
        LOG.info("[DEEPDIVE] '{}' cross-section review: {} objection(s) across {} section(s).",
                subject, objections.size(), bySection.size());
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
            journalDiff(subject, bodies[idx], repaired);
            bodies[idx] = repaired;
        }
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
    private static final Pattern TRIAGE_TARGET = Pattern.compile("\"target\"\\s*:\\s*\"(\\w+)\"");
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
            }
            kept = new ArrayList<>(kept.subList(0, MAX_NEWS));
        }
        m.news = kept;
        m.newsTargets = targets;
    }

    /**
     * One judge sweep over a news list. Returns per item key: the target
     * section ("LAGE"/"KATALYSATOR"/"AUSBLICK") when relevant, {@code ""} when
     * judged off-subject, no entry when the judge never mentioned it (treated
     * as relevant — fail-open, a lost judgement must not lose a real story).
     */
    private Map<String, String> judgeNews(List<RawNewsItem> items, String about,
            String lang, String langName, ChatModel judge) {
        Map<String, String> out = new HashMap<>();
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
        for (int from = 0; from < items.size(); from += TRIAGE_BATCH) {
            // An uncapped pool means up to ~50 judge batches — cancel bites here too.
            checkCancelled();
            List<RawNewsItem> batch = items.subList(from, Math.min(items.size(), from + TRIAGE_BATCH));
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
                        String target = "LAGE";
                        Matcher tM = TRIAGE_TARGET.matcher(o);
                        if (tM.find()) {
                            String t = tM.group(1).toUpperCase(Locale.ROOT);
                            if (t.startsWith("KATALYSATOR") || t.startsWith("CATALYST")) {
                                target = "KATALYSATOR";
                            } else if (t.startsWith("AUSBLICK") || t.startsWith("OUTLOOK")) {
                                target = "AUSBLICK";
                            }
                        }
                        out.put(newsKey(batch.get(i - 1)), relevant ? target : "");
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
        /** The instrument's US sector proxy (XL* SPDR), day snapshot. Null = no mapping. */
        MarketSnapshot sectorEtf;
        String sectorEtfSymbol;
        String sectorDisplayName;
        /** Today's high-impact macro ACTUALS (Ist vs Prognose vs zuvor — the weather pattern). */
        List<TradingViewCalendarClient.TvEvent> macroActualsToday = List.of();
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
            journalNotes(ticker, List.of("News-Pool — " + m.news.size()
                    + " Kandidaten (Symbol + Name + ISIN + Websuche + Presse)"));
        }
        return m;
    }

    /**
     * The review pane's SOURCE containers (user mandate 2026-07-14 "vor den
     * Diffs die Quellen zeigen"): one journal group per delivered data leg,
     * terse outcome only — what was fetched, never narration.
     */
    private void journalCollectedSources(String subject, Material m) {
        if (m.snapshot != null && m.snapshot.hasPrice()) {
            String venue = m.snapshot.exchangeName() == null || m.snapshot.exchangeName().isBlank()
                    ? "Kursdaten" : m.snapshot.exchangeName();
            journalNotes(subject, List.of(venue + " — Kurs " + fmt2(m.snapshot.price())
                    + (m.snapshot.currency() == null ? "" : " " + m.snapshot.currency())));
        }
        if (m.venueStats != null) {
            journalNotes(subject, List.of("Tradegate — Volumen "
                    + groupedInt(m.venueStats.volumeShares()) + " Stück, "
                    + groupedInt(m.venueStats.executions()) + " Trades"));
        }
        if (m.facts != null) {
            journalNotes(subject, List.of("onvista — Profil"
                    + (m.facts.sector() == null ? "" : ": " + m.facts.sector())));
        } else if (m.fundFacts != null) {
            journalNotes(subject, List.of("onvista — Fondsprofil"));
        }
        if (m.analystView != null && m.analystView.hasRatings()) {
            journalNotes(subject, List.of("Consorsbank — " + m.analystView.total()
                    + " Analysten, " + m.analystView.events().size() + " Termine"));
        }
        if (m.deepDive != null) {
            journalNotes(subject, List.of("Consorsbank — Kennzahlen: "
                    + m.deepDive.keyFigures().size() + " Geschäftsjahre, Bilanz "
                    + m.deepDive.balanceSheet().size() + " Jahre"
                    + (m.deepDive.technicalView() != null ? ", Charttechnik" : "")));
        }
        if (m.shortInterest != null) {
            journalNotes(subject, List.of("Bundesanzeiger — "
                    + (m.shortInterest.positions().isEmpty() ? "keine Shortpositionen ≥ 0,5 %"
                    : m.shortInterest.positions().size() + " Shortposition(en), gesamt "
                            + fmt2(m.shortInterest.totalDisclosedPercent()) + " %")));
        }
        if (m.insiderDealings != null) {
            journalNotes(subject, List.of("BaFin — " + m.insiderDealings.deals().size()
                    + " Insider-Meldung(en)"));
        }
        if (m.usStats != null) {
            List<String> bits = new ArrayList<>();
            if (!m.usStats.insiderTrades().isEmpty()) {
                bits.add(m.usStats.insiderTrades().size() + " Insider-Trades");
            }
            if (!m.usStats.shortInterest().isEmpty()) {
                bits.add("Short Interest " + m.usStats.shortInterest().size() + " Stichtage");
            }
            if (m.usStats.analystRatings() != null) bits.add("Analysten-Konsens");
            if (m.usStats.institutionalOwnership() != null) bits.add("13F-Halter");
            if (!m.usStats.earningsSurprises().isEmpty()) {
                bits.add(m.usStats.earningsSurprises().size() + " Quartale");
            }
            journalNotes(subject, List.of("NASDAQ — "
                    + (bits.isEmpty() ? "US-Listing" : String.join(", ", bits))));
        }
        if (m.hedgeFunds != null && !m.hedgeFunds.quarters().isEmpty()) {
            HedgeFundPopularity.QuarterPoint latest =
                    m.hedgeFunds.quarters().get(m.hedgeFunds.quarters().size() - 1);
            journalNotes(subject, List.of("Insider Monkey — " + latest.funds()
                    + " Hedgefonds investiert (" + latest.quarterLabel() + ")"));
        }
        if (m.analystActions != null) {
            List<String> bits = new ArrayList<>();
            if (!m.analystActions.actions().isEmpty()) {
                bits.add(m.analystActions.actions().size() + " Analysten-Aktionen");
            }
            if (m.analystActions.shortStats() != null) bits.add("Short-Quote");
            journalNotes(subject, List.of("MarketBeat — "
                    + (bits.isEmpty() ? "Street-Historie" : String.join(", ", bits))));
        }
        if (m.sectorEtf != null || !m.macroActualsToday.isEmpty() || !m.macroDocket.isEmpty()) {
            List<String> bits = new ArrayList<>();
            if (m.sectorEtf != null) {
                bits.add("Sektor " + m.sectorDisplayName + " (" + m.sectorEtfSymbol + ")");
            }
            if (!m.macroActualsToday.isEmpty()) {
                bits.add(m.macroActualsToday.size() + " Makro-Ist-Zahl(en) heute");
            }
            if (!m.macroDocket.isEmpty() || !m.cbDecisions.isEmpty()) {
                bits.add((m.macroDocket.size() + m.cbDecisions.size()) + " kommende Termine");
            }
            journalNotes(subject, List.of("Sektor/Makro — " + String.join(", ", bits)));
        }
        if (!m.wireHistory.isEmpty()) {
            journalNotes(subject, List.of("Wire-Archiv — " + m.wireHistory.size()
                    + " eigene Zeile(n) zu diesem Subjekt"));
        }
        if (!m.roomUnits.isEmpty()) {
            journalNotes(subject, List.of("Käfig — " + m.evidenceCount + " Erwähnung(en) über "
                    + m.roomUnits.size() + " Subjekt-Einheit(en)"));
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
        sb.append("NOW: ").append(STAMP.format(Instant.now())).append('\n');
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

        appendMarket(sb, m.snapshot, nums);
        appendTrading(sb, m.venueStats, m.facts, nums);
        appendSectorContext(sb, m, nums);
        appendPerformance(sb, m.deepDive, nums);
        out[SEC_SITUATION] = new Shelf(take(sb), newsBlocksFor(m, "LAGE", nums));

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
        out[SEC_VALUATION] = new Shelf(take(sb), List.of());

        appendInsider(sb, m.insiderDealings, nums);
        appendUsInsiders(sb, m.usStats, nums);
        appendShorts(sb, m.shortInterest, nums);
        appendUsShortInterest(sb, m.usStats, nums);
        appendUsShortQuote(sb, m.analystActions, nums);
        appendShareCount(sb, m.deepDive, nums);
        appendTechnical(sb, m.deepDive, nums);
        out[SEC_CATALYSTS] = new Shelf(take(sb), newsBlocksFor(m, "KATALYSATOR", nums));

        appendUpcomingEvents(sb, m.analystView, nums);
        appendMacroDocket(sb, m, nums);
        appendEarningsConsensus(sb, m, nums);
        appendEstimatePath(sb, m.deepDive, nums);
        appendAnalystRatings(sb, m.analystView, nums);
        appendUsAnalysts(sb, m.usStats, nums);
        appendValuationContext(sb, m, nums);
        out[SEC_OUTLOOK] = new Shelf(take(sb), newsBlocksFor(m, "AUSBLICK", nums));

        StringBuilder roomSb = new StringBuilder(256);
        appendWireHistory(roomSb, m, nums);
        String room = roomText(m, nums);
        String roomShelf = room == null || room.isBlank()
                ? (roomSb.length() == 0 ? null : roomSb.toString())
                : (roomSb.length() == 0 ? room : room + "\n" + roomSb);
        out[SEC_ROOM] = new Shelf(roomShelf, List.of());
        out[SEC_THESIS] = new Shelf(null, List.of());
        return out;
    }

    /** One weave step per routed article: the item block WITH its digest. */
    private static List<String> newsBlocksFor(Material m, String target,
            Map<String, Integer> nums) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < m.news.size(); i++) {
            RawNewsItem item = m.news.get(i);
            String route = m.newsTargets.getOrDefault(newsKey(item), "LAGE");
            if (!route.equals(target)) continue;
            String block = newsItemBlock(item, m.digests, mark(nums, "news:" + i));
            if (item.link() != null && !item.link().isBlank()) {
                m.newsLinksByBlock.put(block, item.link().trim());
            }
            out.add(block);
        }
        return out;
    }

    private static String take(StringBuilder sb) {
        String text = sb.toString();
        sb.setLength(0);
        if (text.isBlank()) return null;
        if (text.length() > SECTION_MATERIAL_CHARS) {
            int nl = text.lastIndexOf('\n', SECTION_MATERIAL_CHARS);
            text = text.substring(0, nl > SECTION_MATERIAL_CHARS / 2 ? nl : SECTION_MATERIAL_CHARS)
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
        StringBuilder sb = new StringBuilder(1536);
        // CLAIMS FIRST, key data behind them: a 4B anchors on the material's
        // opening line, and with the price line up front the thesis opened as
        // a price recitation instead of a stance (live runs 8 AND 9, despite
        // the prompt's explicit opener rule) — primacy is the stronger lever.
        sb.append("CLAIM SENTENCES OF THE STANDING SECTIONS:\n");
        for (int i = 0; i < SECTION_COUNT; i++) {
            if (i == SEC_THESIS || bodies[i] == null) continue;
            // The profile section's claim is the company's SELF-DESCRIPTION
            // ("bezeichnet sich als weltweit führend …") — with primacy it
            // became the thesis opener verbatim (live SAP run 2026-07-14).
            // A stance draws from Lage/Bewertung/Katalysatoren/Ausblick/Raum;
            // what the company does is context the thesis reader already has.
            if (i == SEC_ABOUT) continue;
            String claim = firstSentence(bodies[i]);
            if (claim.isEmpty()) continue;
            sb.append("  - ").append(headings.get(i)).append(": ").append(claim).append('\n');
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

    /** The first sentence of a section body — its claim sentence by contract. */
    static String firstSentence(String body) {
        if (body == null || body.isBlank()) return "";
        String firstPara = body.strip().split("\n\\s*\n")[0];
        List<String> sentences = DeepDiveFactCheck.sentences(firstPara);
        return sentences.isEmpty() ? firstPara.strip() : sentences.get(0).strip();
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
        if (m.analystActions != null && (!m.analystActions.actions().isEmpty()
                || m.analystActions.shortStats() != null)) {
            nums.put("marketbeat", ++n);
        }
        if (m.sectorEtf != null || !m.macroActualsToday.isEmpty()
                || !m.macroDocket.isEmpty() || !m.cbDecisions.isEmpty()) {
            nums.put("sector", ++n);
        }
        if (m.earningsEstimate != null) nums.put("whispers", ++n);
        for (int i = 0; i < m.news.size(); i++) nums.put("news:" + i, ++n);
        if (roomHasContent(m)) nums.put("room", ++n);
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
            if (dg.length() > MAX_DIGEST_CHARS) dg = dg.substring(0, MAX_DIGEST_CHARS) + "…";
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
        sb.append("KEY FIGURES BY FISCAL YEAR (verified, 'e' = consensus estimate)")
                .append(mark(nums, "consors")).append(":\n");
        List<CompanyDeepDive.KeyFigureYear> years = d.keyFigures();
        int from = Math.max(0, years.size() - MAX_KEY_FIGURE_YEARS);
        for (CompanyDeepDive.KeyFigureYear y : years.subList(from, years.size())) {
            sb.append("  ").append(y.label()).append(':');
            appendFig(sb, " EPS ", y.eps());
            appendFig(sb, ", dividend ", y.dividendPerShare());
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
                        "the consensus target equals %.1fx the %s consensus EPS",
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
            appendFig(path, " EPS ", y.eps());
            appendFig(path, ", P/E ", y.peRatio());
            appendFig(path, ", dividend ", y.dividendPerShare());
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
            sb.append("  ").append(y.label()).append(':');
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
            sb.append(String.format(Locale.ROOT, "consensus EPS %.2f USD", est.epsEstimate()));
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
        sb.append("US EARNINGS SURPRISES (EPS actual vs consensus, NASDAQ, newest first)")
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
            if (comment.length() > MAX_TECHNICAL_COMMENT_CHARS) {
                // Cut at the last full sentence inside the cap — a torn sentence
                // strands its figures outside the material.
                String head = comment.substring(0, MAX_TECHNICAL_COMMENT_CHARS);
                int lastStop = Math.max(head.lastIndexOf(". "),
                        Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
                comment = (lastStop > MAX_TECHNICAL_COMMENT_CHARS / 2
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
     * The room's material as ONE text (chunked internally by {@link #roomBlocks},
     * newest evidence kept): price anchor first, evidence chronological, the
     * already-published wire lines last. Returns {@code null} when the room has
     * genuinely nothing — the section then gets its honest literal WITHOUT any
     * model call, so an empty room can never be hallucinated into a discussion
     * (live-observed SAP 2026-07-13: evidenceCount 0, yet the report claimed a
     * dated debate).
     */
    static String roomText(Material m, Map<String, Integer> nums) {
        if (!roomHasContent(m)) return null;
        List<SubjectUnit> units = !m.roomUnits.isEmpty() ? m.roomUnits
                : m.unit != null ? List.of(m.unit) : List.of();
        return String.join("", roomBlocks(units, nums));
    }

    /**
     * The room's material as one or more chunk texts (≤ {@link #ROOM_PACKET_CHARS}
     * each, at most {@link #MAX_ROOM_PACKETS}): the first carries the price
     * anchor, the last carries the already-published wire lines, evidence runs
     * CHRONOLOGICALLY through all of them so the retelling can follow the
     * narrative. Draws from the UNION of every matching unit (the room speaks
     * name AND ticker — "Outlook" chatter belongs to the OTLK DD), deduplicated
     * by mention identity. Newest evidence is kept when the total budget is
     * exceeded.
     */
    static List<String> roomBlocks(List<SubjectUnit> units, Map<String, Integer> nums) {
        String head = "ROOM EVIDENCE (r/wallstreetbetsGER, unverified)" + mark(nums, "room");
        if (units == null || units.isEmpty()) {
            return List.of(head + ":\n  (the room has not taken this subject up)\n");
        }
        SubjectUnit primary = units.get(0);
        List<StringBuilder> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder(ROOM_PACKET_CHARS + 256);
        cur.append(head).append(":\n");
        chunks.add(cur);
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
        int budget = ROOM_PACKET_CHARS * MAX_ROOM_PACKETS;
        while (start > 0 && budget - evidence.get(start - 1).snippet().length() - 24 >= 0) {
            start--;
            budget -= evidence.get(start).snippet().length() + 24;
        }
        if (start > 0) cur.append("  (").append(start).append(" older mentions omitted)\n");
        for (SubjectUnit.EvidenceRef ref : evidence.subList(start, evidence.size())) {
            String line = "  - [" + STAMP.format(Instant.ofEpochSecond(ref.addedAtEpoch()))
                    + "] " + ref.snippet() + "\n";
            if (cur.length() + line.length() > ROOM_PACKET_CHARS) {
                cur = new StringBuilder(ROOM_PACKET_CHARS + 256);
                cur.append(head).append(" (continued):\n");
                chunks.add(cur);
            }
            cur.append(line);
        }
        // Wire lines: union over all units, chronological, newest MAX_WIRE_LINES.
        List<SubjectUnit.UnitHeadline> headlines = new ArrayList<>();
        for (SubjectUnit u : units) headlines.addAll(u.headlines());
        headlines.sort(java.util.Comparator.comparingLong(SubjectUnit.UnitHeadline::atEpoch));
        if (!headlines.isEmpty()) {
            cur.append("WIRE LINES ALREADY PUBLISHED FOR THIS SUBJECT:\n");
            int from = Math.max(0, headlines.size() - MAX_WIRE_LINES);
            for (SubjectUnit.UnitHeadline h : headlines.subList(from, headlines.size())) {
                cur.append("  - [").append(STAMP.format(Instant.ofEpochSecond(h.atEpoch())))
                        .append("] ").append(h.text()).append('\n');
            }
        }
        List<String> out = new ArrayList<>(chunks.size());
        for (StringBuilder c : chunks) out.add(c.toString());
        return out;
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
            for (String rawPara : body.strip().split("\n\\s*\n")) {
                String para = rawPara.strip();
                if (para.isEmpty()) continue;
                String norm = para.replaceAll("\\s+", " ");
                if (!seenParagraphs.add(norm)) {
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
                        String cleanRow = row.strip().replaceAll(" ?\\[\\d{1,2}]", "");
                        if (!cleanRow.isEmpty()) table.append(cleanRow).append('\n');
                    }
                    sb.append(table).append('\n');
                    continue;
                }
                StringBuilder kept = new StringBuilder(para.length());
                for (String sentence : DeepDiveFactCheck.sentences(para)) {
                    String sNorm = sentence.strip().replaceAll("\\s+", " ");
                    if (!thesis && sNorm.length() >= CROSS_SECTION_SENTENCE_CHARS
                            && !seenSentences.add(sNorm)) {
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
                String clean = kept.toString().replaceAll(" ?\\[\\d{1,2}]", "")
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

    private static final Pattern MARKER_NUM = Pattern.compile("\\[(\\d{1,2})]");

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
        Matcher mt = Pattern.compile(" ?\\[(\\d{1,2})]").matcher(report);
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
            if (cited != null && e.getKey().startsWith("news:")
                    && !cited.contains(e.getValue())) {
                continue;
            }
            sb.append("- [").append(e.getValue()).append("] ")
                    .append(sourceLabel(m, e.getKey(), de)).append('\n');
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
            case "sector":
                return (de
                        ? "Sektor- und Makro-Kontext - Yahoo Sektor-ETFs, TradingView/ForexFactory-"
                                + "Wirtschaftskalender, Notenbank-Termine"
                        : "Sector and macro context - Yahoo sector ETFs, TradingView/ForexFactory "
                                + "economic calendars, central-bank dates");
            case "marketbeat":
                return "MarketBeat" + (de
                        ? " - Analysten-Aktionshistorie und US-Short-Quote"
                        : " - analyst action history and US short stats");
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
            case "whispers":
                return "EarningsWhispers" + (de
                        ? " - Konsensschätzungen zum nächsten Bericht"
                        : " - consensus estimates for the next report");
            case "room":
                return "r/wallstreetbetsGER" + (de ? " - Diskussion im Käfig (unverifiziert)"
                        : " - the room's discussion (unverified)");
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
