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
            assertTrue(f.svg().startsWith("<svg viewBox=\"0 0 560 "), f.title());
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
        assertTrue(f.svg().contains("Mai"), "German month label");
        assertTrue(f.svg().contains("opacity=\"0.55\""), "half-opaque ticks darken on pileup");
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
