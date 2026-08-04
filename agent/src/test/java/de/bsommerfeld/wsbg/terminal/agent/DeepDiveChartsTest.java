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

    /** A minimal snapshot that carries nothing but a symbol and a close series. */
    static MarketSnapshot closes(String symbol, List<Double> series) {
        return new MarketSnapshot(symbol, series.get(series.size() - 1), Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN, "EUR", null,
                1_700_000_000L, List.of(), series);
    }

    static CompanyDeepDive deepDive() {
        return new CompanyDeepDive("DE0007030009",
                new CompanyDeepDive.Profile("https://www.rheinmetall.com", "Portrait",
                        "Düsseldorf", "Deutschland", 4.6e10, 46_655_696L),
                List.of(
                        new CompanyDeepDive.KeyFigureYear("2024", false, 30.34, 8.10, 1.2,
                                20.5, 1.1, 80.0, 12.0, 15.0, 40.0, 28_000),
                        new CompanyDeepDive.KeyFigureYear("2026e", true, 55.0, 12.0, 1.4,
                                18.0, 0.9, Double.NaN, 14.0, Double.NaN, Double.NaN, -1)),
                List.of(
                        new CompanyDeepDive.BalanceSheetYear("2023", 7_176_000, 586_000,
                                3_100_000, 5_500_000, 8_700_000, 700_000, 340_000),
                        new CompanyDeepDive.BalanceSheetYear("2024", 9_751_000, 936_000,
                                3_800_000, 6_200_000, 10_500_000, 1_000_000, 380_000)),
                List.of(new CompanyDeepDive.BoardMember("Armin Papperger", "MEMBER", "Vorstand")),
                new CompanyDeepDive.TechnicalView(919.27, 948.29, 919.27, 845.42,
                        1096.5, 1141.75, 1187.0, 1, -1, "Kommentar", "2026-07-10"),
                List.of(new CompanyDeepDive.Peer("DE0006292030", "KSB Vz", 1.5e9, 11.9, 2.8),
                        new CompanyDeepDive.Peer("DE000HAG0005", "Hensoldt", 5.2e9, 24.0, 0.9)),
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

    static de.bsommerfeld.wsbg.terminal.core.price.VenueStats venueStats() {
        return new de.bsommerfeld.wsbg.terminal.core.price.VenueStats("Tradegate",
                991.80, 992.40, 300, 500, 992.10, -1.77, 1019.80, 985.00, 1010.0,
                124_374, 17_300_000, 1_234, 0);
    }

    static InsiderDealings insider() {
        return new InsiderDealings("DE0007030009", List.of(
                new InsiderDealings.InsiderDeal("ATP Holding GmbH", "in enger Beziehung",
                        "Aktie", "Kauf", 954.62, "EUR", 3_043_314.50,
                        "2026-06-25", "2026-06-25", "Xetra")), 0);
    }

    static UsListingStats usStats() {
        return new UsListingStats("RHM", "Rheinmetall AG ADR", "NASDAQ-GS",
                "Industrials", "Defense", 4.9e10, 1_200_000L, 1.2,
                List.of( // newest first, as the client delivers
                        new UsListingStats.ShortInterestPoint("2026-06-30", 1_240_000, 350_000, 3.5),
                        new UsListingStats.ShortInterestPoint("2026-06-15", 1_100_000, 340_000, 3.2),
                        new UsListingStats.ShortInterestPoint("2026-05-29", 900_000, 300_000, 3.0)),
                new UsListingStats.InsiderActivity(2, 5, 4, 9, -30_000, -120_000),
                List.of(),
                new UsListingStats.InstitutionalOwnership(61.5, 980, 210_000_000L, 5_400.0, List.of()),
                new UsListingStats.AnalystRatings("Buy", 12, 8, 3, 1, 210.0, 250.0, 160.0),
                List.of( // newest first
                        new UsListingStats.EarningsSurprise("Mar 2026", "2026-04-28", 1.42, 1.35, 5.2),
                        new UsListingStats.EarningsSurprise("Dec 2025", "2026-01-30", 1.10, 1.20, -8.3),
                        new UsListingStats.EarningsSurprise("Sep 2025", "2025-10-28", 0.98, 0.95, 3.2)),
                0);
    }

    static AnalystActions actions() {
        return new AnalystActions("Moderate Buy", 215.0, "USD", List.of(
                new AnalystActions.Action(null, null, "2026-07-01", "Morgan Stanley", null,
                        "Downgrade", "Buy", "Hold", 120.0, 95.0, "USD"),
                new AnalystActions.Action(null, null, "2026-06-20", "Jefferies & Co.", null,
                        "Boost Target", null, "Buy", Double.NaN, 130.0, "USD"),
                new AnalystActions.Action(null, null, "2026-06-01", "HSBC", null,
                        "Initiated Coverage", null, "Hold", Double.NaN, Double.NaN, null)),
                new AnalystActions.UsShortStats(1_240_000, 1_100_000, 4.2e7, 3.5, 0.96,
                        "2026-06-30"),
                0);
    }

    static HedgeFundPopularity hedgeFunds() {
        return new HedgeFundPopularity("RHM", 12345L, List.of( // oldest to newest, as delivered
                new HedgeFundPopularity.QuarterPoint("2025-09-30", "Q3 2025", 38, 1_000_000,
                        4, 2, 80.0, false),
                new HedgeFundPopularity.QuarterPoint("2025-12-31", "Q4 2025", 42, 1_100_000,
                        6, 2, 90.0, false),
                new HedgeFundPopularity.QuarterPoint("2026-03-31", "Q1 2026", 48, 1_300_000,
                        9, 3, 100.0, false)),
                List.of());
    }

    static PressTimeline pressTimeline() {
        return new PressTimeline("RHM", List.of( // newest first
                new PressTimeline.Entry("2026-07-10", "T1", "p"),
                new PressTimeline.Entry("2026-07-01", "T2", "p"),
                new PressTimeline.Entry("2026-06-12", "T3", "p"),
                new PressTimeline.Entry("2026-05-03", "T4", "p"),
                new PressTimeline.Entry("2026-03-20", "T5", "p")));
    }

    static VolumeProfile.Profile volumeProfile() {
        // POC inside the value area, value area inside the profile range.
        return new VolumeProfile.Profile(952.50, 1001.00, 918.00, 42_500, 310_000,
                2.30, 872.00, 987.00);
    }

    static OrderBookSnapshot orderBook() {
        return new OrderBookSnapshot("DE0007030009", "15.07.26 14:32:11",
                List.of(new OrderBookSnapshot.Level(991.80, 3, 420),
                        new OrderBookSnapshot.Level(991.50, 1, 150),
                        new OrderBookSnapshot.Level(990.00, 0, 800)),
                List.of(new OrderBookSnapshot.Level(992.40, 2, 260),
                        new OrderBookSnapshot.Level(993.00, 4, 1_200)));
    }

    static List<MarketEventRecord> memoryEvents() {
        return List.of( // oldest first, as the material carries them
                new MarketEventRecord("2026-03-12", "RHM.DE", "DE0007030009",
                        "EARNINGS_BEAT", "NASDAQ", "surprise +5.2%", "greed", 62.0,
                        3.4, 5.1, "XLI", false),
                new MarketEventRecord("2026-05-20", "RHM.DE", "DE0007030009",
                        "GUIDANCE_CUT", "EQS", "ad-hoc", "fear", 38.0,
                        -6.8, -4.2, "XLI", true),
                new MarketEventRecord("2026-07-01", "RHM.DE", "DE0007030009",
                        "ANALYST_ACTION", "MarketBeat", "downgrade", null, null,
                        null, null, null, null));
    }

    static List<String> worldSignals() {
        return List.of(
                "Maritime chokepoint Suez Canal: 28 transits/day, -12 % vs week before",
                "US petroleum stocks (EIA weekly report, week ending 2026-07-03): "
                        + "crude -3.2 million barrels",
                "World hazard [STORM, HIGH]: hurricane approaching Gulf refineries");
    }

    @Test
    void buildsEveryFigureWhenEveryBlockExists() {
        List<ChartFigure> figures = charts.build(snapshot(), deepDive(), analystView(),
                shorts(), insider(), venueStats(), usStats(), actions(), hedgeFunds(),
                pressTimeline(), worldSignals(), volumeProfile(), orderBook(), memoryEvents());
        // facts strip, eps+dividend, revenue+profit, margins, cashflow+R&D,
        // earnings surprises, analysts, action timeline, hedge-fund curve,
        // peer scatter, price, trading picture, volume profile, order book,
        // press timeline, world signals, performance, 52w range, events board,
        // street band, insider, shorts, US short history, event history, S/R
        assertEquals(25, figures.size());
        for (ChartFigure f : figures) {
            assertTrue(f.svg().startsWith("<svg viewBox=\"0 0 720 "), f.title());
            // No figure may set type below the readability floor: font sizes
            // are viewBox units and shrink with the rendered width.
            assertFalse(f.svg().matches("(?s).*font-size=\"[0-9]\"" + ".*"), f.title());
            assertTrue(f.svg().endsWith("</svg>"), f.title());
            assertTrue(f.svg().contains("var(--ddc-"), f.title());
            assertFalse(f.title().isBlank());
            assertTrue(f.section() >= 0 && f.section() <= 6, f.title());
        }
    }

    @Test
    void factsStripAndEventsBoardCarryTheHeadlineNumbers() {
        List<ChartFigure> figures = charts.build(snapshot(), deepDive(), analystView(),
                null, null, null, null, null, null, null, List.of(), null, null, null);
        ChartFigure facts = figures.stream()
                .filter(f -> f.title().equals("Auf einen Blick")).findFirst().orElseThrow();
        assertEquals(0, facts.section());
        assertTrue(facts.svg().contains("992,10"), "price tile");
        // A tile gives up TYPE SIZE before it gives up digits: the venue strip
        // sets four values across the column, and "991,80 / 992,40" does not
        // fit at the headline size — it must still arrive whole.
        ChartFigure venue = new DeepDiveCharts("de").build(null, null, null, null, null,
                venueStats(), null, null, null, null, List.of(), null, null, null).get(0);
        assertTrue(venue.svg().contains("991,80 / 992,40"), "bid/ask arrives unabridged");
        assertTrue(venue.svg().contains("985,00 – 1.019,80"), "the day range too");
        assertFalse(venue.svg().contains("…"), "nothing in the strip is truncated");
        assertTrue(facts.svg().contains("KGV 2026e"), "nearest ESTIMATE year's P/E");
        assertTrue(facts.svg().contains("1.720,00"), "consensus target");
        // The date board anchors under the ANCHORED outlook section since the
        // eight-section skeleton (Ausblick = ordinal 6).
        ChartFigure events = figures.stream()
                .filter(f -> f.title().equals("Anstehende Termine")).findFirst().orElseThrow();
        assertEquals(6, events.section());
        assertTrue(events.svg().contains("Q2-Zahlen"), "next report date on the board");
        assertTrue(events.svg().contains("01.01.2100"), "German date format");
    }

    @Test
    void everyFigureGuardsItsOwnData() {
        assertTrue(charts.build(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null).isEmpty());
        // A rating-less analyst view and an empty short register draw nothing.
        AnalystView noRatings = new AnalystView(0, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, -1, -1,
                Double.NaN, null, Double.NaN, 0, List.of(), 0);
        ShortInterest noShorts = new ShortInterest("DE0007030009", 0, List.of(), 0);
        assertTrue(charts.build(null, null, noRatings, noShorts, null, null,
                null, null, null, null, List.of(), null, null, null).isEmpty());
        // Present-with-empty US/street/press legs draw nothing either.
        UsListingStats emptyUs = new UsListingStats("X", null, null, null, null,
                Double.NaN, -1, Double.NaN, List.of(), null, List.of(), null, null,
                List.of(), 0);
        AnalystActions emptyActions = new AnalystActions(null, Double.NaN, null,
                List.of(), null, 0);
        HedgeFundPopularity emptyHf = new HedgeFundPopularity("X", 1, List.of(), List.of());
        PressTimeline emptyPress = new PressTimeline("X", List.of());
        assertTrue(charts.build(null, null, null, null, null, null,
                emptyUs, emptyActions, emptyHf, emptyPress, List.of(), null, null, null).isEmpty());
        // A target-less action history is the actions TABLE's job, not a figure's.
        AnalystActions labelOnly = new AnalystActions(null, Double.NaN, null, List.of(
                new AnalystActions.Action(null, null, "2026-07-01", "HSBC", null,
                        "Initiated Coverage", null, "Hold", Double.NaN, Double.NaN, null)),
                null, 0);
        assertTrue(charts.build(null, null, null, null, null, null,
                null, labelOnly, null, null, List.of(), null, null, null).isEmpty());
        // Structure/memory legs: an empty book, a units-less book, a
        // degenerate profile and an empty event register draw nothing.
        OrderBookSnapshot emptyBook = new OrderBookSnapshot("X", null, List.of(), List.of());
        OrderBookSnapshot unitlessBook = new OrderBookSnapshot("X", null,
                List.of(new OrderBookSnapshot.Level(10.0, 0, 0)), List.of());
        VolumeProfile.Profile flatProfile =
                new VolumeProfile.Profile(10, 11, 9, 0, 0, 0.1, 10, 10);
        assertTrue(charts.build(null, null, null, null, null, null,
                null, null, null, null, List.of(), flatProfile, emptyBook, List.of()).isEmpty());
        assertTrue(charts.build(null, null, null, null, null, null,
                null, null, null, null, List.of(), null, unitlessBook, List.of()).isEmpty());
    }

    @Test
    void dataDerivedTextIsXmlEscaped() {
        List<ChartFigure> figures = charts.build(null, null, null, shorts(), null, null,
                null, null, null, null, List.of(), null, null, null);
        assertEquals(1, figures.size());
        String svg = figures.get(0).svg();
        assertTrue(svg.contains("D. E. Shaw &amp; Co., L.P."));
        assertTrue(svg.contains("Qube &lt;Research&gt; AG"));
        assertFalse(svg.contains("<Research>"));
    }

    @Test
    void newSeriesFiguresCarryTheRecitedNumbers() {
        List<ChartFigure> figures = charts.build(snapshot(), deepDive(), null, null, null,
                venueStats(), null, null, null, null, List.of(), null, null, null);
        ChartFigure range = figures.stream()
                .filter(f -> f.title().contains("52-Wochen")).findFirst().orElseThrow();
        assertEquals(4, range.section());
        assertTrue(range.svg().contains("2.008,50"), "52w high");
        assertTrue(range.svg().contains("845,00"), "52w low");
        assertTrue(range.svg().contains("vom Hoch"), "house-computed distance");
        ChartFigure venue = figures.stream()
                .filter(f -> f.title().equals("Handelsbild")).findFirst().orElseThrow();
        assertEquals(2, venue.section());
        assertTrue(venue.svg().contains("Spread"), "bid/ask spread");
        assertTrue(venue.svg().contains("124.374"), "shares traded");
        assertTrue(venue.svg().contains("17,3 Mio."), "EUR turnover");
        ChartFigure cash = figures.stream()
                .filter(f -> f.title().contains("Cashflow")).findFirst().orElseThrow();
        assertEquals(3, cash.section());
        ChartFigure margin = figures.stream()
                .filter(f -> f.title().contains("EBIT-Marge")).findFirst().orElseThrow();
        assertTrue(margin.svg().contains("opacity=\"0.55\""), "estimate year de-emphasized");
    }

    /**
     * The Consorsbank range marks are EUR — a USD snapshot must not put its
     * price mark on the EUR track (live run 9: −37,7 % from a USD price
     * against an EUR high; the honest distance was ~46 %).
     */
    @Test
    void usdSnapshotDrawsNoPriceMarkOnTheEurRange() {
        MarketSnapshot usd = new MarketSnapshot("NOW", 111.26, 112.0, 3.3, 112.83, 108.38, 0,
                178.53, 69.31, "USD", "NYQ", 1_700_000_000L, List.of(), List.of());
        ChartFigure range = charts.build(usd, deepDive(), null, null, null, null,
                        null, null, null, null, List.of(), null, null, null).stream()
                .filter(f -> f.title().contains("52-Wochen")).findFirst().orElseThrow();
        assertFalse(range.svg().contains("vom Hoch"), "no cross-currency distance");
        assertTrue(range.svg().contains("2.008,50"), "the EUR range itself stays");
    }

    @Test
    void estimateYearsAreDeEmphasized() {
        List<ChartFigure> figures = charts.build(null, deepDive(), null, null, null, null,
                null, null, null, null, List.of(), null, null, null);
        ChartFigure eps = figures.stream()
                .filter(f -> f.title().contains("Dividende")).findFirst().orElseThrow();
        assertTrue(eps.svg().contains("opacity=\"0.55\""), "estimate bars carry reduced opacity");
        assertTrue(eps.svg().contains("2026e"), "estimate year keeps its e-label");
    }

    // ---- Wave B: the eight new figures ----

    @Test
    void actionTimelineDrawsArrowsTicksAndLabels() {
        List<ChartFigure> figures = charts.build(null, null, null, null, null, null,
                null, actions(), null, null, List.of(), null, null, null);
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(4, f.section());
        assertTrue(f.title().contains("USD"), "scale currency in the title");
        assertEquals("MarketBeat", f.note());
        assertTrue(f.svg().contains("Morgan Stanley"), "brokerage label");
        assertTrue(f.svg().contains("Buy → Hold"), "rating move beside the row");
        assertTrue(f.svg().contains("95,00"), "new target direct-labeled (German format)");
        assertTrue(f.svg().contains("130,00"), "single-target row keeps a labeled tick");
        assertTrue(f.svg().contains("Jefferies &amp; Co."), "brokerage XML-escaped");
        assertTrue(f.svg().contains("01.07.2026"), "German date");
        assertTrue(f.svg().contains("HSBC"), "target-less row stays a dated row");
        assertTrue(f.svg().contains("01.06.2026"), "target-less row keeps its date");
    }

    @Test
    void usShortHistoryCarriesSharesAndDaysToCover() {
        List<ChartFigure> figures = charts.build(null, null, null, null, null, null,
                usStats(), null, null, null, List.of(), null, null, null);
        ChartFigure f = figures.stream()
                .filter(x -> x.title().contains("Short-Interest")).findFirst().orElseThrow();
        assertEquals(5, f.section());
        assertEquals("NASDAQ · FINRA", f.note());
        assertTrue(f.svg().contains("3,5"), "days-to-cover point label");
        assertTrue(f.svg().contains("1,2 Mio."), "latest shares compact-labeled");
        assertTrue(f.svg().contains("29.05.2026"), "chronological start date");
        assertTrue(f.svg().contains("30.06.2026"), "chronological end date");
    }

    @Test
    void surpriseStripShowsSignedPercentsPerQuarter() {
        List<ChartFigure> figures = charts.build(null, null, null, null, null, null,
                usStats(), null, null, null, List.of(), null, null, null);
        ChartFigure f = figures.stream()
                .filter(x -> x.title().contains("EPS-Überraschung")).findFirst().orElseThrow();
        assertEquals(3, f.section());
        assertEquals("NASDAQ", f.note());
        assertTrue(f.svg().contains("+5,2 %"), "beat labeled with sign");
        assertTrue(f.svg().contains("−8,3 %"), "miss labeled with sign");
        assertTrue(f.svg().contains("Mar 2026"), "quarter label");
        assertTrue(f.svg().contains("var(--ddc-neg"), "miss dot rides the loss tone");
    }

    @Test
    void hedgeFundCurveShowsFundsAndFlows() {
        List<ChartFigure> figures = charts.build(null, null, null, null, null, null,
                null, null, hedgeFunds(), null, List.of(), null, null, null);
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(4, f.section());
        assertEquals("Insider Monkey", f.note());
        assertTrue(f.svg().contains(">48<"), "latest fund count at the end marker");
        assertTrue(f.svg().contains("+9"), "new positions bar labeled");
        assertTrue(f.svg().contains("−3"), "closed positions bar labeled");
        assertTrue(f.svg().contains("Q3 2025"), "first quarter label");
        assertTrue(f.svg().contains("Q1 2026"), "last quarter label");
    }

    @Test
    void streetBandGuardsCurrencyLikeTheScenarioTable() {
        // EUR snapshot against USD targets: the band stands, the price mark
        // and the house distance stay out (cross-currency corruption guard).
        ChartFigure band = charts.build(snapshot(), null, null, null, null, null,
                        usStats(), null, null, null, List.of(), null, null, null).stream()
                .filter(x -> x.title().contains("Kursziel-Band")).findFirst().orElseThrow();
        assertEquals(6, band.section());
        assertEquals("NASDAQ", band.note());
        assertTrue(band.title().contains("USD"));
        assertTrue(band.svg().contains("Street-Hoch"));
        assertTrue(band.svg().contains("250,00"));
        assertTrue(band.svg().contains("160,00"));
        assertTrue(band.svg().contains("210,00"));
        assertFalse(band.svg().contains("bis Konsens"), "no cross-currency distance");
        assertFalse(band.svg().contains("Kurs<"), "no price mark on a foreign-currency band");
    }

    @Test
    void streetBandFallsBackToConsorsConsensusWithMatchingPrice() {
        ChartFigure band = charts.build(snapshot(), null, analystView(), null, null, null,
                        null, null, null, null, List.of(), null, null, null).stream()
                .filter(x -> x.title().contains("Kursziel-Band")).findFirst().orElseThrow();
        assertEquals("Consorsbank", band.note());
        assertTrue(band.title().contains("EUR"));
        assertTrue(band.svg().contains("Konsens"), "consensus rung");
        assertTrue(band.svg().contains("1.720,00"), "consensus value");
        assertTrue(band.svg().contains("992,10"), "matching-currency price mark");
        assertTrue(band.svg().contains("bis Konsens"), "house-computed distance");
        assertTrue(band.svg().contains("+73,4 %"), "distance arithmetic");
    }

    @Test
    void peerScatterHighlightsTheSubjectGold() {
        List<ChartFigure> figures = charts.build(snapshot(), deepDive(), null, null, null,
                null, null, null, null, null, List.of(), null, null, null);
        ChartFigure f = figures.stream()
                .filter(x -> x.title().contains("Peer-Vergleich")).findFirst().orElseThrow();
        assertEquals(4, f.section());
        assertEquals("Consorsbank", f.note());
        assertTrue(f.svg().contains("KSB Vz"), "peer label");
        assertTrue(f.svg().contains("Hensoldt"), "peer label");
        assertTrue(f.svg().contains("RHM"), "subject labeled by its symbol");
        assertTrue(f.svg().contains("var(--ddc-sun"), "subject dot rides the gold token");
    }

    @Test
    void peerScatterNeedsThreePoints() {
        // One peer + the subject = two points: no field to read, no figure.
        CompanyDeepDive d = deepDive();
        CompanyDeepDive thin = new CompanyDeepDive(d.isin(), d.profile(), d.keyFigures(),
                d.balanceSheet(), d.board(), d.technicalView(),
                List.of(d.peers().get(0)), d.performance(), d.indexName(),
                d.fetchedAtEpochSeconds());
        List<ChartFigure> figures = charts.build(snapshot(), thin, null, null, null, null,
                null, null, null, null, List.of(), null, null, null);
        assertTrue(figures.stream().noneMatch(x -> x.title().contains("Peer-Vergleich")));
    }

    @Test
    void pressTimelineShowsDensityWithMonthLabels() {
        List<ChartFigure> figures = charts.build(null, null, null, null, null, null,
                null, null, null, pressTimeline(), List.of(), null, null, null);
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(2, f.section());
        assertEquals("MarketBeat", f.note());
        assertTrue(f.title().contains("5"), "headline count in the title");
        assertTrue(f.title().contains("Presse-Dichte"), "the figure answers HOW DENSE");
        assertTrue(f.svg().contains("Mai"), "German month label");
        // The bare tick strip said nothing: the bar carries the month's COUNT.
        // The fixture's July holds two of the five headlines.
        assertTrue(f.svg().contains(">2</text>"), "the busiest month carries its count");
        assertTrue(f.svg().contains("opacity=\"0.5\""), "exact days ride over the density");
    }

    /**
     * Nothing may be drawn outside its own canvas. A provider VAH sitting
     * ABOVE its own profile high plotted at a negative y and had its label
     * sliced off by the top edge — the ladder was scaled to the profile
     * envelope instead of to the rungs it actually draws.
     */
    @Test
    void everyMarkStaysInsideItsCanvas() {
        List<ChartFigure> figures = charts.build(snapshot(), deepDive(), analystView(),
                shorts(), insider(), venueStats(), usStats(), actions(), hedgeFunds(),
                pressTimeline(), worldSignals(), volumeProfile(), orderBook(), memoryEvents());
        java.util.regex.Pattern box = java.util.regex.Pattern.compile(
                "viewBox=\"0 0 \\d+ (\\d+)\"");
        java.util.regex.Pattern ys = java.util.regex.Pattern.compile(
                "\\b(?:y|cy|y1|y2)=\"(-?[0-9.]+)\"");
        for (ChartFigure f : figures) {
            var mb = box.matcher(f.svg());
            assertTrue(mb.find(), f.title());
            double h = Double.parseDouble(mb.group(1));
            var my = ys.matcher(f.svg());
            while (my.find()) {
                double y = Double.parseDouble(my.group(1));
                assertTrue(y >= 0 && y <= h,
                        f.title() + ": y=" + y + " outside 0.." + h);
            }
        }
    }

    // ---- Wave F: the legs the figure layer had never drawn from ----

    /** Everything the pre-record call sites pass still draws, unchanged. */
    private static DeepDiveCharts.ChartInput input(
            List<de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient.YearPerformance> years,
            List<de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.ConsensusTrend> trend,
            List<HeatmapService.Node> board, String subjectSector, String universe) {
        return new DeepDiveCharts.ChartInput(null, null, null, null, null, null, null, null,
                null, null, List.of(), null, null, null, years, trend, board,
                subjectSector, universe, List.of(), null, null, null, List.of(),
                DeepDiveCharts.Docket.EMPTY, DeepDiveCharts.Regime.EMPTY,
                DeepDiveCharts.Registers.EMPTY, DeepDiveCharts.Boards.EMPTY, List.of());
    }

    /** An input carrying only the wave-G legs; everything else stays silent. */
    private static DeepDiveCharts.ChartInput waveG(
            List<de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter> cnbc,
            MarketSnapshot subject, MarketSnapshot sectorEtf, String etfSymbol, String etfName,
            List<de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint> attention,
            DeepDiveCharts.Docket docket, DeepDiveCharts.Regime regime) {
        return new DeepDiveCharts.ChartInput(subject, null, null, null, null, null, null, null,
                null, null, List.of(), null, null, null, List.of(), List.of(), List.of(),
                null, null, cnbc, sectorEtf, etfSymbol, etfName, attention, docket, regime,
                DeepDiveCharts.Registers.EMPTY, DeepDiveCharts.Boards.EMPTY, List.of());
    }

    @Test
    void deliveryRecordSetsConsensusBesideWhatWasReported() {
        var q = List.of(
                new de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter(
                        "RHM", 2025, 3, null, 4.10, 4.62, null, null, null, null, null,
                        "Beat", null, false),
                new de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter(
                        "RHM", 2025, 4, null, 5.00, 4.55, null, null, null, null, null,
                        "Miss", null, false),
                // still ahead: a consensus with nothing reported yet
                new de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter(
                        "RHM", 2026, 1, null, 5.40, null, null, null, null, null, null,
                        null, null, false));
        List<ChartFigure> figures = charts.build(waveG(q, null, null, null, null, List.of(),
                DeepDiveCharts.Docket.EMPTY, DeepDiveCharts.Regime.EMPTY));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(3, f.section());
        assertEquals("CNBC", f.note());
        assertTrue(f.svg().contains("Q3/25") && f.svg().contains("Q1/26"), "quarter ticks");
        assertTrue(f.svg().contains("4,62"), "what was actually reported");
        assertTrue(f.svg().contains("opacity=\"0.55\""), "an unreported quarter is de-emphasized");
        // One quarter is no record.
        assertTrue(charts.build(waveG(q.subList(0, 1), null, null, null, null, List.of(),
                DeepDiveCharts.Docket.EMPTY, DeepDiveCharts.Regime.EMPTY)).isEmpty());
    }

    @Test
    void relativeStrengthIndexesBothSeriesToTheSameStart() {
        MarketSnapshot subject = closes("RHM", List.of(100.0, 110.0, 130.0));
        MarketSnapshot sector = closes("EXV1.DE", List.of(200.0, 202.0, 206.0));
        List<ChartFigure> figures = charts.build(waveG(List.of(), subject, sector,
                "EXV1.DE", "Rüstung Europa", List.of(),
                DeepDiveCharts.Docket.EMPTY, DeepDiveCharts.Regime.EMPTY));
        // the price figure plus the relative-strength figure
        ChartFigure f = figures.stream().filter(x -> x.title().contains("Relative Stärke"))
                .findFirst().orElseThrow();
        assertEquals(2, f.section());
        assertTrue(f.note().contains("EXV1.DE"), f.note());
        assertTrue(f.svg().contains("+30,0 %"), "the subject's own move over the window");
        assertTrue(f.svg().contains("+3,0 %"), "the sector's move on the SAME scale");
        assertTrue(f.svg().contains("Rüstung Europa"), "the proxy is named, not just tickered");
        // No proxy, no comparison.
        assertTrue(charts.build(waveG(List.of(), subject, null, null, null, List.of(),
                DeepDiveCharts.Docket.EMPTY, DeepDiveCharts.Regime.EMPTY)).stream()
                .noneMatch(x -> x.title().contains("Relative Stärke")));
    }

    @Test
    void attentionCurveNamesItsPeakDay() {
        List<de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint> curve =
                new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            curve.add(new de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint(
                    java.time.LocalDate.of(2026, 7, 1).plusDays(i), i == 6 ? 9000 : 1000));
        }
        List<ChartFigure> figures = charts.build(waveG(List.of(), null, null, null, null, curve,
                DeepDiveCharts.Docket.EMPTY, DeepDiveCharts.Regime.EMPTY));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(2, f.section());
        assertEquals("Wikimedia", f.note());
        assertTrue(f.svg().contains("07.07.2026"), "the peak day is dated");
        assertTrue(f.svg().contains("9.000"), "and carries its own count in full");
        // Too short a window is no curve.
        assertTrue(charts.build(waveG(List.of(), null, null, null, null,
                curve.subList(0, 4), DeepDiveCharts.Docket.EMPTY,
                DeepDiveCharts.Regime.EMPTY)).isEmpty());
    }

    @Test
    void regimeMeterCarriesTodayAndWhereItCameFrom() {
        var fg = new de.bsommerfeld.wsbg.terminal.feargreed.FearGreedIndex(
                72, "Greed", 70, 55.0, 41.0, null, java.time.Instant.EPOCH,
                List.of(), List.of());
        var pc = new de.bsommerfeld.wsbg.terminal.briefing.CboePutCallClient.PutCallRatios(
                "2026-08-03", 0.87, 0.62, 1.21, 14.5, 5600);
        var bund = new de.bsommerfeld.wsbg.terminal.briefing.BundYieldClient.YieldPoint(
                "2026-08-03", 2.54, 2.49);
        var regime = new DeepDiveCharts.Regime(fg, pc, bund, 1.0842, 97.3);
        List<ChartFigure> figures = charts.build(waveG(List.of(), null, null, null, null,
                List.of(), DeepDiveCharts.Docket.EMPTY, regime));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(2, f.section());
        assertTrue(f.svg().contains("72 · Greed"), "today's composite and its rating");
        assertTrue(f.svg().contains("vor 1 Wo. 55") && f.svg().contains("vor 1 Mon. 41"),
                "where the tape came from");
        assertTrue(f.svg().contains("0,87") && f.svg().contains("2,54 %")
                && f.svg().contains("1,0842"), "the anchors a valuation hangs on");
        // A yield's move is basis points, never a percent OF the yield.
        assertTrue(f.svg().contains("Vortag 2,49 %"), "the previous LEVEL, not a delta");
        // An empty regime draws nothing at all.
        assertTrue(charts.build(waveG(List.of(), null, null, null, null, List.of(),
                DeepDiveCharts.Docket.EMPTY, DeepDiveCharts.Regime.EMPTY)).isEmpty());
    }

    @Test
    void docketPutsEveryDatedLegOnOneForwardAxis() {
        java.time.LocalDate today = java.time.LocalDate.now();
        var docket = new DeepDiveCharts.Docket(
                List.of(new de.bsommerfeld.wsbg.terminal.briefing.EqsEventsClient.CorporateEvent(
                        today.plusDays(12).atStartOfDay(java.time.ZoneId.systemDefault())
                                .toInstant(), "DE0007030009", "Rheinmetall", "Q3-Zahlen")),
                List.of(),
                List.of(new de.bsommerfeld.wsbg.terminal.briefing.EconCalendarClient.EconEvent(
                        "CPI", "DE", today.plusDays(4).atStartOfDay(
                                java.time.ZoneId.systemDefault()).toEpochSecond(),
                        "high", null, null)),
                List.of(new de.bsommerfeld.wsbg.terminal.briefing.CentralBankCalendarClient
                        .CbMeeting("EZB", "Zinsentscheid", today.plusDays(30))),
                List.of(), List.of());
        List<ChartFigure> figures = charts.build(waveG(List.of(), null, null, null, null,
                List.of(), docket, DeepDiveCharts.Regime.EMPTY));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(6, f.section());
        assertTrue(f.svg().contains("Emittent") && f.svg().contains("Makro")
                && f.svg().contains("Zentralbank"), "one lane per kind");
        assertFalse(f.svg().contains("Statistik"), "an empty leg gets no lane");
        assertTrue(f.title().contains("3"), "the total the lanes hold");
        // A docket of only past dates is no forecast.
        var pastOnly = new DeepDiveCharts.Docket(List.of(), List.of(), List.of(),
                List.of(new de.bsommerfeld.wsbg.terminal.briefing.CentralBankCalendarClient
                        .CbMeeting("EZB", "Zinsentscheid", today.minusDays(30))),
                List.of(), List.of());
        assertTrue(charts.build(waveG(List.of(), null, null, null, null, List.of(),
                pastOnly, DeepDiveCharts.Regime.EMPTY)).isEmpty());
    }

    @Test
    void calendarYearArcCarriesEveryYearsOwnMove() {
        var years = List.of(
                new de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient
                        .YearPerformance(2022, 90, 60, -30, -33.3),
                new de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient
                        .YearPerformance(2023, 60, 120, 60, 100.0),
                new de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient
                        .YearPerformance(2024, 120, 150, 30, 25.0));
        List<ChartFigure> figures = charts.build(input(years, List.of(), List.of(), null, null));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(2, f.section());
        assertEquals("onvista", f.note());
        assertTrue(f.title().contains("2022") && f.title().contains("2024"), f.title());
        assertTrue(f.svg().contains("+100,0 %"), "the year's own move, direct-labeled");
        assertTrue(f.svg().contains("−33,3 %"), "a losing year keeps its sign");
        // A single year is no arc.
        assertTrue(charts.build(input(years.subList(0, 1), List.of(), List.of(), null, null))
                .isEmpty());
    }

    @Test
    void consensusArcShowsWhereTheStreetCameFrom() {
        var trend = List.of(
                new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                        .ConsensusTrend("Heute", 12, 4, 1),
                new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                        .ConsensusTrend("vor 6 Mon.", 8, 6, 3),
                new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                        .ConsensusTrend("vor 12 Mon.", 5, 7, 5));
        List<ChartFigure> figures = charts.build(input(List.of(), trend, List.of(), null, null));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(4, f.section());
        assertEquals("finanzen.net", f.note());
        assertTrue(f.svg().contains("vor 12 Mon."), "the far end of the arc");
        assertTrue(f.svg().contains("Kaufen"), "localized tier legend");
        // A single point in time is no arc, and an all-zero row draws nothing.
        assertTrue(charts.build(input(List.of(), trend.subList(0, 1), List.of(), null, null))
                .isEmpty());
    }

    @Test
    void sectorBoardRanksSectorsAndMarksTheSubjectsOwn() {
        List<HeatmapService.Node> board = List.of(
                new HeatmapService.Node("s1", "Rüstung", null, null, 1, 3.4, null, null),
                new HeatmapService.Node("s2", "Banken", null, null, 1, -1.2, null, null),
                new HeatmapService.Node("s3", "Chemie", null, null, 1, 0.6, null, null),
                // a leaf carries parent AND symbol — it is not a sector row
                new HeatmapService.Node("l1", "Rheinmetall", "s1", "RHM", 1, 5.0, null, null));
        List<ChartFigure> figures = charts.build(
                input(List.of(), List.of(), board, "Rüstung", "DAX"));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(2, f.section());
        assertTrue(f.note().contains("DAX"), f.note());
        assertTrue(f.svg().contains("Rüstung") && f.svg().contains("Banken"));
        assertFalse(f.svg().contains("Rheinmetall"), "leaves are not sector rows");
        assertTrue(f.svg().contains("+3,4 %") && f.svg().contains("−1,2 %"));
        assertTrue(f.svg().contains("--ddc-sun"), "the subject's own sector is marked");
        // Fewer than three sectors is no field to read.
        assertTrue(charts.build(input(List.of(), List.of(), board.subList(0, 2), null, null))
                .isEmpty());
    }

    /**
     * No LABEL may run off the side of its own canvas. The y-bound test above
     * catches marks; this one catches text, which is where the overruns
     * actually happen: a label is anchored at an x inside the box and then
     * extends past the edge because nothing budgeted its width.
     */
    @Test
    void noLabelRunsOffTheSide() {
        List<ChartFigure> figures = allFigures();
        java.util.regex.Pattern box = java.util.regex.Pattern.compile("viewBox=\"0 0 (\\d+) ");
        java.util.regex.Pattern txt = java.util.regex.Pattern.compile(
                "<text x=\"(-?[0-9.]+)\"[^>]*text-anchor=\"(\\w+)\"[^>]*font-size=\"(\\d+)\""
                        + "[^>]*>([^<]*)</text>");
        List<String> overruns = new java.util.ArrayList<>();
        for (ChartFigure f : figures) {
            var mb = box.matcher(f.svg());
            assertTrue(mb.find(), f.title());
            double w = Double.parseDouble(mb.group(1));
            var mt = txt.matcher(f.svg());
            while (mt.find()) {
                double x = Double.parseDouble(mt.group(1));
                String anchor = mt.group(2);
                int size = Integer.parseInt(mt.group(3));
                String content = mt.group(4);
                // Same estimate the builder uses to decide collisions.
                double tw = content.length() * size * 0.58;
                double left = switch (anchor) {
                    case "end" -> x - tw;
                    case "middle" -> x - tw / 2;
                    default -> x;
                };
                double right = left + tw;
                // A 2-unit tolerance: the estimate is an estimate.
                if (left < -2 || right > w + 2) {
                    overruns.add(f.title() + ": \"" + content + "\" ["
                            + Math.round(left) + ".." + Math.round(right) + "] in 0.." + (int) w);
                }
            }
        }
        assertTrue(overruns.isEmpty(), "labels running off the canvas:\n"
                + String.join("\n", overruns));
    }

    /**
     * The same invariant under LOAD: real registers carry long holder names,
     * long brokerage names and long signal lines, and those are what actually
     * push a label off the edge. Benign fixtures prove nothing here.
     */
    @Test
    void noLabelRunsOffTheSideWithLongRealWorldStrings() {
        String longName = "Deutsche Gesellschaft für Wertpapierverwahrung und "
                + "Sondervermögen mbH & Co. KG";
        var longShorts = new ShortInterest("DE0007030009", 4.2, List.of(
                new ShortInterest.ShortPosition(longName, 2.10, "2026-08-01"),
                new ShortInterest.ShortPosition(longName + " II", 1.05, "2026-08-01")), 2);
        var longActions = new AnalystActions(null, Double.NaN, null, List.of(
                new AnalystActions.Action(null, null, "2026-07-01",
                        "Morgan Stanley & Co. International plc", null,
                        "Reiterated Rating with a very long action type",
                        "Equal-Weight / In-Line", "Overweight / Outperform",
                        120.0, 195.0, "USD")), null, 1);
        var longSignals = List.of(
                "World hazard [STORM, HIGH]: an unusually long advisory line that a producer "
                        + "may well emit in full, with clauses and a trailing tail",
                "US petroleum stocks (EIA weekly report, week ending 2026-07-03): crude "
                        + "-3.2 million barrels, gasoline +1.1 million barrels");
        var longSectors = List.of(
                new HeatmapService.Node("s1",
                        "Industrielle Güter, Rüstung und Luft- und Raumfahrttechnik",
                        null, null, 1, 3.4, null, null),
                new HeatmapService.Node("s2", "Banken und Finanzdienstleistungen",
                        null, null, 1, -12.7, null, null),
                new HeatmapService.Node("s3", "Chemie", null, null, 1, 0.6, null, null));
        var longEvents = new AnalystView(19, 3, 5, 0, 0, 27, 16, 3, 6, 0, 0, 25, 0,
                1720.0, "EUR", 73.4, 0, List.of(new AnalystView.CorporateEvent(
                        4_102_444_800L, "Hauptversammlung mit ordentlicher Beschlussfassung "
                                + "über die Verwendung des Bilanzgewinns", "AGM")), 1);

        List<ChartFigure> figures = new DeepDiveCharts("de").build(
                new DeepDiveCharts.ChartInput(
                        snapshot(), deepDive(), longEvents, longShorts, insider(), venueStats(),
                        usStats(), longActions, hedgeFunds(), pressTimeline(), longSignals,
                        volumeProfile(), orderBook(), memoryEvents(),
                        List.of(), List.of(), longSectors,
                        "Industrielle Güter, Rüstung und Luft- und Raumfahrttechnik", "DAX",
                        List.of(), null, null, null, List.of(),
                        DeepDiveCharts.Docket.EMPTY, DeepDiveCharts.Regime.EMPTY,
                        DeepDiveCharts.Registers.EMPTY, DeepDiveCharts.Boards.EMPTY, List.of()));
        assertNoLabelOverruns(figures);
    }

    private static void assertNoLabelOverruns(List<ChartFigure> figures) {
        java.util.regex.Pattern box = java.util.regex.Pattern.compile("viewBox=\"0 0 (\\d+) ");
        java.util.regex.Pattern txt = java.util.regex.Pattern.compile(
                "<text x=\"(-?[0-9.]+)\"[^>]*text-anchor=\"(\\w+)\"[^>]*font-size=\"(\\d+)\""
                        + "[^>]*>([^<]*)</text>");
        List<String> overruns = new java.util.ArrayList<>();
        for (ChartFigure f : figures) {
            var mb = box.matcher(f.svg());
            assertTrue(mb.find(), f.title());
            double w = Double.parseDouble(mb.group(1));
            var mt = txt.matcher(f.svg());
            while (mt.find()) {
                double x = Double.parseDouble(mt.group(1));
                int size = Integer.parseInt(mt.group(3));
                String content = mt.group(4);
                double tw = content.length() * size * 0.58;
                double left = switch (mt.group(2)) {
                    case "end" -> x - tw;
                    case "middle" -> x - tw / 2;
                    default -> x;
                };
                if (left < -2 || left + tw > w + 2) {
                    overruns.add(f.title() + ": \"" + content + "\" ["
                            + Math.round(left) + ".." + Math.round(left + tw)
                            + "] in 0.." + (int) w);
                }
            }
        }
        assertTrue(overruns.isEmpty(), "labels running off the canvas:\n"
                + String.join("\n", overruns));
    }

    /** Every figure the fixtures can produce, wave A through G. */
    private static List<ChartFigure> allFigures() {
        return new DeepDiveCharts("de").build(new DeepDiveCharts.ChartInput(
                snapshot(), deepDive(), analystView(), shorts(), insider(), venueStats(),
                usStats(), actions(), hedgeFunds(), pressTimeline(), worldSignals(),
                volumeProfile(), orderBook(), memoryEvents(),
                List.of(new de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient
                                .YearPerformance(2023, 60, 120, 60, 100.0),
                        new de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient
                                .YearPerformance(2024, 120, 90, -30, -25.0)),
                List.of(new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                                .ConsensusTrend("Heute", 12, 4, 1),
                        new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                                .ConsensusTrend("vor 12 Mon.", 5, 7, 5)),
                List.of(new HeatmapService.Node("s1", "Rüstung und Verteidigung", null, null,
                                1, 3.4, null, null),
                        new HeatmapService.Node("s2", "Banken", null, null, 1, -1.2, null, null),
                        new HeatmapService.Node("s3", "Chemie", null, null, 1, 0.6, null, null)),
                "Rüstung und Verteidigung", "DAX",
                List.of(new de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter(
                                "RHM", 2025, 3, null, 4.10, 4.62, null, null, null, null, null,
                                null, null, false),
                        new de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter(
                                "RHM", 2025, 4, null, 5.00, 4.55, null, null, null, null, null,
                                null, null, false)),
                closes("EXV1.DE", List.of(180.0, 184.9, 188.0)), "EXV1.DE",
                "Rüstung und Luftfahrt Europa",
                attentionFixture(), docketFixture(),
                new DeepDiveCharts.Regime(
                        new de.bsommerfeld.wsbg.terminal.feargreed.FearGreedIndex(
                                72, "Greed", 70, 55.0, 41.0, null, java.time.Instant.EPOCH,
                                List.of(), List.of()),
                        new de.bsommerfeld.wsbg.terminal.briefing.CboePutCallClient.PutCallRatios(
                                "2026-08-03", 0.87, 0.62, 1.21, 14.52, 5600),
                        new de.bsommerfeld.wsbg.terminal.briefing.BundYieldClient.YieldPoint(
                                "2026-08-03", 2.54, 2.49),
                        1.0842, 97.31),
                DeepDiveCharts.Registers.EMPTY, DeepDiveCharts.Boards.EMPTY, List.of()));
    }

    private static List<de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint>
            attentionFixture() {
        List<de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint> out =
                new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            out.add(new de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint(
                    java.time.LocalDate.of(2026, 7, 5).plusDays(i), i == 7 ? 91_000 : 1200));
        }
        return out;
    }

    private static DeepDiveCharts.Docket docketFixture() {
        java.time.LocalDate t = java.time.LocalDate.now();
        return new DeepDiveCharts.Docket(
                List.of(new de.bsommerfeld.wsbg.terminal.briefing.EqsEventsClient.CorporateEvent(
                        t.plusDays(12).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(),
                        "DE0007030009", "Rheinmetall", "Q3")),
                List.of(),
                List.of(new de.bsommerfeld.wsbg.terminal.briefing.EconCalendarClient.EconEvent(
                        "CPI", "DE", t.plusDays(4).atStartOfDay(
                                java.time.ZoneId.systemDefault()).toEpochSecond(),
                        "high", null, null)),
                List.of(new de.bsommerfeld.wsbg.terminal.briefing.CentralBankCalendarClient
                        .CbMeeting("EZB", "Zinsentscheid", t.plusDays(30))),
                List.of(), List.of());
    }

    // ---- Wave H: the registry legs that were collected but never drawn ----

    private static DeepDiveCharts.ChartInput waveH(DeepDiveCharts.Registers registers,
            DeepDiveCharts.Boards boards, List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem>
                    pressHistory, MarketSnapshot subject) {
        return new DeepDiveCharts.ChartInput(subject, null, null, null, null, null, null, null,
                null, null, List.of(), null, null, null, List.of(), List.of(), List.of(),
                null, null, List.of(), null, null, null, List.of(),
                DeepDiveCharts.Docket.EMPTY, DeepDiveCharts.Regime.EMPTY,
                registers, boards, pressHistory);
    }

    @Test
    void eightFiscalYearsShowTheCycleTheShortSeriesCannot() {
        java.util.List<de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.FundamentalYear>
                years = new java.util.ArrayList<>();
        double[][] rows = {{2018, 6100, 300}, {2019, 6250, 420}, {2020, 5900, -120},
                {2021, 6800, 510}, {2022, 7200, 580}, {2023, 7176, 586}, {2024, 9751, 936}};
        for (double[] r : rows) {
            years.add(new de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient
                    .FundamentalYear((int) r[0], java.util.Map.of(
                            "Umsatz", r[1], "Jahresüberschuss", r[2])));
        }
        var regs = new DeepDiveCharts.Registers(null, null, null, years, List.of(), List.of(),
                null, List.of(), List.of(), List.of(), List.of());
        List<ChartFigure> figures = charts.build(waveH(regs, DeepDiveCharts.Boards.EMPTY,
                List.of(), null));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(3, f.section());
        assertEquals("boerse.de", f.note());
        assertTrue(f.svg().contains("2018") && f.svg().contains("2024"), "the whole cycle");
        // Two years is a trend, not a cycle.
        var few = new DeepDiveCharts.Registers(null, null, null, years.subList(0, 2), List.of(),
                List.of(), null, List.of(), List.of(), List.of(), List.of());
        assertTrue(charts.build(waveH(few, DeepDiveCharts.Boards.EMPTY, List.of(), null))
                .isEmpty());
    }

    @Test
    void ownershipPutsTheFreeFloatBesideItsAnchors() {
        var company = new de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient
                .CompanySnapshot("Rheinmetall AG", "Rheinmetall AG", "AG", "DE", "Rüstung",
                "Industrie", "https://x.invalid", "Portrait", 46_655_696L, 4.6e10, "EUR",
                62.5, "31.12.", 2024,
                List.of(new de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient
                                .Shareholder("BlackRock Inc.", 7.4),
                        new de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient
                                .Shareholder("Wellington", 5.1)),
                List.of(), List.of(), List.of(), List.of());
        var regs = new DeepDiveCharts.Registers(company, null, null, List.of(), List.of(),
                List.of(), null, List.of(), List.of(), List.of(), List.of());
        List<ChartFigure> figures = charts.build(waveH(regs, DeepDiveCharts.Boards.EMPTY,
                List.of(), null));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(4, f.section());
        assertTrue(f.title().contains("62,5"), "the free float belongs in the title");
        assertTrue(f.svg().contains("BlackRock Inc."), "anchors are named");
        assertTrue(f.svg().contains("Streubesitz"), "and so is the float");
    }

    @Test
    void peerUpsideSortsTheFieldAndMarksOurOwn() {
        var peers = List.of(
                new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                        .PeerConsensus("Hensoldt", 8, 3, 1, 55.0, "EUR", 12.5),
                new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                        .PeerConsensus("RHM", 19, 3, 5, 1720.0, "EUR", 73.4),
                new de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient
                        .PeerConsensus("KSB Vz", 2, 4, 2, 500.0, "EUR", -4.2));
        var regs = new DeepDiveCharts.Registers(null, null, null, List.of(), List.of(),
                List.of(), null, List.of(), peers, List.of(), List.of());
        // The snapshot rides so the figure can recognise our own row - it also
        // draws the price line, so pick the peer figure out by name.
        ChartFigure f = charts.build(waveH(regs, DeepDiveCharts.Boards.EMPTY, List.of(),
                        snapshot())).stream()
                .filter(x -> x.title().contains("Kurspotenzial"))
                .findFirst().orElseThrow();
        assertEquals(4, f.section());
        assertTrue(f.svg().contains("+73,4 %") && f.svg().contains("−4,2 %"),
                "both directions keep their sign");
        assertTrue(f.svg().contains("--ddc-sun"), "our own name is marked");
    }

    @Test
    void theBoardsCarryBothDirectionsSorted() {
        var boards = new DeepDiveCharts.Boards(
                List.of(new de.bsommerfeld.wsbg.terminal.briefing.TradingEconomicsClient.Quote(
                                "CL", "Rohöl WTI", "USD/bbl", 78.2, -1.4, -1.76, "2026-08-03"),
                        new de.bsommerfeld.wsbg.terminal.briefing.TradingEconomicsClient.Quote(
                                "HG", "Kupfer", "USD/lb", 4.1, 0.09, 2.24, "2026-08-03")),
                List.of(new de.bsommerfeld.wsbg.terminal.briefing.TradingEconomicsClient.Quote(
                        "DE10Y", "Bund 10J", "%", 2.54, 0.05, 2.01, "2026-08-03")));
        List<ChartFigure> figures = charts.build(waveH(DeepDiveCharts.Registers.EMPTY, boards,
                List.of(), null));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(2, f.section());
        assertTrue(f.svg().contains("Kupfer") && f.svg().contains("Rohöl WTI"));
        assertTrue(f.svg().contains("−1,8 %"), "a falling board row keeps its sign");
    }

    @Test
    void theArchiveBecomesAShapeOnlyPerYear() {
        java.util.List<de.bsommerfeld.wsbg.terminal.source.RawNewsItem> history =
                new java.util.ArrayList<>();
        int[][] perYear = {{2019, 2}, {2020, 5}, {2021, 1}, {2022, 4}};
        for (int[] y : perYear) {
            for (int i = 0; i < y[1]; i++) {
                history.add(new de.bsommerfeld.wsbg.terminal.source.RawNewsItem(
                        "u" + y[0] + i, "Titel", "Publisher", "https://x.invalid/" + y[0] + i,
                        java.time.LocalDate.of(y[0], 6, 1).atStartOfDay(
                                java.time.ZoneId.systemDefault()).toInstant(),
                        List.of(), null, null, false, null));
            }
        }
        List<ChartFigure> figures = charts.build(waveH(DeepDiveCharts.Registers.EMPTY,
                DeepDiveCharts.Boards.EMPTY, history, null));
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(2, f.section());
        assertTrue(f.svg().contains("2019") && f.svg().contains("2022"), "the whole span");
        assertTrue(f.title().contains("12"), "the entry count rides in the title");
    }

    // ---- Wave E: the market-memory figures ----

    @Test
    void volumeProfileLadderCarriesPocValueAreaAndPrice() {
        List<ChartFigure> figures = charts.build(snapshot(), null, null, null, null, null,
                null, null, null, null, List.of(), volumeProfile(), null, null);
        assertEquals(2, figures.size()); // price figure + the profile ladder
        ChartFigure f = figures.stream()
                .filter(x -> x.title().contains("Volumenprofil")).findFirst().orElseThrow();
        assertEquals(2, f.section());
        assertTrue(f.note().contains("~6 Monate"), "horizon in the note: " + f.note());
        assertTrue(f.note().contains("Stundenkerzen"), "house-computed source note");
        assertTrue(f.svg().contains("POC"), "POC rung");
        assertTrue(f.svg().contains("952,50"), "POC value");
        assertTrue(f.svg().contains("1.001,00"), "VAH value");
        assertTrue(f.svg().contains("918,00"), "VAL value");
        assertTrue(f.svg().contains("42.500"), "POC volume justification");
        assertTrue(f.svg().contains("13,7 %"), "POC share of total volume");
        assertTrue(f.svg().contains("310.000"), "total traded units");
        assertTrue(f.svg().contains("992,10"), "live price mark");
        assertTrue(f.svg().contains("opacity=\"0.08\""), "value-area wash");
    }

    @Test
    void orderBookLadderShowsBothSidesWithOrderCounts() {
        List<ChartFigure> figures = charts.build(null, null, null, null, null, null,
                null, null, null, null, List.of(), null, orderBook(), null);
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(2, f.section());
        assertTrue(f.title().contains("3×2"), "level counts in the title: " + f.title());
        assertEquals("Börse Frankfurt · 15.07.26 14:32:11", f.note());
        assertTrue(f.svg().contains("GELD"), "bid side header");
        assertTrue(f.svg().contains("BRIEF"), "ask side header");
        assertTrue(f.svg().contains("991,80"), "best bid price");
        assertTrue(f.svg().contains("992,40"), "best ask price");
        assertTrue(f.svg().contains("3 Ord."), "order count where published");
        assertTrue(f.svg().contains("1.200"), "resting units");
        assertFalse(f.svg().contains("0 Ord."), "unpublished order counts stay silent");
        assertTrue(f.svg().contains("var(--ddc-pos"), "bid bars ride the gain tone");
        assertTrue(f.svg().contains("var(--ddc-neg"), "ask bars ride the loss tone");
    }

    @Test
    void eventHistoryColorsMarksBySignOfMeasuredReaction() {
        List<ChartFigure> figures = charts.build(null, null, null, null, null, null,
                null, null, null, null, List.of(), null, null, memoryEvents());
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(5, f.section());
        assertEquals("eigenes Ereignis-Register", f.note());
        assertTrue(f.title().contains("3 Ereignisse"), f.title());
        assertTrue(f.svg().contains("EARNINGS_BEAT"), "class token");
        assertTrue(f.svg().contains("GUIDANCE_CUT"), "class token");
        assertTrue(f.svg().contains("ANALYST_ACTION"), "unmeasured event keeps its mark");
        assertTrue(f.svg().contains("+3,4 %"), "measured positive reaction");
        assertTrue(f.svg().contains("−6,8 %"), "measured negative reaction");
        assertTrue(f.svg().contains("var(--ddc-pos"), "positive reaction tone");
        assertTrue(f.svg().contains("var(--ddc-neg"), "negative reaction tone");
        assertTrue(f.svg().contains("var(--ddc-mute"), "unmeasured event stays muted");
        assertTrue(f.svg().contains("12.03.2026"), "range start date");
        assertTrue(f.svg().contains("01.07.2026"), "range end date");
    }

    @Test
    void worldSignalStripDrawsAGlyphPerLine() {
        List<ChartFigure> figures = charts.build(null, null, null, null, null, null,
                null, null, null, null, worldSignals(), null, null, null);
        assertEquals(1, figures.size());
        ChartFigure f = figures.get(0);
        assertEquals(2, f.section());
        assertTrue(f.svg().contains("Maritime chokepoint Suez Canal"), "line as caption");
        assertTrue(f.svg().contains("US petroleum stocks"), "line as caption");
        assertTrue(f.svg().contains("var(--ddc-neg"), "hazard triangle rides the warning tone");
        assertTrue(f.svg().contains("<path"), "drawn glyph primitives, not text icons");
    }
}
