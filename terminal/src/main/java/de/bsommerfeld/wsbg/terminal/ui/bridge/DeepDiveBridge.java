package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.DeepDiveService;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveFinishedEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveJournalEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveProgressEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveStartedEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord;
import de.bsommerfeld.wsbg.terminal.ui.export.DeepDivePdfExporter;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The KI-DD's socket backend. Inbound {@code {type:"deepdive", payload:{command,…}}}:
 * <ul>
 *   <li>{@code {command:"generate", name:"…"}} — kick one report generation
 *       (rejected while one is running — the UI's button is disabled anyway);</li>
 *   <li>{@code {command:"list"}} — request the current state;</li>
 *   <li>{@code {command:"get", id:"dd-…"}} — one full report, answered with a
 *       {@code deepdive-report} broadcast;</li>
 *   <li>{@code {command:"delete", id:"dd-…"}} — remove one archived report
 *       (explicit user action; the archive rewrites its file);</li>
 *   <li>{@code {command:"export-pdf", id:"dd-…"}} — native save dialog (EDT),
 *       then Chromium {@code printToPDF}; the outcome rides the next state push.</li>
 * </ul>
 * Outbound: topic {@code deepdive} {@code {busy, stage, subject, reports:[meta…],
 * item?, pdf?}} on client open, every progress event and every mutation.
 */
@Singleton
public final class DeepDiveBridge {

    private static final Logger LOG = LoggerFactory.getLogger(DeepDiveBridge.class);

    private static final int LIST_LIMIT = 25;

    private final DeepDiveService service;
    private final DeepDivePdfExporter pdfExporter;
    private final PushHub hub;

    /** The stage the running generation is in (null = idle) — for late-joining clients. */
    private volatile String stage;
    /** Run start (ms) and the monotonic progress fraction — the UI's rough ETA. */
    private volatile long runStartMs;
    private volatile double fraction;
    /** In-stage narration (integrate passes: "2/5 · Analysten & Insider"), null when none. */
    private volatile String stageDetail;
    private volatile String subject;
    /** Rides along exactly one push after an export finished ({ok, path}). */
    private volatile Map<String, Object> pdfOutcome;
    /** The freshly finished report rides one push so the UI shows it immediately. */
    private volatile String freshReportId;
    /**
     * The running generation's desk journal: one GROUP per pipeline step
     * (one diff hunk / one note batch), each a list of lines {k: add|del|
     * ctx|gap|note, t: text, o/n: old/new sentence number}. Appended live
     * over its own topic, kept whole for late-joining clients, cleared when
     * the next run starts.
     */
    private final List<List<Map<String, Object>>> journal =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private static final int JOURNAL_MAX_GROUPS = 400;

    @Inject
    public DeepDiveBridge(DeepDiveService service, DeepDivePdfExporter pdfExporter,
            PushHub hub, ApplicationEventBus eventBus) {
        this.service = service;
        this.pdfExporter = pdfExporter;
        this.hub = hub;
        hub.on("deepdive", this::onCommand);
        hub.onClientOpen(this::push);
        eventBus.register(this);
    }

    @Subscribe
    public void onStarted(DeepDiveStartedEvent event) {
        subject = event.subject();
        stage = "collect";
        stageDetail = null;
        runStartMs = System.currentTimeMillis();
        fraction = 0.0;
        journal.clear();
        push();
    }

    @Subscribe
    public void onJournal(DeepDiveJournalEvent event) {
        List<Map<String, Object>> group = new ArrayList<>(event.lines().size());
        for (DeepDiveJournalEvent.Line line : event.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("k", line.kind());
            m.put("t", line.text());
            if (line.oldLine() > 0) m.put("o", line.oldLine());
            if (line.newLine() > 0) m.put("n", line.newLine());
            group.add(m);
        }
        if (group.isEmpty()) return;
        synchronized (journal) {
            journal.add(group);
            while (journal.size() > JOURNAL_MAX_GROUPS) journal.remove(0);
        }
        // Appends ride their own topic so the frequent journal traffic never
        // re-sends the whole widget state.
        hub.broadcastSafe("deepdive-journal", () -> Map.of("group", group));
    }

    @Subscribe
    public void onProgress(DeepDiveProgressEvent event) {
        subject = event.subject();
        stage = event.stage();
        stageDetail = event.detail();
        fraction = Math.max(fraction, estimateFraction(event.stage(), event.detail()));
        push();
    }

    @Subscribe
    public void onFinished(DeepDiveFinishedEvent event) {
        stage = null;
        stageDetail = null;
        subject = null;
        freshReportId = event.reportId();
        push();
    }

    private void onCommand(Map<String, Object> payload) {
        try {
            String cmd = Payloads.str(payload.get("command"));
            switch (cmd == null ? "" : cmd) {
                case "generate" -> {
                    boolean accepted = service.generate(Payloads.str(payload.get("name")));
                    if (!accepted) push(); // re-sync the client's busy state
                }
                case "cancel" -> service.cancelCurrent(); // finish event pushes the idle state
                case "list" -> push();
                case "get" -> service.byId(Payloads.str(payload.get("id"))).ifPresent(r ->
                        hub.broadcastSafe("deepdive-report", () -> Map.of("item", itemJson(r, true))));
                case "delete" -> {
                    service.delete(Payloads.str(payload.get("id")));
                    push();
                }
                case "export-pdf" -> service.byId(Payloads.str(payload.get("id")))
                        .ifPresent(this::exportPdf);
                default -> LOG.debug("deepdive: ignoring unknown command '{}'", cmd);
            }
        } catch (Exception e) {
            LOG.warn("deepdive command failed: {}", e.getMessage());
        }
    }

    /**
     * Native save dialog on the EDT, then the Chromium print. The dialog blocks
     * only the EDT interaction moment; the print itself is async and reports
     * back through the next state push.
     */
    private void exportPdf(DeepDiveRecord record) {
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("KI-DD als PDF speichern");
            chooser.setFileFilter(new FileNameExtensionFilter("PDF", "pdf"));
            String name = (record.canonicalName() != null ? record.canonicalName() : record.subject())
                    .replaceAll("[\\\\/:*?\"<>|]", "_");
            chooser.setSelectedFile(new File("KI-DD " + name + " " + LocalDate.now() + ".pdf"));
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                file = new File(file.getParentFile(), file.getName() + ".pdf");
            }
            final String path = file.getAbsolutePath();
            boolean started = pdfExporter.export(record, file.toPath(), ok -> {
                pdfOutcome = Map.of("ok", ok, "path", ok ? path : "");
                push();
            });
            if (!started) {
                pdfOutcome = Map.of("ok", false, "path", "");
                push();
            }
        });
    }

    private void push() {
        hub.broadcastSafe("deepdive", this::statePayload);
    }

    // ---- the rough, self-correcting ETA (presentation-side estimation) ----

    /**
     * Progress fraction from the pipeline's own narration ("3/8 · Lage",
     * "… · Quelle 12/34") - an ESTIMATE for the user's patience, never a
     * contract. The weights mirror the measured time shares of full-pool
     * runs (2026-07-16: the Lage carries ~60% of the wall clock); the ETA
     * self-corrects because it divides real elapsed time by the fraction.
     */
    private static double estimateFraction(String stage, String detail) {
        if (stage == null) return 0.0;
        // Cumulative [start, end) fractions per section NUMBER in the
        // narration ("<n>/8 · <name>"), reflecting the run's ACTUAL order
        // (the thesis, 2/8, is written last).
        double[][] bySection = {
                {0.08, 0.10}, // 1/8 Worum es geht
                {0.92, 0.95}, // 2/8 These (written last)
                {0.10, 0.70}, // 3/8 Lage (the weave bulk)
                {0.70, 0.73}, // 4/8 Fundamentale Entwicklung
                {0.73, 0.78}, // 5/8 Bewertung und Wettbewerb
                {0.78, 0.86}, // 6/8 Katalysatoren und Risiken
                {0.86, 0.90}, // 7/8 Ausblick
                {0.90, 0.92}, // 8/8 Der Raum
        };
        switch (stage) {
            case "collect" -> { return 0.01; }
            case "triage" -> { return 0.05; }
            case "these" -> { return 0.92; }
            case "finish" -> { return 0.96; }
            case "sections" -> {
                if (detail == null) return 0.08;
                java.util.regex.Matcher sec =
                        java.util.regex.Pattern.compile("^(\\d)/8").matcher(detail);
                if (!sec.find()) return 0.08;
                int idx = Integer.parseInt(sec.group(1)) - 1;
                if (idx < 0 || idx >= bySection.length) return 0.08;
                double start = bySection[idx][0];
                double span = bySection[idx][1] - start;
                java.util.regex.Matcher step = java.util.regex.Pattern
                        .compile("(?:Quelle|source) (\\d+)/(\\d+)").matcher(detail);
                if (step.find()) {
                    double inner = Double.parseDouble(step.group(1))
                            / Math.max(1.0, Double.parseDouble(step.group(2)));
                    return start + span * (0.10 + 0.80 * inner);
                }
                if (detail.contains("Anfechtung") || detail.contains("challenge")
                        || detail.contains("Revision") || detail.contains("revision")
                        || detail.contains("Lektorat") || detail.contains("copy edit")) {
                    return start + span * 0.92;
                }
                return start; // author/chronicle opening the section
            }
            default -> { return 0.0; }
        }
    }

    /**
     * Remaining seconds: early in the run the median of the archived runs'
     * durations anchors the guess; once enough of the run is measured, the
     * elapsed/fraction ratio takes over and self-corrects every push.
     */
    private long etaSeconds() {
        long elapsed = System.currentTimeMillis() - runStartMs;
        double f = fraction;
        long eta;
        if (f >= 0.12) {
            eta = (long) (elapsed * (1.0 - f) / Math.max(f, 0.01)) / 1000;
        } else {
            long baseline = baselineDurationMs();
            eta = Math.max((baseline - elapsed) / 1000, 120);
        }
        return Math.max(30, Math.min(eta, 3 * 3600));
    }

    /** Median duration of the recent archived runs, or a 30-min default. */
    private long baselineDurationMs() {
        List<Long> durations = new ArrayList<>();
        for (DeepDiveRecord r : service.recent(5)) {
            if (r.durationMs() > 0) durations.add(r.durationMs());
        }
        if (durations.isEmpty()) return 30 * 60_000L;
        java.util.Collections.sort(durations);
        return durations.get(durations.size() / 2);
    }

    private Map<String, Object> statePayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("busy", service.isBusy());
        if (stage != null) m.put("stage", stage);
        if (stageDetail != null) m.put("stageDetail", stageDetail);
        if (service.isBusy() && runStartMs > 0) {
            m.put("progress", (int) Math.round(fraction * 100));
            m.put("etaSeconds", etaSeconds());
        }
        if (subject != null) m.put("subject", subject);
        synchronized (journal) {
            if (!journal.isEmpty()) m.put("journal", new ArrayList<>(journal));
        }
        List<Map<String, Object>> reports = new ArrayList<>();
        for (DeepDiveRecord r : service.recent(LIST_LIMIT)) {
            reports.add(itemJson(r, false));
        }
        m.put("reports", reports);
        String fresh = freshReportId;
        if (fresh != null) {
            service.byId(fresh).ifPresent(r -> m.put("item", itemJson(r, true)));
            freshReportId = null;
        }
        Map<String, Object> pdf = pdfOutcome;
        if (pdf != null) {
            m.put("pdf", pdf);
            pdfOutcome = null;
        }
        return m;
    }

    private static Map<String, Object> itemJson(DeepDiveRecord r, boolean withReport) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("subject", r.subject());
        if (r.canonicalName() != null) m.put("canonicalName", r.canonicalName());
        if (r.ticker() != null) m.put("ticker", r.ticker());
        if (r.isin() != null) m.put("isin", r.isin());
        m.put("createdAt", r.createdAtEpoch());
        if (r.priceAtTime() != null) {
            m.put("price", r.priceAtTime());
            if (r.priceCurrency() != null) m.put("currency", r.priceCurrency());
        }
        m.put("reportWords", reportWords(r.report()));
        if (withReport) {
            m.put("report", r.report());
            if (!r.chartsOrEmpty().isEmpty()) {
                List<Map<String, Object>> charts = new ArrayList<>();
                for (var fig : r.chartsOrEmpty()) {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("section", fig.section());
                    fm.put("title", fig.title());
                    if (fig.note() != null) fm.put("note", fig.note());
                    fm.put("svg", fig.svg());
                    charts.add(fm);
                }
                m.put("charts", charts);
            }
        }
        return m;
    }

    /** Prose word count for the reading-time badge — the list payload carries
     *  no report text, so the count travels instead. Tokens without a letter
     *  or digit (table pipes, heading markers, rules) are pure markdown
     *  scaffolding, not words. */
    private static int reportWords(String report) {
        if (report == null || report.isBlank()) return 0;
        int words = 0;
        for (String token : report.split("\\s+")) {
            if (token.codePoints().anyMatch(Character::isLetterOrDigit)) words++;
        }
        return words;
    }
}
