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
 * Writes the daily Wetterbericht: once per day, at the configurable local
 * {@code weather.report-time}, the whole day's wire output is condensed into
 * one white-paper-style evening report.
 *
 * <p><b>Map-reduce, not one giant prompt.</b> gemma4's context cannot hold a
 * full day, so the day's headlines (the permanent {@link HeadlineArchive} is
 * the source — it survives restarts, unlike any in-memory state) are grouped
 * by subject, packed into batches, and each batch is condensed by a foreman
 * call ({@code weather-digest}); the condensed material is then folded once
 * more if still oversized, and a final call ({@code weather-report}) writes
 * the report prose. This is the one deliberately slow, thorough job of the
 * day — every call still funnels through the shared {@link LlmGate}, so the
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
    /** Max chars of condensed material for the final call; above this the digests fold once more. */
    private static final int MAX_MATERIAL_CHARS = 6500;
    /** Boot catch-up delay when the report time already passed — lets Ollama finish warming. */
    private static final long CATCH_UP_DELAY_SECONDS = 90;
    /** A report shorter than this is a whiffed reply, retried once. */
    private static final int MIN_REPORT_CHARS = 80;

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
            String text = writeReport(headlines);
            if (text == null || text.isBlank()) {
                LOG.warn("Wetterbericht {}: model returned no usable text; giving up for this run.", date);
                return;
            }
            WeatherStatsCollector.Stats stats = statsCollector.collect(headlines);
            int important = (int) headlines.stream()
                    .filter(r -> r.highlight() == HeadlineHighlight.IMPORTANT).count();
            reportArchive.append(new WeatherReportRecord(date, Instant.now().getEpochSecond(),
                    text, brain.getUserLanguage().code(), headlines.size(), important,
                    stats.indices(), stats.tickers(), stats.news()));
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

    private String writeReport(List<HeadlineRecord> headlines) {
        String lang = brain.getUserLanguage().code();
        List<String> batches = pack(wireLines(headlines));

        String material;
        if (batches.size() == 1) {
            // A small day fits the final call directly — skip the lossy map stage.
            material = batches.get(0);
        } else {
            String digestSys = localized("weather-digest", lang);
            material = String.join("\n", digestAll(batches, digestSys));
            if (material.length() > MAX_MATERIAL_CHARS) {
                // One fold round: a very long day can out-size the final call even condensed.
                material = String.join("\n",
                        digestAll(pack(List.of(material.split("\n"))), digestSys));
            }
            if (material.isBlank()) {
                material = batches.get(0); // degraded: every digest whiffed — report the first batch raw
            } else if (material.length() > MAX_MATERIAL_CHARS) {
                material = material.substring(0, MAX_MATERIAL_CHARS);
            }
        }

        String reportSys = localized("weather-report", lang);
        String text = clean(gateway.chat(brain.getProseModel(), reportSys, material));
        if (text.length() < MIN_REPORT_CHARS) {
            text = clean(gateway.chat(brain.getProseModel(), reportSys, material));
        }
        return text.length() < MIN_REPORT_CHARS ? "" : text;
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
                    sb.append(" [").append(r.tickerSymbol()).append(']');
                }
                sb.append(' ').append(r.headline());
                out.add(sb.toString());
            }
        }
        return out;
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
