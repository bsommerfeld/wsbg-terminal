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
            LOG.info("Wetterbericht {}: no headlines today — nothing to report.", date);
            return;
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
                        .build(stats, headlines, zone);
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
        String[] shelves = WeatherMaterial.sectionShelves(stats, today,
                wires[0], wires[1], wires[2]);
        List<String> headings = de ? SECTIONS_DE : SECTIONS_EN;

        String sectionSys = localized("weather-section", lang);
        String challengeSys = localized("weather-challenge", lang);
        String reviseSys = localized("weather-revise", lang);
        String header = (de ? "ABENDAUSGABE (Wetterbericht) vom "
                : "EVENING EDITION (Wetterbericht), ") + today + "\n\n";

        String[] bodies = new String[WeatherMaterial.SECTION_COUNT];
        String previousThought = null;
        int written = 0;
        for (int idx = 0; idx < bodies.length; idx++) {
            long t0 = System.currentTimeMillis();
            try {
                bodies[idx] = writeSection(model, sectionSys, challengeSys, reviseSys,
                        headerWithThought(header, previousThought), headings.get(idx),
                        shelves[idx], de);
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
            String reviseSys, String header, String heading, String shelf, boolean de) {
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
        body = examineAndRepair(model, reviseSys, header, heading, shelf, de, body);

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
                    revised.strip());
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
     * The deterministic examiner cycle (the DD's {@code examineAndRepair}):
     * inspect against the shelf, let the author fix, re-inspect, and remove
     * the sentences of hard figure/date survivors — a lost sentence beats a
     * spliced figure in the evening edition.
     */
    private String examineAndRepair(ChatModel model, String reviseSys, String header,
            String heading, String material, boolean de, String body) {
        List<DeepDiveFactCheck.Objection> objections = inspectSection(body, material, de);
        if (objections.isEmpty()) return body;
        LOG.info("Wetterbericht section '{}': examiner raised {} objection(s): {}",
                heading, objections.size(),
                objections.stream().map(o -> o.kind() + " " + o.problem()).toList());
        String revised = revise(model, reviseSys, header, heading, body, material,
                renderObjections(objections));
        if (revised != null && !revised.isBlank()) body = revised.strip();
        List<DeepDiveFactCheck.Objection> hard = inspectSection(body, material, de).stream()
                .filter(DeepDiveFactCheck.Objection::hard).toList();
        if (!hard.isEmpty()) {
            String cut = DeepDiveFactCheck.removeOffendingSentences(body, hard);
            LOG.info("Wetterbericht section '{}': {} unverifiable figure sentence(s) removed "
                    + "({} -> {} chars).", heading, hard.size(), body.length(), cut.length());
            body = cut;
        }
        return DeepDiveFactCheck.scrubNonMarkerBrackets(DeepDiveFactCheck.scrubResidue(body));
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
                    "section over the one-short-paragraph contract ("
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

    /** What an empty window honestly reads like — never a model call. */
    static String honestLiteral(int section, boolean de) {
        if (section == WeatherMaterial.SEC_OUTLOOK) {
            return de ? "Für morgen liegt nichts auf dem Kalender."
                    : "Nothing sits on tomorrow's docket.";
        }
        if (section == WeatherMaterial.SEC_PICTURE) {
            return de ? "Der Tag trug nicht genug Material für eine Großwetterlage."
                    : "The day carried too little material for a big picture.";
        }
        return de ? "Der Käfig blieb in diesem Fenster still."
                : "The cage stayed quiet in this window.";
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
