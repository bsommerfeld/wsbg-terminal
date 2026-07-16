package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActions;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystView;
import de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive;
import de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularity;
import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealings;
import de.bsommerfeld.wsbg.terminal.core.price.OrderBookSnapshot;
import de.bsommerfeld.wsbg.terminal.core.price.PressTimeline;
import de.bsommerfeld.wsbg.terminal.core.price.ShortInterest;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord.ChartFigure;
import de.bsommerfeld.wsbg.terminal.db.MarketEventRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The KI-DD's figure layer: server-rendered SVG charts built ONCE from the
 * verified material at generation time and frozen into the archived record, so
 * the widget and the PDF show the same picture forever. Deterministic data
 * rendering — the model never draws; every figure exists only when its data
 * block does.
 *
 * <p>Design follows the dataviz method: thin marks (bars ≤ 24px, 4px rounded
 * data-ends, square at the baseline), 2px surface gaps between touching fills,
 * 2px lines with an ≥8px surface-ringed end marker, hairline grid, values
 * direct-labeled (every column carries its value, so no y-axis is needed),
 * legends for two-series charts, text always in ink tokens — never the series
 * color. Colors ride CSS custom properties with LIGHT-mode fallbacks
 * ({@code var(--ddc-*, <hex>)}): the widget maps them to theme tokens
 * (dark-first, see deepdive.css), the PDF simply renders the fallbacks.
 * Palette: categorical blue/aqua (validated pair); gain/loss rides the app's
 * green/red convention — a CVD floor-band pair, legal because every such mark
 * carries its signed value as text (secondary encoding).
 */
final class DeepDiveCharts {

    private static final int W = 560;

    // Color roles (light fallbacks from the validated reference palette).
    private static final String S1 = "var(--ddc-s1,#2a78d6)";
    private static final String S2 = "var(--ddc-s2,#1baf7a)";
    private static final String POS = "var(--ddc-pos,#008300)";
    private static final String NEG = "var(--ddc-neg,#d03b3b)";
    private static final String INK = "var(--ddc-ink,#0b0b0b)";
    private static final String MUTE = "var(--ddc-mute,#898781)";
    private static final String GRID = "var(--ddc-grid,#e1e0d9)";
    private static final String AXIS = "var(--ddc-axis,#c3c2b7)";
    private static final String SURFACE = "var(--ddc-surface,#ffffff)";

    // Report section ordinals (must match the fixed EIGHT-section skeleton:
    // Worum es geht / These / Lage / Fundamentale Entwicklung / Bewertung und
    // Wettbewerb / Katalysatoren und Risiken / Ausblick / Der Raum — see
    // DeepDiveService.SECTIONS_DE). The prose stays narrative; the figures
    // carry the number series — profile strip up top, price and performance
    // under the situation, the fiscal-year series under the fundamentals, the
    // analyst votes under the valuation, insiders/shorts/chart levels under
    // catalysts, and the dated event board under the ANCHORED outlook.
    private static final int SEC_ABOUT = 0;
    private static final int SEC_SITUATION = 2;
    private static final int SEC_FUNDAMENTALS = 3;
    private static final int SEC_VALUATION = 4;
    private static final int SEC_CATALYSTS = 5;
    private static final int SEC_OUTLOOK = 6;

    private final boolean de;

    DeepDiveCharts(String languageCode) {
        this.de = "de".equalsIgnoreCase(languageCode);
    }

    /** Builds every figure the material supports, report-section-anchored. */
    List<ChartFigure> build(MarketSnapshot snapshot, CompanyDeepDive deepDive,
            AnalystView analystView, ShortInterest shortInterest,
            InsiderDealings insiderDealings,
            de.bsommerfeld.wsbg.terminal.core.price.VenueStats venueStats,
            UsListingStats usStats, AnalystActions analystActions,
            HedgeFundPopularity hedgeFunds, PressTimeline pressTimeline,
            List<String> worldSignalKeep, VolumeProfile.Profile volumeProfile,
            OrderBookSnapshot orderBook, List<MarketEventRecord> memoryEvents) {
        List<ChartFigure> out = new ArrayList<>();
        addIfPresent(out, factsFigure(snapshot, deepDive, analystView));
        if (deepDive != null) {
            addIfPresent(out, epsDividendFigure(deepDive.keyFigures()));
            addIfPresent(out, revenueProfitFigure(deepDive.balanceSheet()));
            addIfPresent(out, marginFigure(deepDive.keyFigures()));
            addIfPresent(out, cashflowFigure(deepDive.balanceSheet()));
        }
        addIfPresent(out, surpriseFigure(usStats));
        addIfPresent(out, analystFigure(analystView));
        addIfPresent(out, actionsFigure(analystActions));
        addIfPresent(out, hedgeFundFigure(hedgeFunds));
        addIfPresent(out, peerScatterFigure(deepDive, snapshot));
        addIfPresent(out, priceFigure(snapshot));
        addIfPresent(out, venueFigure(venueStats));
        addIfPresent(out, volumeProfileFigure(volumeProfile, snapshot));
        addIfPresent(out, orderBookFigure(orderBook, snapshot));
        addIfPresent(out, pressTimelineFigure(pressTimeline));
        addIfPresent(out, worldSignalsFigure(worldSignalKeep));
        if (deepDive != null) {
            addIfPresent(out, performanceFigure(deepDive.performance()));
            addIfPresent(out, rangeFigure(deepDive.performance(), snapshot));
        }
        addIfPresent(out, eventsFigure(analystView));
        addIfPresent(out, streetBandFigure(usStats, analystView, snapshot));
        addIfPresent(out, insiderFigure(insiderDealings));
        addIfPresent(out, shortFigure(shortInterest));
        addIfPresent(out, usShortHistoryFigure(usStats));
        addIfPresent(out, memoryEventsFigure(memoryEvents));
        if (deepDive != null) {
            addIfPresent(out, srFigure(deepDive.technicalView(), snapshot));
        }
        return out;
    }

    // ---- 0. the key-facts strip (stat tiles) — section Worum es geht ----

