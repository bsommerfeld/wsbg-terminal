package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystView;
import de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive;
import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealings;
import de.bsommerfeld.wsbg.terminal.core.price.ShortInterest;
import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord.ChartFigure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KI-DD figure layer: every chart exists exactly when its data block does,
 * SVGs are self-contained (viewBox, CSS-var colors with light fallbacks), and
 * data-derived text (short-seller names!) is XML-escaped.
 */
class DeepDiveChartsTest {

    private final DeepDiveCharts charts = new DeepDiveCharts("de");

    static MarketSnapshot snapshot() {
        return new MarketSnapshot("RHM", 992.10, 1010.0, -1.77, 1019.8, 985.0, 0,
                2008.5, 845.0, "EUR", "LSX", 1_700_000_000L,
                List.of(1000.0, 995.0, 992.1),
                List.of(1100.0, 1080.0, 1020.0, 992.1));
    }

    static CompanyDeepDive deepDive() {
        return new CompanyDeepDive("DE0007030009",
                new CompanyDeepDive.Profile("https://www.rheinmetall.com", "Portrait",
                        "Düsseldorf", "Deutschland", 4.6e10, 46_655_696L),
                List.of(
                        new CompanyDeepDive.KeyFigureYear("2024", false, 30.34, 8.10, 1.2,
                                20.5, 1.1, 80.0, 12.0, 15.0, 40.0, 28_000),
                        new CompanyDeepDive.KeyFigureYear("2026e", true, 55.0, 12.0, 1.4,
                                18.0, 0.9, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1)),
                List.of(
                        new CompanyDeepDive.BalanceSheetYear("2023", 7_176_000, 586_000,
                                3_100_000, 5_500_000, 8_700_000, 700_000, 340_000),
                        new CompanyDeepDive.BalanceSheetYear("2024", 9_751_000, 936_000,
                                3_800_000, 6_200_000, 10_500_000, 1_000_000, 380_000)),
                List.of(new CompanyDeepDive.BoardMember("Armin Papperger", "MEMBER", "Vorstand")),
                new CompanyDeepDive.TechnicalView(919.27, 948.29, 919.27, 845.42,
                        1096.5, 1141.75, 1187.0, 1, -1, "Kommentar", "2026-07-10"),
                List.of(new CompanyDeepDive.Peer("DE0006292030", "KSB Vz", 1.5e9, 11.9, 2.8)),
                new CompanyDeepDive.PerformanceStats(-9.2, -17.1, -33.0, -47.6, -46.1,
                        71.76, 44.92, 2008.5, "2025-10-02", 845.0, "2026-06-24"),
                "DAX PERFORMANCE INDEX", 0);
    }

    static AnalystView analystView() {
        // Far-future epochs so the events board always sees them as upcoming.
        return new AnalystView(19, 3, 5, 0, 0, 27, 16, 3, 6, 0, 0, 4, 3,
                1720.0, "EUR", 73.4, 1_700_000_000L, List.of(
                        new AnalystView.CorporateEvent(4_102_444_800L, "REPORT", "Q2-Zahlen"),
                        new AnalystView.CorporateEvent(4_105_123_200L, "AGM", "Hauptversammlung")), 0);
    }

    static ShortInterest shorts() {
        return new ShortInterest("DE0007030009", 1.15, List.of(
                new ShortInterest.ShortPosition("D. E. Shaw & Co., L.P.", 0.60, "2026-06-25"),
                new ShortInterest.ShortPosition("Qube <Research> AG", 0.55, "2026-06-20")), 0);
    }

    static InsiderDealings insider() {
        return new InsiderDealings("DE0007030009", List.of(
                new InsiderDealings.InsiderDeal("ATP Holding GmbH", "in enger Beziehung",
                        "Aktie", "Kauf", 954.62, "EUR", 3_043_314.50,
                        "2026-06-25", "2026-06-25", "Xetra")), 0);
    }

    @Test
    void buildsEveryFigureWhenEveryBlockExists() {
        List<ChartFigure> figures = charts.build(snapshot(), deepDive(), analystView(),
                shorts(), insider());
        // facts strip, eps+dividend, revenue+profit, analysts, price, performance,
        // events board, insider, shorts, S/R
        assertEquals(10, figures.size());
        for (ChartFigure f : figures) {
            assertTrue(f.svg().startsWith("<svg viewBox=\"0 0 560 "), f.title());
            assertTrue(f.svg().endsWith("</svg>"), f.title());
            assertTrue(f.svg().contains("var(--ddc-"), f.title());
            assertFalse(f.title().isBlank());
            assertTrue(f.section() >= 0 && f.section() <= 5, f.title());
        }
    }

    @Test
    void factsStripAndEventsBoardCarryTheHeadlineNumbers() {
        List<ChartFigure> figures = charts.build(snapshot(), deepDive(), analystView(),
                null, null);
        ChartFigure facts = figures.stream()
                .filter(f -> f.title().equals("Auf einen Blick")).findFirst().orElseThrow();
        assertEquals(0, facts.section());
        assertTrue(facts.svg().contains("992,10"), "price tile");
        assertTrue(facts.svg().contains("KGV 2026e"), "nearest ESTIMATE year's P/E");
        assertTrue(facts.svg().contains("1.720,00"), "consensus target");
        ChartFigure events = figures.stream()
                .filter(f -> f.title().equals("Anstehende Termine")).findFirst().orElseThrow();
        assertEquals(5, events.section());
        assertTrue(events.svg().contains("Q2-Zahlen"), "next report date on the board");
        assertTrue(events.svg().contains("01.01.2100"), "German date format");
    }

    @Test
    void everyFigureGuardsItsOwnData() {
        assertTrue(charts.build(null, null, null, null, null).isEmpty());
        // A rating-less analyst view and an empty short register draw nothing.
        AnalystView noRatings = new AnalystView(0, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1, -1,
                Double.NaN, null, Double.NaN, 0, List.of(), 0);
        ShortInterest noShorts = new ShortInterest("DE0007030009", 0, List.of(), 0);
        assertTrue(charts.build(null, null, noRatings, noShorts, null).isEmpty());
    }

    @Test
    void dataDerivedTextIsXmlEscaped() {
        List<ChartFigure> figures = charts.build(null, null, null, shorts(), null);
        assertEquals(1, figures.size());
        String svg = figures.get(0).svg();
        assertTrue(svg.contains("D. E. Shaw &amp; Co., L.P."));
        assertTrue(svg.contains("Qube &lt;Research&gt; AG"));
        assertFalse(svg.contains("<Research>"));
    }

    @Test
    void estimateYearsAreDeEmphasized() {
        List<ChartFigure> figures = charts.build(null, deepDive(), null, null, null);
        ChartFigure eps = figures.stream()
                .filter(f -> f.title().contains("Dividende")).findFirst().orElseThrow();
        assertTrue(eps.svg().contains("opacity=\"0.55\""), "estimate bars carry reduced opacity");
        assertTrue(eps.svg().contains("2026e"), "estimate year keeps its e-label");
    }
}
