package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.WeatherReportFinishedEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.WeatherReportStartedEvent;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportArchive;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord;
import dev.langchain4j.model.chat.ChatModel;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the daily Wetterbericht — since the Abendausgabe cut (2026-07-13)
 * a full evening edition: once per day, at the configurable local
 * {@code weather.report-time}, the day's wire output PLUS the frozen world
 * view ({@link WeatherStatsCollector} — markets, macro, ad-hocs, movers,
 * social, crypto, depth, outlook, colour) is written into one report.
 *
 * <p><b>Map-reduce for the wire, deterministic blocks for the numbers.</b>
 * gemma4's context cannot hold a full day, so the day's headlines (the
 * permanent {@link HeadlineArchive} is the source — it survives restarts,
 * unlike any in-memory state) are grouped by subject, packed into batches,
 * and each batch is condensed by a foreman call ({@code weather-digest});
 * the frozen stats are formatted deterministically ({@link WeatherMaterial}
 * — a 4B model must never re-tell numbers it could mangle).
 *
 * <p><b>The Redaktion, the KI-DD workspace pattern (2026-07-13 late):</b> the
 * five forecast sections (Großwetterlage / Morgen / Mittag / Abend / Ausblick)
 * are a SECTION WORKSPACE assembled deterministically — the system sets the
 * headings, the model only ever writes ONE section body from THAT section's
 * material shelf ({@link WeatherMaterial#sectionShelves}), the deterministic
 * examiner ({@link DeepDiveFactCheck}) reconciles every figure and date
 * against the shelf (hard survivors cost the sentence), and a challenger
 * ({@code weather-challenge}) grills each section until it STANDS (stalemate
 * and runaway guards, the DD loop verbatim). The old draft→QA→final 3-pass
 * could not catch a spliced figure or a speculating outlook — this replaces
 * it. Every call still funnels through the shared {@link LlmGate}, so the
 * live wire is never starved, merely interleaved.
 *
 * <p>Scheduling is wall-clock (the interval-based {@code JitteredScheduler}
 * doesn't fit a daily local time): one single-thread daemon executor arms the
 * next occurrence, re-arming after every run and on a config change. If the
 * app starts after the report time and the day has no report yet, a catch-up
 * run fires shortly after startup — the archive carries the day regardless of
 * when the process was up.
 */
@Singleton
public class WeatherReportService {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherReportService.class);

    /** Max chars of wire lines per foreman (map) call — comfortably inside num_ctx with the prompt. */
    private static final int MAX_BATCH_CHARS = 4000;
    /** Max chars of one WINDOW's condensed wire; above this the digests fold once more. */
    private static final int MAX_WINDOW_WIRE_CHARS = 1600;
    /** Boot catch-up delay when the report time already passed — lets Ollama finish warming. */
    private static final long CATCH_UP_DELAY_SECONDS = 90;
    /** {@link #looksLikeReport} floor: real evening editions run well past this. */
    private static final int MIN_STRUCTURED_CHARS = 400;
    /** dpa-AFX press items condensed deterministically into the material. */
    private static final int MAX_PRESS_ITEMS = 3;
    /** Budget for the whole condensed wire (map-reduce output across windows). */
    private static final int MAX_WIRE_CHARS = 4500;
    /** Absolute floor under which a whiffed draft publishes as nothing. */
    private static final int MIN_REPORT_CHARS = 80;

    /**
     * The forecast skeleton's heading literals — set DETERMINISTICALLY at
     * assembly (the KI-DD convention): the model writes bodies only, so a
     * drifting or invented heading is mechanically impossible. Ordinals match
     * {@link WeatherMaterial}'s shelf indices and {@link WeatherCharts}' anchors.
     */
    static final List<String> SECTIONS_DE = List.of(
            "Großwetterlage", "Der Morgen", "Der Mittag", "Der Abend", "Der Ausblick");
    static final List<String> SECTIONS_EN = List.of(
            "The Big Picture", "The Morning", "The Midday", "The Evening", "The Outlook");

    /** A weather section is ONE short paragraph — the DD's length contract scaled down. */
    static final int MIN_SECTION_CHARS = 120;
    static final int MAX_SECTION_CHARS = 1100;
    /** Bold is for the load-bearing few — spans beyond this are unwrapped mechanically. */
    static final int MAX_BOLD_SPANS = 4;
    /** Pure runaway guard — the challenge loop normally ends on STANDS/stalemate. */
    private static final int CHALLENGE_ROUNDS_BACKSTOP = 6;
    /**
     * Weave runaway guard, NOT a curation cap — hits are logged loudly. 40
     * halved a real US evening (live smoke 2: 97 stories, 57 unwoven) against
     * the uncap mandate; a full day's press peaks around ~100 stories per
     * window, so anything beyond this is a malfunctioning feed, not news.
     */
    private static final int WEAVE_GROUPS_BACKSTOP = 150;
    /** Output reservation of the deep-dive model handle (its numPredict). */
    private static final int NUM_PREDICT_RESERVE = 3584;
    private static final double CHARS_PER_TOKEN = 3.0;

    /** Day-part window bounds (local hour) — must match the collector's dayparts. */
    private static final int MIDDAY_FROM = 12;
    private static final int EVENING_FROM = 16;

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final AgentBrain brain;
    private final ChatGateway gateway;
    private final HeadlineArchive headlineArchive;
    private final WeatherReportArchive reportArchive;
    private final WeatherStatsCollector statsCollector;
    private final GlobalConfig config;
    private final ApplicationEventBus eventBus;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "weather-report");
                t.setDaemon(true);
                return t;
            });

    /** The house's shared article digester (prod-wired, absent in unit tests/smoke). */
    private volatile NewsDigester newsDigester;

    @com.google.inject.Inject(optional = true)
    void setNewsDigester(NewsDigester digester) {
        this.newsDigester = digester;
    }

    /** The market memory's event register (prod-wired, absent in unit tests/smoke). */
    private volatile de.bsommerfeld.wsbg.terminal.db.MarketEventArchive marketEventArchive;

    @com.google.inject.Inject(optional = true)
    void setMarketEventArchive(de.bsommerfeld.wsbg.terminal.db.MarketEventArchive archive) {
        this.marketEventArchive = archive;
    }

    /** The daily Fear&Greed history (prod-wired, absent in unit tests/smoke). */
    private volatile de.bsommerfeld.wsbg.terminal.db.FearGreedHistoryArchive fearGreedHistory;

    @com.google.inject.Inject(optional = true)
    void setFearGreedHistoryArchive(de.bsommerfeld.wsbg.terminal.db.FearGreedHistoryArchive archive) {
        this.fearGreedHistory = archive;
    }

    private final AtomicBoolean started = new AtomicBoolean(false);
    private ScheduledFuture<?> pending;
    private long nextRunAtMs;
    private volatile boolean generating;

    @Inject
    public WeatherReportService(AgentBrain brain, LlmGate llmGate,
            HeadlineArchive headlineArchive, WeatherReportArchive reportArchive,
            WeatherStatsCollector statsCollector, GlobalConfig config,
            ApplicationEventBus eventBus) {
        this.brain = brain;
        this.gateway = new ChatGateway(brain, llmGate);
        this.headlineArchive = headlineArchive;
        this.reportArchive = reportArchive;
        this.statsCollector = statsCollector;
        this.config = config;
        this.eventBus = eventBus;
    }

    /** Arms the daily schedule. Called from AppMain once the window is up; idempotent. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        armSchedule();
    }

    /** Re-arms after a report-time change from the widget. No-op before {@link #start()}. */
    public synchronized void rearm() {
        if (started.get()) armSchedule();
    }

    public boolean isGenerating() {
        return generating;
    }

    /** Epoch millis of the next scheduled run — the widget's countdown target. */
    public synchronized long nextRunEpochMs() {
        return nextRunAtMs;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private synchronized void armSchedule() {
        if (pending != null) pending.cancel(false);
        long now = System.currentTimeMillis();
        nextRunAtMs = computeNextRunMs(now);
        pending = scheduler.schedule(this::runDue,
                Math.max(1_000, nextRunAtMs - now), TimeUnit.MILLISECONDS);
        LOG.info("Wetterbericht scheduled for {}", Instant.ofEpochMilli(nextRunAtMs));
    }

    private long computeNextRunMs(long nowMs) {
        LocalTime time = reportTime();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        boolean todayDone = reportArchive.byDate(today.toString()).isPresent();
        if (!todayDone) {
            long todayAt = today.atTime(time).atZone(zone).toInstant().toEpochMilli();
            // Missed slot (app started late, or the time was moved into the past):
            // catch up shortly — the archive carries the day either way.
            return nowMs < todayAt ? todayAt : nowMs + CATCH_UP_DELAY_SECONDS * 1_000;
        }
        return today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli();
    }

    private LocalTime reportTime() {
        try {
            return LocalTime.parse(config.getWeather().getReportTime());
        } catch (Exception e) {
            return LocalTime.of(20, 0);
        }
    }

    private void runDue() {
        try {
            generateForToday();
        } catch (Throwable t) {
            LOG.warn("Wetterbericht generation failed: {}", t.getMessage(), t);
        } finally {
            armSchedule();
        }
    }

    /** Visible for the smoke path/tests; normally only the scheduler calls this. */
    void generateForToday() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        String date = today.toString();
        if (reportArchive.byDate(date).isPresent()) return;

        long dayStart = today.atStartOfDay(zone).toEpochSecond();
        List<HeadlineRecord> headlines = headlineArchive.all().stream()
                .filter(r -> r.createdAt() >= dayStart)
                .toList();
        if (headlines.isEmpty()) {
            // The edition exists independently of the room (user mandate
            // 2026-07-14): a silent cage — Reddit outage, dead day — still
            // yields a report from the world data (press review, calendar,
            // sectors, markets); the room's silence is itself a finding.
            LOG.info("Wetterbericht {}: no headlines today — writing from world data alone.",
                    date);
        }

        generating = true;
        eventBus.post(new WeatherReportStartedEvent(date));
        boolean success = false;
        try {
            long t0 = System.currentTimeMillis();
            // Freeze the world FIRST — the passes are written FROM the frozen
            // numbers, so the text and the shown stats can never disagree.
            String pressText = WeatherMaterial.pressText(
                    statsCollector.pressItems(MAX_PRESS_ITEMS));
            WeatherStatsCollector.Stats stats = statsCollector.collect(headlines, pressText);
            String text = writeReport(headlines, stats);
            if (text == null || text.isBlank()) {
                LOG.warn("Wetterbericht {}: model returned no usable text; giving up for this run.", date);
                return;
            }
            int important = (int) headlines.stream()
                    .filter(r -> r.highlight() == HeadlineHighlight.IMPORTANT).count();
            // The figure layer: deterministic SVGs from the frozen stats,
            // section-anchored, frozen with the record (the KI-DD pattern) —
            // a whiffed chart build never blocks the edition.
            List<WeatherReportRecord.ChartStat> charts = List.of();
            try {
                charts = new WeatherCharts(brain.getUserLanguage().code())
                        .build(stats, headlines, zone, fearGreedSeries(today));
            } catch (Exception e) {
                LOG.warn("Wetterbericht chart build failed: {}", e.getMessage());
            }
            reportArchive.append(new WeatherReportRecord(date, Instant.now().getEpochSecond(),
                    text, brain.getUserLanguage().code(), headlines.size(), important,
                    stats.indices(), stats.tickers(), stats.news(), stats.sentiment(),
                    stats.world(), charts));
            success = true;
            LOG.info("Wetterbericht {} written: {} headlines → {} chars in {} s.",
                    date, headlines.size(), text.length(), (System.currentTimeMillis() - t0) / 1_000);
        } finally {
            generating = false;
            // Re-arm BEFORE announcing: the finish push must already carry
            // tomorrow's countdown target, not the just-elapsed slot.
            if (started.get()) armSchedule();
            eventBus.post(new WeatherReportFinishedEvent(date, success));
        }
    }

    /** Calendar window of the Fear&Greed regime band (~60 days ≈ 40 trading points). */
    private static final int FG_REGIME_DAYS = 60;

    /**
     * The last ~{@value #FG_REGIME_DAYS} days of archived Fear&amp;Greed
     * scores, chronological (today last, non-trading days simply absent) —
     * the regime band's series. Empty when the history archive isn't wired.
     */
    private List<Integer> fearGreedSeries(java.time.LocalDate today) {
        var archive = fearGreedHistory;
        if (archive == null) return List.of();
        List<Integer> out = new java.util.ArrayList<>();
        for (int back = FG_REGIME_DAYS - 1; back >= 0; back--) {
            archive.byDate(today.minusDays(back).toString())
                    .ifPresent(r -> out.add((int) Math.round(r.score())));
        }
        return out;
    }

    /**
     * The Redaktion: window-split wire digests → five section shelves → per
     * section author → deterministic examiner → challenge/revise until STANDS
     * → deterministic assembly with system-set headings and the formatting
     * belt. A whiffed section degrades to its honest literal, never to a
     * missing heading (the charts anchor by ordinal).
     */
    private String writeReport(List<HeadlineRecord> headlines, WeatherStatsCollector.Stats stats) {
        String lang = brain.getUserLanguage().code();
        boolean de = "de".equalsIgnoreCase(lang);
        ChatModel model = brain.getDeepDiveModel() != null
                ? brain.getDeepDiveModel() : brain.getProseModel();
        if (model == null) return "";

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        String digestSys = localized("weather-digest", lang);
        String[] wires = {
                windowWire(headlines, 0, MIDDAY_FROM, digestSys, zone),
                windowWire(headlines, MIDDAY_FROM, EVENING_FROM, digestSys, zone),
                windowWire(headlines, EVENING_FROM, 24, digestSys, zone)};
        List<WeatherMaterial.WorldSignal> worldSignals = triageWorldSignals(
                stats.world() == null ? null : stats.world().worldSignals(), lang);
        String[] shelves = WeatherMaterial.sectionShelves(stats, today,
                wires[0], wires[1], wires[2], worldSignals);
        // Market memory (deterministic): today's event classes with their house
        // base rates + attributed literature priors onto the EVENING shelf —
        // "wie reagierte der Markt historisch auf so eine Nachricht" beside the
        // day's disclosures; the block's discipline line keeps it a prior.
        try {
            String memoryBlock = MarketMemoryBriefing.dayBlock(marketEventArchive, today, de);
            if (memoryBlock != null && shelves.length > 3) {
                shelves[3] = shelves[3] == null || shelves[3].isBlank()
                        ? memoryBlock : shelves[3] + "\n" + memoryBlock;
            }
        } catch (Exception e) {
            LOG.debug("[WEATHER] market-memory block failed: {}", e.getMessage());
        }
        List<String> headings = de ? SECTIONS_DE : SECTIONS_EN;

        String sectionSys = localized("weather-section", lang);
        String challengeSys = localized("weather-challenge", lang);
        String reviseSys = localized("weather-revise", lang);
        String weaveSys = localized("weather-weave", lang);
        // The DD's same-story arbiter prompt VERBATIM — it is desk-generic
        // (woven source vs new candidate), so the weather weave reuses it
        // instead of growing a twin pair.
        String samestorySys = localized("deepdive-samestory", lang);

        // The window's press stories for the weave loop — every item is
        // considered INDIVIDUALLY by the model (user mandate 2026-07-14: no
        // information lost to fixed caps; the DD's weave pattern), grouped by
        // story so ten outlets' versions of one release cost one step.
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat> allPress =
                stats.world() == null ? List.of() : stats.world().pressReview();
        String header = (de ? "ABENDAUSGABE (Wetterbericht) vom "
                : "EVENING EDITION (Wetterbericht), ") + today + "\n\n";

        String[] bodies = new String[WeatherMaterial.SECTION_COUNT];
        String previousThought = null;
        int written = 0;
        for (int idx = 0; idx < bodies.length; idx++) {
            long t0 = System.currentTimeMillis();
            // Window sections weave their window's press stories item by item;
            // Großwetterlage and Ausblick author from their shelves alone.
            int window = idx - WeatherMaterial.SEC_MORNING;
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat> press =
                    window >= 0 && window <= 2
                            ? WeatherMaterial.pressInWindow(allPress, window) : List.of();
            try {
                bodies[idx] = writeSection(model, sectionSys, challengeSys, reviseSys,
                        weaveSys, samestorySys, headerWithThought(header, previousThought),
                        headings.get(idx), shelves[idx], press, de);
            } catch (Exception e) {
                LOG.warn("Wetterbericht section '{}' failed: {}", headings.get(idx),
                        e.getMessage());
            }
            if (bodies[idx] != null) {
                written++;
                // The red thread: the next section's author sees where the
                // previous one ended (the DD convention).
                previousThought = DeepDiveService.lastSentence(bodies[idx]);
            }
            LOG.info("Wetterbericht section '{}' {} in {} s.", headings.get(idx),
                    bodies[idx] != null ? "stands" : "empty (honest literal)",
                    (System.currentTimeMillis() - t0) / 1_000);
        }
        if (written == 0) return "";
        return assemble(headings, bodies, de, nameCatalog(headlines, stats.tickers()));
    }

    /**
     * The weave pass-case sentinel: the prompt licenses answering with the
     * single word UNCHANGED instead of re-emitting the whole section (~1k
     * saved output tokens per pass). Only an exact standalone match counts —
     * a body that merely CONTAINS the word is a normal weave result.
     */
    static boolean isUnchangedSentinel(String reply) {
        if (reply == null) return false;
        String t = reply.strip();
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t.equalsIgnoreCase("UNCHANGED") || t.equalsIgnoreCase("UNVERÄNDERT");
    }

    // --- fishing-net world-signal triage (2026-07-15) -----------------------

    private static final Pattern WORLD_TRIAGE_OBJ = Pattern.compile("\\{[^{}]*}");
    private static final Pattern WORLD_TRIAGE_I = Pattern.compile("\"i\"\\s*:\\s*(\\d+)");
    private static final Pattern WORLD_TRIAGE_REL =
            Pattern.compile("\"relevant\"\\s*:\\s*(true|false)");
    private static final int WORLD_TRIAGE_BATCH = 10;

    /**
     * The fishing-net relevance triage: every frozen world signal is judged by
     * the model ("does this plausibly matter for TODAY's market report?")
     * before it may reach a shelf — ingestion is uncurated, the shelves are
     * guarded. Deliberately FAIL-CLOSED per unjudged signal (the opposite of
     * the DD's news triage): the frozen record keeps everything for the UI,
     * but an unjudged Blaulicht line must never flood the report's scarce
     * shelf space. A whiffed triage costs its batch's shelf lines, never the
     * report.
     */
    private List<WeatherMaterial.WorldSignal> triageWorldSignals(
            WeatherReportRecord.WorldSignals signals, String lang) {
        List<WeatherMaterial.WorldSignal> candidates =
                WeatherMaterial.worldSignalCandidates(signals);
        if (candidates.isEmpty()) return List.of();
        ChatModel judge = brain.getAgentModel();
        if (judge == null) return List.of();
        String sys = localized("weather-worldtriage", lang);
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        for (int from = 0; from < candidates.size(); from += WORLD_TRIAGE_BATCH) {
            List<WeatherMaterial.WorldSignal> batch = candidates.subList(from,
                    Math.min(candidates.size(), from + WORLD_TRIAGE_BATCH));
            StringBuilder list = new StringBuilder(768);
            for (int i = 0; i < batch.size(); i++) {
                list.append(i + 1).append(". ").append(batch.get(i).line()).append('\n');
            }
            // A zero-parse reply is a whiffed judge call, not a verdict — one
            // retry before the batch falls closed (live smoke 2026-07-15: two
            // of seven batches whiffed and 20 real signals were lost).
            for (int attempt = 1; attempt <= 2; attempt++) {
                int parsed = 0;
                try {
                    String reply = gateway.chat(judge, sys, "SIGNALS:\n" + list);
                    Matcher obj = WORLD_TRIAGE_OBJ.matcher(reply == null ? "" : reply);
                    while (obj.find()) {
                        String o = obj.group();
                        Matcher iM = WORLD_TRIAGE_I.matcher(o);
                        if (!iM.find()) continue;
                        int i = Integer.parseInt(iM.group(1));
                        if (i < 1 || i > batch.size()) continue;
                        Matcher relM = WORLD_TRIAGE_REL.matcher(o);
                        if (relM.find() && Boolean.parseBoolean(relM.group(1))) {
                            keep.add(batch.get(i - 1).i());
                        }
                        parsed++;
                    }
                } catch (Exception e) {
                    LOG.warn("[WEATHER] world-signal triage batch failed: {}{}",
                            e.getMessage(), attempt == 1 ? " — retrying" : " — fail-closed.");
                }
                if (parsed > 0) break;
                if (attempt == 2) {
                    LOG.warn("[WEATHER] world-signal triage batch yielded no verdicts "
                            + "twice — its {} signal(s) stay off the shelves (fail-closed).",
                            batch.size());
                }
            }
        }
        List<WeatherMaterial.WorldSignal> survivors = candidates.stream()
                .filter(c -> keep.contains(c.i())).toList();
        LOG.info("[WEATHER] world signals: {} candidate(s), {} relevant after triage.",
                candidates.size(), survivors.size());
        return survivors;
    }

    /**
     * The ticker→display-name catalog for {@link #tickersToNames}: the day's
     * top tickers PLUS every named subject of every archived headline — the
     * capped top-8 list alone let "KOSPI.KS" and "SKHYV" reach the prose raw
     * in the first live run.
     */
    static List<WeatherReportRecord.TickerStat> nameCatalog(List<HeadlineRecord> headlines,
            List<WeatherReportRecord.TickerStat> top) {
        Map<String, String> names = new LinkedHashMap<>();
        for (WeatherReportRecord.TickerStat t : top) {
            if (t.ticker() != null && t.name() != null && !t.name().isBlank()) {
                names.putIfAbsent(t.ticker().toUpperCase(Locale.ROOT), t.name());
            }
        }
        for (HeadlineRecord r : headlines) {
            for (de.bsommerfeld.wsbg.terminal.db.HeadlineSubject s : r.subjects()) {
                if (s.ticker() != null && !s.ticker().isBlank()
                        && s.name() != null && !s.name().isBlank()) {
                    names.putIfAbsent(s.ticker().toUpperCase(Locale.ROOT), s.name());
                }
            }
        }
        return names.entrySet().stream()
                .map(e -> new WeatherReportRecord.TickerStat(e.getKey(), e.getValue(),
                        0, 0, null, null, null, null, null))
                .toList();
    }

    /**
     * One section through the desk's loop (the KI-DD's {@code writeSection},
     * weather-sized): author (one retry) → deterministic examine-and-repair →
     * challenge/revise until the section STANDS, the objections stall, or a
     * revision stops changing the text. Returns {@code null} for an empty
     * shelf or a substance-less result — the typesetter then sets the honest
     * literal.
     */
    private String writeSection(ChatModel model, String sectionSys, String challengeSys,
            String reviseSys, String weaveSys, String samestorySys, String header,
            String heading, String shelf,
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat> press,
            boolean de) {
        if (WeatherMaterial.shelfEmpty(shelf)) return null;

        String body = null;
        for (int attempt = 1; attempt <= 2 && body == null; attempt++) {
            String raw = clean(gateway.chat(model, sectionSys,
                    authorMessage(header, heading, shelf, sectionSys.length())));
            if (raw != null && raw.strip().length() >= MIN_SECTION_CHARS / 2) {
                body = raw.strip();
            } else {
                LOG.warn("Wetterbericht section '{}' author attempt {} whiffed ({} chars).",
                        heading, attempt, raw == null ? 0 : raw.length());
            }
        }
        if (body == null) return null;
        body = examineAndRepair(model, reviseSys, header, heading, shelf, de, body).body();
        body = weavePress(model, weaveSys, reviseSys, samestorySys, header, heading, shelf,
                press, de, body);
        body = enforceLength(model, reviseSys, header, heading, shelf, body);

        String previousObjections = null;
        for (int round = 1; round <= CHALLENGE_ROUNDS_BACKSTOP; round++) {
            List<String> objections = challenge(model, challengeSys, header, heading, body, shelf);
            if (objections.isEmpty()) {
                LOG.info("Wetterbericht section '{}' stands after {} challenge round(s).",
                        heading, round - 1);
                break;
            }
            String normalized = String.join("\n", objections).replaceAll("\\s+", " ");
            if (normalized.equals(previousObjections)) {
                LOG.info("Wetterbericht section '{}': identical objections twice — stalemate.",
                        heading);
                break;
            }
            previousObjections = normalized;
            String revised = revise(model, reviseSys, header, heading, body, shelf,
                    String.join("\n", objections));
            if (revised == null || revised.isBlank()) {
                LOG.warn("Wetterbericht section '{}' revision whiffed — keeping the draft.",
                        heading);
                break;
            }
            String before = body.strip();
            body = examineAndRepair(model, reviseSys, header, heading, shelf, de,
                    revised.strip()).body();
            if (body.strip().equals(before)) {
                LOG.info("Wetterbericht section '{}': revision changed nothing — converged.",
                        heading);
                break;
            }
            if (round == CHALLENGE_ROUNDS_BACKSTOP) {
                LOG.warn("Wetterbericht section '{}' hit the challenge backstop ({}).",
                        heading, CHALLENGE_ROUNDS_BACKSTOP);
            }
        }

        body = capBoldSpans(body, MAX_BOLD_SPANS);
        return body.strip().length() < MIN_SECTION_CHARS ? null : body.strip();
    }

    /**
     * The press weave loop — the DD's weave pattern on the evening edition
     * (user mandate 2026-07-14: nothing lost to fixed caps; the model
     * considers every article INDIVIDUALLY and works it in iteratively, each
     * step examined afterwards). Items are grouped by story first (ten
     * outlets' versions of one release = ONE step — the DD wave-5 lesson
     * against quadratic churn). A step that returns the standing text
     * verbatim costs nothing further; a changed body goes through the
     * deterministic examiner against the shelf PLUS the story (so the
     * story's own figures reconcile).
     */
    private String weavePress(ChatModel model, String weaveSys, String reviseSys,
            String samestorySys, String header, String heading, String shelf,
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat> press,
            boolean de, String body) {
        if (press == null || press.isEmpty() || body == null) return body;
        List<List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat>> groups =
                groupStories(press);
        LOG.info("Wetterbericht section '{}': weaving {} press stories ({} items).",
                heading, groups.size(), press.size());
        int step = 0;
        int skipped = 0;
        ChatModel arbiter = brain.getAgentModel();
        List<String> wovenStories = new ArrayList<>();
        for (var group : groups) {
            step++;
            if (step > WEAVE_GROUPS_BACKSTOP) {
                LOG.warn("Wetterbericht section '{}': weave hit the {}-story runaway backstop"
                        + " — {} stories not woven.", heading, WEAVE_GROUPS_BACKSTOP,
                        groups.size() - WEAVE_GROUPS_BACKSTOP);
                break;
            }
            String story = pressStoryBlock(group);
            try {
                // Same-story gate, the DD's arbiter pattern verbatim: token
                // similarity to an ALREADY WOVEN story is only the SUSPICION —
                // the arbiter reads both and rules re-spin vs own news value.
                // A ruled re-spin is never read out individually (user mandate
                // 2026-07-14, ported from the DD) — the weave step is saved.
                String suspect = mostSimilarWoven(story, wovenStories);
                if (suspect != null
                        && sameStoryVerdict(arbiter, samestorySys, suspect, story)) {
                    skipped++;
                    LOG.info("Wetterbericht section '{}' weave step {}: arbiter ruled the "
                            + "story ({} item(s)) a re-spin of an already woven one — skipped.",
                            heading, step, group.size());
                    continue;
                }
                String fixed = header + "SECTION: ## " + heading + "\n\nSTANDING TEXT:\n" + body
                        + "\n\nPRESS STORY of this window (work in or return unchanged):\n";
                String reply = clean(gateway.chat(model, weaveSys,
                        fixed + budgeted(story, weaveSys.length() + fixed.length())));
                if (reply == null || reply.isBlank()) continue;
                wovenStories.add(story);
                // The UNCHANGED sentinel (2026-07-15): a pass-case no longer
                // re-emits the whole section — one word instead of ~1k tokens
                // of generation, and no examiner pass on an unchanged body.
                if (isUnchangedSentinel(reply) || reply.strip().equals(body.strip())) continue;
                Repair repaired = examineAndRepair(model, reviseSys, header, heading,
                        shelf + "\n\n" + story, de, reply.strip());
                body = repaired.body();
                // RE-KNOCK (the DD's examiner-as-trigger, landed 2026-07-14
                // late and ported same night): a HARD figure/date finding on a
                // weave step whose story was only known by title+teaser means
                // the figure may live in the article BODY the desk never read.
                // Fetch the story's full-text digests NOW (the shared
                // NewsDigester session cache makes repeats free — DD/wire/
                // weather read the same instance) and weave the story ONCE
                // more with the enriched material. One re-knock per story.
                if (repaired.hadHard()) {
                    String extra = reknockDigests(group);
                    if (!extra.isEmpty()) {
                        LOG.info("Wetterbericht section '{}' weave step {}: hard finding — "
                                + "re-knock with full-text digests.", heading, step);
                        String enriched = story + "\n" + extra;
                        // Rebuilt with the CURRENT standing text — the first
                        // weave's integration must survive the re-knock.
                        String refixed = header + "SECTION: ## " + heading
                                + "\n\nSTANDING TEXT:\n" + body
                                + "\n\nPRESS STORY of this window (work in or return unchanged):\n";
                        String rewoven = clean(gateway.chat(model, weaveSys,
                                refixed + budgeted(enriched, weaveSys.length() + refixed.length())));
                        if (rewoven != null && !rewoven.isBlank()) {
                            body = examineAndRepair(model, reviseSys, header, heading,
                                    shelf + "\n\n" + enriched, de, rewoven.strip()).body();
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Wetterbericht section '{}' weave step {} failed: {}",
                        heading, step, e.getMessage());
            }
        }
        if (skipped > 0) {
            LOG.info("Wetterbericht section '{}': {} of {} stories ruled re-spins by the "
                    + "arbiter.", heading, skipped, groups.size());
        }
        return body;
    }

    /** At most this many full-text reads per re-knocked story (the DD's bound). */
    private static final int MAX_REKNOCK_DIGESTS = 3;

    /**
     * The re-knock's full-text fetch: the story members' article digests via
     * the SHARED {@link NewsDigester} (same instance and session cache as the
     * wire and the KI-DD — an article is only ever read once house-wide).
     * Empty when no member carries a link, the digester is absent, or every
     * read misses (paywall/consent shell).
     */
    private String reknockDigests(
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat> group) {
        NewsDigester digester = newsDigester;
        if (digester == null) return "";
        StringBuilder extra = new StringBuilder(256);
        int fetched = 0;
        for (var item : group) {
            if (fetched >= MAX_REKNOCK_DIGESTS) break;
            if (item.link() == null || item.link().isBlank()) continue;
            String digest = digester.digestNow(item.link());
            if (digest == null || digest.isBlank()) continue;
            extra.append("  - ").append(digest.replace('\n', ' ').strip()).append('\n');
            fetched++;
        }
        if (extra.length() == 0) return "";
        return "REQUESTED FULL-TEXT DIGESTS (the desk asked for the missing sources):\n" + extra;
    }

    /**
     * The already-woven story most similar to the candidate — null when none
     * crosses the suspicion threshold (then no arbiter call is spent). The
     * DD's {@code mostSimilarWoven} verbatim, weather-typed.
     */
    private static String mostSimilarWoven(String candidate, List<String> wovenStories) {
        Set<String> candidateWords = storyWords(candidate);
        String best = null;
        double bestScore = 0;
        for (String prior : wovenStories) {
            double s = jaccard(candidateWords, storyWords(prior));
            if (s > bestScore) {
                bestScore = s;
                best = prior;
            }
        }
        return bestScore >= WEAVE_SUSPICION_SIMILARITY ? best : null;
    }

    /**
     * The arbiter's same-story verdict on a suspicious weave candidate — the
     * DD's {@code sameStoryVerdict} verbatim (same prompt, same fail-open:
     * a whiffed call weaves the story; losing coverage is the worse error).
     */
    private boolean sameStoryVerdict(ChatModel arbiter, String prompt, String woven,
            String candidate) {
        if (arbiter == null) return false;
        try {
            String reply = gateway.chat(arbiter, prompt,
                    "WOVEN SOURCE:\n" + woven + "\n\nNEW CANDIDATE:\n" + candidate);
            return reply != null && DUPLICATE_TRUE.matcher(reply).find();
        } catch (Exception e) {
            return false;
        }
    }

    private static final Pattern DUPLICATE_TRUE =
            Pattern.compile("\"duplicate\"\\s*:\\s*true");

    /**
     * A candidate this token-similar to an ALREADY WOVEN story is a SUSPICION,
     * never a verdict (the DD's calibration: the mechanical check is the smoke
     * detector, the AI the judge).
     */
    private static final double WEAVE_SUSPICION_SIMILARITY = 0.35;

    /** One story group rendered as the weave step's material block. */
    static String pressStoryBlock(
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat> group) {
        StringBuilder sb = new StringBuilder("PRESS STORY (attributed — the press's reading):");
        for (var p : group) {
            sb.append("\n- ");
            if (p.time() != null) sb.append(p.time()).append(' ');
            sb.append('[').append(p.source()).append("] ").append(p.title());
            if (p.teaser() != null && !p.teaser().isBlank()) {
                sb.append(" — ").append(p.teaser().strip());
            }
        }
        return sb.toString();
    }

    /**
     * Deterministic story grouping — the DD's {@code groupStoryBlocks}
     * mechanics: greedy with TRANSITIVE chaining (a headline joins the first
     * group where ANY member reaches the similarity, so a story chain A~B~C
     * folds even when A and C differ), tokens diacritics-normalized, generic
     * market words dropped (they would glue unrelated headlines together).
     * Order kept — a group sits at its first member's position, no item is
     * ever dropped.
     */
    static List<List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat>>
            groupStories(List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat> press) {
        List<List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat>> groups =
                new ArrayList<>();
        List<List<Set<String>>> groupTokens = new ArrayList<>();
        for (var item : press) {
            Set<String> words = storyWords(item.title());
            int home = -1;
            outer:
            for (int g = 0; g < groups.size(); g++) {
                for (Set<String> member : groupTokens.get(g)) {
                    if (jaccard(words, member) >= STORY_GROUP_SIMILARITY) {
                        home = g;
                        break outer;
                    }
                }
            }
            if (home < 0) {
                groups.add(new ArrayList<>(List.of(item)));
                groupTokens.add(new ArrayList<>(List.of(words)));
            } else {
                groups.get(home).add(item);
                groupTokens.get(home).add(words);
            }
        }
        return groups;
    }

    /** Title token-Jaccard at or above this = the same story re-reported (DD calibration). */
    private static final double STORY_GROUP_SIMILARITY = 0.5;

    /** Tokens every market headline carries — they say nothing about the story. */
    private static final Set<String> GENERIC_PRESS_TOKENS = Set.of(
            "stock", "stocks", "market", "markets", "shares", "aktie", "aktien",
            "borse", "boerse", "wall", "street", "today", "heute");

    private static Set<String> storyWords(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        String normalized = java.text.Normalizer.normalize(
                text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        for (String w : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !GENERIC_PRESS_TOKENS.contains(w)) out.add(w);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String w : a) {
            if (b.contains(w)) inter++;
        }
        return (double) inter / (a.size() + b.size() - inter);
    }

    /**
     * The length gate AFTER the weave loop, BEFORE the challenge: the weave
     * grows the section faster than per-step revisions cut it (live smoke 2:
     * 1105 → 1661 chars over the morning window, and the challenger then
     * burned its rounds on the same length objection into the backstop). ONE
     * focused revise call asks for the cut; if the model still won't, whole
     * sentences are dropped from the END deterministically — the load-bearing
     * story leads a forecast paragraph, the tail is the weakest material.
     */
    private String enforceLength(ChatModel model, String reviseSys, String header,
            String heading, String shelf, String body) {
        if (body == null || body.strip().length() <= MAX_SECTION_CHARS) return body;
        String objection = "E: \"" + headOf(body) + "\" — section over the one-short-paragraph"
                + " contract (" + body.strip().length()
                + " chars) — keep only the load-bearing story, cut to under "
                + MAX_SECTION_CHARS + " characters";
        String revised = revise(model, reviseSys, header, heading, body, shelf, objection);
        if (revised != null && !revised.isBlank()
                && revised.strip().length() <= MAX_SECTION_CHARS) {
            return revised.strip();
        }
        String base = revised != null && !revised.isBlank()
                && revised.strip().length() < body.strip().length()
                ? revised.strip() : body.strip();
        StringBuilder cut = new StringBuilder();
        for (String sentence : DeepDiveFactCheck.sentences(base)) {
            if (cut.length() > 0 && cut.length() + sentence.length() + 1 > MAX_SECTION_CHARS) break;
            if (cut.length() > 0) cut.append(' ');
            cut.append(sentence.strip());
        }
        String out = cut.length() >= MIN_SECTION_CHARS ? cut.toString()
                : base.substring(0, Math.min(base.length(), MAX_SECTION_CHARS)).strip();
        LOG.info("Wetterbericht section '{}': deterministic length cut {} -> {} chars.",
                heading, base.length(), out.length());
        return out;
    }

    /**
     * The deterministic examiner cycle (the DD's {@code examineAndRepair}):
     * inspect against the shelf, let the author fix, re-inspect, and remove
     * the sentences of hard figure/date survivors — a lost sentence beats a
     * spliced figure in the evening edition.
     */
    /** One examiner cycle's outcome: the repaired body plus whether HARD findings occurred. */
    private record Repair(String body, boolean hadHard) {}

    private Repair examineAndRepair(ChatModel model, String reviseSys, String header,
            String heading, String material, boolean de, String body) {
        List<DeepDiveFactCheck.Objection> objections = inspectSection(body, material, de);
        if (objections.isEmpty()) return new Repair(body, false);
        boolean anyHard = objections.stream().anyMatch(DeepDiveFactCheck.Objection::hard);
        LOG.info("Wetterbericht section '{}': examiner raised {} objection(s): {}",
                heading, objections.size(),
                objections.stream().map(o -> o.kind() + " " + o.problem()).toList());
        String revised = revise(model, reviseSys, header, heading, body, material,
                renderObjections(objections));
        if (revised != null && !revised.isBlank()) body = revised.strip();
        List<DeepDiveFactCheck.Objection> hard = inspectSection(body, material, de).stream()
                .filter(DeepDiveFactCheck.Objection::hard).toList();
        if (!hard.isEmpty()) {
            anyHard = true;
            String cut = DeepDiveFactCheck.removeOffendingSentences(body, hard);
            LOG.info("Wetterbericht section '{}': {} unverifiable figure sentence(s) removed "
                    + "({} -> {} chars).", heading, hard.size(), body.length(), cut.length());
            body = cut;
        }
        return new Repair(
                DeepDiveFactCheck.scrubNonMarkerBrackets(DeepDiveFactCheck.scrubResidue(body)),
                anyHard);
    }

    /**
     * The examiner's raster for a weather section: the DD inspection against
     * mixed-locale material (the shelf carries German-formatted blocks AND
     * user-language wire prose), with the DD's length/density contract
     * replaced by the forecast's own one-short-paragraph bounds. No source
     * markers exist in this material, so any {@code [n]} in prose objects.
     */
    static List<DeepDiveFactCheck.Objection> inspectSection(String body, String material,
            boolean de) {
        List<DeepDiveFactCheck.Objection> out = new ArrayList<>(
                DeepDiveFactCheck.inspect(body, material, Set.of(), de, true));
        out.removeIf(o -> o.kind() == DeepDiveFactCheck.Objection.Kind.LENGTH);
        if (body != null && body.strip().length() > MAX_SECTION_CHARS) {
            out.add(new DeepDiveFactCheck.Objection(headOf(body),
                    de ? "Sektion über dem Ein-kurzer-Absatz-Kontrakt ("
                            + body.strip().length()
                            + " Zeichen) - auf die tragende Geschichte kürzen"
                    : "section over the one-short-paragraph contract ("
                            + body.strip().length() + " chars) — cut to the load-bearing story",
                    DeepDiveFactCheck.Objection.Kind.LENGTH));
        }
        return out;
    }

    private List<String> challenge(ChatModel model, String challengeSys, String header,
            String heading, String body, String material) {
        String fixed = header + "SECTION: ## " + heading + "\n\nDRAFT:\n" + body
                + "\n\nMATERIAL (the only admissible evidence):\n";
        String reply = gateway.chat(model, challengeSys,
                fixed + budgeted(material, challengeSys.length() + fixed.length()));
        List<String> out = new ArrayList<>();
        if (reply == null) return out;
        for (String line : reply.split("\n")) {
            String s = line.strip();
            if (s.startsWith("E:")) out.add(s);
            if (out.size() >= 4) break;
        }
        return out;
    }

    private String revise(ChatModel model, String reviseSys, String header, String heading,
            String body, String material, String objections) {
        String fixed = header + "SECTION: ## " + heading + "\n\nCURRENT TEXT:\n" + body
                + "\n\nOBJECTIONS (fix each from the material or REMOVE the claim):\n"
                + objections + "\n\nMATERIAL:\n";
        return clean(gateway.chat(model, reviseSys,
                fixed + budgeted(material, reviseSys.length() + fixed.length())));
    }

    private String authorMessage(String header, String heading, String material,
            int promptChars) {
        String fixed = header + "SECTION TO WRITE: ## " + heading
                + "\n\nMATERIAL (verified blocks — the only admissible evidence):\n";
        return fixed + budgeted(material, promptChars + fixed.length());
    }

    private static String renderObjections(List<DeepDiveFactCheck.Objection> objections) {
        StringBuilder sb = new StringBuilder(256);
        for (DeepDiveFactCheck.Objection o : objections) {
            sb.append("E: \"").append(o.quote()).append("\" — ").append(o.problem()).append('\n');
        }
        return sb.toString();
    }

    private static String headOf(String s) {
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() <= 160 ? flat : flat.substring(0, 157) + "…";
    }

    /** The letterhead plus the previous section's closing thought (the red thread). */
    private static String headerWithThought(String header, String previousThought) {
        if (previousThought == null || previousThought.isBlank()) return header;
        return header + "PREVIOUS SECTION ENDED WITH: " + previousThought.strip() + "\n\n";
    }

    /**
     * Hard context budget for a pass (the DD convention — Ollama truncates a
     * longer prompt silently, which starves a pass into whiffing).
     */
    private String budgeted(String material, int fixedChars) {
        int budget = (int) ((brain.contextTokens() - NUM_PREDICT_RESERVE) * CHARS_PER_TOKEN)
                - fixedChars - 200;
        if (material.length() <= budget) return material;
        int cut = Math.max(budget, 800);
        int nl = material.lastIndexOf('\n', cut);
        if (nl > cut / 2) cut = nl;
        LOG.warn("Wetterbericht material over the context budget ({} > {} chars) — trimmed.",
                material.length(), budget);
        return material.substring(0, cut) + "\n  (material trimmed to the context budget)\n";
    }

    // --- typesetting -------------------------------------------------------

    /**
     * Deterministic assembly: OUR heading literals, honest literals for
     * whiffed sections (the charts anchor by ordinal — a heading may never
     * vanish), then the formatting belt: wire-markup strip, raw wire tickers
     * swapped for the day's display names, German percent spacing.
     */
    static String assemble(List<String> headings, String[] bodies, boolean de,
            List<WeatherReportRecord.TickerStat> tickers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headings.size(); i++) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("## ").append(headings.get(i)).append('\n');
            sb.append(bodies[i] != null && !bodies[i].isBlank()
                    ? bodies[i].strip() : honestLiteral(i, de));
        }
        String out = stripWireMarkup(sb.toString().strip());
        out = tickersToNames(out, tickers);
        if (de) out = out.replaceAll("(?<=\\d)%", " %");
        return out;
    }

    /**
     * What an empty window honestly reads like — never a model call. Since the
     * Reddit-independence cut a window shelf also carries press/calendar/
     * sector material, so this literal only prints when EVERY source of the
     * window came up empty — it must not blame the cage alone.
     */
    static String honestLiteral(int section, boolean de) {
        if (section == WeatherMaterial.SEC_OUTLOOK) {
            return de ? "Für morgen liegt nichts auf dem Kalender."
                    : "Nothing sits on tomorrow's docket.";
        }
        if (section == WeatherMaterial.SEC_PICTURE) {
            return de ? "Der Tag trug nicht genug Material für eine Großwetterlage."
                    : "The day carried too little material for a big picture.";
        }
        return de ? "Für dieses Fenster erreichte den Desk kein Material."
                : "No material reached the desk in this window.";
    }

    /**
     * Unwraps {@code **bold**} spans beyond the budget — the 4B gilds every
     * figure it writes; the first few spans carry the section, the rest is
     * noise (the formatting finding of the 2026-07-13 live report).
     */
    static String capBoldSpans(String body, int max) {
        if (body == null) return null;
        Matcher m = Pattern.compile("\\*\\*(.+?)\\*\\*", Pattern.DOTALL).matcher(body);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find()) {
            count++;
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    count <= max ? m.group() : m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Swaps a raw wire ticker the model parroted into prose ("DTRUY", "SPCX")
     * for the day's display name. Conservative: symbol-shaped tickers of ≥3
     * chars whose name is a real name (not the symbol itself or a
     * symbol-headed name like "SAP SE"), and never inside parentheses —
     * "Name (TICKER)" is legitimate typography.
     */
    static String tickersToNames(String text, List<WeatherReportRecord.TickerStat> tickers) {
        if (text == null || tickers == null || tickers.isEmpty()) return text;
        String out = text;
        for (WeatherReportRecord.TickerStat t : tickers) {
            String sym = t.ticker();
            String name = t.name();
            if (sym == null || name == null || name.isBlank()) continue;
            if (sym.length() < 3 || !sym.matches("[A-Z0-9.\\-]{3,}")) continue;
            if (name.equalsIgnoreCase(sym)
                    || name.toUpperCase(Locale.ROOT).startsWith(sym)) {
                continue;
            }
            out = out.replaceAll("(?<!\\()\\b" + Pattern.quote(sym) + "\\b(?!\\))",
                    Matcher.quoteReplacement(name));
        }
        return out;
    }

    // --- the wire's window digests ------------------------------------------

    /**
     * One day-part window's wire, condensed: the window's lines (grouped by
     * subject), packed and digested by the foremen when they outsize one
     * batch. Empty when the cage was silent in the window.
     */
    private String windowWire(List<HeadlineRecord> headlines, int fromHour, int toHourExclusive,
            String digestSys, ZoneId zone) {
        List<HeadlineRecord> inWindow = headlines.stream()
                .filter(r -> {
                    int hour = LocalDateTime.ofInstant(
                            Instant.ofEpochSecond(r.createdAt()), zone).getHour();
                    return hour >= fromHour && hour < toHourExclusive;
                })
                .toList();
        if (inWindow.isEmpty()) return "";
        List<String> batches = pack(wireLines(inWindow));
        if (batches.size() == 1) return batches.get(0);
        String wire = String.join("\n", digestAll(batches, digestSys));
        if (wire.length() > MAX_WINDOW_WIRE_CHARS) {
            wire = String.join("\n", digestAll(pack(List.of(wire.split("\n"))), digestSys));
        }
        if (wire.isBlank()) return batches.get(0);
        return wire.length() > MAX_WINDOW_WIRE_CHARS
                ? wire.substring(0, MAX_WINDOW_WIRE_CHARS) : wire;
    }

    /**
     * Structural gate for a pass result (the KI-DD's {@code looksLikeReport}
     * shape): enough prose AND the sectioned skeleton. Package-private for tests.
     */
    static boolean looksLikeReport(String text) {
        if (text == null || text.length() < MIN_STRUCTURED_CHARS) return false;
        int sections = 0;
        for (String line : text.split("\\R")) {
            if (line.startsWith("## ")) sections++;
        }
        return sections >= 3;
    }

    private List<String> digestAll(List<String> batches, String digestSys) {
        List<String> digests = new ArrayList<>();
        for (String batch : batches) {
            try {
                String d = clean(gateway.chat(brain.getProseModel(), digestSys, batch));
                if (!d.isBlank()) digests.add(d);
            } catch (Exception e) {
                LOG.warn("Wetterbericht digest batch failed: {}", e.getMessage());
            }
        }
        return digests;
    }

    /**
     * The day's wire lines, grouped by subject (a story's updates stay adjacent so
     * a foreman sees the whole arc in one batch), groups ordered by first
     * appearance, chronological within.
     */
    private static List<String> wireLines(List<HeadlineRecord> headlines) {
        List<HeadlineRecord> sorted = headlines.stream()
                .sorted(Comparator.comparingLong(HeadlineRecord::createdAt))
                .toList();
        Map<String, List<HeadlineRecord>> groups = new LinkedHashMap<>();
        for (HeadlineRecord r : sorted) {
            String key = r.tickerSymbol() != null && !r.tickerSymbol().isBlank()
                    ? r.tickerSymbol().toUpperCase(Locale.ROOT)
                    : String.valueOf(r.clusterId());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        ZoneId zone = ZoneId.systemDefault();
        List<String> out = new ArrayList<>();
        for (List<HeadlineRecord> group : groups.values()) {
            for (HeadlineRecord r : group) {
                StringBuilder sb = new StringBuilder();
                if (r.highlight() == HeadlineHighlight.IMPORTANT) sb.append("[!] ");
                sb.append(LocalTime.ofInstant(Instant.ofEpochSecond(r.createdAt()), zone)
                        .format(HOUR_MINUTE));
                if (r.tickerSymbol() != null && !r.tickerSymbol().isBlank()) {
                    // A caret index symbol ("^IXIC") is vendor-speak — hand the
                    // model the readable name so it can't parrot the symbol into
                    // the report prose. Same for a "name:<norm>" unit key
                    // (live-observed as the tag of a name-only subject).
                    String sym = r.tickerSymbol();
                    String shown = sym;
                    if (IndexCatalog.isIndexSymbol(sym)) {
                        String name = IndexCatalog.displayNameFor(sym);
                        shown = name != null ? name : sym.substring(1);
                    } else if (sym.regionMatches(true, 0, "name:", 0, 5)) {
                        shown = sym.substring(5).strip();
                    }
                    sb.append(" [").append(shown).append(']');
                }
                sb.append(' ').append(r.headline());
                String extras = lineExtras(r);
                if (!extras.isEmpty()) sb.append(" {").append(extras).append('}');
                out.add(sb.toString());
            }
        }
        return out;
    }

    /**
     * Compact per-line enrichment the foremen read alongside the headline text
     * — the verified day move and the line's crowd read. Kept terse on purpose:
     * more per-line decoration dilutes the digests.
     */
    private static String lineExtras(HeadlineRecord r) {
        StringBuilder sb = new StringBuilder();
        if (r.priceMovePercent() != null && Double.isFinite(r.priceMovePercent())) {
            sb.append(r.priceMovePercent() > 0 ? "+" : "")
                    .append(String.format(Locale.GERMANY, "%.1f", r.priceMovePercent()))
                    .append(" %");
        }
        String mood = moodWord(r.sentiment());
        if (mood != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(mood);
        }
        return sb.toString();
    }

    /** Sentiment enum → the terse German crowd-read word the material carries. */
    private static String moodWord(de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment s) {
        if (s == null) return null;
        return switch (s) {
            case BULLISH -> "bullisch";
            case BEARISH -> "bärisch";
            case MIXED -> "gespalten";
            case FOMO -> "FOMO";
            case CAPITULATION -> "Kapitulation";
            case SQUEEZE -> "Squeeze-Gerede";
            case REVERSAL -> "Umkehr";
            case BREAKOUT -> "Ausbruch";
            case NEUTRAL -> null;
        };
    }

    /** Greedy line packing into batches of at most {@link #MAX_BATCH_CHARS} (always ≥1 line each). */
    private static List<String> pack(List<String> lines) {
        List<String> batches = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (current.length() > 0 && current.length() + line.length() + 1 > MAX_BATCH_CHARS) {
                batches.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append('\n');
            current.append(line);
        }
        if (current.length() > 0) batches.add(current.toString());
        return batches;
    }

    private String localized(String prompt, String lang) {
        return PromptLoader.loadLocalized(prompt, lang)
                .replace("{{LANGUAGE}}", brain.getUserLanguage().displayName());
    }

    /** Raw caret index symbols ("^IXIC") left in prose after the bracket unwrap. */
    private static final java.util.regex.Pattern CARET_SYMBOL =
            java.util.regex.Pattern.compile("(?<![\\w])(\\^[A-Za-z0-9.\\-]{1,10})");

    /**
     * Strips wire-input markup a 4B model sometimes parrots into the report
     * prose — the deterministic belt-and-suspenders behind the prompt rule:
     * the {@code [!]} flag, bracketed ticker tokens ({@code [NVDA]},
     * {@code ([^IXIC])}), and raw caret symbols (swapped for the catalogued
     * index name — {@code ^IXIC} → „Nasdaq Composite" — or de-careted).
     * Markdown constructs (headings, bold, links) pass through untouched.
     */
    static String stripWireMarkup(String text) {
        if (text == null || text.isBlank()) return "";
        String t = text.replace("[!]", "");
        // "([TOKEN])" / "[TOKEN]" → bare TOKEN. Only short, symbol-shaped tokens,
        // and never before "(" so markdown links stay intact.
        t = t.replaceAll("\\(\\[(\\^?[A-Za-z0-9.\\-=/]{1,12})\\]\\)", "($1)");
        t = t.replaceAll("\\[(\\^?[A-Za-z0-9.\\-=/]{1,12})\\](?!\\()", "$1");
        // The wire lines' per-line extras ride in braces ("{-4,4 %, gespalten}")
        // — machine markup a 4B parrots (live-observed first Redaktion smoke).
        // Unwrapped to parentheses: the content is legitimate, the dress is not.
        t = t.replaceAll("\\{([^{}\\n]*)\\}", "($1)");
        java.util.regex.Matcher m = CARET_SYMBOL.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = IndexCatalog.displayNameFor(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    name != null ? name : m.group(1).substring(1)));
        }
        m.appendTail(sb);
        return sb.toString()
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll(" +([,.;:!?])", "$1")
                .strip();
    }

    /** Strips a stray code fence and surrounding whitespace off a prose reply. */
    private static String clean(String text) {
        if (text == null) return "";
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstBreak = t.indexOf('\n');
            t = firstBreak < 0 ? "" : t.substring(firstBreak + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
        }
        return t.strip();
    }
}
