package de.bsommerfeld.wsbg.terminal.ui.export;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefPdfPrintCallback;
import org.cef.misc.CefPdfPrintSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Renders a KI-DD report to PDF through the embedded Chromium: the report's
 * markdown is converted to a print-styled standalone HTML page (self-contained,
 * light theme — a PDF is paper), written to a temp file, loaded in a one-shot
 * hidden browser, and printed via CEF's {@code printToPDF}. Everything is
 * cleaned up afterwards (browser closed, temp file deleted, load listener
 * deregistered) — a print never leaves residue in the CEF runtime.
 *
 * <p>One export at a time; the completion callback fires on a CEF thread —
 * callers must not touch Swing state in it without marshalling.
 */
@Singleton
public final class DeepDivePdfExporter {

    private static final Logger LOG = LoggerFactory.getLogger(DeepDivePdfExporter.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final CefHost cefHost;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Inject
    public DeepDivePdfExporter(CefHost cefHost) {
        this.cefHost = cefHost;
    }

    /**
     * Exports {@code record} to {@code target}. Async: {@code done} receives the
     * outcome exactly once (also on every failure path). Returns {@code false}
     * without doing anything when an export is already running.
     */
    public boolean export(DeepDiveRecord record, Path target, Consumer<Boolean> done) {
        if (record == null || target == null) return false;
        if (!busy.compareAndSet(false, true)) return false;
        Path tempHtml;
        try {
            Path dir = StorageUtils.getAppDataDir().resolve("tmp");
            Files.createDirectories(dir);
            tempHtml = Files.createTempFile(dir, "dd-print-", ".html");
            Files.writeString(tempHtml, buildHtml(record), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[DD-PDF] failed to stage print page: {}", e.getMessage());
            busy.set(false);
            done.accept(false);
            return true;
        }
        final Path staged = tempHtml;
        SwingUtilities.invokeLater(() -> print(staged, target, ok -> {
            busy.set(false);
            done.accept(ok);
        }));
        return true;
    }

    /** EDT: creates the one-shot hidden browser and prints once its page loaded. */
    private void print(Path stagedHtml, Path target, Consumer<Boolean> done) {
        CefBrowser browser;
        try {
            browser = cefHost.createFetchBrowser(stagedHtml.toUri().toString());
        } catch (Exception e) {
            LOG.warn("[DD-PDF] print browser creation failed: {}", e.getMessage());
            cleanup(stagedHtml);
            done.accept(false);
            return;
        }
        final CefBrowser printBrowser = browser;
        final AtomicBoolean printed = new AtomicBoolean(false);
        final BiConsumer<CefBrowser, Integer>[] listenerRef = new BiConsumer[1];
        BiConsumer<CefBrowser, Integer> listener = (b, status) -> {
            if (b != printBrowser || !printed.compareAndSet(false, true)) return;
            CefPdfPrintSettings settings = new CefPdfPrintSettings();
            settings.print_background = true;
            settings.paper_width = 8.27;   // A4, inches
            settings.paper_height = 11.69;
            printBrowser.printToPDF(target.toString(), settings, new CefPdfPrintCallback() {
                @Override
                public void onPdfPrintFinished(String path, boolean ok) {
                    LOG.info("[DD-PDF] print {} → {}", ok ? "succeeded" : "FAILED", path);
                    SwingUtilities.invokeLater(() -> {
                        cefHost.removeLoadEndListener(listenerRef[0]);
                        try {
                            printBrowser.close(true);
                        } catch (Exception e) {
                            LOG.debug("[DD-PDF] print browser close: {}", e.getMessage());
                        }
                        cleanup(stagedHtml);
                    });
                    done.accept(ok);
                }
            });
        };
        listenerRef[0] = listener;
        cefHost.addLoadEndListener(listener);
    }

    private static void cleanup(Path stagedHtml) {
        try {
            Files.deleteIfExists(stagedHtml);
        } catch (Exception ignored) {
            // temp dir is app-owned; a leftover file is harmless
        }
    }

    // ---- markdown → print HTML ----

    /**
     * The report's markdown subset (## crossheads, **bold**, paragraphs) rendered
     * into a self-contained print page. Escape-first, so report content can never
     * break out of the markup.
     */
    static String buildHtml(DeepDiveRecord r) {
        String title = r.canonicalName() != null ? r.canonicalName() : r.subject();
        StringBuilder meta = new StringBuilder(STAMP.format(Instant.ofEpochSecond(r.createdAtEpoch())));
        if (r.ticker() != null) meta.append(" · ").append(r.ticker());
        if (r.isin() != null) meta.append(" · ").append(r.isin());
        if (r.priceAtTime() != null) {
            meta.append(" · ").append(String.format(java.util.Locale.GERMANY, "%,.2f", r.priceAtTime()));
            if ("EUR".equals(r.priceCurrency())) meta.append(" €");
            else if (r.priceCurrency() != null) meta.append(' ').append(r.priceCurrency());
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>"
                + escape(title) + "</title><style>"
                + "body{font-family:Georgia,'Times New Roman',serif;color:#1a1a1a;margin:48px 56px;"
                + "font-size:12.5px;line-height:1.65;}"
                + "h1{font-family:Helvetica,Arial,sans-serif;font-size:21px;margin:0 0 2px;}"
                + ".meta{font-family:Helvetica,Arial,sans-serif;font-size:10px;color:#666;"
                + "letter-spacing:.06em;margin-bottom:6px;}"
                + ".rule{border-top:2px solid #1a1a1a;margin:10px 0 18px;}"
                + "h2{font-family:Helvetica,Arial,sans-serif;font-size:12px;letter-spacing:.08em;"
                + "text-transform:uppercase;margin:20px 0 6px;padding-left:8px;"
                + "border-left:3px solid #b8860b;}"
                + "p{margin:0 0 9px;text-align:justify;font-variant-numeric:tabular-nums;}"
                + "table{width:100%;border-collapse:collapse;margin:10px 0 14px;"
                + "font-size:11px;page-break-inside:avoid;}"
                + "th{font-family:Helvetica,Arial,sans-serif;font-size:9.5px;"
                + "letter-spacing:.06em;text-transform:uppercase;text-align:left;"
                + "padding:4px 8px;border-bottom:1.5px solid #b8860b;background:#faf3e3;}"
                + "td{padding:4px 8px;border-bottom:1px solid #ddd;"
                + "font-variant-numeric:tabular-nums;}"
                + "h2:first-of-type+p{font-size:13.5px;line-height:1.7;}"
                + "strong{color:#000;}"
                + "figure.dd-figure{margin:14px 0 18px;page-break-inside:avoid;}"
                + "figure.dd-figure svg{display:block;width:100%;height:auto;}"
                + "figure.dd-figure figcaption{font-family:Helvetica,Arial,sans-serif;"
                + "font-size:9.5px;color:#52514e;margin-bottom:6px;}"
                + "figure.dd-figure .fig-note{color:#898781;float:right;}"
                + ".src{font-size:10.5px;color:#444;margin:0 0 3px;padding-left:14px;text-indent:-14px;}"
                + ".foot{font-family:Helvetica,Arial,sans-serif;font-size:9px;color:#888;"
                + "margin-top:26px;border-top:1px solid #ccc;padding-top:8px;}"
                + "</style></head><body>"
                + "<h1>" + escape(title) + "</h1>"
                + "<div class=\"meta\">KI-DD · " + escape(meta.toString()) + "</div>"
                + "<div class=\"rule\"></div>"
                + reportWithFigures(r)
                + "<div class=\"foot\">WSBG-Terminal · KI-generierter Bericht, nicht redaktionell "
                + "geprüft - kann Fehler enthalten. Keine Anlageberatung.</div>"
                + "</body></html>";
    }

    /**
     * The report's {@code ## } sections rendered in order, each followed by its
     * figures (section-anchored by ordinal) — the same white-paper layout the
     * widget shows. The SVG is our own builder's output (trusted; its
     * {@code var(--ddc-*, <light hex>)} colors render as the light fallbacks
     * here — a PDF is paper); captions are escaped.
     */
    static String reportWithFigures(DeepDiveRecord r) {
        var charts = r.chartsOrEmpty();
        if (charts.isEmpty()) return markdownToHtml(r.report());
        String[] lines = r.report() == null ? new String[0] : r.report().split("\n", -1);
        StringBuilder out = new StringBuilder(r.report() == null ? 512 : r.report().length() + 4096);
        StringBuilder chunk = new StringBuilder();
        int section = -1; // -1 = preamble before the first heading
        for (String line : lines) {
            if (line.strip().startsWith("## ")) {
                flushChunkWithFigures(out, chunk, section, charts);
                section++;
            }
            chunk.append(line).append('\n');
        }
        flushChunkWithFigures(out, chunk, section, charts);
        // Defensive: figures whose section ordinal never appeared go to the end.
        for (var fig : charts) {
            if (fig.section() > section) out.append(figureHtml(fig));
        }
        return out.toString();
    }

    private static void flushChunkWithFigures(StringBuilder out, StringBuilder chunk,
            int section, java.util.List<DeepDiveRecord.ChartFigure> charts) {
        if (chunk.length() > 0) {
            out.append(markdownToHtml(chunk.toString()));
            chunk.setLength(0);
        }
        if (section < 0) return;
        for (var fig : charts) {
            if (fig.section() == section) out.append(figureHtml(fig));
        }
    }

    private static String figureHtml(DeepDiveRecord.ChartFigure fig) {
        return "<figure class=\"dd-figure\"><figcaption>"
                + (fig.note() != null ? "<span class=\"fig-note\">" + escape(fig.note()) + "</span>" : "")
                + escape(fig.title())
                + "</figcaption>" + fig.svg() + "</figure>";
    }

    /**
     * Escape-first markdown subset: ## headings, **bold**, blank-line
     * paragraphs, compact pipe tables (model-licensed 2026-07-14).
     */
    static String markdownToHtml(String markdown) {
        if (markdown == null) return "";
        StringBuilder out = new StringBuilder(markdown.length() + 512);
        StringBuilder para = new StringBuilder();
        List<String> table = new ArrayList<>();
        for (String line : markdown.split("\n", -1)) {
            String stripped = line.strip();
            if (isTableRow(stripped)) {
                flushPara(out, para);
                table.add(stripped);
                continue;
            }
            flushTable(out, table);
            if (stripped.startsWith("## ")) {
                flushPara(out, para);
                out.append("<h2>").append(inline(escape(stripped.substring(3)))).append("</h2>");
            } else if (stripped.startsWith("- ")) {
                // The deterministic source register ("- [n] …") — the only list
                // in a DD; one line each, never merged into a paragraph.
                flushPara(out, para);
                out.append("<div class=\"src\">").append(inline(escape(stripped.substring(2))))
                        .append("</div>");
            } else if (stripped.isEmpty()) {
                flushPara(out, para);
            } else {
                if (para.length() > 0) para.append(' ');
                para.append(stripped);
            }
        }
        flushTable(out, table);
        flushPara(out, para);
        return out.toString();
    }

    // ---- pipe tables (GFM subset: header row, separator row, data rows) ----

    private static boolean isTableRow(String stripped) {
        return stripped.length() > 1 && stripped.startsWith("|")
                && stripped.indexOf('|', 1) > 0;
    }

    /**
     * Renders a collected pipe-table block as a real table (header +
     * separator + data rows); a pipe run without a separator row falls back
     * to plain paragraphs. Cells are escaped first, then the inline subset.
     */
    private static void flushTable(StringBuilder out, List<String> table) {
        if (table.isEmpty()) return;
        List<String> rows = List.copyOf(table);
        table.clear();
        if (rows.size() < 2 || !isSeparatorRow(rows.get(1))) {
            for (String r : rows) {
                out.append("<p>").append(inline(escape(r))).append("</p>");
            }
            return;
        }
        List<String> aligns = new ArrayList<>();
        for (String c : splitRow(rows.get(1))) {
            boolean left = c.startsWith(":");
            boolean right = c.endsWith(":");
            aligns.add(left && right ? "center" : right ? "right" : "");
        }
        out.append("<table><thead><tr>");
        List<String> head = splitRow(rows.get(0));
        for (int i = 0; i < head.size(); i++) {
            out.append(cellHtml("th", head.get(i), align(aligns, i)));
        }
        out.append("</tr></thead><tbody>");
        for (int r = 2; r < rows.size(); r++) {
            out.append("<tr>");
            List<String> cells = splitRow(rows.get(r));
            for (int i = 0; i < cells.size(); i++) {
                out.append(cellHtml("td", cells.get(i), align(aligns, i)));
            }
            out.append("</tr>");
        }
        out.append("</tbody></table>");
    }

    private static String align(List<String> aligns, int i) {
        return i < aligns.size() ? aligns.get(i) : "";
    }

    private static String cellHtml(String tag, String cell, String align) {
        return "<" + tag + (align.isEmpty() ? "" : " style=\"text-align:" + align + "\"")
                + ">" + inline(escape(cell)) + "</" + tag + ">";
    }

    private static List<String> splitRow(String row) {
        String r = row.strip();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String c : r.split("\\|", -1)) cells.add(c.strip());
        return cells;
    }

    private static boolean isSeparatorRow(String row) {
        List<String> cells = splitRow(row);
        if (cells.isEmpty()) return false;
        for (String c : cells) {
            if (!c.matches(":?-+:?")) return false;
        }
        return true;
    }

    private static void flushPara(StringBuilder out, StringBuilder para) {
        if (para.length() == 0) return;
        out.append("<p>").append(inline(escape(para.toString()))).append("</p>");
        para.setLength(0);
    }

    /**
     * Inline markdown on ESCAPED text: **bold** and *italics* (attributed
     * views ride italics throughout the report prose) — the report subset,
     * mirroring the web renderer's rules (bold consumed first; italics only
     * after a start/space/paren so mid-word asterisks stay literal).
     */
    private static String inline(String escaped) {
        return escaped
                .replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>")
                .replaceAll("(^|[\\s(])\\*([^*\\n]+)\\*", "$1<em>$2</em>");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
