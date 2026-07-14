package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ChartStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DepthStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoonStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoverStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RoomPulse;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SocialStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldStats;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Abendausgabe's figure layer — the KI-DD ChartFigure pattern lifted onto
 * the evening report (user mandate 2026-07-13: "Zahlen visualisieren, Text mit
 * Diagrammen füllen"): server-rendered SVGs built ONCE from the frozen stats
 * at generation time, anchored to the report's seven `## ` sections by 0-based
 * ordinal, frozen into the archived record. Deterministic data rendering — the
 * model never draws; every figure exists only when its data leg does.
 *
 * <p>Visual grammar and the {@code var(--ddc-*, <hex>)} color roles are shared
 * verbatim with {@code DeepDiveCharts} (weather.css maps the same tokens), so
 * DD and Abendausgabe read as one figure system.
 */
final class WeatherCharts {

    private static final int W = 560;

    private static final String S1 = "var(--ddc-s1,#2a78d6)";
    private static final String POS = "var(--ddc-pos,#008300)";
    private static final String NEG = "var(--ddc-neg,#d03b3b)";
    private static final String INK = "var(--ddc-ink,#0b0b0b)";
    private static final String MUTE = "var(--ddc-mute,#898781)";
    private static final String GRID = "var(--ddc-grid,#e1e0d9)";
    private static final String AXIS = "var(--ddc-axis,#c3c2b7)";
    private static final String SURFACE = "var(--ddc-surface,#ffffff)";

    // Report section ordinals (must match the prompt's fixed FIVE-section
    // forecast structure: Großwetterlage / Morgens / Mittags / Abends /
    // Ausblick — the day told like an actual weather report).
    private static final int SEC_PICTURE = 0;
    private static final int SEC_MORNING = 1;
    private static final int SEC_MIDDAY = 2;
    private static final int SEC_EVENING = 3;
    private static final int SEC_OUTLOOK = 4;

    private final boolean de;

    WeatherCharts(String languageCode) {
        this.de = "de".equalsIgnoreCase(languageCode);
    }

    /** Every figure the frozen day supports, section-anchored. */
    List<ChartStat> build(WeatherStatsCollector.Stats stats, List<HeadlineRecord> headlines,
            ZoneId zone) {
        WorldStats w = stats.world();
        List<ChartStat> out = new ArrayList<>();
        addIfPresent(out, factsStrip(stats, w));
        addIfPresent(out, pulseFigure(w == null ? null : w.pulse()));
        addIfPresent(out, hourActivityFigure(headlines, zone));
        addIfPresent(out, mostDiscussedFigure(stats.tickers()));
        addIfPresent(out, marketMovesFigure(stats.indices()));
        addIfPresent(out, sectorFigure(w == null ? List.of() : w.sectors()));
        addIfPresent(out, depthFigure(w == null ? List.of() : w.depth()));
        addIfPresent(out, moversFigure(w == null ? List.of() : w.movers()));
        addIfPresent(out, socialFigure(w == null ? List.of() : w.social()));
        addIfPresent(out, hazardsFigure(w == null ? List.of() : w.hazards()));
        addIfPresent(out, outlookFigure(w == null ? List.of() : w.outlook()));
        addIfPresent(out, worldWeatherFigure(w == null ? List.of() : w.worldWeather()));
        addIfPresent(out, moonFigure(w == null ? null : w.moon()));
        return out;
    }

    // ---- 0. the at-a-glance strip — Großwetterlage --------------------------

    /**
     * The hero strip: the day in five tiles — home index, US pair, fear gauge,
     * the cage's own volume, Bitcoin. Deterministic, so the essentials are
     * visible whatever the prose does.
     */
    private ChartStat factsStrip(WeatherStatsCollector.Stats stats, WorldStats w) {
        record Tile(String label, String value, String sub, String tone) {}
        List<Tile> tiles = new ArrayList<>();
        for (String want : new String[]{"DAX", "S&P 500", "Bitcoin"}) {
            for (IndexStat ix : stats.indices()) {
                if (!want.equals(ix.name()) || ix.last() == null) continue;
                String value = "FX".equals(ix.currency()) ? fmt(ix.last(), 4)
                        : fmt(ix.last(), ix.last() >= 1000 ? 0 : 2);
                String sub = ix.changePercent() == null ? null : pct(ix.changePercent());
                tiles.add(new Tile(ix.name(), value, sub,
                        ix.changePercent() == null ? MUTE : ix.changePercent() >= 0 ? POS : NEG));
                break;
            }
        }
        SentimentStat s = stats.sentiment();
        if (s != null && s.score() != null) {
            tiles.add(new Tile("Fear & Greed", String.valueOf(s.score()),
                    s.previousClose() == null ? null
                            : (de ? "Vortag " : "prior ") + s.previousClose(),
                    INK));
        }
        RoomPulse p = w == null ? null : w.pulse();
        if (p != null) {
            int lines = p.bullish() + p.bearish() + p.neutral();
            tiles.add(new Tile(de ? "Käfig" : "Cage",
                    lines + (de ? " Zeilen" : " lines"),
                    p.redCount() > 0 ? p.redCount() + (de ? " rot" : " red") : null, INK));
        }
        if (tiles.size() < 3) return null;
        if (tiles.size() > 6) tiles = tiles.subList(0, 6);

        int cols = Math.min(tiles.size(), 5);
        int rows = (tiles.size() + cols - 1) / cols;
        int tileH = 52, padT = 8;
        int h = padT + rows * tileH;
        double colW = (double) W / cols;
        StringBuilder svg = open(h);
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            double x = (i % cols) * colW + 4;
            double y = padT + (i / cols) * tileH;
            text(svg, x, y + 12, "start", 10, MUTE, tile.label().toUpperCase(Locale.ROOT), false);
            text(svg, x, y + 32, "start", 17, INK, tile.value(), true);
            if (tile.sub() != null) text(svg, x, y + 46, "start", 10, tile.tone(), tile.sub(), false);
        }
        svg.append("</svg>");
        return new ChartStat(SEC_PICTURE, de ? "Der Tag auf einen Blick" : "The day at a glance",
                de ? "alle Quellen" : "all sources", svg.toString());
    }

    // ---- 1a. the cage's mood split (stacked h-bar) — Der Käfig ---------------

    private ChartStat pulseFigure(RoomPulse p) {
        if (p == null) return null;
        int total = p.bullish() + p.bearish() + p.neutral();
        if (total <= 0) return null;

        int barH = 22, padT = 10, labelY = 24;
        int h = padT + barH + labelY + 6;
        int plotW = W - 24;
        StringBuilder svg = open(h);
        String[] fills = {POS, NEG, MUTE};
        double[] alpha = {1, 1, 0.5};
        int[] counts = {p.bullish(), p.bearish(), p.neutral()};
        String[] names = de
                ? new String[]{"bullisch", "bärisch", "neutral/gemischt"}
                : new String[]{"bullish", "bearish", "neutral/mixed"};
        double x = 12;
        for (int i = 0; i < 3; i++) {
            if (counts[i] <= 0) continue;
            double barW = (double) counts[i] / total * plotW - 2;
            if (barW < 1) barW = 1;
            svg.append("<rect x=\"").append(r1(x)).append("\" y=\"").append(padT)
                    .append("\" width=\"").append(r1(barW)).append("\" height=\"").append(barH)
                    .append("\" rx=\"3\" fill=\"").append(fills[i])
                    .append("\" opacity=\"").append(alpha[i]).append("\"/>");
            if (barW >= 20) {
                text(svg, x + barW / 2, padT + barH / 2.0 + 4, "middle", 11,
                        alpha[i] >= 1 ? SURFACE : INK, String.valueOf(counts[i]), true);
            }
            x += barW + 2;
        }
        // Legend row + the red count as its own token.
        double lx = 12;
        double ly = padT + barH + 16;
        for (int i = 0; i < 3; i++) {
            svg.append("<rect x=\"").append(r1(lx)).append("\" y=\"").append(r1(ly - 8))
                    .append("\" width=\"9\" height=\"9\" rx=\"2\" fill=\"").append(fills[i])
                    .append("\" opacity=\"").append(alpha[i]).append("\"/>");
            text(svg, lx + 13, ly, "start", 10, MUTE, names[i], false);
            lx += 13 + names[i].length() * 5.6 + 18;
        }
        if (p.redCount() > 0) {
            text(svg, W - 12, ly, "end", 10, NEG,
                    p.redCount() + (de ? " rot markiert" : " red-flagged"), true);
        }
        svg.append("</svg>");
        String title = de
                ? "Stimmung im Käfig (" + total + " Zeilen, " + p.distinctSubjects() + " Subjekte)"
                : "Cage mood (" + total + " lines, " + p.distinctSubjects() + " subjects)";
        return new ChartStat(SEC_PICTURE, title, de ? "eigene Wire" : "own wire", svg.toString());
    }

    // ---- 1b. lines per hour (columns) — Der Käfig -----------------------------

    private ChartStat hourActivityFigure(List<HeadlineRecord> headlines, ZoneId zone) {
        if (headlines == null || headlines.size() < 3) return null;
        int[] byHour = new int[24];
        int first = 24, last = -1;
        for (HeadlineRecord r : headlines) {
            int hour = LocalDateTime.ofInstant(Instant.ofEpochSecond(r.createdAt()), zone).getHour();
            byHour[hour]++;
            first = Math.min(first, hour);
            last = Math.max(last, hour);
        }
        if (last - first < 1) return null;
        int n = last - first + 1;
        int max = 0;
        for (int i = first; i <= last; i++) max = Math.max(max, byHour[i]);
        if (max <= 0) return null;

        int h = 120, padT = 16, padB = 20, padL = 12, padR = 12;
        int plotH = h - padT - padB;
        double slot = (double) (W - padL - padR) / n;
        double barW = Math.min(22, slot * 0.62);
        StringBuilder svg = open(h);
        line(svg, padL, padT + plotH, W - padR, padT + plotH, AXIS, 1);
        for (int i = 0; i < n; i++) {
            int hour = first + i;
            int v = byHour[hour];
            double x = padL + slot * i + (slot - barW) / 2;
            if (v > 0) {
                double len = (double) v / max * plotH;
                svg.append(roundedTopBar(x, padT + plotH - len, barW, len, S1));
                text(svg, x + barW / 2, padT + plotH - len - 4, "middle", 9, INK,
                        String.valueOf(v), false);
            }
            if (hour % 2 == 0 || n <= 8) {
                text(svg, x + barW / 2, h - 6, "middle", 9, MUTE, hour + "h", false);
            }
        }
        svg.append("</svg>");
        // The weather report's Temperaturkurve: the cage's heat by hour.
        return new ChartStat(SEC_PICTURE,
                de ? "Temperaturkurve des Käfigs (Zeilen pro Stunde)"
                        : "The cage's temperature curve (lines per hour)",
                de ? "eigene Wire" : "own wire", svg.toString());
    }

    // ---- 1c. most discussed (h-bars by mentions, tinted by day move) ---------

    private ChartStat mostDiscussedFigure(List<TickerStat> tickers) {
        List<TickerStat> rows = tickers == null ? List.of()
                : tickers.stream().limit(6).toList();
        if (rows.size() < 2) return null;
        int max = rows.stream().mapToInt(TickerStat::headlineCount).max().orElse(0);
        if (max <= 0) return null;

        int barH = 14, rowGap = 10, labelW = 170, padT = 8;
        int h = padT + rows.size() * (barH + rowGap);
        int plotW = W - labelW - 170;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            TickerStat t = rows.get(i);
            double y = padT + i * (barH + rowGap);
            text(svg, labelW - 8, y + barH / 2.0 + 4, "end", 11, MUTE,
                    truncate(t.name(), 26), false);
            double w = Math.max((double) t.headlineCount() / max * plotW, 2);
            String tone = t.changePercent() == null ? S1 : t.changePercent() >= 0 ? POS : NEG;
            svg.append(roundedRightBar(labelW, y, w, barH, tone));
            StringBuilder label = new StringBuilder().append(t.headlineCount()).append('×');
            if (t.importantCount() > 0) label.append(" · ").append(t.importantCount())
                    .append(de ? " rot" : " red");
            if (t.changePercent() != null) label.append(" · ").append(pct(t.changePercent()));
            text(svg, labelW + w + 8, y + barH / 2.0 + 4, "start", 11, INK,
                    label.toString(), false);
        }
        svg.append("</svg>");
        return new ChartStat(SEC_MORNING,
                de ? "Meistdiskutiert (Zeilen, gefärbt nach Tagesbewegung)"
                        : "Most discussed (lines, tinted by day move)",
                de ? "eigene Wire · L&S/Yahoo" : "own wire · L&S/Yahoo", svg.toString());
    }

    // ---- 2a. market day moves (signed h-bars) — Makro und Märkte -------------

    private ChartStat marketMovesFigure(List<IndexStat> indices) {
        List<IndexStat> rows = indices == null ? List.of() : indices.stream()
                .filter(ix -> ix.changePercent() != null)
                .toList();
        if (rows.size() < 3) return null;
        return signedBars(rows.stream()
                        .map(ix -> new SignedRow(ix.name(), ix.changePercent())).toList(),
                SEC_MIDDAY,
                de ? "Märkte des Tages (Tagesbewegung)" : "Markets of the day (day move)",
                "Yahoo");
    }

    // ---- 2b. sector rotation (signed h-bars) ----------------------------------

    private ChartStat sectorFigure(List<IndexStat> sectors) {
        List<IndexStat> rows = sectors == null ? List.of() : sectors.stream()
                .filter(ix -> ix.changePercent() != null)
                .toList();
        if (rows.size() < 4) return null;
        return signedBars(rows.stream()
                        .map(ix -> new SignedRow(ix.name(), ix.changePercent())).toList(),
                SEC_EVENING,
                de ? "US-Sektorrotation" : "US sector rotation",
                de ? "Sektor-ETFs, Yahoo" : "sector ETFs, Yahoo");
    }

    // ---- 3. street depth: implied move to consensus target — Deutschland ------

    private ChartStat depthFigure(List<DepthStat> depth) {
        List<DepthStat> rows = depth == null ? List.of() : depth.stream()
                .filter(d -> d.upsidePercent() != null)
                .toList();
        if (rows.isEmpty()) return null;
        ChartStat bars = signedBars(rows.stream()
                        .map(d -> new SignedRow(d.ticker()
                                + (d.targetPrice() == null ? ""
                                        : " (" + (de ? "Ziel " : "target ")
                                                + fmt(d.targetPrice(), 2)
                                                + (d.targetCurrency() == null ? ""
                                                        : " " + d.targetCurrency()) + ")"),
                                d.upsidePercent())).toList(),
                SEC_MIDDAY,
                de ? "Konsens-Kursziele der Top-Papiere (implizierte Bewegung)"
                        : "Consensus targets of the top names (implied move)",
                "Consorsbank");
        return bars;
    }

    // ---- 4a. US movers (signed h-bars, gainers + losers) — Übersee ------------

    private ChartStat moversFigure(List<MoverStat> movers) {
        if (movers == null || movers.isEmpty()) return null;
        List<SignedRow> rows = new ArrayList<>();
        movers.stream().filter(m -> "GAINER".equals(m.kind()) && m.changePercent() != null)
                .limit(4)
                .forEach(m -> rows.add(new SignedRow(moverLabel(m), m.changePercent())));
        movers.stream().filter(m -> "LOSER".equals(m.kind()) && m.changePercent() != null)
                .limit(4)
                .forEach(m -> rows.add(new SignedRow(moverLabel(m), m.changePercent())));
        if (rows.size() < 3) return null;
        return signedBars(rows, SEC_EVENING,
                de ? "US-Mover des Tages" : "US movers of the day",
                de ? "Yahoo-Screener" : "Yahoo screeners");
    }

    private String moverLabel(MoverStat m) {
        String name = truncate(m.name() == null || m.name().isBlank() ? m.symbol() : m.name(), 22);
        return m.inKaefig() ? name + (de ? " ◆ Käfig" : " ◆ cage") : name;
    }

    // ---- 4b. neighbour-board rank climbers (h-bars) ----------------------------

    private ChartStat socialFigure(List<SocialStat> social) {
        List<SocialStat> rows = social == null ? List.of() : social.stream()
                .filter(s -> s.rankClimb() != null && s.rankClimb() > 0)
                .limit(5)
                .toList();
        if (rows.size() < 2) return null;
        int max = rows.stream().mapToInt(SocialStat::rankClimb).max().orElse(0);
        if (max <= 0) return null;

        int barH = 14, rowGap = 10, labelW = 170, padT = 8;
        int h = padT + rows.size() * (barH + rowGap);
        int plotW = W - labelW - 180;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            SocialStat s = rows.get(i);
            double y = padT + i * (barH + rowGap);
            text(svg, labelW - 8, y + barH / 2.0 + 4, "end", 11, MUTE,
                    truncate(s.name() == null || s.name().isBlank() ? s.ticker() : s.name(), 26),
                    false);
            double w = Math.max((double) s.rankClimb() / max * plotW, 2);
            svg.append(roundedRightBar(labelW, y, w, barH, S1));
            text(svg, labelW + w + 8, y + barH / 2.0 + 4, "start", 11, INK,
                    "+" + s.rankClimb() + (de ? " Ränge · jetzt #" : " ranks · now #") + s.rank(),
                    false);
        }
        svg.append("</svg>");
        return new ChartStat(SEC_EVENING,
                de ? "Rank-Sprünge drüben (24 h)" : "Rank climbers next door (24h)",
                "ApeWisdom", svg.toString());
    }

    // ---- 5. tomorrow's board (date rows) — Ausblick ----------------------------

    private ChartStat outlookFigure(List<OutlookStat> outlook) {
        if (outlook == null || outlook.isEmpty()) return null;
        List<OutlookStat> rows = outlook.stream().limit(8).toList();

        int rowH = 24, padT = 10;
        int h = padT + rows.size() * rowH;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            OutlookStat o = rows.get(i);
            double y = padT + i * rowH + 14;
            boolean lead = i == 0;
            if (lead) {
                svg.append("<rect x=\"4\" y=\"").append(r1(y - 12))
                        .append("\" width=\"3\" height=\"16\" rx=\"1.5\" fill=\"")
                        .append(S1).append("\"/>");
            }
            String left = "EARNINGS".equals(o.kind())
                    ? (de ? "Zahlen" : "Earnings") + (o.time() != null ? " · " + o.time() : "")
                    : (o.time() == null ? "" : o.time())
                            + (o.detail() == null || o.detail().isBlank() ? "" : " " + o.detail());
            text(svg, 16, y, "start", 11, lead ? INK : MUTE, truncate(left, 24), lead);
            String right = o.title()
                    + ("EARNINGS".equals(o.kind()) && o.detail() != null && !o.detail().isBlank()
                            ? " (" + o.detail() + ")" : "")
                    + (o.impact() != null && !o.impact().isBlank() ? " · " + o.impact() : "");
            text(svg, 160, y, "start", 11, lead ? INK : MUTE, truncate(right, 60), false);
        }
        svg.append("</svg>");
        return new ChartStat(SEC_OUTLOOK,
                de ? "Der morgige Kalender" : "Tomorrow's board",
                "ForexFactory · NASDAQ", svg.toString());
    }

    // ---- 5b. the LITERAL world weather over the trading map — Ausblick ----------

    /**
     * The sky over the market-relevant places as a tile strip WITH drawn
     * weather glyphs (user mandate 2026-07-14: "Wetter können wir PERFEKT in
     * Diagrammen darstellen") — each tile: glyph, current temperature, place,
     * role, tomorrow's range. Deterministic from the frozen Open-Meteo leg.
     */
    private ChartStat worldWeatherFigure(
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PlaceWeatherStat> places) {
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PlaceWeatherStat> rows =
                places == null ? List.of()
                        : places.stream().filter(p -> p.tempC() != null).toList();
        if (rows.size() < 3) return null;
        if (rows.size() > 8) rows = rows.subList(0, 8);

        int cols = Math.min(rows.size(), 4);
        int rowCount = (rows.size() + cols - 1) / cols;
        int tileH = 72, padT = 6;
        int h = padT + rowCount * tileH;
        double colW = (double) W / cols;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            var p = rows.get(i);
            double x = (i % cols) * colW + 6;
            double y = padT + (i / cols) * tileH;
            weatherGlyph(svg, x + 12, y + 22, p.word());
            text(svg, x + 30, y + 28, "start", 16, INK,
                    fmt(p.tempC(), 0) + " °C", true);
            text(svg, x, y + 44, "start", 10, INK, truncate(p.place(), 16), true);
            StringBuilder sub = new StringBuilder();
            if (p.role() != null && !p.role().isBlank()) sub.append(p.role());
            text(svg, x, y + 56, "start", 9, MUTE, truncate(sub.toString(), 22), false);
            if (p.tomorrowMaxC() != null) {
                String tm = (de ? "morgen bis " : "tomorrow to ") + fmt(p.tomorrowMaxC(), 0)
                        + " °C" + (p.tomorrowWord() != null ? ", " + p.tomorrowWord() : "");
                text(svg, x, y + 67, "start", 9, MUTE, truncate(tm, 24), false);
            }
        }
        svg.append("</svg>");
        return new ChartStat(SEC_OUTLOOK,
                de ? "Weltwetter über den Handelsplätzen" : "World weather over the trading map",
                "Open-Meteo", svg.toString());
    }

    /** A small drawn condition glyph centered at (cx, cy) — sun, cloud, rain, snow, fog, bolt. */
    private static void weatherGlyph(StringBuilder svg, double cx, double cy, String word) {
        String w = word == null ? "" : word;
        String sun = "var(--ddc-sun,#c99a1e)";
        switch (w) {
            case "klar", "heiter" -> {
                svg.append("<circle cx=\"").append(r1(cx)).append("\" cy=\"").append(r1(cy))
                        .append("\" r=\"6\" fill=\"").append(sun).append("\"/>");
                for (int a = 0; a < 360; a += 45) {
                    double rad = Math.toRadians(a);
                    line(svg, cx + Math.cos(rad) * 8.5, cy + Math.sin(rad) * 8.5,
                            cx + Math.cos(rad) * 11.5, cy + Math.sin(rad) * 11.5, sun, 2);
                }
                if ("heiter".equals(w)) cloudShape(svg, cx + 4, cy + 4, MUTE, 0.8);
            }
            case "Nebel" -> {
                for (int i = 0; i < 3; i++) {
                    line(svg, cx - 9, cy - 4 + i * 5, cx + 9, cy - 4 + i * 5, MUTE, 2);
                }
            }
            case "Regen", "Niesel", "Schauer" -> {
                cloudShape(svg, cx, cy - 2, MUTE, 1);
                for (int i = -1; i <= 1; i++) {
                    line(svg, cx + i * 5.5, cy + 6, cx + i * 5.5 - 2, cy + 11, S1, 2);
                }
            }
            case "Schnee", "Schneeschauer" -> {
                cloudShape(svg, cx, cy - 2, MUTE, 1);
                for (int i = -1; i <= 1; i++) {
                    text(svg, cx + i * 6, cy + 12, "middle", 8, S1, "✳", false);
                }
            }
            case "Gewitter" -> {
                cloudShape(svg, cx, cy - 2, MUTE, 1);
                svg.append("<path d=\"M ").append(r1(cx + 1)).append(' ').append(r1(cy + 4))
                        .append(" L ").append(r1(cx - 3)).append(' ').append(r1(cy + 10))
                        .append(" L ").append(r1(cx)).append(' ').append(r1(cy + 10))
                        .append(" L ").append(r1(cx - 2)).append(' ').append(r1(cy + 15))
                        .append(" L ").append(r1(cx + 4)).append(' ').append(r1(cy + 8))
                        .append(" L ").append(r1(cx + 1)).append(' ').append(r1(cy + 8))
                        .append(" Z\" fill=\"").append(NEG).append("\"/>");
            }
            default -> cloudShape(svg, cx, cy, MUTE, 1);
        }
    }

    /** A simple two-lobe cloud, filled. */
    private static void cloudShape(StringBuilder svg, double cx, double cy, String fill,
            double scale) {
        double s = scale;
        svg.append("<circle cx=\"").append(r1(cx - 4 * s)).append("\" cy=\"").append(r1(cy + 1))
                .append("\" r=\"").append(r1(4.5 * s)).append("\" fill=\"").append(fill).append("\"/>")
                .append("<circle cx=\"").append(r1(cx + 2 * s)).append("\" cy=\"").append(r1(cy - 2 * s))
                .append("\" r=\"").append(r1(5.5 * s)).append("\" fill=\"").append(fill).append("\"/>")
                .append("<rect x=\"").append(r1(cx - 7 * s)).append("\" y=\"").append(r1(cy))
                .append("\" width=\"").append(r1(15 * s)).append("\" height=\"").append(r1(5 * s))
                .append("\" rx=\"2.5\" fill=\"").append(fill).append("\"/>");
    }

    // ---- 4c. the hazards board (storms / quakes / US aviation) — Abend -----------

    /**
     * The physical world's disruption board: severity dot, kind chip, event
     * text — NHC storms, significant quakes, FAA ground stops/delays. The
     * user's "Luftverkehr in Bildern": the airline-sector disruption state at
     * a glance.
     */
    private ChartStat hazardsFigure(
            List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HazardStat> hazards) {
        if (hazards == null || hazards.isEmpty()) return null;
        List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HazardStat> rows =
                hazards.size() > 8 ? hazards.subList(0, 8) : hazards;

        int rowH = 24, padT = 10;
        int h = padT + rows.size() * rowH;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            var hz = rows.get(i);
            double y = padT + i * rowH + 14;
            String tone = "HIGH".equalsIgnoreCase(hz.severity()) ? NEG : MUTE;
            svg.append("<circle cx=\"10\" cy=\"").append(r1(y - 4))
                    .append("\" r=\"4\" fill=\"").append(tone).append("\"/>");
            String kind = switch (hz.kind() == null ? "" : hz.kind()) {
                case "STORM" -> de ? "Sturm" : "storm";
                case "QUAKE" -> de ? "Beben" : "quake";
                case "AVIATION" -> de ? "Luftverkehr" : "aviation";
                default -> hz.kind() == null ? "" : hz.kind().toLowerCase(Locale.ROOT);
            };
            text(svg, 22, y, "start", 10, MUTE, kind.toUpperCase(Locale.ROOT), true);
            text(svg, 110, y, "start", 11, INK, truncate(hz.text(), 66), false);
        }
        svg.append("</svg>");
        return new ChartStat(SEC_EVENING,
                de ? "Gefahrenlage (Stürme · Beben · US-Luftverkehr)"
                        : "Hazards (storms · quakes · US aviation)",
                "NHC · USGS · FAA", svg.toString());
    }

    // ---- 6. the moon — Randnotizen ----------------------------------------------

    /**
     * The moon disc, drawn from the phase arithmetic: lit portion via the
     * standard two-arc terminator (a half-disc plus a half-ellipse whose x-radius
     * follows the illumination). Waxing lights the right limb, waning the left.
     * Zum Mond deserves a picture, not just a number.
     */
    private ChartStat moonFigure(MoonStat moon) {
        if (moon == null || moon.illuminationPercent() == null || moon.daysToFull() == null) {
            return null;
        }
        int h = 96;
        double cx = 60, cy = h / 2.0, r = 32;
        double ill = Math.max(0, Math.min(100, moon.illuminationPercent())) / 100.0;
        boolean waxing = moon.phase() != null && moon.phase().startsWith("WAX")
                || "NEW_MOON".equals(moon.phase()) || "FIRST_QUARTER".equals(moon.phase());

        StringBuilder svg = open(h);
        // Dark disc first, lit shape on top.
        svg.append("<circle cx=\"").append(r1(cx)).append("\" cy=\"").append(r1(cy))
                .append("\" r=\"").append(r1(r)).append("\" fill=\"").append(GRID)
                .append("\"/>");
        if (ill >= 0.99) {
            svg.append("<circle cx=\"").append(r1(cx)).append("\" cy=\"").append(r1(cy))
                    .append("\" r=\"").append(r1(r)).append("\" fill=\"").append(S1).append("\"/>");
        } else if (ill > 0.01) {
            // Terminator ellipse x-radius: r at full/new limbs, 0 at the quarters.
            double rx = Math.abs(2 * ill - 1) * r;
            int limb = waxing ? 1 : 0;            // outer arc sweep: right or left limb
            int terminator = (ill > 0.5) == waxing ? 0 : 1;
            svg.append("<path d=\"M ").append(r1(cx)).append(' ').append(r1(cy - r))
                    .append(" A ").append(r1(r)).append(' ').append(r1(r)).append(" 0 0 ")
                    .append(limb).append(' ').append(r1(cx)).append(' ').append(r1(cy + r))
                    .append(" A ").append(r1(rx)).append(' ').append(r1(r)).append(" 0 0 ")
                    .append(terminator).append(' ').append(r1(cx)).append(' ').append(r1(cy - r))
                    .append(" Z\" fill=\"").append(S1).append("\"/>");
        }
        svg.append("<circle cx=\"").append(r1(cx)).append("\" cy=\"").append(r1(cy))
                .append("\" r=\"").append(r1(r)).append("\" fill=\"none\" stroke=\"")
                .append(AXIS).append("\" stroke-width=\"1\"/>");

        String big = moon.daysToFull() == 0
                ? (de ? "Vollmond heute Nacht" : "Full moon tonight")
                : (de ? "Vollmond in " + moon.daysToFull() + " Tagen"
                        : "Full moon in " + moon.daysToFull() + " days");
        text(svg, 116, cy - 4, "start", 15, INK, big, true);
        text(svg, 116, cy + 16, "start", 11, MUTE,
                moon.illuminationPercent() + (de ? " % beleuchtet" : " % lit"), false);
        svg.append("</svg>");
        return new ChartStat(SEC_OUTLOOK, de ? "Zum Mond" : "To the moon",
                de ? "lokale Berechnung" : "local arithmetic", svg.toString());
    }

    // ---- signed h-bar engine (± values around a zero axis) -----------------------

    private record SignedRow(String label, double value) {}

    /** Horizontal ± bars around a centered zero axis, sorted descending, direct-labeled. */
    private ChartStat signedBars(List<SignedRow> input, int section, String title, String note) {
        List<SignedRow> rows = new ArrayList<>(input);
        rows.sort((a, b) -> Double.compare(b.value(), a.value()));
        if (rows.size() > 12) rows = rows.subList(0, 12);
        double max = rows.stream().mapToDouble(r -> Math.abs(r.value())).max().orElse(0);
        if (max <= 0) max = 1;

        int barH = 13, rowGap = 9, labelW = 168, padT = 8, valueW = 62;
        int h = padT + rows.size() * (barH + rowGap);
        double axisX = labelW + (W - labelW - valueW) / 2.0;
        double half = (W - labelW - valueW) / 2.0 - 4;
        StringBuilder svg = open(h);
        line(svg, axisX, padT - 2, axisX, h - 4, AXIS, 1);
        for (int i = 0; i < rows.size(); i++) {
            SignedRow row = rows.get(i);
            double y = padT + i * (barH + rowGap);
            text(svg, labelW - 8, y + barH / 2.0 + 4, "end", 11, MUTE,
                    truncate(row.label(), 30), false);
            double w = Math.max(Math.abs(row.value()) / max * half, 1.5);
            boolean up = row.value() >= 0;
            if (up) {
                svg.append(roundedRightBar(axisX + 1, y, w, barH, POS));
                text(svg, axisX + 1 + w + 6, y + barH / 2.0 + 4, "start", 10, INK,
                        pct(row.value()), false);
            } else {
                svg.append(roundedLeftBar(axisX - 1 - w, y, w, barH, NEG));
                text(svg, axisX - 1 - w - 6, y + barH / 2.0 + 4, "end", 10, INK,
                        pct(row.value()), false);
            }
        }
        svg.append("</svg>");
        return new ChartStat(section, title, note, svg.toString());
    }

    // ---- svg primitives (shared grammar with DeepDiveCharts) ----------------------

    private static void addIfPresent(List<ChartStat> out, ChartStat f) {
        if (f != null) out.add(f);
    }

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

    /** Mirrored horizontal bar: rounded LEFT data-end, square at the right (zero-axis) side. */
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

    // ---- formatting ----------------------------------------------------------------

    private String fmt(double v, int decimals) {
        String s = String.format(Locale.ROOT, "%,." + decimals + "f", v);
        return de ? s.replace(',', ' ').replace('.', ',').replace(' ', '.') : s;
    }

    private String pct(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + fmt(Math.abs(v), 1) + " %";
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
