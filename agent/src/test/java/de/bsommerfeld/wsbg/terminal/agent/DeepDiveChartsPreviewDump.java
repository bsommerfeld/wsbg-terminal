package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord.ChartFigure;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Dev-only dump: writes the sample figures to an HTML page for eyeballing. */
@Tag("integration")
class DeepDiveChartsPreviewDump {

    @Test
    void dump() throws Exception {
        String out = System.getenv("DD_CHART_PREVIEW");
        if (out == null) return;
        List<ChartFigure> figures = new DeepDiveCharts("de").build(
                new DeepDiveCharts.ChartInput(
                        DeepDiveChartsTest.snapshot(), DeepDiveChartsTest.deepDive(),
                        DeepDiveChartsTest.analystView(), DeepDiveChartsTest.shorts(),
                        DeepDiveChartsTest.insider(), DeepDiveChartsTest.venueStats(),
                        DeepDiveChartsTest.usStats(), DeepDiveChartsTest.actions(),
                        DeepDiveChartsTest.hedgeFunds(), DeepDiveChartsTest.pressTimeline(),
                        DeepDiveChartsTest.worldSignals(), DeepDiveChartsTest.volumeProfile(),
                        DeepDiveChartsTest.orderBook(), DeepDiveChartsTest.memoryEvents(),
                        calendarYears(), consensusTrend(), sectorBoard(), "Rüstung", "DAX",
                        cnbcEarnings(), sectorEtf(), "EXV1.DE", "Rüstung & Luftfahrt Europa",
                        attentionCurve(), docket(), regime(),
                        DeepDiveCharts.Registers.EMPTY, DeepDiveCharts.Boards.EMPTY, List.of()));
        // Mirrors the app's figure lane (deepdive.css .dd-figure): the card
        // frame and the width the figures really get, so what reads well here
        // reads well in the report and the PDF.
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8>"
                + "<body style='background:#f4f3ee;max-width:1000px;margin:24px auto;"
                + "font-family:Inter,sans-serif'>");
        for (ChartFigure f : figures) {
            html.append("<figure style='background:#fff;border:1px solid #e1e0d9;border-radius:10px;"
                        + "padding:14px 18px 10px;margin:0 0 18px'>")
                .append("<figcaption style='display:flex;align-items:baseline;gap:12px;"
                        + "margin-bottom:10px;font-size:13px;font-weight:600;color:#52514e'>")
                .append(f.title())
                .append("<span style='flex:1;border-top:1px solid #e1e0d9'></span>")
                .append("<small style='color:#898781;font-weight:400'>").append(f.note())
                .append("</small></figcaption>")
                .append(f.svg())
                .append("</figure>");
        }
        Files.writeString(Path.of(out), html.append("</body>").toString());
    }

    private static java.util.List<de.bsommerfeld.wsbg.terminal.onvista
            .OnvistaMarketClient.YearPerformance> calendarYears() {
        double[][] rows = {{2016, 4.2}, {2017, 18.9}, {2018, -22.4}, {2019, 31.0},
                {2020, -8.1}, {2021, 26.5}, {2022, -14.7}, {2023, 54.3},
                {2024, 92.6}, {2025, 41.2}};
        java.util.List<de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient.YearPerformance>
                out = new java.util.ArrayList<>();
        for (double[] r : rows) {
            out.add(new de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient
                    .YearPerformance((int) r[0], 100, 100 * (1 + r[1] / 100), 0, r[1]));
        }
        return out;
    }

    private static java.util.List<de.bsommerfeld.wsbg.terminal.finanzennet
            .FinanzenNetMarketClient.ConsensusTrend> consensusTrend() {
        return List.of(
                new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                        .ConsensusTrend("Heute", 19, 3, 5),
                new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                        .ConsensusTrend("vor 6 Mon.", 14, 6, 5),
                new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                        .ConsensusTrend("vor 12 Mon.", 9, 8, 7));
    }

    private static java.util.List<de.bsommerfeld.wsbg.terminal.cnbc
            .CnbcQuoteClient.EarningsQuarter> cnbcEarnings() {
        double[][] rows = {{2024, 3, 3.10, 3.42}, {2024, 4, 3.80, 3.61}, {2025, 1, 3.95, 4.30},
                {2025, 2, 4.20, 4.05}, {2025, 3, 4.10, 4.62}, {2025, 4, 5.00, 4.55},
                {2026, 1, 5.40, Double.NaN}};
        java.util.List<de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter> out =
                new java.util.ArrayList<>();
        for (double[] r : rows) {
            out.add(new de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter(
                    "RHM", (int) r[0], (int) r[1], null, r[2],
                    Double.isNaN(r[3]) ? null : r[3], null, null, null, null, null,
                    null, null, false));
        }
        return out;
    }

    private static de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot sectorEtf() {
        return DeepDiveChartsTest.closes("EXV1.DE",
                List.of(180.0, 182.4, 181.0, 184.9, 186.2, 185.1, 188.0));
    }

    private static java.util.List<de.bsommerfeld.wsbg.terminal.briefing
            .WikidataClient.PageviewPoint> attentionCurve() {
        long[] views = {1200, 1150, 1310, 1280, 1190, 1240, 4800, 9100, 5200, 3100,
                2400, 2050, 1820, 1700, 1610, 1540, 1490, 1450, 1420, 1380,
                1350, 1330, 1310, 1290, 1280, 1260, 1250, 1240, 1230, 1220};
        java.util.List<de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint> out =
                new java.util.ArrayList<>();
        for (int i = 0; i < views.length; i++) {
            out.add(new de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint(
                    java.time.LocalDate.of(2026, 7, 5).plusDays(i), views[i]));
        }
        return out;
    }

    private static DeepDiveCharts.Regime regime() {
        return new DeepDiveCharts.Regime(
                new de.bsommerfeld.wsbg.terminal.feargreed.FearGreedIndex(
                        72, "Greed", 70, 55.0, 41.0, null, java.time.Instant.EPOCH,
                        List.of(), List.of()),
                new de.bsommerfeld.wsbg.terminal.briefing.CboePutCallClient.PutCallRatios(
                        "2026-08-03", 0.87, 0.62, 1.21, 14.52, 5600),
                new de.bsommerfeld.wsbg.terminal.briefing.BundYieldClient.YieldPoint(
                        "2026-08-03", 2.54, 2.49),
                1.0842, 97.31);
    }

    private static DeepDiveCharts.Docket docket() {
        java.time.LocalDate t = java.time.LocalDate.now();
        var issuer = new java.util.ArrayList<de.bsommerfeld.wsbg.terminal.briefing
                .EqsEventsClient.CorporateEvent>();
        for (int d : new int[]{9, 12, 47, 96}) {
            issuer.add(new de.bsommerfeld.wsbg.terminal.briefing.EqsEventsClient.CorporateEvent(
                    t.plusDays(d).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(),
                    "DE0007030009", "Rheinmetall", "Termin"));
        }
        var macro = new java.util.ArrayList<de.bsommerfeld.wsbg.terminal.briefing
                .EconCalendarClient.EconEvent>();
        for (int d : new int[]{2, 4, 9, 16, 23, 31, 38, 52, 66, 80, 94, 110}) {
            macro.add(new de.bsommerfeld.wsbg.terminal.briefing.EconCalendarClient.EconEvent(
                    "Makro", "DE", t.plusDays(d).atStartOfDay(
                            java.time.ZoneId.systemDefault()).toEpochSecond(),
                    "high", null, null));
        }
        var cb = new java.util.ArrayList<de.bsommerfeld.wsbg.terminal.briefing
                .CentralBankCalendarClient.CbMeeting>();
        for (int d : new int[]{18, 60, 102}) {
            cb.add(new de.bsommerfeld.wsbg.terminal.briefing.CentralBankCalendarClient
                    .CbMeeting("EZB", "Zinsentscheid", t.plusDays(d)));
        }
        var stats = new java.util.ArrayList<de.bsommerfeld.wsbg.terminal.briefing
                .StatsReleaseCalendarClient.Release>();
        for (int d : new int[]{6, 20, 34, 48, 62, 76}) {
            stats.add(new de.bsommerfeld.wsbg.terminal.briefing.StatsReleaseCalendarClient
                    .Release(t.plusDays(d), "Destatis", "Veröffentlichung", "Q", null,
                    false, false));
        }
        return new DeepDiveCharts.Docket(issuer, List.of(), macro, cb, stats, List.of());
    }

    private static java.util.List<HeatmapService.Node> sectorBoard() {
        String[] names = {"Rüstung", "Technologie", "Banken", "Chemie", "Versorger",
                "Automobil", "Immobilien"};
        double[] perf = {3.4, 1.9, 0.8, 0.2, -0.5, -1.6, -3.1};
        java.util.List<HeatmapService.Node> out = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            out.add(new HeatmapService.Node("s" + i, names[i], null, null, 1, perf[i],
                    null, null));
        }
        return out;
    }
}