    /**
     * The white-paper hero strip: the handful of numbers a retail reader needs
     * on sight — price, market cap, forward P/E, dividend yield, consensus
     * target, next report date. Deterministic, so the essentials are visible
     * whatever the prose does.
     */
    private ChartFigure factsFigure(MarketSnapshot s, CompanyDeepDive d, AnalystView av) {
        record Tile(String label, String value, String sub) {}
        List<Tile> tiles = new ArrayList<>();
        if (s != null && s.hasPrice()) {
            String sub = Double.isFinite(s.dayChangePercent()) ? pct(s.dayChangePercent()) : null;
            String cur = "EUR".equals(s.currency()) ? " €"
                    : "PTS".equals(s.currency()) ? (de ? " Pkt" : " pts")
                    : s.currency() == null ? "" : " " + s.currency();
            tiles.add(new Tile(de ? "KURS" : "PRICE", fmt(s.price(), 2) + cur, sub));
        }
        CompanyDeepDive.Profile p = d != null ? d.profile() : null;
        if (p != null && Double.isFinite(p.marketCapEur())) {
            tiles.add(new Tile(de ? "MARKTKAP." : "MARKET CAP",
                    fmt(p.marketCapEur() / 1e9, 1) + (de ? " Mrd. €" : "B €"), null));
        }
        if (d != null) {
            // Forward valuation: the NEAREST estimate year carrying a P/E.
            for (CompanyDeepDive.KeyFigureYear y : d.keyFigures()) {
                if (y.estimate() && Double.isFinite(y.peRatio())) {
                    tiles.add(new Tile((de ? "KGV " : "P/E ") + y.label(),
                            fmt(y.peRatio(), 1),
                            Double.isFinite(y.dividendYieldPercent())
                                    ? (de ? "Div. " : "Div. ") + fmt(y.dividendYieldPercent(), 1) + " %"
                                    : null));
                    break;
                }
            }
        }
        if (av != null && av.hasRatings() && Double.isFinite(av.targetPrice())) {
            tiles.add(new Tile(de ? "KURSZIEL Ø" : "TARGET AVG",
                    fmt(av.targetPrice(), 2)
                            + (av.targetCurrency() != null && av.targetCurrency().equals("EUR")
                                    ? " €" : av.targetCurrency() == null ? "" : " " + av.targetCurrency()),
                    Double.isFinite(av.expectedUpsidePercent())
                            ? pct(av.expectedUpsidePercent()) + (de ? " Potenzial" : " upside") : null));
        }
        if (av != null) {
            AnalystView.CorporateEvent next = av.nextEvent(java.time.Instant.now().getEpochSecond());
            if (next != null) {
                tiles.add(new Tile(de ? "NÄCHSTER TERMIN" : "NEXT EVENT",
                        isoToDe(java.time.Instant.ofEpochSecond(next.atEpochSeconds())),
                        truncate(next.title(), 26)));
            }
        }
        if (tiles.size() < 3) return null;
        if (tiles.size() > 6) tiles = tiles.subList(0, 6);

        int cols = 3;
        int rows = (tiles.size() + cols - 1) / cols;
        int tileH = 52, padT = 8;
        int h = padT + rows * tileH;
        double colW = (double) W / cols;
        StringBuilder svg = open(h);
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            double x = (i % cols) * colW + 4;
            double y = padT + (i / cols) * tileH;
            text(svg, x, y + 12, "start", 10, MUTE, tile.label(), false);
            text(svg, x, y + 32, "start", 17, INK, tile.value(), true);
            if (tile.sub() != null) text(svg, x, y + 46, "start", 10, MUTE, tile.sub(), false);
        }
        svg.append("</svg>");
        String title = de ? "Auf einen Blick" : "At a glance";
        return new ChartFigure(SEC_ABOUT, title, de ? "alle Quellen" : "all sources", svg.toString());
    }

    // ---- upcoming dated events — section Katalysatoren und Risiken ----

    /**
     * The date board: report and dividend dates as a deterministic figure, so
     * "Q2-Zahlen am 06.08." can never fall out of the report, whatever the
     * prose does. Next event leads, emphasized.
     */
    private ChartFigure eventsFigure(AnalystView av) {
        if (av == null || av.events().isEmpty()) return null;
        long nowEpoch = java.time.Instant.now().getEpochSecond();
        List<AnalystView.CorporateEvent> upcoming = av.events().stream()
                .filter(e -> e.atEpochSeconds() >= nowEpoch)
                .limit(4)
                .toList();
        if (upcoming.isEmpty()) return null;

        int rowH = 24, padT = 10;
        int h = padT + upcoming.size() * rowH;
        StringBuilder svg = open(h);
        for (int i = 0; i < upcoming.size(); i++) {
            AnalystView.CorporateEvent e = upcoming.get(i);
            double y = padT + i * rowH + 14;
            boolean next = i == 0;
            if (next) { // amber-style tick like the report crossheads: the one to watch
                svg.append("<rect x=\"4\" y=\"").append(r1(y - 12)).append("\" width=\"3\" height=\"16\" rx=\"1.5\" fill=\"")
                        .append(S1).append("\"/>");
            }
            text(svg, 16, y, "start", 12, next ? INK : MUTE,
                    isoToDe(java.time.Instant.ofEpochSecond(e.atEpochSeconds())), next);
            text(svg, 110, y, "start", 11, next ? INK : MUTE,
                    truncate(e.title() != null ? e.title() : (e.type() == null ? "" : e.type()), 66), false);
        }
        svg.append("</svg>");
        String title = de ? "Anstehende Termine" : "Upcoming dates";
        return new ChartFigure(SEC_OUTLOOK, title, "Consorsbank", svg.toString());
    }

    /**
     * The quant-signal board — one row per house-computed signal (title +
     * formatted value), section Lage. The strip mirrors the material's QUANT
     * SIGNALS block so the reader sees the same numbers the author saw; the
     * time-series charts over these values come later from the archived
     * {@code signals} components of past reports.
     */
    ChartFigure signalsFigure(List<de.bsommerfeld.wsbg.terminal.db.SignalValue> signals) {
        if (signals == null || signals.isEmpty()) return null;
        List<de.bsommerfeld.wsbg.terminal.db.SignalValue> rows =
                signals.size() > 8 ? signals.subList(0, 8) : signals;
        int rowH = 24, padT = 10;
        int h = padT + rows.size() * rowH;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            var s = rows.get(i);
            double y = padT + i * rowH + 14;
            svg.append("<rect x=\"4\" y=\"").append(r1(y - 12))
                    .append("\" width=\"3\" height=\"16\" rx=\"1.5\" fill=\"")
                    .append(S1).append("\"/>");
            text(svg, 16, y, "start", 11, INK, truncate(s.title(), 38), false);
            text(svg, W - 8, y, "end", 11, MUTE, truncate(s.formattedValue(), 44), false);
        }
        svg.append("</svg>");
        String title = de ? "Quant-Signale (im Code berechnet)" : "Quant signals (house-computed)";
        return new ChartFigure(SEC_SITUATION, title,
                de ? "Haus-Statistik" : "house statistics", svg.toString());
    }

    private String isoToDe(java.time.Instant instant) {
        java.time.LocalDate date = java.time.LocalDate.ofInstant(instant, java.time.ZoneId.systemDefault());
        return de
                ? String.format(Locale.ROOT, "%02d.%02d.%d", date.getDayOfMonth(), date.getMonthValue(), date.getYear())
                : date.toString();
    }

    private static void addIfPresent(List<ChartFigure> out, ChartFigure f) {
        if (f != null) out.add(f);
    }

    // ---- 1. price history (line + wash) — section Lage ----

    private ChartFigure priceFigure(MarketSnapshot s) {
        if (s == null) return null;
        List<Double> series = s.dailyCloses().size() >= 2 ? s.dailyCloses()
                : (s.spark().size() >= 2 ? s.spark() : null);
        if (series == null) return null;
        boolean daily = s.dailyCloses().size() >= 2;
        List<Double> vals = series.stream().filter(v -> v != null && Double.isFinite(v)).toList();
        if (vals.size() < 2) return null;

        int h = 150, padL = 10, padR = 84, padT = 12, padB = 10;
        double min = vals.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = vals.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double span = max - min == 0 ? 1 : max - min;
        int plotW = W - padL - padR, plotH = h - padT - padB;

        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < vals.size(); i++) {
            double x = padL + (double) i / (vals.size() - 1) * plotW;
            double y = padT + (1 - (vals.get(i) - min) / span) * plotH;
            if (i > 0) pts.append(' ');
            pts.append(r1(x)).append(',').append(r1(y));
        }
        double lastX = padL + plotW;
        double lastY = padT + (1 - (vals.get(vals.size() - 1) - min) / span) * plotH;
        boolean up = vals.get(vals.size() - 1) >= vals.get(0);
        String tone = up ? POS : NEG;

        StringBuilder svg = open(h);
        for (int g = 0; g <= 2; g++) { // three recessive hairlines
            double gy = padT + g * plotH / 2.0;
            line(svg, padL, gy, padL + plotW, gy, GRID, 1);
        }
        svg.append("<path d=\"M ").append(r1(padL)).append(',').append(r1(padT + plotH))
                .append(" L ").append(pts).append(" L ").append(r1(lastX)).append(',')
                .append(r1(padT + plotH)).append(" Z\" fill=\"").append(tone)
                .append("\" opacity=\"0.1\"/>");
        svg.append("<polyline points=\"").append(pts).append("\" fill=\"none\" stroke=\"")
                .append(tone).append("\" stroke-width=\"2\" stroke-linejoin=\"round\" ")
                .append("stroke-linecap=\"round\"/>");
        svg.append("<circle cx=\"").append(r1(lastX)).append("\" cy=\"").append(r1(lastY))
                .append("\" r=\"4\" fill=\"").append(tone).append("\" stroke=\"").append(SURFACE)
                .append("\" stroke-width=\"2\"/>");
        // Direct labels: last value at the marker; min/max muted at the right
        // edge, each SKIPPED when the last-value label already sits there
        // (the last close IS the min/max often enough — collided labels are noise).
        double lastLabelY = clamp(lastY + 4, padT + 10, padT + plotH);
        text(svg, lastX + 8, lastLabelY, "start", 12, INK,
                fmt(vals.get(vals.size() - 1), 2), true);
        if (Math.abs(padT + 4 - lastLabelY) >= 12) {
            text(svg, lastX + 8, padT + 4, "start", 10, MUTE, fmt(max, 2), false);
        }
        if (Math.abs(padT + plotH - lastLabelY) >= 12) {
            text(svg, lastX + 8, padT + plotH, "start", 10, MUTE, fmt(min, 2), false);
        }
        svg.append("</svg>");

        String title = daily
                ? (de ? "Kursverlauf (Tagesschlusskurse, " + vals.size() + " Handelstage)"
                      : "Price history (daily closes, " + vals.size() + " trading days)")
                : (de ? "Kursverlauf (Intraday)" : "Price history (intraday)");
        String note = s.exchangeName() != null && !s.exchangeName().isBlank()
                ? s.exchangeName() : "L&S";
        return new ChartFigure(SEC_SITUATION, title, note, svg.toString());
    }

    // ---- 2. EPS + dividend per fiscal year (grouped columns) — Fundamentaldaten ----

    private ChartFigure epsDividendFigure(List<CompanyDeepDive.KeyFigureYear> years) {
        List<CompanyDeepDive.KeyFigureYear> rows = years == null ? List.of() : years.stream()
                .filter(y -> Double.isFinite(y.eps()) || Double.isFinite(y.dividendPerShare()))
                .toList();
        if (rows.size() < 2) return null;
        if (rows.size() > 6) rows = rows.subList(rows.size() - 6, rows.size());

        String legend = de ? "Gewinn je Aktie|Dividende je Aktie" : "Earnings per share|Dividend per share";
        StringBuilder svg = groupedColumns(rows.stream().map(y -> new Group(
                        y.label(),
                        new double[]{fin(y.eps()), fin(y.dividendPerShare())},
                        y.estimate())).toList(),
                new String[]{S1, S2}, legend.split("\\|"), 2);
        String title = de ? "Gewinn und Dividende je Aktie nach Geschäftsjahr (e = Schätzung)"
                : "Earnings and dividend per share by fiscal year (e = estimate)";
        return svg == null ? null : new ChartFigure(SEC_FUNDAMENTALS, title, "Consorsbank", svg.toString());
    }

    // ---- 3. turnover + net income (grouped columns, thousands EUR) ----

    private ChartFigure revenueProfitFigure(List<CompanyDeepDive.BalanceSheetYear> years) {
        List<CompanyDeepDive.BalanceSheetYear> rows = years == null ? List.of() : years.stream()
                .filter(y -> Double.isFinite(y.turnover()) || Double.isFinite(y.netIncome()))
                .toList();
        if (rows.size() < 2) return null;

        String legend = de ? "Umsatz|Nettogewinn" : "Revenue|Net income";
        StringBuilder svg = groupedColumns(rows.stream().map(y -> new Group(
                        y.label(),
                        new double[]{fin(y.turnover()), fin(y.netIncome())},
                        false)).toList(),
                new String[]{S1, S2}, legend.split("\\|"), 0);
        String title = de ? "Umsatz und Nettogewinn nach Geschäftsjahr (Mio. EUR)"
                : "Revenue and net income by fiscal year (EUR millions)";
        return svg == null ? null : new ChartFigure(SEC_FUNDAMENTALS, title, "Consorsbank", svg.toString());
    }

    // ---- 4. analyst distribution now vs 3 months ago (stacked h-bars) ----

    private ChartFigure analystFigure(AnalystView av) {
        if (av == null || !av.hasRatings()) return null;
        int[] now = {av.buy(), av.overweight(), av.hold(), av.underweight(), av.sell()};
        boolean has3m = av.buy3m() >= 0;
        int[] ago = has3m
                ? new int[]{av.buy3m(), av.overweight3m(), av.hold3m(), av.underweight3m(), av.sell3m()}
                : null;
        // Tier colors: a diverging read — full/soft positive arm, neutral gray
        // midpoint, soft/full negative arm. Counts ride every segment (relief).
        String[] fills = {POS, POS, MUTE, NEG, NEG};
        double[] alpha = {1, 0.55, 0.5, 0.55, 1};
        String[] tierNames = (de ? "Kaufen|Übergewichten|Halten|Untergewichten|Verkaufen"
                : "Buy|Overweight|Hold|Underweight|Sell").split("\\|");

        int rowCount = has3m ? 2 : 1;
        int barH = 18, rowGap = 14, labelW = 88, padT = 10, legendH = 24;
        int h = padT + rowCount * (barH + rowGap) + legendH;
        int plotW = W - labelW - 60;
        int total = java.util.Arrays.stream(now).sum();
        int totalAgo = has3m ? java.util.Arrays.stream(ago).sum() : 0;
        int scaleMax = Math.max(total, totalAgo);
        if (scaleMax <= 0) return null;

        StringBuilder svg = open(h);
        String[] rowLabels = {de ? "Heute" : "Now", de ? "vor 3 Mon." : "3 mo ago"};
        int[][] rows = has3m ? new int[][]{now, ago} : new int[][]{now};
        for (int r = 0; r < rows.length; r++) {
            double y = padT + r * (barH + rowGap);
            text(svg, labelW - 8, y + barH / 2.0 + 4, "end", 11, MUTE, rowLabels[r], false);
            double x = labelW;
            for (int t = 0; t < 5; t++) {
                int v = rows[r][t];
                if (v <= 0) continue;
                double w = (double) v / scaleMax * plotW - 2; // 2px surface gap
                if (w < 1) w = 1;
                svg.append("<rect x=\"").append(r1(x)).append("\" y=\"").append(r1(y))
                        .append("\" width=\"").append(r1(w)).append("\" height=\"").append(barH)
                        .append("\" rx=\"2\" fill=\"").append(fills[t])
                        .append("\" opacity=\"").append(alpha[t]).append("\"/>");
                if (w >= 16) { // count fits inside the segment
                    String inkIn = alpha[t] >= 1 ? SURFACE : INK;
                    text(svg, x + w / 2, y + barH / 2.0 + 4, "middle", 10, inkIn,
                            String.valueOf(v), false);
                }
                x += w + 2;
            }
        }
        // Legend: five tiers, swatch + text token.
        double lx = labelW;
        double ly = padT + rowCount * (barH + rowGap) + 12;
        for (int t = 0; t < 5; t++) {
            svg.append("<rect x=\"").append(r1(lx)).append("\" y=\"").append(r1(ly - 8))
                    .append("\" width=\"9\" height=\"9\" rx=\"2\" fill=\"").append(fills[t])
                    .append("\" opacity=\"").append(alpha[t]).append("\"/>");
            text(svg, lx + 13, ly, "start", 10, MUTE, tierNames[t], false);
            lx += 13 + tierNames[t].length() * 5.6 + 16;
        }
        svg.append("</svg>");

        String title = de
                ? "Analysten-Votum (" + total + " Analysten)"
                : "Analyst ratings (" + total + " analysts)";
        return new ChartFigure(SEC_VALUATION, title, "Consorsbank", svg.toString());
    }

    // ---- 5. insider buys vs sells (aggregated h-bars) ----

    private ChartFigure insiderFigure(InsiderDealings id) {
        if (id == null || id.deals().isEmpty()) return null;
        double buyVol = 0, sellVol = 0;
        int buys = 0, sells = 0;
        for (InsiderDealings.InsiderDeal d : id.deals()) {
            boolean buy = d.dealType() != null && d.dealType().toLowerCase(Locale.ROOT).contains("kauf")
                    && !d.dealType().toLowerCase(Locale.ROOT).contains("verkauf");
            double v = Double.isFinite(d.volumeEur()) ? d.volumeEur() : 0;
            if (buy) {
                buys++;
                buyVol += v;
            } else {
                sells++;
                sellVol += v;
            }
        }
        if (buys + sells == 0) return null;
        double maxVol = Math.max(buyVol, sellVol);
        if (maxVol <= 0) return null;

        int barH = 16, rowGap = 12, labelW = 96, padT = 10;
        int h = padT + 2 * (barH + rowGap);
        int plotW = W - labelW - 150;
        StringBuilder svg = open(h);
        String[] names = {de ? "Käufe" : "Buys", de ? "Verkäufe" : "Sells"};
        double[] vols = {buyVol, sellVol};
        int[] counts = {buys, sells};
        String[] tones = {POS, NEG};
        for (int i = 0; i < 2; i++) {
            double y = padT + i * (barH + rowGap);
            text(svg, labelW - 8, y + barH / 2.0 + 4, "end", 11, MUTE, names[i], false);
            double w = Math.max(vols[i] / maxVol * plotW, counts[i] > 0 ? 2 : 0);
            if (w > 0) svg.append(roundedRightBar(labelW, y, w, barH, tones[i]));
            String unit = de ? (counts[i] == 1 ? " Meldung · " : " Meldungen · ")
                    : (counts[i] == 1 ? " filing · " : " filings · ");
            text(svg, labelW + w + 8, y + barH / 2.0 + 4, "start", 11, INK,
                    counts[i] + unit + compactEur(vols[i]), false);
        }
        svg.append("</svg>");
        String title = de ? "Insider-Transaktionen (aggregiertes Volumen)"
                : "Insider transactions (aggregated volume)";
        return new ChartFigure(SEC_CATALYSTS, title, "BaFin", svg.toString());
    }

    // ---- 6. disclosed short positions by holder (h-bars) ----

    private ChartFigure shortFigure(ShortInterest si) {
        if (si == null || si.positions().isEmpty()) return null;
        List<ShortInterest.ShortPosition> rows = si.positions().stream().limit(6).toList();
        double max = rows.stream().mapToDouble(ShortInterest.ShortPosition::percent).max().orElse(0);
        if (max <= 0) return null;

        int barH = 14, rowGap = 10, labelW = 220, padT = 8;
        int h = padT + rows.size() * (barH + rowGap);
        int plotW = W - labelW - 74;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            ShortInterest.ShortPosition p = rows.get(i);
            double y = padT + i * (barH + rowGap);
            text(svg, labelW - 8, y + barH / 2.0 + 4, "end", 11, MUTE,
                    truncate(p.holder(), 34), false);
            double w = Math.max(p.percent() / max * plotW, 2);
            svg.append(roundedRightBar(labelW, y, w, barH, S1));
            text(svg, labelW + w + 8, y + barH / 2.0 + 4, "start", 11, INK,
                    fmt(p.percent(), 2) + " %", false);
        }
        svg.append("</svg>");
        String title = de
                ? "Gemeldete Shortpositionen (gesamt " + fmt(si.totalDisclosedPercent(), 2) + " %)"
                : "Disclosed short positions (total " + fmt(si.totalDisclosedPercent(), 2) + " %)";
        return new ChartFigure(SEC_CATALYSTS, title, "Bundesanzeiger", svg.toString());
    }

    // ---- 7. support/resistance ladder — Charttechnik ----

    private record Level(String name, double value, boolean isPrice, boolean isPivot) {}

    /**
     * Level plausibility: supports ascending (S3 &lt; S2 &lt; S1), resistances
     * ascending (R1 &lt; R2 &lt; R3), every support below every resistance.
     * Violations are DATA damage: no figure, no material line. The pivot is
     * deliberately NOT required to be distinct — TradingCentral routinely sets
     * it EQUAL to one of the levels (live: Rheinmetall and SAP both answer
     * pivot == S2); the ladder merges coincident marks into one label instead
     * of nudging them apart as two seemingly different levels.
     */
    static boolean plausibleLevels(CompanyDeepDive.TechnicalView t) {
        if (t == null) return false;
        double[] supports = {t.support3(), t.support2(), t.support1()};
        double[] resistances = {t.resistance1(), t.resistance2(), t.resistance3()};
        double prev = Double.NEGATIVE_INFINITY;
        double maxSupport = Double.NEGATIVE_INFINITY;
        for (double v : supports) {
            if (!Double.isFinite(v)) continue;
            if (v <= prev) return false;
            prev = v;
            maxSupport = v;
        }
        prev = Double.NEGATIVE_INFINITY;
        double minResistance = Double.POSITIVE_INFINITY;
        for (double v : resistances) {
            if (!Double.isFinite(v)) continue;
            if (v <= prev) return false;
            prev = v;
            if (v < minResistance) minResistance = v;
        }
        return !Double.isFinite(maxSupport) || !Double.isFinite(minResistance)
                || maxSupport < minResistance;
    }

    private ChartFigure srFigure(CompanyDeepDive.TechnicalView t, MarketSnapshot s) {
        if (t == null || !plausibleLevels(t)) return null;
        List<Level> levels = new ArrayList<>();
        String[] names = {"R3", "R2", "R1", "S1", "S2", "S3"};
        double[] values = {t.resistance3(), t.resistance2(), t.resistance1(),
                t.support1(), t.support2(), t.support3()};
        for (int i = 0; i < names.length; i++) {
            if (Double.isFinite(values[i])) levels.add(new Level(names[i], values[i], false, false));
        }
        if (Double.isFinite(t.pivot())) {
            // TradingCentral routinely sets the pivot EQUAL to one of the S/R
            // levels (live: SAP/Rheinmetall pivot == S2) — a coincident pivot
            // merges into that mark's label instead of standing beside it as a
            // seemingly different level (the old collision nudge did exactly
            // that with the SAP ladder).
            int coincident = -1;
            for (int i = 0; i < levels.size(); i++) {
                if (Math.abs(levels.get(i).value() - t.pivot()) < 0.005) {
                    coincident = i;
                    break;
                }
            }
            if (coincident >= 0) {
                Level l = levels.get(coincident);
                levels.set(coincident, new Level(l.name() + " · Pivot", l.value(), false, true));
            } else {
                levels.add(new Level("Pivot", t.pivot(), false, true));
            }
        }
        double price = s != null && s.hasPrice() ? s.price() : Double.NaN;
        if (Double.isFinite(price)) levels.add(new Level(de ? "Kurs" : "Price", price, true, false));
        if (levels.size() < 3) return null;

        double min = levels.stream().mapToDouble(Level::value).min().orElse(0);
        double max = levels.stream().mapToDouble(Level::value).max().orElse(1);
        double span = max - min == 0 ? 1 : max - min;
        int h = 190, padT = 14, padB = 14, xL = 64, xR = W - 78;
        int plotH = h - padT - padB;

        // Collision pass: labels sorted by value descending, nudged ≥ 12px apart.
        List<Level> sorted = new ArrayList<>(levels);
        sorted.sort((a, b) -> Double.compare(b.value(), a.value()));
        double[] ys = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            ys[i] = padT + (1 - (sorted.get(i).value() - min) / span) * plotH;
            if (i > 0 && ys[i] - ys[i - 1] < 12) ys[i] = ys[i - 1] + 12;
        }

        StringBuilder svg = open(h);
        for (int i = 0; i < sorted.size(); i++) {
            Level l = sorted.get(i);
            double y = ys[i];
            if (l.isPrice()) {
                line(svg, xL, y, xR, y, S1, 2);
                svg.append("<circle cx=\"").append(r1(xR)).append("\" cy=\"").append(r1(y))
                        .append("\" r=\"4\" fill=\"").append(S1).append("\" stroke=\"")
                        .append(SURFACE).append("\" stroke-width=\"2\"/>");
                text(svg, xL - 8, y + 4, "end", 11, INK, l.name(), true);
                text(svg, xR + 10, y + 4, "start", 11, INK, fmt(l.value(), 2), true);
            } else {
                line(svg, xL, y, xR, y, l.isPivot() ? AXIS : GRID, 1);
                text(svg, xL - 8, y + 4, "end", 10, MUTE, l.name(), false);
                text(svg, xR + 10, y + 4, "start", 10, MUTE, fmt(l.value(), 2), false);
            }
        }
        svg.append("</svg>");
        String title = de ? "Charttechnische Marken (Unterstützungen und Widerstände)"
                : "Chart-technical levels (supports and resistances)";
        return new ChartFigure(SEC_CATALYSTS, title,
                "TradingCentral" + (t.asOfIso() != null ? " · " + t.asOfIso() : ""), svg.toString());
    }

    // ---- 8. performance columns — Charttechnik ----

    private ChartFigure performanceFigure(CompanyDeepDive.PerformanceStats p) {
        if (p == null) return null;
        String[] names = {"1W", "4W", "3M", "6M", "52W"};
        double[] vals = {p.perf1w(), p.perf4w(), p.perf3m(), p.perf6m(), p.perf52w()};
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < vals.length; i++) {
            if (Double.isFinite(vals[i])) idx.add(i);
        }
        if (idx.size() < 2) return null;

        int h = 160, padT = 22, padB = 26, padL = 16, padR = 16;
        int plotH = h - padT - padB;
        // Zero baseline placed proportionally to the data's sign balance.
        double posMax = idx.stream().mapToDouble(i -> Math.max(0, vals[i])).max().orElse(0);
        double negMax = idx.stream().mapToDouble(i -> Math.max(0, -vals[i])).max().orElse(0);
        double totalSpan = posMax + negMax == 0 ? 1 : posMax + negMax;
        double baseY = padT + posMax / totalSpan * plotH;

        int n = idx.size();
        double slot = (double) (W - padL - padR) / n;
        double barW = Math.min(24, slot * 0.4);

        StringBuilder svg = open(h);
        line(svg, padL, baseY, W - padR, baseY, AXIS, 1);
        for (int k = 0; k < n; k++) {
            int i = idx.get(k);
            double v = vals[i];
            double x = padL + slot * k + (slot - barW) / 2;
            double len = Math.abs(v) / totalSpan * plotH;
            String tone = v >= 0 ? POS : NEG;
            // Value label at the data end; the window name pinned to the AXIS on
            // the bar-free side, so long bars can never run into their own tick.
            if (v >= 0) {
                svg.append(roundedTopBar(x, baseY - len, barW, len, tone));
                text(svg, x + barW / 2, baseY - len - 6, "middle", 11, INK, pct(v), false);
                text(svg, x + barW / 2, baseY + 14, "middle", 10, MUTE, names[i], false);
            } else {
                svg.append(roundedBottomBar(x, baseY, barW, len, tone));
                text(svg, x + barW / 2, baseY + len + 14, "middle", 11, INK, pct(v), false);
                text(svg, x + barW / 2, baseY - 6, "middle", 10, MUTE, names[i], false);
            }
        }
        svg.append("</svg>");
        String title = de ? "Kursperformance über Zeiträume" : "Price performance by window";
        return new ChartFigure(SEC_SITUATION, title, "Consorsbank", svg.toString());
    }

    // ---- 9. EBIT margin + equity ratio per fiscal year — Fundamentaldaten ----

    /**
     * The profitability series the prose used to recite: margin and equity
     * ratio per fiscal year as grouped columns, estimates de-emphasized.
     */
    private ChartFigure marginFigure(List<CompanyDeepDive.KeyFigureYear> years) {
        List<CompanyDeepDive.KeyFigureYear> rows = years == null ? List.of() : years.stream()
                .filter(y -> Double.isFinite(y.ebitMarginPercent())
                        || Double.isFinite(y.equityRatioPercent()))
                .toList();
        if (rows.size() < 2) return null;
        if (rows.size() > 6) rows = rows.subList(rows.size() - 6, rows.size());

        String legend = de ? "EBIT-Marge|Eigenkapitalquote" : "EBIT margin|Equity ratio";
        StringBuilder svg = groupedColumns(rows.stream().map(y -> new Group(
                        y.label(),
                        new double[]{fin(y.ebitMarginPercent()), fin(y.equityRatioPercent())},
                        y.estimate())).toList(),
                new String[]{S1, S2}, legend.split("\\|"), 1);
        String title = de
                ? "EBIT-Marge und Eigenkapitalquote nach Geschäftsjahr (%, e = Schätzung)"
                : "EBIT margin and equity ratio by fiscal year (%, e = estimate)";
        return svg == null ? null : new ChartFigure(SEC_FUNDAMENTALS, title, "Consorsbank", svg.toString());
    }

    // ---- 10. cash flow + R&D per fiscal year — Fundamentaldaten ----

    private ChartFigure cashflowFigure(List<CompanyDeepDive.BalanceSheetYear> years) {
        List<CompanyDeepDive.BalanceSheetYear> rows = years == null ? List.of() : years.stream()
                .filter(y -> Double.isFinite(y.cashflowNet()) || Double.isFinite(y.researchExpenses()))
                .toList();
        if (rows.size() < 2) return null;

        String legend = de ? "Cashflow|F&E-Aufwand" : "Cash flow|R&D expenses";
        StringBuilder svg = groupedColumns(rows.stream().map(y -> new Group(
                        y.label(),
                        new double[]{fin(y.cashflowNet()), fin(y.researchExpenses())},
                        false)).toList(),
                new String[]{S1, S2}, legend.split("\\|"), 0);
        String title = de ? "Cashflow und F&E-Aufwand nach Geschäftsjahr"
                : "Cash flow and R&D expenses by fiscal year";
        return svg == null ? null : new ChartFigure(SEC_FUNDAMENTALS, title, "Consorsbank", svg.toString());
    }

    // ---- 11. 52-week range bar — Bewertung ----

    /**
     * The 52-week standing the prose quoted in three sections at once: low and
     * high with their dates on a horizontal track, the current price marked
     * between them, the house-computed distances direct-labeled.
     */
    private ChartFigure rangeFigure(CompanyDeepDive.PerformanceStats p, MarketSnapshot s) {
        if (p == null || !Double.isFinite(p.high52w()) || !Double.isFinite(p.low52w())
                || p.high52w() <= p.low52w()) {
            return null;
        }
        // The Consorsbank range marks are EUR — a USD/foreign-venue snapshot
        // must not be plotted against them (live run 9: a USD price mark on
        // an EUR track put the distance off by ~9 points). The bar itself
        // (low/high with dates) stays; only the price mark needs the match.
        double price = s != null && s.hasPrice() && "EUR".equals(s.currency())
                ? s.price() : Double.NaN;

        int h = 96, xL = 24, xR = W - 24;
        double trackY = 56, trackW = xR - xL;
        StringBuilder svg = open(h);
        // Track with rounded caps; end marks carry value + date.
        svg.append("<line x1=\"").append(xL).append("\" y1=\"").append(r1(trackY))
                .append("\" x2=\"").append(xR).append("\" y2=\"").append(r1(trackY))
                .append("\" stroke=\"").append(GRID).append("\" stroke-width=\"6\" stroke-linecap=\"round\"/>");
        line(svg, xL, trackY - 7, xL, trackY + 7, AXIS, 1);
        line(svg, xR, trackY - 7, xR, trackY + 7, AXIS, 1);
        text(svg, xL, trackY + 22, "start", 11, INK,
                (de ? "Tief " : "Low ") + fmt(p.low52w(), 2), true);
        if (p.low52wDateIso() != null) {
            text(svg, xL, trackY + 36, "start", 10, MUTE, isoDate(p.low52wDateIso()), false);
        }
        text(svg, xR, trackY + 22, "end", 11, INK,
                (de ? "Hoch " : "High ") + fmt(p.high52w(), 2), true);
        if (p.high52wDateIso() != null) {
            text(svg, xR, trackY + 36, "end", 10, MUTE, isoDate(p.high52wDateIso()), false);
        }
        if (Double.isFinite(price)) {
            double frac = clamp((price - p.low52w()) / (p.high52w() - p.low52w()), 0, 1);
            double px = xL + frac * trackW;
            svg.append("<circle cx=\"").append(r1(px)).append("\" cy=\"").append(r1(trackY))
                    .append("\" r=\"6\" fill=\"").append(S1).append("\" stroke=\"").append(SURFACE)
                    .append("\" stroke-width=\"2\"/>");
            String anchor = frac < 0.12 ? "start" : frac > 0.88 ? "end" : "middle";
            text(svg, px, trackY - 26, anchor, 12, INK, fmt(price, 2), true);
            double fromHigh = (price - p.high52w()) / p.high52w() * 100;
            double aboveLow = (price - p.low52w()) / p.low52w() * 100;
            text(svg, px, trackY - 12, anchor, 10, MUTE,
                    pct(fromHigh) + (de ? " vom Hoch · " : " from high · ")
                            + pct(aboveLow) + (de ? " über Tief" : " above low"), false);
        }
        svg.append("</svg>");
        String title = de ? "52-Wochen-Spanne und aktueller Stand"
                : "52-week range and current standing";
        return new ChartFigure(SEC_VALUATION, title, "Consorsbank · L&S", svg.toString());
    }

    // ---- 12. the trading picture (venue stat tiles) — Lage ----

    /**
     * The order-book numbers the prose used to recite: bid/ask with the
     * spread, traded volume in shares and EUR, executions, day range — as a
     * deterministic tile strip from the venue leg.
     */
    private ChartFigure venueFigure(de.bsommerfeld.wsbg.terminal.core.price.VenueStats v) {
        if (v == null) return null;
        record Tile(String label, String value, String sub) {}
        List<Tile> tiles = new ArrayList<>();
        if (Double.isFinite(v.bid()) && Double.isFinite(v.ask()) && v.ask() > 0) {
            double mid = (v.bid() + v.ask()) / 2;
            double spreadPct = mid > 0 ? (v.ask() - v.bid()) / mid * 100 : Double.NaN;
            tiles.add(new Tile(de ? "GELD / BRIEF" : "BID / ASK",
                    fmt(v.bid(), 2) + " / " + fmt(v.ask(), 2),
                    Double.isFinite(spreadPct) ? "Spread " + fmt(spreadPct, 2) + " %" : null));
        }
        if (v.turnoverEur() > 0 || v.volumeShares() > 0) {
            String value = v.turnoverEur() > 0 ? compactEur(v.turnoverEur())
                    : fmt(v.volumeShares(), 0) + (de ? " Stück" : " shares");
            String sub = v.turnoverEur() > 0 && v.volumeShares() > 0
                    ? fmt(v.volumeShares(), 0) + (de ? " Stück" : " shares") : null;
            tiles.add(new Tile(de ? "TAGESUMSATZ" : "DAY TURNOVER", value, sub));
        }
        if (v.executions() > 0) {
            tiles.add(new Tile("TRADES", fmt(v.executions(), 0),
                    de ? "Ausführungen" : "executions"));
        }
        if (Double.isFinite(v.dayLow()) && Double.isFinite(v.dayHigh()) && v.dayHigh() > 0) {
            tiles.add(new Tile(de ? "TAGESSPANNE" : "DAY RANGE",
                    fmt(v.dayLow(), 2) + " – " + fmt(v.dayHigh(), 2), null));
        }
        if (tiles.size() < 2) return null;

        int cols = Math.min(tiles.size(), 4);
        int tileH = 52, padT = 8;
        int h = padT + tileH;
        double colW = (double) W / cols;
        StringBuilder svg = open(h);
        for (int i = 0; i < tiles.size() && i < cols; i++) {
            Tile tile = tiles.get(i);
            double x = (i % cols) * colW + 4;
            double y = padT;
            text(svg, x, y + 12, "start", 10, MUTE, tile.label(), false);
            text(svg, x, y + 32, "start", 15, INK, tile.value(), true);
            if (tile.sub() != null) text(svg, x, y + 46, "start", 10, MUTE, tile.sub(), false);
        }
        svg.append("</svg>");
        String title = de ? "Handelsbild" : "Trading picture";
        return new ChartFigure(SEC_SITUATION, title,
                v.venue() != null && !v.venue().isBlank() ? v.venue() : "Tradegate",
                svg.toString());
    }

    // ---- 21. volume-profile ladder (POC + value area) — Lage ----

    /**
     * The market memory's traded-structure ladder: profile range, the 70 %
     * value area as a shaded band, the POC emphasized WITH the volume that
     * justifies it (a level is only ever justified by the volume behind it),
     * the live price marked between them. The finished profile carries
     * aggregates, not raw buckets — the POC bar's length is its honest share
     * of total traded volume, and the share rides as text beside it.
     */
    private ChartFigure volumeProfileFigure(VolumeProfile.Profile p, MarketSnapshot s) {
        if (p == null || !Double.isFinite(p.poc()) || !Double.isFinite(p.low())
                || !Double.isFinite(p.high()) || p.high() <= p.low()
                || p.totalUnits() <= 0) {
            return null;
        }
        double price = s != null && s.hasPrice() ? s.price() : Double.NaN;
        double min = p.low(), max = p.high();
        if (Double.isFinite(price)) {
            min = Math.min(min, price);
            max = Math.max(max, price);
        }
        double span = max - min == 0 ? 1 : max - min;
        int h = 190, padT = 14, padB = 20, xL = 96, xR = W - 96;
        int plotH = h - padT - padB;

        record Rung(String name, double value, boolean isPrice, boolean isPoc) {}
        List<Rung> rungs = new ArrayList<>();
        rungs.add(new Rung(de ? "Profil-Hoch" : "Profile high", p.high(), false, false));
        if (Double.isFinite(p.vah())) rungs.add(new Rung("VAH", p.vah(), false, false));
        rungs.add(new Rung("POC", p.poc(), false, true));
        if (Double.isFinite(p.val())) rungs.add(new Rung("VAL", p.val(), false, false));
        rungs.add(new Rung(de ? "Profil-Tief" : "Profile low", p.low(), false, false));
        if (Double.isFinite(price)) rungs.add(new Rung(de ? "Kurs" : "Price", price, true, false));

        StringBuilder svg = open(h);
        // The 70 % value area as a shaded acceptance band.
        if (Double.isFinite(p.vah()) && Double.isFinite(p.val()) && p.vah() > p.val()) {
            double yVah = padT + (1 - (p.vah() - min) / span) * plotH;
            double yVal = padT + (1 - (p.val() - min) / span) * plotH;
            svg.append("<rect x=\"").append(xL).append("\" y=\"").append(r1(yVah))
                    .append("\" width=\"").append(xR - xL).append("\" height=\"")
                    .append(r1(Math.max(yVal - yVah, 1))).append("\" fill=\"").append(S1)
                    .append("\" opacity=\"0.08\"/>");
        }
        // Collision pass (the srFigure grammar): sorted by value, nudged apart.
        List<Rung> sorted = new ArrayList<>(rungs);
        sorted.sort((a, b) -> Double.compare(b.value(), a.value()));
        double[] ys = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            ys[i] = padT + (1 - (sorted.get(i).value() - min) / span) * plotH;
            if (i > 0 && ys[i] - ys[i - 1] < 12) ys[i] = ys[i - 1] + 12;
        }
        for (int i = 0; i < sorted.size(); i++) {
            Rung rg = sorted.get(i);
            double y = ys[i];
            if (rg.isPrice()) {
                line(svg, xL, y, xR, y, S1, 2);
                svg.append("<circle cx=\"").append(r1(xR)).append("\" cy=\"").append(r1(y))
                        .append("\" r=\"4\" fill=\"").append(S1).append("\" stroke=\"")
                        .append(SURFACE).append("\" stroke-width=\"2\"/>");
                text(svg, xL - 8, y + 4, "end", 11, INK, rg.name(), true);
                text(svg, xR + 10, y + 4, "start", 11, INK, fmt(rg.value(), 2), true);
            } else if (rg.isPoc()) {
                // The POC bar: its length is the POC bucket's share of total
                // traded volume — the level arrives WITH its justification.
                double share = (double) p.pocUnits() / p.totalUnits();
                double w = Math.max(clamp(share, 0, 1) * (xR - xL), 3);
                svg.append(roundedRightBar(xL, y - 4, w, 8, S2));
                text(svg, xL - 8, y + 4, "end", 11, INK, rg.name(), true);
                text(svg, xR + 10, y + 4, "start", 11, INK, fmt(rg.value(), 2), true);
                text(svg, xL + w + 8, y + 4, "start", 9, MUTE,
                        units(p.pocUnits()) + (de ? " Stück · " : " units · ")
                                + fmt(share * 100, 1) + " %", false);
            } else {
                line(svg, xL, y, xR, y, GRID, 1);
                text(svg, xL - 8, y + 4, "end", 10, MUTE, rg.name(), false);
                text(svg, xR + 10, y + 4, "start", 10, MUTE, fmt(rg.value(), 2), false);
            }
        }
        text(svg, xL, h - 4, "start", 9, MUTE,
                (de ? "gehandelt insgesamt " : "total traded ") + units(p.totalUnits())
                        + (de ? " Stück" : " units"), false);
        svg.append("</svg>");
        String title = de
                ? "Volumenprofil: Akzeptanzzonen (POC und 70-%-Value-Area)"
                : "Volume profile: acceptance zones (POC and 70 % value area)";
        String note = de
                ? "hausgerechnet aus Stundenkerzen, ~"
                        + (DeepDiveService.VOLUME_PROFILE_RANGE_DAYS / 30) + " Monate (Yahoo)"
                : "house-computed from hourly bars, ~"
                        + (DeepDiveService.VOLUME_PROFILE_RANGE_DAYS / 30) + " months (Yahoo)";
        return new ChartFigure(SEC_SITUATION, title, note, svg.toString());
    }

    // ---- 22. order-book depth ladder (bids left, asks right) — Lage ----

    /**
     * Who stands there NOW: the visible window of the floor specialist book as
     * a mirrored depth ladder — bid levels left (green), ask levels right
     * (red), the price labels down the center spine, resting orders and units
     * as text at every level. An empty book is no figure.
     */
    private ChartFigure orderBookFigure(OrderBookSnapshot book, MarketSnapshot s) {
        if (book == null || (book.bids().isEmpty() && book.asks().isEmpty())) return null;
        List<OrderBookSnapshot.Level> bids = book.bids().size() > 10
                ? book.bids().subList(0, 10) : book.bids();
        List<OrderBookSnapshot.Level> asks = book.asks().size() > 10
                ? book.asks().subList(0, 10) : book.asks();
        long maxUnits = 0;
        for (OrderBookSnapshot.Level l : bids) maxUnits = Math.max(maxUnits, l.units());
        for (OrderBookSnapshot.Level l : asks) maxUnits = Math.max(maxUnits, l.units());
        if (maxUnits <= 0) return null;

        int rows = Math.max(bids.size(), asks.size());
        int barH = 14, rowGap = 8, padT = 26;
        int h = padT + rows * (barH + rowGap);
        double centerX = W / 2.0;
        double bx = centerX - 62, ax = centerX + 62; // bar baselines beside the price spine
        double half = bx - 70;                        // leave room for the outer text
        StringBuilder svg = open(h);
        text(svg, bx, 14, "end", 10, MUTE, de ? "GELD (Kauf)" : "BID (buy)", true);
        text(svg, ax, 14, "start", 10, MUTE, de ? "BRIEF (Verkauf)" : "ASK (sell)", true);
        line(svg, centerX, padT - 4, centerX, h - 4, AXIS, 1);
        for (int i = 0; i < rows; i++) {
            double y = padT + i * (barH + rowGap);
            double cy = y + barH / 2.0 + 4;
            if (i < bids.size()) {
                OrderBookSnapshot.Level l = bids.get(i);
                double w = Math.max((double) l.units() / maxUnits * half, 2);
                svg.append(roundedLeftBar(bx - w, y, w, barH, POS));
                text(svg, centerX - 6, cy, "end", 10, INK, fmt(l.price(), 2), i == 0);
                text(svg, bx - w - 6, cy, "end", 9, MUTE, levelLabel(l), false);
            }
            if (i < asks.size()) {
                OrderBookSnapshot.Level l = asks.get(i);
                double w = Math.max((double) l.units() / maxUnits * half, 2);
                svg.append(roundedRightBar(ax, y, w, barH, NEG));
                text(svg, centerX + 6, cy, "start", 10, INK, fmt(l.price(), 2), i == 0);
                text(svg, ax + w + 6, cy, "start", 9, MUTE, levelLabel(l), false);
            }
        }
        svg.append("</svg>");
        String title = de
                ? "Orderbuch-Tiefe (sichtbares Fenster, " + bids.size() + "×" + asks.size()
                        + " Level)"
                : "Order-book depth (visible window, " + bids.size() + "×" + asks.size()
                        + " levels)";
        String note = "Börse Frankfurt"
                + (book.time() != null && !book.time().isBlank() ? " · " + book.time() : "");
        return new ChartFigure(SEC_SITUATION, title, note, svg.toString());
    }

    /** Resting interest of one book level: units, plus the order count where published. */
    private String levelLabel(OrderBookSnapshot.Level l) {
        String base = units(l.units());
        return l.orders() > 0
                ? base + " · " + l.orders() + (de ? " Ord." : " ord.")
                : base;
    }

    // ---- 23. the instrument's event history (market memory) — Katalysatoren ----

    /**
     * "Was war" measured: the house register's dated events on a time axis,
     * one mark per event colored by the SIGN of its measured reaction
     * (CAR(−1,+1)); unmeasured events stay muted. The class token and the
     * measured percent ride as tiny labels — only fields the record carries,
     * never an invented probability. Newest ~20 events.
     */
    private ChartFigure memoryEventsFigure(List<MarketEventRecord> events) {
        if (events == null || events.isEmpty()) return null;
        List<MarketEventRecord> tail = events.size() > 20
                ? events.subList(events.size() - 20, events.size()) : events;
        record Ev(java.time.LocalDate date, MarketEventRecord rec) {}
        List<Ev> rows = new ArrayList<>();
        for (MarketEventRecord e : tail) {
            if (e.date() == null) continue;
            try {
                rows.add(new Ev(java.time.LocalDate.parse(e.date()), e));
            } catch (Exception ignored) {
                // an unparseable register date is no mark
            }
        }
        if (rows.isEmpty()) return null;
        java.time.LocalDate min = rows.stream().map(Ev::date)
                .min(java.time.LocalDate::compareTo).orElseThrow();
        java.time.LocalDate max = rows.stream().map(Ev::date)
                .max(java.time.LocalDate::compareTo).orElseThrow();
        if (min.equals(max)) max = max.plusDays(1);
        long spanDays = java.time.temporal.ChronoUnit.DAYS.between(min, max);

        int h = 118, xL = 20, xR = W - 20;
        double axisY = 88;
        StringBuilder svg = open(h);
        line(svg, xL, axisY, xR, axisY, AXIS, 1);
        for (int i = 0; i < rows.size(); i++) {
            Ev ev = rows.get(i);
            MarketEventRecord e = ev.rec();
            double x = xL + (double) java.time.temporal.ChronoUnit.DAYS.between(min, ev.date())
                    / spanDays * (xR - xL);
            String tone = e.carEvent() == null ? MUTE : e.carEvent() >= 0 ? POS : NEG;
            svg.append("<circle cx=\"").append(r1(x)).append("\" cy=\"").append(r1(axisY))
                    .append("\" r=\"4.5\" fill=\"").append(tone).append("\" stroke=\"")
                    .append(SURFACE).append("\" stroke-width=\"1.5\"/>");
            // Two label lanes above the axis so neighbouring events don't collide.
            double labelY = i % 2 == 0 ? axisY - 42 : axisY - 16;
            double lx = clamp(x, xL + 20, xR - 20);
            line(svg, x, axisY - 6, x, labelY + (e.carEvent() != null ? 14 : 4), GRID, 1);
            text(svg, lx, labelY, "middle", 8, MUTE,
                    truncate(e.eventClass() == null ? "?" : e.eventClass(), 14), false);
            if (e.carEvent() != null) {
                text(svg, lx, labelY + 10, "middle", 9, INK, pct(e.carEvent()), false);
            }
        }
        // Edge dates beneath the axis.
        text(svg, xL, axisY + 16, "start", 9, MUTE, isoDate(min.toString()), false);
        text(svg, xR, axisY + 16, "end", 9, MUTE, isoDate(max.toString()), false);
        svg.append("</svg>");
        String title = de
                ? "Ereignis-Historie (" + rows.size()
                        + " Ereignisse; Farbe = Vorzeichen der Reaktion CAR(−1,+1))"
                : "Event history (" + rows.size()
                        + " events; color = sign of the measured reaction CAR(−1,+1))";
        return new ChartFigure(SEC_CATALYSTS, title,
                de ? "eigenes Ereignis-Register" : "house event register", svg.toString());
    }

    /** A units count: full grouped digits below a million, compact above. */
    private String units(long v) {
        return v >= 1_000_000 ? compactNum(v) : fmt(v, 0);
    }

    /** Mirrored horizontal bar: rounded LEFT data-end, square at the right (spine) side. */
    private static String roundedLeftBar(double x, double y, double w, double h, String fill) {
        if (w <= 4) {
            return "<rect x=\"" + r1(x) + "\" y=\"" + r1(y) + "\" width=\"" + r1(Math.max(w, 1))
                    + "\" height=\"" + r1(h) + "\" fill=\"" + fill + "\"/>";
        }
        double r = 4, x2 = x + w;
        return "<path d=\"M " + r1(x2) + ' ' + r1(y)
                + " L " + r1(x + r) + ' ' + r1(y)
                + " Q " + r1(x) + ' ' + r1(y) + ' ' + r1(x) + ' ' + r1(y + r)
                + " L " + r1(x) + ' ' + r1(y + h - r)
                + " Q " + r1(x) + ' ' + r1(y + h) + ' ' + r1(x + r) + ' ' + r1(y + h)
                + " L " + r1(x2) + ' ' + r1(y + h) + " Z\" fill=\"" + fill + "\"/>";
    }

    /** An ISO date string rendered in the report language (dotted German). */
    private String isoDate(String iso) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(iso);
            return de
                    ? String.format(Locale.ROOT, "%02d.%02d.%d",
                            d.getDayOfMonth(), d.getMonthValue(), d.getYear())
                    : iso;
        } catch (Exception e) {
            return iso;
        }
    }

    // ---- 13. analyst-action timeline (targets old → new) — Bewertung ----

    /**
     * The street's dated action trail: one row per action, newest first, the
     * target move drawn as an arrow on a shared value scale (green up, red
     * down) with the new target direct-labeled at the right edge. Actions
     * without both target halves keep their dated row — a single target draws
     * a tick mark, a target-less action stays a labeled row. The scale needs
     * ONE currency: the first target-carrying action sets it; rows in another
     * currency keep their label but draw no mark.
     */
    private ChartFigure actionsFigure(AnalystActions aa) {
        if (aa == null || aa.actions() == null || aa.actions().isEmpty()) return null;
        List<AnalystActions.Action> rows = aa.actions().stream()
                .filter(a -> a.dateIso() != null && a.brokerage() != null)
                .limit(10)
                .toList();
        if (rows.isEmpty()) return null;
        String cur = rows.stream()
                .filter(a -> a.targetCurrency() != null
                        && (Double.isFinite(a.targetOld()) || Double.isFinite(a.targetNew())))
                .map(AnalystActions.Action::targetCurrency)
                .findFirst().orElse(null);
        // A label-only history is the actions TABLE's job, not a figure's.
        if (cur == null) return null;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (AnalystActions.Action a : rows) {
            if (!cur.equals(a.targetCurrency())) continue;
            for (double v : new double[]{a.targetOld(), a.targetNew()}) {
                if (Double.isFinite(v) && v > 0) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) return null;
        if (min == max) { // one lone value still needs a span
            min *= 0.95;
            max *= 1.05;
        }
        double span = max - min;

        int rowH = 24, padT = 8;
        int h = padT + rows.size() * rowH;
        double xS = 306, xE = W - 58;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            AnalystActions.Action a = rows.get(i);
            double y = padT + i * rowH + 12;
            text(svg, 4, y + 4, "start", 10, MUTE, isoDate(a.dateIso()), false);
            text(svg, 68, y + 4, "start", 10, INK, truncate(a.brokerage(), 18), false);
            String rating = a.ratingOld() != null && a.ratingNew() != null
                    ? a.ratingOld() + " → " + a.ratingNew()
                    : a.ratingNew() != null ? a.ratingNew()
                    : a.ratingOld() != null ? a.ratingOld()
                    : a.actionType() != null ? a.actionType() : "";
            text(svg, 178, y + 4, "start", 9, MUTE, truncate(rating, 24), false);
            boolean inScale = cur.equals(a.targetCurrency());
            boolean hasOld = inScale && Double.isFinite(a.targetOld()) && a.targetOld() > 0;
            boolean hasNew = inScale && Double.isFinite(a.targetNew()) && a.targetNew() > 0;
            if (hasOld && hasNew) {
                double xO = xS + (a.targetOld() - min) / span * (xE - xS);
                double xN = xS + (a.targetNew() - min) / span * (xE - xS);
                String tone = a.targetNew() > a.targetOld() ? POS
                        : a.targetNew() < a.targetOld() ? NEG : MUTE;
                line(svg, xO, y, xN, y, tone, 2);
                double dx = xN >= xO ? 1 : -1;
                svg.append("<path d=\"M ").append(r1(xN + 3 * dx)).append(' ').append(r1(y))
                        .append(" L ").append(r1(xN - 3 * dx)).append(' ').append(r1(y - 3.5))
                        .append(" L ").append(r1(xN - 3 * dx)).append(' ').append(r1(y + 3.5))
                        .append(" Z\" fill=\"").append(tone).append("\"/>");
                text(svg, xE + 10, y + 4, "start", 10, INK, fmt(a.targetNew(), 2), true);
            } else if (hasOld || hasNew) {
                double v = hasNew ? a.targetNew() : a.targetOld();
                double x = xS + (v - min) / span * (xE - xS);
                line(svg, x, y - 5, x, y + 5, AXIS, 2);
                text(svg, xE + 10, y + 4, "start", 10, MUTE, fmt(v, 2), false);
            }
        }
        svg.append("</svg>");
        String title = de
                ? "Analysten-Aktionen (Kursziele alt → neu, " + cur + ")"
                : "Analyst actions (price targets old → new, " + cur + ")";
        return new ChartFigure(SEC_VALUATION, title, "MarketBeat", svg.toString());
    }

    // ---- 14. US short-interest history (FINRA settlements) — Katalysatoren ----

    /**
     * The bi-monthly FINRA settlement series as a line (shares held short),
     * each point direct-labeled with its days-to-cover — the two numbers the
     * prose used to recite side by side.
     */
    private ChartFigure usShortHistoryFigure(UsListingStats us) {
        if (us == null || us.shortInterest() == null) return null;
        List<UsListingStats.ShortInterestPoint> pts = us.shortInterest().stream()
                .filter(p -> p.settlementDateIso() != null && p.shortInterestShares() >= 0)
                .toList();
        if (pts.size() < 2) return null;
        if (pts.size() > 12) pts = pts.subList(0, 12);
        List<UsListingStats.ShortInterestPoint> chron = new ArrayList<>(pts);
        java.util.Collections.reverse(chron); // newest first → chronological

        int h = 150, padL = 14, padR = 90, padT = 24, padB = 22;
        int plotW = W - padL - padR, plotH = h - padT - padB;
        double min = chron.stream().mapToDouble(p -> p.shortInterestShares()).min().orElse(0);
        double max = chron.stream().mapToDouble(p -> p.shortInterestShares()).max().orElse(1);
        double span = max - min == 0 ? 1 : max - min;

        StringBuilder svg = open(h);
        for (int g = 0; g <= 2; g++) {
            double gy = padT + g * plotH / 2.0;
            line(svg, padL, gy, padL + plotW, gy, GRID, 1);
        }
        StringBuilder poly = new StringBuilder();
        for (int i = 0; i < chron.size(); i++) {
            double x = padL + (double) i / (chron.size() - 1) * plotW;
            double y = padT + (1 - (chron.get(i).shortInterestShares() - min) / span) * plotH;
            if (i > 0) poly.append(' ');
            poly.append(r1(x)).append(',').append(r1(y));
        }
        svg.append("<polyline points=\"").append(poly).append("\" fill=\"none\" stroke=\"")
                .append(S1).append("\" stroke-width=\"2\" stroke-linejoin=\"round\" ")
                .append("stroke-linecap=\"round\"/>");
        for (int i = 0; i < chron.size(); i++) {
            UsListingStats.ShortInterestPoint p = chron.get(i);
            double x = padL + (double) i / (chron.size() - 1) * plotW;
            double y = padT + (1 - (p.shortInterestShares() - min) / span) * plotH;
            svg.append("<circle cx=\"").append(r1(x)).append("\" cy=\"").append(r1(y))
                    .append("\" r=\"3\" fill=\"").append(S1).append("\" stroke=\"").append(SURFACE)
                    .append("\" stroke-width=\"1.5\"/>");
            if (Double.isFinite(p.daysToCover())) {
                text(svg, x, clamp(y - 8, 10, h - padB), "middle", 9, MUTE,
                        fmt(p.daysToCover(), 1), false);
            }
        }
        UsListingStats.ShortInterestPoint last = chron.get(chron.size() - 1);
        double lastY = padT + (1 - (last.shortInterestShares() - min) / span) * plotH;
        text(svg, padL + plotW + 8, clamp(lastY + 4, padT + 8, padT + plotH), "start", 11, INK,
                compactNum(last.shortInterestShares()), true);
        text(svg, padL, h - 6, "start", 9, MUTE, isoDate(chron.get(0).settlementDateIso()), false);
        text(svg, padL + plotW, h - 6, "end", 9, MUTE, isoDate(last.settlementDateIso()), false);
        svg.append("</svg>");
        String title = de
                ? "US-Short-Interest je Stichtag (Aktien; Punktwerte = Days to Cover)"
                : "US short interest by settlement (shares; point labels = days to cover)";
        return new ChartFigure(SEC_CATALYSTS, title, "NASDAQ · FINRA", svg.toString());
    }

    // ---- 15. earnings-surprise strip (beat/miss per quarter) — Fundamentaldaten ----

    /**
     * The quarterly track record against the street: one lollipop per quarter,
     * green above / red below consensus, the surprise percent as text — every
     * mark carries its signed value (the CVD secondary encoding).
     */
    private ChartFigure surpriseFigure(UsListingStats us) {
        if (us == null || us.earningsSurprises() == null) return null;
        List<UsListingStats.EarningsSurprise> rows = us.earningsSurprises().stream()
                .filter(q -> q.fiscalQuarter() != null && Double.isFinite(q.surprisePercent()))
                .limit(8)
                .toList();
        if (rows.size() < 2) return null;
        List<UsListingStats.EarningsSurprise> chron = new ArrayList<>(rows);
        java.util.Collections.reverse(chron); // newest first → chronological

        int h = 150, padT = 22, padB = 26, padL = 16, padR = 16;
        int plotH = h - padT - padB;
        double posMax = chron.stream().mapToDouble(q -> Math.max(0, q.surprisePercent())).max().orElse(0);
        double negMax = chron.stream().mapToDouble(q -> Math.max(0, -q.surprisePercent())).max().orElse(0);
        double totalSpan = posMax + negMax == 0 ? 1 : posMax + negMax;
        double baseY = padT + posMax / totalSpan * plotH;
        int n = chron.size();
        double slot = (double) (W - padL - padR) / n;

        StringBuilder svg = open(h);
        line(svg, padL, baseY, W - padR, baseY, AXIS, 1);
        for (int i = 0; i < n; i++) {
            UsListingStats.EarningsSurprise q = chron.get(i);
            double v = q.surprisePercent();
            double x = padL + slot * i + slot / 2;
            double len = Math.abs(v) / totalSpan * plotH;
            String tone = v >= 0 ? POS : NEG;
            double dotY = v >= 0 ? baseY - len : baseY + len;
            line(svg, x, baseY, x, dotY, tone, 1);
            svg.append("<circle cx=\"").append(r1(x)).append("\" cy=\"").append(r1(dotY))
                    .append("\" r=\"5\" fill=\"").append(tone).append("\" stroke=\"").append(SURFACE)
                    .append("\" stroke-width=\"1.5\"/>");
            text(svg, x, v >= 0 ? dotY - 9 : dotY + 16, "middle", 10, INK, pct(v), false);
            text(svg, x, h - 8, "middle", 9, MUTE, truncate(q.fiscalQuarter(), 12), false);
        }
        svg.append("</svg>");
        String title = de
                ? "EPS-Überraschung je Quartal (Ist gegen Konsens)"
                : "EPS surprise by quarter (actual vs consensus)";
        return new ChartFigure(SEC_FUNDAMENTALS, title, "NASDAQ", svg.toString());
    }

    // ---- 16. hedge-fund positioning curve (13F quarters) — Bewertung ----

    /**
     * Insider Monkey's quarterly popularity curve: the fund count as a line,
     * new/closed positions as small +/− bars beneath — the flow behind the
     * level. The still-filling current quarter is de-emphasized.
     */
    private ChartFigure hedgeFundFigure(HedgeFundPopularity hf) {
        if (hf == null || hf.quarters() == null) return null;
        List<HedgeFundPopularity.QuarterPoint> rows = hf.quarters().stream()
                .filter(q -> q.funds() >= 0)
                .toList();
        if (rows.size() < 2) return null;
        if (rows.size() > 12) rows = rows.subList(rows.size() - 12, rows.size());

        int h = 185, padL = 16, padR = 70, padT = 14;
        int lineH = 86;
        int plotW = W - padL - padR;
        double min = rows.stream().mapToDouble(HedgeFundPopularity.QuarterPoint::funds).min().orElse(0);
        double max = rows.stream().mapToDouble(HedgeFundPopularity.QuarterPoint::funds).max().orElse(1);
        double span = max - min == 0 ? 1 : max - min;
        int n = rows.size();

        StringBuilder svg = open(h);
        for (int g = 0; g <= 1; g++) {
            double gy = padT + g * lineH;
            line(svg, padL, gy, padL + plotW, gy, GRID, 1);
        }
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double x = padL + (double) i / (n - 1) * plotW;
            double y = padT + (1 - (rows.get(i).funds() - min) / span) * lineH;
            if (i > 0) pts.append(' ');
            pts.append(r1(x)).append(',').append(r1(y));
        }
        svg.append("<polyline points=\"").append(pts).append("\" fill=\"none\" stroke=\"")
                .append(S1).append("\" stroke-width=\"2\" stroke-linejoin=\"round\" ")
                .append("stroke-linecap=\"round\"/>");
        HedgeFundPopularity.QuarterPoint last = rows.get(n - 1);
        double lastX = padL + plotW;
        double lastY = padT + (1 - (last.funds() - min) / span) * lineH;
        svg.append("<circle cx=\"").append(r1(lastX)).append("\" cy=\"").append(r1(lastY))
                .append("\" r=\"4\" fill=\"").append(S1).append("\" stroke=\"").append(SURFACE)
                .append("\" stroke-width=\"2\"/>");
        text(svg, lastX + 8, lastY + 4, "start", 12, INK,
                last.funds() + (last.ongoing() ? (de ? " (läuft)" : " (open)") : ""), true);
        text(svg, padL, padT + (1 - (rows.get(0).funds() - min) / span) * lineH - 8, "start",
                10, MUTE, String.valueOf(rows.get(0).funds()), false);

        // Flow bars beneath the curve: new positions up (green), closed down (red).
        double flowBase = padT + lineH + 34;
        double flowMax = 1;
        for (HedgeFundPopularity.QuarterPoint q : rows) {
            flowMax = Math.max(flowMax, Math.max(q.newPositions(), q.closedPositions()));
        }
        double slot = (double) plotW / n;
        double barW = Math.min(10, slot * 0.5);
        line(svg, padL, flowBase, padL + plotW, flowBase, AXIS, 1);
        for (int i = 0; i < n; i++) {
            HedgeFundPopularity.QuarterPoint q = rows.get(i);
            double x = padL + slot * i + (slot - barW) / 2;
            if (q.newPositions() > 0) {
                double len = q.newPositions() / flowMax * 20;
                svg.append(roundedTopBar(x, flowBase - len, barW, len, POS));
                text(svg, x + barW / 2, flowBase - len - 3, "middle", 8, MUTE,
                        "+" + q.newPositions(), false);
            }
            if (q.closedPositions() > 0) {
                double len = q.closedPositions() / flowMax * 20;
                svg.append(roundedBottomBar(x, flowBase, barW, len, NEG));
                text(svg, x + barW / 2, flowBase + len + 9, "middle", 8, MUTE,
                        "−" + q.closedPositions(), false);
            }
        }
        text(svg, padL, h - 4, "start", 9, MUTE, rows.get(0).quarterLabel(), false);
        text(svg, padL + plotW, h - 4, "end", 9, MUTE, last.quarterLabel(), false);
        svg.append("</svg>");
        String title = de
                ? "Hedgefonds-Positionierung (Linie: Fonds; Balken: neue/geschlossene Positionen)"
                : "Hedge-fund positioning (line: funds; bars: new/closed positions)";
        return new ChartFigure(SEC_VALUATION, title, "Insider Monkey", svg.toString());
    }

    // ---- 17. street target band on the price ladder — Ausblick ----

    /**
     * The street's target band as a vertical ladder (the srFigure grammar):
     * street high / consensus / street low with the band shaded, the live
     * price marked between them and the house-computed distance to consensus
     * as text — SAME currency only (the scenario table's guard: a
     * cross-currency percentage is the figure-corruption class the EUR guards
     * killed). Primary leg: the NASDAQ target panel (USD); fallback: the
     * Consorsbank consensus target, then a lone mean line needs the price
     * mark to be a figure at all.
     */
    private ChartFigure streetBandFigure(UsListingStats us, AnalystView av, MarketSnapshot s) {
        double lo = Double.NaN, mean = Double.NaN, hi = Double.NaN;
        String cur = null, source = null;
        UsListingStats.AnalystRatings r = us == null ? null : us.analystRatings();
        if (r != null && Double.isFinite(r.meanPriceTargetUsd())
                && Double.isFinite(r.highPriceTargetUsd()) && Double.isFinite(r.lowPriceTargetUsd())
                && r.lowPriceTargetUsd() > 0 && r.highPriceTargetUsd() >= r.lowPriceTargetUsd()) {
            lo = r.lowPriceTargetUsd();
            mean = r.meanPriceTargetUsd();
            hi = r.highPriceTargetUsd();
            cur = "USD";
            source = "NASDAQ";
        } else if (av != null && av.hasRatings() && Double.isFinite(av.targetPrice())
                && av.targetPrice() > 0) {
            mean = av.targetPrice();
            cur = av.targetCurrency();
            source = "Consorsbank";
        } else {
            return null;
        }
        boolean band = Double.isFinite(lo) && Double.isFinite(hi);
        double price = s != null && s.hasPrice() && cur != null && cur.equals(s.currency())
                ? s.price() : Double.NaN;
        if (!band && !Double.isFinite(price)) return null; // a lone mean line is no ladder

        record Rung(String name, double value, boolean isPrice, boolean emph) {}
        List<Rung> rungs = new ArrayList<>();
        if (band) rungs.add(new Rung(de ? "Street-Hoch" : "Street high", hi, false, false));
        rungs.add(new Rung(de ? "Konsens" : "Consensus", mean, false, true));
        if (band) rungs.add(new Rung(de ? "Street-Tief" : "Street low", lo, false, false));
        if (Double.isFinite(price)) rungs.add(new Rung(de ? "Kurs" : "Price", price, true, true));

        double min = rungs.stream().mapToDouble(Rung::value).min().orElse(0);
        double max = rungs.stream().mapToDouble(Rung::value).max().orElse(1);
        double span = max - min == 0 ? 1 : max - min;
        int h = 170, padT = 16, padB = 16, xL = 110, xR = W - 90;
        int plotH = h - padT - padB;

        StringBuilder svg = open(h);
        if (band) {
            double yHi = padT + (1 - (hi - min) / span) * plotH;
            double yLo = padT + (1 - (lo - min) / span) * plotH;
            svg.append("<rect x=\"").append(xL).append("\" y=\"").append(r1(yHi))
                    .append("\" width=\"").append(xR - xL).append("\" height=\"")
                    .append(r1(Math.max(yLo - yHi, 1))).append("\" fill=\"").append(S1)
                    .append("\" opacity=\"0.08\"/>");
        }
        List<Rung> sorted = new ArrayList<>(rungs);
        sorted.sort((a, b) -> Double.compare(b.value(), a.value()));
        double[] ys = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            ys[i] = padT + (1 - (sorted.get(i).value() - min) / span) * plotH;
            if (i > 0 && ys[i] - ys[i - 1] < 12) ys[i] = ys[i - 1] + 12;
        }
        for (int i = 0; i < sorted.size(); i++) {
            Rung rg = sorted.get(i);
            double y = ys[i];
            if (rg.isPrice()) {
                line(svg, xL, y, xR, y, S1, 2);
                svg.append("<circle cx=\"").append(r1(xR)).append("\" cy=\"").append(r1(y))
                        .append("\" r=\"4\" fill=\"").append(S1).append("\" stroke=\"")
                        .append(SURFACE).append("\" stroke-width=\"2\"/>");
                text(svg, xL - 8, y + 4, "end", 11, INK, rg.name(), true);
                text(svg, xR + 10, y + 4, "start", 11, INK, fmt(rg.value(), 2), true);
                double toMean = (mean / rg.value() - 1) * 100;
                text(svg, (xL + xR) / 2.0, y - 6, "middle", 10, MUTE,
                        pct(toMean) + (de ? " bis Konsens" : " to consensus"), false);
            } else {
                line(svg, xL, y, xR, y, rg.emph() ? AXIS : GRID, 1);
                text(svg, xL - 8, y + 4, "end", 10, rg.emph() ? INK : MUTE, rg.name(), rg.emph());
                text(svg, xR + 10, y + 4, "start", 10, rg.emph() ? INK : MUTE,
                        fmt(rg.value(), 2), rg.emph());
            }
        }
        svg.append("</svg>");
        String title = de
                ? "Kursziel-Band der Street (" + cur + ")"
                : "Street target band (" + cur + ")";
        return new ChartFigure(SEC_OUTLOOK, title, source, svg.toString());
    }

    // ---- 18. peer scatter (market cap × P/E) — Bewertung ----

    /**
     * The peer field as a scatter: x = market cap (log scale), y = P/E, the
     * subject highlighted gold. Only peers carrying BOTH values plot; fewer
     * than three points is no field to read.
     */
    private ChartFigure peerScatterFigure(CompanyDeepDive d, MarketSnapshot s) {
        if (d == null) return null;
        record Pt(String name, double mcap, double pe, boolean subject) {}
        List<Pt> pts = new ArrayList<>();
        List<CompanyDeepDive.Peer> peers = d.peers() == null ? List.of() : d.peers();
        for (CompanyDeepDive.Peer p : peers) {
            if (p.name() == null || !Double.isFinite(p.marketCapEur()) || p.marketCapEur() <= 0
                    || !Double.isFinite(p.peRatio()) || p.peRatio() <= 0) {
                continue;
            }
            pts.add(new Pt(p.name(), p.marketCapEur(), p.peRatio(), false));
            if (pts.size() >= 8) break;
        }
        CompanyDeepDive.Profile prof = d.profile();
        double subjPe = Double.NaN;
        if (d.keyFigures() != null) {
            for (CompanyDeepDive.KeyFigureYear y : d.keyFigures()) {
                if (!Double.isFinite(y.peRatio()) || y.peRatio() <= 0) continue;
                if (!y.estimate()) subjPe = y.peRatio(); // latest ACTUAL wins
                else if (!Double.isFinite(subjPe)) subjPe = y.peRatio(); // else nearest estimate
            }
        }
        if (prof != null && Double.isFinite(prof.marketCapEur()) && prof.marketCapEur() > 0
                && Double.isFinite(subjPe)) {
            String name = s != null && s.symbol() != null ? s.symbol()
                    : de ? "Subjekt" : "subject";
            pts.add(new Pt(name, prof.marketCapEur(), subjPe, true));
        }
        if (pts.size() < 3) return null;

        double xMin = Double.POSITIVE_INFINITY, xMax = Double.NEGATIVE_INFINITY;
        double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
        for (Pt p : pts) {
            double lx = Math.log10(p.mcap());
            xMin = Math.min(xMin, lx);
            xMax = Math.max(xMax, lx);
            yMin = Math.min(yMin, p.pe());
            yMax = Math.max(yMax, p.pe());
        }
        if (xMax - xMin < 0.2) { xMin -= 0.1; xMax += 0.1; }
        if (yMax - yMin < 1) { yMin -= 0.5; yMax += 0.5; }
        double xSpan = xMax - xMin, ySpan = yMax - yMin;
        int h = 190, padL = 46, padR = 24, padT = 18, padB = 30;
        int plotW = W - padL - padR, plotH = h - padT - padB;
        String gold = "var(--ddc-sun,#c99a1e)";

        StringBuilder svg = open(h);
        for (int g = 0; g <= 2; g++) {
            double gy = padT + g * plotH / 2.0;
            line(svg, padL, gy, padL + plotW, gy, GRID, 1);
        }
        text(svg, padL - 6, padT + 4, "end", 9, MUTE, (de ? "KGV " : "P/E ") + fmt(yMax, 0), false);
        text(svg, padL - 6, padT + plotH + 4, "end", 9, MUTE, fmt(yMin, 0), false);
        text(svg, padL, h - 6, "start", 9, MUTE, compactEur(Math.pow(10, xMin)), false);
        text(svg, padL + plotW, h - 6, "end", 9, MUTE, compactEur(Math.pow(10, xMax)), false);
        for (Pt p : pts) {
            double x = padL + (Math.log10(p.mcap()) - xMin) / xSpan * plotW;
            double y = padT + (1 - (p.pe() - yMin) / ySpan) * plotH;
            svg.append("<circle cx=\"").append(r1(x)).append("\" cy=\"").append(r1(y))
                    .append("\" r=\"").append(p.subject() ? 6 : 4).append("\" fill=\"")
                    .append(p.subject() ? gold : S1).append("\" stroke=\"").append(SURFACE)
                    .append("\" stroke-width=\"1.5\"/>");
            double lx = clamp(x, padL + 30, padL + plotW - 30);
            text(svg, lx, y - 9, "middle", 9, p.subject() ? INK : MUTE,
                    truncate(p.name(), 18), p.subject());
        }
        svg.append("</svg>");
        String title = de
                ? "Peer-Vergleich: Marktkapitalisierung (log) × KGV"
                : "Peer comparison: market cap (log) × P/E";
        return new ChartFigure(SEC_VALUATION, title, "Consorsbank", svg.toString());
    }

    // ---- 19. press-timeline strip (dated coverage density) — Lage ----

    /**
     * "Was war" as a picture: one tick per dated headline on a horizontal time
     * axis, month labels beneath — coverage density readable at a glance
     * (overlapping half-opaque ticks darken where the news piled up).
     */
    private ChartFigure pressTimelineFigure(PressTimeline pt) {
        if (pt == null || pt.entries() == null) return null;
        List<java.time.LocalDate> dates = new ArrayList<>();
        for (PressTimeline.Entry e : pt.entries()) {
            if (e.dateIso() == null) continue;
            try {
                dates.add(java.time.LocalDate.parse(e.dateIso()));
            } catch (Exception ignored) {
                // an unparseable provider date is no tick
            }
            if (dates.size() >= 40) break; // entries arrive newest first
        }
        if (dates.size() < 3) return null;
        java.time.LocalDate min = dates.stream().min(java.time.LocalDate::compareTo).orElseThrow();
        java.time.LocalDate max = dates.stream().max(java.time.LocalDate::compareTo).orElseThrow();
        if (min.equals(max)) max = max.plusDays(1);
        long spanDays = java.time.temporal.ChronoUnit.DAYS.between(min, max);

        int h = 72, xL = 16, xR = W - 16;
        double axisY = 48;
        StringBuilder svg = open(h);
        line(svg, xL, axisY, xR, axisY, AXIS, 1);
        for (java.time.LocalDate d : dates) {
            double x = xL + (double) java.time.temporal.ChronoUnit.DAYS.between(min, d)
                    / spanDays * (xR - xL);
            svg.append("<line x1=\"").append(r1(x)).append("\" y1=\"28\" x2=\"").append(r1(x))
                    .append("\" y2=\"46\" stroke=\"").append(S1)
                    .append("\" stroke-width=\"2\" opacity=\"0.55\"/>");
        }
        // Month labels: every month start inside the range, thinned when crowded.
        List<java.time.LocalDate> months = new ArrayList<>();
        java.time.LocalDate m = min.withDayOfMonth(1);
        if (m.isBefore(min)) m = m.plusMonths(1);
        while (!m.isAfter(max)) {
            months.add(m);
            m = m.plusMonths(1);
        }
        if (months.isEmpty()) { // whole range inside one month: date the edges directly
            text(svg, xL, axisY + 16, "start", 9, MUTE, isoDate(min.toString()), false);
            text(svg, xR, axisY + 16, "end", 9, MUTE, isoDate(max.toString()), false);
        }
        int step = Math.max(1, (int) Math.ceil(months.size() / 8.0));
        boolean spansYears = min.getYear() != max.getYear();
        for (int i = 0; i < months.size(); i += step) {
            java.time.LocalDate mo = months.get(i);
            double x = xL + (double) java.time.temporal.ChronoUnit.DAYS.between(min, mo)
                    / spanDays * (xR - xL);
            line(svg, x, axisY, x, axisY + 4, AXIS, 1);
            text(svg, clamp(x, xL + 12, xR - 12), axisY + 16, "middle", 9, MUTE,
                    monthShort(mo.getMonthValue())
                            + (spansYears ? " " + (mo.getYear() % 100) : ""), false);
        }
        svg.append("</svg>");
        String title = de
                ? "Presse-Zeitleiste (" + dates.size() + " datierte Schlagzeilen)"
                : "Press timeline (" + dates.size() + " dated headlines)";
        return new ChartFigure(SEC_SITUATION, title, "MarketBeat", svg.toString());
    }

    private String monthShort(int month) {
        String[] deM = {"Jan", "Feb", "Mär", "Apr", "Mai", "Jun",
                "Jul", "Aug", "Sep", "Okt", "Nov", "Dez"};
        String[] enM = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return (de ? deM : enM)[month - 1];
    }

    // ---- 20. world-signals icon strip — Lage ----

    /**
     * The judged world signals as a glyph strip: one tile per surviving line,
     * the glyph chosen by the line's stable producer prefix (the
     * worldSignalCandidateLines contract), the line's head as caption. Glyphs
     * are drawn SVG primitives — the WeatherCharts wxIcon approach, never
     * emoji/text icons.
     */
    private ChartFigure worldSignalsFigure(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        List<String> rows = lines.size() > 8 ? lines.subList(0, 8) : lines;

        int rowH = 26, padT = 8;
        int h = padT + rows.size() * rowH;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            String line = rows.get(i);
            double cy = padT + i * rowH + rowH / 2.0 - 1;
            signalGlyph(svg, 18, cy, line);
            text(svg, 38, cy + 4, "start", 10, INK, truncate(line, 82), false);
        }
        svg.append("</svg>");
        String title = de
                ? "Weltsignale (je Subjekt KI-beurteilt)"
                : "World signals (AI-judged per subject)";
        return new ChartFigure(SEC_SITUATION, title,
                de ? "Welt-Datenquellen" : "world data feeds", svg.toString());
    }

    /** A small drawn glyph centered at (cx, cy), chosen by the signal line's prefix. */
    private static void signalGlyph(StringBuilder svg, double cx, double cy, String line) {
        String l = line == null ? "" : line;
        String sun = "var(--ddc-sun,#c99a1e)";
        if (l.startsWith("US petroleum")) { // oil barrel
            svg.append("<rect x=\"").append(r1(cx - 5)).append("\" y=\"").append(r1(cy - 7))
                    .append("\" width=\"10\" height=\"14\" rx=\"2\" fill=\"").append(MUTE).append("\"/>");
            line(svg, cx - 5, cy - 2.5, cx + 5, cy - 2.5, SURFACE, 1);
            line(svg, cx - 5, cy + 2.5, cx + 5, cy + 2.5, SURFACE, 1);
        } else if (l.startsWith("Maritime chokepoint")) { // ship
            svg.append("<path d=\"M ").append(r1(cx - 8)).append(' ').append(r1(cy + 1))
                    .append(" L ").append(r1(cx + 8)).append(' ').append(r1(cy + 1))
                    .append(" L ").append(r1(cx + 5)).append(' ').append(r1(cy + 6))
                    .append(" L ").append(r1(cx - 5)).append(' ').append(r1(cy + 6))
                    .append(" Z\" fill=\"").append(S1).append("\"/>")
                    .append("<rect x=\"").append(r1(cx - 2)).append("\" y=\"").append(r1(cy - 5))
                    .append("\" width=\"5\" height=\"5\" fill=\"").append(S1).append("\"/>");
        } else if (l.startsWith("Container charter")) { // container
            svg.append("<rect x=\"").append(r1(cx - 7)).append("\" y=\"").append(r1(cy - 4))
                    .append("\" width=\"14\" height=\"9\" rx=\"1\" fill=\"").append(S2).append("\"/>");
            line(svg, cx - 3, cy - 4, cx - 3, cy + 5, SURFACE, 1);
            line(svg, cx + 1, cy - 4, cx + 1, cy + 5, SURFACE, 1);
        } else if (l.startsWith("German day-ahead power")) { // bolt
            svg.append("<path d=\"M ").append(r1(cx + 2)).append(' ').append(r1(cy - 7))
                    .append(" L ").append(r1(cx - 4)).append(' ').append(r1(cy + 1))
                    .append(" L ").append(r1(cx)).append(' ').append(r1(cy + 1))
                    .append(" L ").append(r1(cx - 2)).append(' ').append(r1(cy + 7))
                    .append(" L ").append(r1(cx + 5)).append(' ').append(r1(cy - 2))
                    .append(" L ").append(r1(cx + 1)).append(' ').append(r1(cy - 2))
                    .append(" Z\" fill=\"").append(sun).append("\"/>");
        } else if (l.startsWith("Space weather")) { // sun
            svg.append("<circle cx=\"").append(r1(cx)).append("\" cy=\"").append(r1(cy))
                    .append("\" r=\"4\" fill=\"").append(sun).append("\"/>");
            for (int a = 0; a < 360; a += 45) {
                double rad = Math.toRadians(a);
                line(svg, cx + Math.cos(rad) * 5.5, cy + Math.sin(rad) * 5.5,
                        cx + Math.cos(rad) * 8, cy + Math.sin(rad) * 8, sun, 1);
            }
        } else if (l.startsWith("Actively exploited")) { // bug
            svg.append("<circle cx=\"").append(r1(cx)).append("\" cy=\"").append(r1(cy + 1))
                    .append("\" r=\"4.5\" fill=\"").append(MUTE).append("\"/>")
                    .append("<circle cx=\"").append(r1(cx)).append("\" cy=\"").append(r1(cy - 5))
                    .append("\" r=\"2\" fill=\"").append(MUTE).append("\"/>");
            for (int side = -1; side <= 1; side += 2) {
                line(svg, cx + 4 * side, cy - 2, cx + 7 * side, cy - 4, MUTE, 1);
                line(svg, cx + 4.5 * side, cy + 1, cx + 8 * side, cy + 1, MUTE, 1);
                line(svg, cx + 4 * side, cy + 4, cx + 7 * side, cy + 6, MUTE, 1);
            }
        } else if (l.startsWith("Policy wire")) { // newspaper
            svg.append("<rect x=\"").append(r1(cx - 7)).append("\" y=\"").append(r1(cy - 5))
                    .append("\" width=\"14\" height=\"11\" rx=\"1\" fill=\"").append(MUTE).append("\"/>");
            line(svg, cx - 5, cy - 2, cx + 5, cy - 2, SURFACE, 1);
            line(svg, cx - 5, cy + 1, cx + 5, cy + 1, SURFACE, 1);
            line(svg, cx - 5, cy + 4, cx + 1, cy + 4, SURFACE, 1);
        } else if (l.startsWith("World hazard")) { // warning triangle
            svg.append("<path d=\"M ").append(r1(cx)).append(' ').append(r1(cy - 7))
                    .append(" L ").append(r1(cx + 8)).append(' ').append(r1(cy + 6))
                    .append(" L ").append(r1(cx - 8)).append(' ').append(r1(cy + 6))
                    .append(" Z\" fill=\"").append(NEG).append("\"/>");
            line(svg, cx, cy - 2.5, cx, cy + 1.5, SURFACE, 2);
            svg.append("<circle cx=\"").append(r1(cx)).append("\" cy=\"").append(r1(cy + 4))
                    .append("\" r=\"1\" fill=\"").append(SURFACE).append("\"/>");
        } else { // generic dot
            svg.append("<circle cx=\"").append(r1(cx)).append("\" cy=\"").append(r1(cy))
                    .append("\" r=\"3\" fill=\"").append(MUTE).append("\"/>");
        }
    }

    /** A plain count in compact units (shares, not money — no € suffix). */
    private String compactNum(double v) {
        double abs = Math.abs(v);
        if (abs >= 1e9) return fmt(v / 1e9, 1) + (de ? " Mrd." : "B");
        if (abs >= 1e6) return fmt(v / 1e6, 1) + (de ? " Mio." : "M");
        if (abs >= 1e3) return fmt(v / 1e3, 0) + (de ? " Tsd." : "k");
        return fmt(v, 0);
    }

    // ---- grouped-column engine (two series, direct-labeled caps, legend) ----

    private record Group(String label, double[] values, boolean deEmphasized) {}

    private StringBuilder groupedColumns(List<Group> groups, String[] fills,
            String[] legendNames, int decimals) {
        List<Group> rows = groups.stream()
                .filter(g -> Double.isFinite(g.values()[0]) || Double.isFinite(g.values()[1]))
                .toList();
        if (rows.size() < 2) return null;

        boolean thousandsEur = decimals == 0; // revenue chart: values arrive in thousands EUR
        double posMax = 0, negMax = 0;
        for (Group g : rows) {
            for (double v : g.values()) {
                if (!Double.isFinite(v)) continue;
                if (v >= 0) posMax = Math.max(posMax, v);
                else negMax = Math.max(negMax, -v);
            }
        }
        double totalSpan = posMax + negMax == 0 ? 1 : posMax + negMax;

        int h = 190, padT = 22, padB = 40, padL = 16, padR = 16, legendY = 14;
        int plotH = h - padT - padB;
        double baseY = padT + posMax / totalSpan * plotH;
        int n = rows.size();
        double slot = (double) (W - padL - padR) / n;
        double barW = Math.min(20, (slot - 14) / 2 - 1);

        StringBuilder svg = open(h);
        // Legend (two series → always present), swatches + muted text.
        double lx = padL;
        for (int sIdx = 0; sIdx < 2; sIdx++) {
            svg.append("<rect x=\"").append(r1(lx)).append("\" y=\"").append(legendY - 9)
                    .append("\" width=\"9\" height=\"9\" rx=\"2\" fill=\"").append(fills[sIdx]).append("\"/>");
            text(svg, lx + 13, legendY, "start", 10, MUTE, legendNames[sIdx], false);
            lx += 13 + legendNames[sIdx].length() * 5.6 + 18;
        }
        line(svg, padL, baseY, W - padR, baseY, AXIS, 1);

        for (int k = 0; k < n; k++) {
            Group g = rows.get(k);
            double groupX = padL + slot * k + (slot - (barW * 2 + 2)) / 2;
            double opacity = g.deEmphasized() ? 0.55 : 1.0;
            for (int sIdx = 0; sIdx < 2; sIdx++) {
                double v = g.values()[sIdx];
                if (!Double.isFinite(v)) continue;
                double x = groupX + sIdx * (barW + 2); // 2px surface gap between the pair
                double len = Math.abs(v) / totalSpan * plotH;
                String bar = v >= 0 ? roundedTopBar(x, baseY - len, barW, len, fills[sIdx])
                        : roundedBottomBar(x, baseY, barW, len, fills[sIdx]);
                if (opacity < 1) {
                    bar = bar.replace("/>", " opacity=\"" + opacity + "\"/>");
                }
                svg.append(bar);
                String label = thousandsEur ? compactFromThousands(v) : fmt(v, decimals);
                double ly = v >= 0 ? baseY - len - 5 : baseY + len + 13;
                text(svg, x + barW / 2, ly, "middle", 9, INK, label, false);
            }
            text(svg, groupX + barW + 1, h - 8, "middle", 10,
                    g.deEmphasized() ? MUTE : INK, g.label(), false);
        }
        svg.append("</svg>");
        return svg;
    }

    // ---- svg primitives ----

    private static StringBuilder open(int h) {
        return new StringBuilder(2048)
                .append("<svg viewBox=\"0 0 ").append(W).append(' ').append(h)
                .append("\" xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" ")
                .append("font-family=\"Inter, Helvetica, Arial, sans-serif\">");
    }

    private static void line(StringBuilder svg, double x1, double y1, double x2, double y2,
            String stroke, int width) {
        svg.append("<line x1=\"").append(r1(x1)).append("\" y1=\"").append(r1(y1))
                .append("\" x2=\"").append(r1(x2)).append("\" y2=\"").append(r1(y2))
                .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"").append(width)
                .append("\"/>");
    }

    private static void text(StringBuilder svg, double x, double y, String anchor, int size,
            String fill, String content, boolean bold) {
        svg.append("<text x=\"").append(r1(x)).append("\" y=\"").append(r1(y))
                .append("\" text-anchor=\"").append(anchor).append("\" font-size=\"").append(size)
                .append(bold ? "\" font-weight=\"600" : "")
                .append("\" fill=\"").append(fill).append("\">").append(esc(content)).append("</text>");
    }

    /** Column: 4px rounded top data-end, square at the baseline. */
    private static String roundedTopBar(double x, double y, double w, double h, String fill) {
        if (h <= 4) {
            return "<rect x=\"" + r1(x) + "\" y=\"" + r1(y) + "\" width=\"" + r1(w)
                    + "\" height=\"" + r1(Math.max(h, 1)) + "\" fill=\"" + fill + "\"/>";
        }
        double r = 4;
        return "<path d=\"M " + r1(x) + ' ' + r1(y + h)
                + " L " + r1(x) + ' ' + r1(y + r)
                + " Q " + r1(x) + ' ' + r1(y) + ' ' + r1(x + r) + ' ' + r1(y)
                + " L " + r1(x + w - r) + ' ' + r1(y)
                + " Q " + r1(x + w) + ' ' + r1(y) + ' ' + r1(x + w) + ' ' + r1(y + r)
                + " L " + r1(x + w) + ' ' + r1(y + h) + " Z\" fill=\"" + fill + "\"/>";
    }

    /** Downward column (negative values): rounded bottom data-end, square at the baseline. */
    private static String roundedBottomBar(double x, double baseY, double w, double h, String fill) {
        if (h <= 4) {
            return "<rect x=\"" + r1(x) + "\" y=\"" + r1(baseY) + "\" width=\"" + r1(w)
                    + "\" height=\"" + r1(Math.max(h, 1)) + "\" fill=\"" + fill + "\"/>";
        }
        double r = 4, y2 = baseY + h;
        return "<path d=\"M " + r1(x) + ' ' + r1(baseY)
                + " L " + r1(x) + ' ' + r1(y2 - r)
                + " Q " + r1(x) + ' ' + r1(y2) + ' ' + r1(x + r) + ' ' + r1(y2)
                + " L " + r1(x + w - r) + ' ' + r1(y2)
                + " Q " + r1(x + w) + ' ' + r1(y2) + ' ' + r1(x + w) + ' ' + r1(y2 - r)
                + " L " + r1(x + w) + ' ' + r1(baseY) + " Z\" fill=\"" + fill + "\"/>";
    }

    /** Horizontal bar: 4px rounded right data-end, square at the left baseline. */
    private static String roundedRightBar(double x, double y, double w, double h, String fill) {
        if (w <= 4) {
            return "<rect x=\"" + r1(x) + "\" y=\"" + r1(y) + "\" width=\"" + r1(Math.max(w, 1))
                    + "\" height=\"" + r1(h) + "\" fill=\"" + fill + "\"/>";
        }
        double r = 4, x2 = x + w;
        return "<path d=\"M " + r1(x) + ' ' + r1(y)
                + " L " + r1(x2 - r) + ' ' + r1(y)
                + " Q " + r1(x2) + ' ' + r1(y) + ' ' + r1(x2) + ' ' + r1(y + r)
                + " L " + r1(x2) + ' ' + r1(y + h - r)
                + " Q " + r1(x2) + ' ' + r1(y + h) + ' ' + r1(x2 - r) + ' ' + r1(y + h)
                + " L " + r1(x) + ' ' + r1(y + h) + " Z\" fill=\"" + fill + "\"/>";
    }

    // ---- formatting ----

    private String fmt(double v, int decimals) {
        String s = String.format(Locale.ROOT, "%,." + decimals + "f", v);
        return de ? s.replace(',', ' ').replace('.', ',').replace(' ', '.') : s;
    }

    private String pct(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + fmt(Math.abs(v), 1) + " %";
    }

    /** Values arriving in THOUSANDS EUR → compact Mio./Mrd. label. */
    private String compactFromThousands(double thousands) {
        double eur = thousands * 1000;
        return compactEur(eur);
    }

    private String compactEur(double eur) {
        double abs = Math.abs(eur);
        String s;
        if (abs >= 1e9) s = fmt(eur / 1e9, 1) + (de ? " Mrd." : "B");
        else if (abs >= 1e6) s = fmt(eur / 1e6, 1) + (de ? " Mio." : "M");
        else if (abs >= 1e3) s = fmt(eur / 1e3, 0) + (de ? " Tsd." : "k");
        else s = fmt(eur, 0);
        return s + " €";
    }

    private static double fin(double v) {
        return Double.isFinite(v) ? v : Double.NaN;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1).stripTrailing() + "…";
    }

    private static String r1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
