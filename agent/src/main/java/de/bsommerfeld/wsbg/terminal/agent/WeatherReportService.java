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
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p><b>Multi-pass, the KI-DD pattern:</b> draft ({@code weather-report}) →
 * adversarial Q&amp;A ({@code weather-qa}) → final ({@code weather-final}),
 * with a {@link #looksLikeReport} gate and graceful degradation to the draft
 * — there is always a report when the day had lines. This is the one
 * deliberately slow, thorough job of the day — every call still funnels
 * through the shared {@link LlmGate}, so the live wire is never starved,
 * merely interleaved.
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
    /** Max chars of the condensed WIRE material; above this the digests fold once more. */
    private static final int MAX_WIRE_CHARS = 4500;
    /** Boot catch-up delay when the report time already passed — lets Ollama finish warming. */
    private static final long CATCH_UP_DELAY_SECONDS = 90;
    /** A reply shorter than this is a whiff regardless of shape. */
    private static final int MIN_REPORT_CHARS = 80;
    /** {@link #looksLikeReport} floor: real evening editions run well past this. */
    private static final int MIN_STRUCTURED_CHARS = 400;
    /** dpa-AFX press items condensed deterministically into the material. */
    private static final int MAX_PRESS_ITEMS = 3;

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

    private String writeReport(List<HeadlineRecord> headlines, WeatherStatsCollector.Stats stats) {
        String lang = brain.getUserLanguage().code();
        String material = buildMaterial(headlines, stats, lang);

        // Pass 1 — draft. The roomy free-form handle (the KI-DD's): an evening
        // edition doesn't fit the wire prompts' tight token caps.
        String reportSys = localized("weather-report", lang);
        String draft = stripWireMarkup(clean(
                gateway.chat(brain.getDeepDiveModel(), reportSys, material)));
        if (!looksLikeReport(draft)) {
            draft = stripWireMarkup(clean(
                    gateway.chat(brain.getDeepDiveModel(), reportSys, material)));
        }
        if (!looksLikeReport(draft)) {
            // Both draft attempts whiffed structurally — publish whatever prose
            // survived the floor rather than nothing (the v1 guarantee).
            return draft.length() < MIN_REPORT_CHARS ? "" : draft;
        }

        // Pass 2 — adversarial Q&A, strictly from the material; a whiff simply
        // skips the final pass (graceful degrade, the KI-DD pattern).
        String qa = "";
        try {
            qa = clean(gateway.chat(brain.getDeepDiveModel(), localized("weather-qa", lang),
                    material + "\n\nDRAFT REPORT UNDER REVIEW:\n" + draft));
        } catch (Exception e) {
            LOG.warn("Wetterbericht QA pass failed: {}", e.getMessage());
        }
        if (qa.isBlank()) return draft;

        // Pass 3 — work the review in; surviving sentences verbatim.
        try {
            String finalPass = stripWireMarkup(clean(gateway.chat(brain.getDeepDiveModel(),
                    localized("weather-final", lang),
                    material + "\n\nDRAFT REPORT:\n" + draft
                            + "\n\nADVERSARIAL REVIEW (Q&A):\n" + qa)));
            if (looksLikeReport(finalPass)) return finalPass;
            LOG.warn("Wetterbericht final pass whiffed structurally — keeping the draft.");
        } catch (Exception e) {
            LOG.warn("Wetterbericht final pass failed: {} — keeping the draft.", e.getMessage());
        }
        return draft;
    }

    /**
     * The passes' material: the model-condensed wire stories on top (the
     * cage leads), the deterministic stat blocks below. Wire condensation is
     * the v1 map-reduce, budgeted so wire + blocks stay inside num_ctx.
     */
    private String buildMaterial(List<HeadlineRecord> headlines,
            WeatherStatsCollector.Stats stats, String lang) {
        List<String> batches = pack(wireLines(headlines));
        String wire;
        if (batches.size() == 1) {
            // A small day fits directly — skip the lossy map stage.
            wire = batches.get(0);
        } else {
            String digestSys = localized("weather-digest", lang);
            wire = String.join("\n", digestAll(batches, digestSys));
            if (wire.length() > MAX_WIRE_CHARS) {
                // One fold round: a very long day can out-size the budget even condensed.
                wire = String.join("\n", digestAll(pack(List.of(wire.split("\n"))), digestSys));
            }
            if (wire.isBlank()) {
                wire = batches.get(0); // degraded: every digest whiffed — the first batch raw
            } else if (wire.length() > MAX_WIRE_CHARS) {
                wire = wire.substring(0, MAX_WIRE_CHARS);
            }
        }
        String blocks = WeatherMaterial.blocks(stats);
        StringBuilder material = new StringBuilder("WIRE STORIES of the day (the cage, condensed):\n");
        material.append(wire);
        if (!blocks.isEmpty()) material.append("\n\n").append(blocks);
        return material.toString();
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
                    // the report prose.
                    String sym = r.tickerSymbol();
                    String shown = sym;
                    if (IndexCatalog.isIndexSymbol(sym)) {
                        String name = IndexCatalog.displayNameFor(sym);
                        shown = name != null ? name : sym.substring(1);
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
