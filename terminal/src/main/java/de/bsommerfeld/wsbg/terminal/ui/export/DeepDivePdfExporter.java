package de.bsommerfeld.wsbg.terminal.ui.export;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
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

    /**
     * The paper's typography. Deliberately the SAME document grammar as the
     * widget (deepdive.css): ONE column holds prose, tables and figures at the
     * same width, sections carry their own numbers and figures carry theirs.
     * In-app and PDF are one document — the geometry may not fork between them.
     *
     * <p>Page margins are set on the PRINT SETTINGS, not here, because the
     * running head and foot have to stand in them. They are chosen so the text
     * block lands near the figure canvas width ({@code DeepDiveCharts.W}),
     * which is what lets a figure print at scale ~1 instead of resampled.
     */
    private static final String DOC_CSS = """
            body{font-family:Georgia,'Times New Roman',serif;color:#1a1a1a;margin:0;
              font-size:16px;line-height:1.75;counter-reset:sec fig;}
            .doc-head{margin:0 0 26px;padding-bottom:10px;
              border-bottom:2px solid #1a1a1a;}
            .doc-kicker{font-family:Helvetica,Arial,sans-serif;font-size:11px;
              letter-spacing:.18em;text-transform:uppercase;color:#b8860b;
              margin-bottom:5px;}
            h1{font-family:Helvetica,Arial,sans-serif;font-size:29px;line-height:1.2;
              margin:0 0 4px;letter-spacing:-.01em;}
            .meta{font-family:Helvetica,Arial,sans-serif;font-size:12px;color:#666;
              letter-spacing:.05em;}
            /* Numbered sections: a reader can cite "3." and be understood. */
            h2{font-family:Helvetica,Arial,sans-serif;font-size:13px;letter-spacing:.1em;
              text-transform:uppercase;margin:34px 0 12px;
              break-after:avoid;page-break-after:avoid;counter-increment:sec;}
            h2::before{content:counter(sec) ". ";color:#b8860b;}
            p{margin:0 0 13px;text-align:justify;hyphens:auto;
              font-variant-numeric:tabular-nums;orphans:3;widows:3;}
            h2:first-of-type+p{font-size:17.5px;line-height:1.7;color:#333;}
            strong{color:#000;font-weight:700;}
            /* Tables: rules only where they carry meaning (booktabs grammar). */
            table{width:100%;border-collapse:collapse;margin:14px 0 18px;
              font-size:13px;break-inside:avoid;page-break-inside:avoid;}
            th{font-family:Helvetica,Arial,sans-serif;font-size:11px;
              letter-spacing:.06em;text-transform:uppercase;text-align:left;
              padding:5px 8px;border-top:1.2px solid #1a1a1a;
              border-bottom:0.6px solid #1a1a1a;}
            td{padding:5px 8px;border-bottom:0.4px solid #ddd;
              font-variant-numeric:tabular-nums;}
            tbody tr:last-child td{border-bottom:1.2px solid #1a1a1a;}
            /* A figure is one plate, and its caption sits BENEATH it. */
            figure.dd-figure{margin:26px 0 30px;padding:0;
              break-inside:avoid;page-break-inside:avoid;counter-increment:fig;}
            figure.dd-figure svg{display:block;width:100%;height:auto;
              border:0.6px solid #e1e0d9;border-radius:6px;padding:8px 10px;
              box-sizing:border-box;background:#fff;}
            figure.dd-figure figcaption{font-family:Helvetica,Arial,sans-serif;
              font-size:11px;line-height:1.45;color:#52514e;margin-top:8px;
              display:flex;gap:8px;align-items:baseline;}
            figure.dd-figure .fig-id{color:#1a1a1a;font-weight:700;white-space:nowrap;}
            figure.dd-figure .fig-title{flex:1;}
            figure.dd-figure .fig-note{color:#898781;white-space:nowrap;}
            /* The source register, set as footnotes with a hanging indent. */
            .src{font-size:12px;color:#444;line-height:1.5;margin:0 0 2px;
              padding-left:15px;text-indent:-15px;}
            /* An archived report's frozen figure keeps the size it was drawn
               for; only today's canvas fills the column. */
            figure.dd-figure.legacy svg{max-width:var(--fig-natural);}
            .foot{font-family:Helvetica,Arial,sans-serif;font-size:10px;color:#888;
              margin-top:28px;border-top:0.6px solid #ccc;padding-top:8px;}
            """;

    private final CefHost cefHost;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final GlobalConfig config;

    @Inject
    public DeepDivePdfExporter(CefHost cefHost, GlobalConfig config) {
        this.cefHost = cefHost;
        this.config = config;
    }

    /**
     * The paper's language. Same source and same mechanism the figures use
     * ({@code DeepDiveCharts}) — a generated document localizes as ONE piece,
     * it does not fork its furniture from its pictures.
     */
    private boolean german() {
        try {
            return "de".equalsIgnoreCase(config.getUser().getUserLanguage().code());
        } catch (Exception e) {
            return true; // the app's primary user language
        }
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
            Files.writeString(tempHtml, buildHtml(record, german()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[DD-PDF] failed to stage print page: {}", e.getMessage());
            busy.set(false);
            done.accept(false);
            return true;
        }
        final Path staged = tempHtml;
        final String head = record.canonicalName() != null
                ? record.canonicalName() : record.subject();
        SwingUtilities.invokeLater(() -> print(staged, target, head, ok -> {
            busy.set(false);
            done.accept(ok);
        }));
        return true;
    }

    /** EDT: creates the one-shot hidden browser and prints once its page loaded. */
    private void print(Path stagedHtml, Path target, String headTitle, Consumer<Boolean> done) {
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
            // A document has a running head and numbered pages. The page
            // margins live HERE (not in the body's CSS) so the header and
            // footer have somewhere to stand.
            settings.margin_type = CefPdfPrintSettings.MarginType.CUSTOM;
            settings.margin_top = 0.66;
            settings.margin_bottom = 0.62;
            settings.margin_left = 0.50;
            settings.margin_right = 0.50;
            settings.display_header_footer = true;
            settings.header_template = runningHead(headTitle);
            settings.footer_template = runningFoot();
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

    /**
     * The running head Chromium stamps on every page: the subject on the left,
     * the desk on the right, under a hairline. Chromium renders these
     * templates in an isolated document with a tiny default type size, so
     * every rule has to be inline and explicit.
     */
    private String runningHead(String title) {
        return "<div style=\"width:100%;font-family:Helvetica,Arial,sans-serif;font-size:7.5px;"
                + "color:#8a8a8a;letter-spacing:.06em;padding:0 0.50in;"
                + "border-bottom:0.5px solid #d8d8d8;margin-bottom:2px;"
                + "display:flex;justify-content:space-between;\">"
                + "<span>" + escape(title == null ? "" : title) + "</span>"
                + "<span>KI-DD · WSBG-Terminal</span></div>";
    }

    /** The running foot: the date left, the page count right. */
    private String runningFoot() {
        boolean de = german();
        return "<div style=\"width:100%;font-family:Helvetica,Arial,sans-serif;font-size:7.5px;"
                + "color:#8a8a8a;padding:0 0.50in;margin-top:2px;"
                + "display:flex;justify-content:space-between;\">"
                + "<span class=\"date\"></span>"
                + "<span>" + (de ? "Seite " : "Page ") + "<span class=\"pageNumber\"></span>"
                + (de ? " von " : " of ") + "<span class=\"totalPages\"></span></span></div>";
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
    static String buildHtml(DeepDiveRecord r, boolean de) {
        String title = r.canonicalName() != null ? r.canonicalName() : r.subject();
        StringBuilder meta = new StringBuilder(STAMP.format(Instant.ofEpochSecond(r.createdAtEpoch())));
        if (r.ticker() != null) meta.append(" · ").append(r.ticker());
        if (r.isin() != null) meta.append(" · ").append(r.isin());
        if (r.priceAtTime() != null) {
            meta.append(" · ").append(String.format(
                    de ? java.util.Locale.GERMANY : java.util.Locale.US, "%,.2f", r.priceAtTime()));
            if ("EUR".equals(r.priceCurrency())) meta.append(" €");
            else if (r.priceCurrency() != null) meta.append(' ').append(r.priceCurrency());
        }
        // `lang` is not decoration: Chromium hyphenates justified text only
        // when it knows which language's patterns to use.
        return "<!doctype html><html lang=\"" + (de ? "de" : "en")
                + "\"><head><meta charset=\"utf-8\"><title>"
                + escape(title) + "</title><style>" + DOC_CSS + "</style></head><body>"
                + "<header class=\"doc-head\">"
                + "<div class=\"doc-kicker\">"
                + (de ? "Due-Diligence-Bericht" : "Due-diligence report") + "</div>"
                + "<h1>" + escape(title) + "</h1>"
                + "<div class=\"meta\">" + escape(meta.toString()) + "</div>"
                + "</header>"
                + reportWithFigures(r, de)
                + "<div class=\"foot\">" + (de
                        ? "WSBG-Terminal · KI-generierter Bericht, nicht redaktionell geprüft - "
                                + "kann Fehler enthalten. Keine Anlageberatung."
                        : "WSBG Terminal · AI-generated report, not editorially reviewed - "
                                + "may contain errors. Not investment advice.")
                + "</div>"
                + "</body></html>";
    }

    /**
     * The report's {@code ## } sections rendered in order, each followed by its
     * figures (section-anchored by ordinal) — the same white-paper layout the
     * widget shows. The SVG is our own builder's output (trusted; its
     * {@code var(--ddc-*, <light hex>)} colors render as the light fallbacks
     * here — a PDF is paper); captions are escaped.
     */
    static String reportWithFigures(DeepDiveRecord r, boolean de) {
        var charts = r.chartsOrEmpty();
        if (charts.isEmpty()) return markdownToHtml(r.report());
        String[] lines = r.report() == null ? new String[0] : r.report().split("\n", -1);
        StringBuilder out = new StringBuilder(r.report() == null ? 512 : r.report().length() + 4096);
        StringBuilder chunk = new StringBuilder();
        int section = -1; // -1 = preamble before the first heading
        for (String line : lines) {
            if (line.strip().startsWith("## ")) {
                flushChunkWithFigures(out, chunk, section, charts, de);
                section++;
            }
            chunk.append(line).append('\n');
        }
        flushChunkWithFigures(out, chunk, section, charts, de);
        // Defensive: figures whose section ordinal never appeared go to the end.
        for (var fig : charts) {
            if (fig.section() > section) out.append(figureHtml(fig, figureId(charts, fig), de));
        }
        return out.toString();
    }

    private static void flushChunkWithFigures(StringBuilder out, StringBuilder chunk,
            int section, java.util.List<DeepDiveRecord.ChartFigure> charts, boolean de) {
        if (chunk.length() > 0) {
            out.append(markdownToHtml(chunk.toString()));
            chunk.setLength(0);
        }
        if (section < 0) return;
        for (var fig : charts) {
            if (fig.section() == section) out.append(figureHtml(fig, figureId(charts, fig), de));
        }
    }

    /** Positional figure ID (A1, A2, ...) — the register the prose cites. */
    private static String figureId(java.util.List<DeepDiveRecord.ChartFigure> charts,
            DeepDiveRecord.ChartFigure fig) {
        int idx = charts.indexOf(fig);
        return idx < 0 ? null : "A" + (idx + 1);
    }

    /** The document column's width — the canvas today's figures are drawn on. */
    private static final int COLUMN = 720;

    private static final java.util.regex.Pattern VIEWBOX =
            java.util.regex.Pattern.compile("viewBox=\"0 0 (\\d+(?:\\.\\d+)?) ");

    /**
     * A figure's own canvas width, read off its viewBox. Archived reports carry
     * FROZEN SVGs from whatever builder wrote them; a narrower canvas must not
     * be blown up to today's column, because that magnifies its labels AND its
     * old collisions without repairing either.
     */
    private static double canvasWidth(String svg) {
        var m = VIEWBOX.matcher(svg == null ? "" : svg);
        return m.find() ? Double.parseDouble(m.group(1)) : COLUMN;
    }

    /** Plate first, caption beneath it — the figure convention of set documents. */
    private static String figureHtml(DeepDiveRecord.ChartFigure fig, String id, boolean de) {
        double w = canvasWidth(fig.svg());
        String frame = w < COLUMN
                ? "<figure class=\"dd-figure legacy\" style=\"--fig-natural:"
                        + Math.round(w * 1.2) + "px\">"
                : "<figure class=\"dd-figure\">";
        return frame + fig.svg() + "<figcaption>"
                + (id != null
                        ? "<span class=\"fig-id\">" + (de ? "Abb. " : "Fig. ") + id + "</span>" : "")
                + "<span class=\"fig-title\">" + escape(fig.title()) + "</span>"
                + (fig.note() != null
                        ? "<span class=\"fig-note\">" + escape(fig.note()) + "</span>" : "")
                + "</figcaption></figure>";
    }

    /**
     * Escape-first markdown subset: ## headings, **bold**, blank-line
     * paragraphs, compact pipe tables (typesetter-set - the model's table licence was revoked 2026-07-16).
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
