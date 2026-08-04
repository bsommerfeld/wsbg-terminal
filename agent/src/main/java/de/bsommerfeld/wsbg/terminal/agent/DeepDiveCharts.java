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
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

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

    /**
     * The figure canvas width, and it is the DOCUMENT COLUMN's width (see
     * {@code --dd-column} in deepdive.css and DOC_CSS in DeepDivePdfExporter).
     * Text and figures share one column, so a figure renders at a scale of
     * about 1.0 instead of being stretched or squeezed — which is the only way
     * the type inside a figure keeps the size it was designed at. Every figure
     * derives its geometry from this constant; nothing places a mark at a
     * hardcoded far-right x.
     */
    private static final int W = 720;

    /**
     * The smallest type any figure may set. Font sizes are viewBox units, so
     * they scale with the rendered width — an 8-unit label sat at ~5 CSS px in
     * the live workshop's narrow pane (user finding 2026-08-03). Ten is the
     * floor, 11 is body, 12+ is emphasis; nothing goes below.
     */
    private static final int MIN_FONT = 10;

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

    /**
     * Everything the figure layer may draw from, in ONE value. The material
     * feeds ~90 fields and the figure layer keeps growing into them; a
     * positional parameter list would be at twenty-odd arguments by now and
     * every new figure would touch every call site.
     *
     * @param calendarYears calendar-year performance, the long arc (onvista)
     * @param consensusTrend the consensus at three points in time (finanzen.net)
     * @param sectorBoard   the day's sector standings (house heatmap)
     * @param subjectSector the subject's own sector name inside that board
     * @param sectorUniverse which universe the board was computed over
     */
    record ChartInput(
            MarketSnapshot snapshot,
            CompanyDeepDive deepDive,
            AnalystView analystView,
            ShortInterest shortInterest,
            InsiderDealings insiderDealings,
            de.bsommerfeld.wsbg.terminal.core.price.VenueStats venueStats,
            UsListingStats usStats,
            AnalystActions analystActions,
            HedgeFundPopularity hedgeFunds,
            PressTimeline pressTimeline,
            List<String> worldSignalKeep,
            VolumeProfile.Profile volumeProfile,
            OrderBookSnapshot orderBook,
            List<MarketEventRecord> memoryEvents,
            List<de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient.YearPerformance>
                    calendarYears,
            List<de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.ConsensusTrend>
                    consensusTrend,
            List<HeatmapService.Node> sectorBoard,
            String subjectSector,
            String sectorUniverse,
            List<de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter> cnbcEarnings,
            MarketSnapshot sectorEtf,
            String sectorEtfSymbol,
            String sectorDisplayName,
            List<de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint> attentionCurve,
            Docket docket,
            Regime regime,
            Registers registers,
            Boards boards,
            List<RawNewsItem> pressHistory) {
    }

    /**
     * The instrument's own registry legs - the ones a German or European name
     * carries and a US listing usually does not, plus the US filing register.
     * They were collected for the prose long before anything drew them.
     */
    record Registers(
            de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.CompanySnapshot company,
            de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.BenchmarkFit benchmark,
            de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.AnalystConsensus consensus,
            List<de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.FundamentalYear>
                    fundamentalYears,
            List<de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.AnalystCall>
                    analystCalls,
            List<de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.InsiderDeal>
                    insiderDeals,
            de.bsommerfeld.wsbg.terminal.handelsblatt.HandelsblattInstrument hbInstrument,
            List<de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.EstimateRow>
                    estimates,
            List<de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.PeerConsensus>
                    peers,
            List<de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.VenueQuote>
                    venues,
            List<de.bsommerfeld.wsbg.terminal.edgar.EdgarClient.EdgarEvent> edgarEvents) {

        static final Registers EMPTY = new Registers(null, null, null, List.of(), List.of(),
                List.of(), null, List.of(), List.of(), List.of(), List.of());
    }

    /** The day's movers on the commodity and bond boards - the input-price side. */
    record Boards(
            List<de.bsommerfeld.wsbg.terminal.briefing.TradingEconomicsClient.Quote> commodities,
            List<de.bsommerfeld.wsbg.terminal.briefing.TradingEconomicsClient.Quote> bonds) {

        static final Boards EMPTY = new Boards(List.of(), List.of());
    }

    /**
     * The dated forward calendar, from the ten legs that each hold a piece of
     * it. Any one of them is patchy; together they are the report's answer to
     * "when does it get busy?".
     */
    record Docket(
            List<de.bsommerfeld.wsbg.terminal.briefing.EqsEventsClient.CorporateEvent> issuerEvents,
            List<de.bsommerfeld.wsbg.terminal.briefing.BorsaItalianaEventsClient.EuEvent> euEvents,
            List<de.bsommerfeld.wsbg.terminal.briefing.EconCalendarClient.EconEvent> macroDocket,
            List<de.bsommerfeld.wsbg.terminal.briefing.CentralBankCalendarClient.CbMeeting>
                    cbDecisions,
            List<de.bsommerfeld.wsbg.terminal.briefing.StatsReleaseCalendarClient.Release> statsDocket,
            List<de.bsommerfeld.wsbg.terminal.briefing.FederalRegisterClient.DatedAction> regDocket) {

        static final Docket EMPTY = new Docket(List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    /**
     * The weather the measurement was taken in. Not about the subject at all -
     * and that is the point: the same instrument reads differently in a
     * fearful tape than in a greedy one.
     */
    record Regime(
            de.bsommerfeld.wsbg.terminal.feargreed.FearGreedIndex fearGreed,
            de.bsommerfeld.wsbg.terminal.briefing.CboePutCallClient.PutCallRatios putCall,
            de.bsommerfeld.wsbg.terminal.briefing.BundYieldClient.YieldPoint bundYield,
            Double eurUsdRate,
            Double dollarIndex) {

        static final Regime EMPTY = new Regime(null, null, null, null, null);
    }

    /**
     * The pre-{@link ChartInput} shape, kept so existing call sites and the
     * figure tests read without ceremony. New legs go on the record.
     */
    List<ChartFigure> build(MarketSnapshot snapshot, CompanyDeepDive deepDive,
            AnalystView analystView, ShortInterest shortInterest,
            InsiderDealings insiderDealings,
            de.bsommerfeld.wsbg.terminal.core.price.VenueStats venueStats,
            UsListingStats usStats, AnalystActions analystActions,
            HedgeFundPopularity hedgeFunds, PressTimeline pressTimeline,
            List<String> worldSignalKeep, VolumeProfile.Profile volumeProfile,
            OrderBookSnapshot orderBook, List<MarketEventRecord> memoryEvents) {
        return build(new ChartInput(snapshot, deepDive, analystView, shortInterest,
                insiderDealings, venueStats, usStats, analystActions, hedgeFunds,
                pressTimeline, worldSignalKeep, volumeProfile, orderBook, memoryEvents,
                List.of(), List.of(), List.of(), null, null,
                List.of(), null, null, null, List.of(), Docket.EMPTY, Regime.EMPTY,
                Registers.EMPTY, Boards.EMPTY, List.of()));
    }

    /** Builds every figure the material supports, report-section-anchored. */
    List<ChartFigure> build(ChartInput in) {
        MarketSnapshot snapshot = in.snapshot();
        CompanyDeepDive deepDive = in.deepDive();
        AnalystView analystView = in.analystView();
        UsListingStats usStats = in.usStats();
        List<ChartFigure> out = new ArrayList<>();
        addIfPresent(out, factsFigure(snapshot, deepDive, analystView));
        if (deepDive != null) {
            addIfPresent(out, epsDividendFigure(deepDive.keyFigures()));
            addIfPresent(out, revenueProfitFigure(deepDive.balanceSheet()));
            addIfPresent(out, marginFigure(deepDive.keyFigures()));
            addIfPresent(out, cashflowFigure(deepDive.balanceSheet()));
        }
        addIfPresent(out, surpriseFigure(usStats));
        addIfPresent(out, cnbcDeliveryFigure(in.cnbcEarnings()));
        addIfPresent(out, analystFigure(analystView));
        addIfPresent(out, consensusArcFigure(in.consensusTrend()));
        addIfPresent(out, actionsFigure(in.analystActions()));
        addIfPresent(out, hedgeFundFigure(in.hedgeFunds()));
        addIfPresent(out, peerScatterFigure(deepDive, snapshot));
        addIfPresent(out, priceFigure(snapshot));
        addIfPresent(out, venueFigure(in.venueStats()));
        addIfPresent(out, volumeProfileFigure(in.volumeProfile(), snapshot));
        addIfPresent(out, orderBookFigure(in.orderBook(), snapshot));
        addIfPresent(out, pressTimelineFigure(in.pressTimeline()));
        addIfPresent(out, worldSignalsFigure(in.worldSignalKeep()));
        if (deepDive != null) {
            addIfPresent(out, performanceFigure(deepDive.performance(),
                    in.registers().hbInstrument()));
            addIfPresent(out, rangeFigure(deepDive.performance(), snapshot));
        }
        addIfPresent(out, calendarYearsFigure(in.calendarYears()));
        addIfPresent(out, boerseYearsFigure(in.registers().fundamentalYears()));
        addIfPresent(out, estimatePathFigure(in.registers().estimates()));
        addIfPresent(out, ownershipFigure(in.registers().company()));
        addIfPresent(out, benchmarkFigure(in.registers().benchmark()));
        addIfPresent(out, peerTargetsFigure(in.registers().peers(), snapshot));
        addIfPresent(out, venueVolumeFigure(in.registers().venues()));
        addIfPresent(out, deskCallsFigure(in.registers().analystCalls()));
        addIfPresent(out, deskInsiderFigure(in.registers().insiderDeals()));
        addIfPresent(out, edgarFigure(in.registers().edgarEvents()));
        addIfPresent(out, archiveYearsFigure(in.pressHistory()));
        addIfPresent(out, boardsFigure(in.boards()));
        addIfPresent(out, sectorRelativeFigure(snapshot, in.sectorEtf(),
                in.sectorEtfSymbol(), in.sectorDisplayName()));
        addIfPresent(out, sectorBoardFigure(in.sectorBoard(), in.subjectSector(),
                in.sectorUniverse()));
        addIfPresent(out, attentionFigure(in.attentionCurve()));
        addIfPresent(out, regimeFigure(in.regime()));
        addIfPresent(out, eventsFigure(analystView));
        addIfPresent(out, docketFigure(in.docket()));
        addIfPresent(out, streetBandFigure(usStats, analystView, snapshot,
                in.registers().consensus()));
        addIfPresent(out, insiderFigure(in.insiderDealings()));
        addIfPresent(out, shortFigure(in.shortInterest()));
        addIfPresent(out, usShortHistoryFigure(usStats));
        addIfPresent(out, memoryEventsFigure(in.memoryEvents()));
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
        int tileH = 66, padT = 10;
        int h = padT + rows * tileH;
        double colW = (double) W / cols;
        double inner = colW - 16; // the budget a tile's text may occupy
        StringBuilder svg = open(h);
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            double x = (i % cols) * colW + 4;
            double y = padT + (i / cols) * tileH;
            text(svg, x, y + 13, "start", 11, MUTE, fit(tile.label(), inner, 11), false);
            text(svg, x, y + 38, "start", fitSize(tile.value(), inner, 21), INK,
                    fit(tile.value(), inner, MIN_FONT), true);
            if (tile.sub() != null) {
                text(svg, x, y + 55, "start", 11, MUTE, fit(tile.sub(), inner, 11), false);
            }
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

        int rowH = 30, padT = 12, titleX = 136;
        int h = padT + upcoming.size() * rowH;
        StringBuilder svg = open(h);
        for (int i = 0; i < upcoming.size(); i++) {
            AnalystView.CorporateEvent e = upcoming.get(i);
            double y = padT + i * rowH + 16;
            boolean next = i == 0;
            if (next) { // amber-style tick like the report crossheads: the one to watch
                svg.append("<rect x=\"4\" y=\"").append(r1(y - 13)).append("\" width=\"3\" height=\"18\" rx=\"1.5\" fill=\"")
                        .append(S1).append("\"/>");
            }
            text(svg, 18, y, "start", 13, next ? INK : MUTE,
                    isoToDe(java.time.Instant.ofEpochSecond(e.atEpochSeconds())), next);
            text(svg, titleX, y, "start", 12, next ? INK : MUTE,
                    fit(e.title() != null ? e.title() : (e.type() == null ? "" : e.type()),
                            W - titleX - 12, 12), false);
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
        int rowH = 30, padT = 12;
        int h = padT + rows.size() * rowH;
        // Title left, value right — each gets a fixed share of the row so the
        // two can never meet in the middle.
        double titleW = (W - 28) * 0.52, valueW = (W - 28) * 0.44;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            var s = rows.get(i);
            double y = padT + i * rowH + 16;
            svg.append("<rect x=\"4\" y=\"").append(r1(y - 13))
                    .append("\" width=\"3\" height=\"18\" rx=\"1.5\" fill=\"")
                    .append(S1).append("\"/>");
            text(svg, 18, y, "start", 12, INK, fit(s.title(), titleW, 12), false);
            text(svg, W - 10, y, "end", 12, MUTE, fit(s.formattedValue(), valueW, 12), false);
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

        int h = 210, padL = 14, padR = 104, padT = 16, padB = 16;
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
        int barH = 24, rowGap = 18, labelW = 108, padT = 12, legendH = 30;
        int h = padT + rowCount * (barH + rowGap) + legendH;
        int plotW = W - labelW - 80;
        int total = java.util.Arrays.stream(now).sum();
        int totalAgo = has3m ? java.util.Arrays.stream(ago).sum() : 0;
        int scaleMax = Math.max(total, totalAgo);
        if (scaleMax <= 0) return null;

        StringBuilder svg = open(h);
        String[] rowLabels = {de ? "Heute" : "Now", de ? "vor 3 Mon." : "3 mo ago"};
        int[][] rows = has3m ? new int[][]{now, ago} : new int[][]{now};
        for (int r = 0; r < rows.length; r++) {
            double y = padT + r * (barH + rowGap);
            text(svg, labelW - 10, y + barH / 2.0 + 4, "end", 12, MUTE, rowLabels[r], false);
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
                if (w >= 20) { // count fits inside the segment
                    String inkIn = alpha[t] >= 1 ? SURFACE : INK;
                    text(svg, x + w / 2, y + barH / 2.0 + 4, "middle", 11, inkIn,
                            String.valueOf(v), false);
                }
                x += w + 2;
            }
        }
        // Legend: five tiers, swatch + text token.
        double lx = labelW;
        double ly = padT + rowCount * (barH + rowGap) + 16;
        for (int t = 0; t < 5; t++) {
            svg.append("<rect x=\"").append(r1(lx)).append("\" y=\"").append(r1(ly - 9))
                    .append("\" width=\"10\" height=\"10\" rx=\"2\" fill=\"").append(fills[t])
                    .append("\" opacity=\"").append(alpha[t]).append("\"/>");
            text(svg, lx + 15, ly, "start", 11, MUTE, tierNames[t], false);
            lx += 15 + textW(tierNames[t], 11) + 18;
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

        int barH = 22, rowGap = 16, labelW = 112, padT = 12;
        int h = padT + 2 * (barH + rowGap);
        int plotW = W - labelW - 230;
        StringBuilder svg = open(h);
        String[] names = {de ? "Käufe" : "Buys", de ? "Verkäufe" : "Sells"};
        double[] vols = {buyVol, sellVol};
        int[] counts = {buys, sells};
        String[] tones = {POS, NEG};
        for (int i = 0; i < 2; i++) {
            double y = padT + i * (barH + rowGap);
            text(svg, labelW - 10, y + barH / 2.0 + 4, "end", 12, MUTE, names[i], false);
            double w = Math.max(vols[i] / maxVol * plotW, counts[i] > 0 ? 2 : 0);
            if (w > 0) svg.append(roundedRightBar(labelW, y, w, barH, tones[i]));
            String unit = de ? (counts[i] == 1 ? " Meldung · " : " Meldungen · ")
                    : (counts[i] == 1 ? " filing · " : " filings · ");
            text(svg, labelW + w + 10, y + barH / 2.0 + 4, "start", 12, INK,
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

        int barH = 20, rowGap = 14, labelW = 300, padT = 10;
        int h = padT + rows.size() * (barH + rowGap);
        int plotW = W - labelW - 100;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            ShortInterest.ShortPosition p = rows.get(i);
            double y = padT + i * (barH + rowGap);
            text(svg, labelW - 10, y + barH / 2.0 + 4, "end", 12, MUTE,
                    fit(p.holder(), labelW - 14, 12), false);
            double w = Math.max(p.percent() / max * plotW, 2);
            svg.append(roundedRightBar(labelW, y, w, barH, S1));
            text(svg, labelW + w + 10, y + barH / 2.0 + 4, "start", 12, INK,
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
        int h = 250, padT = 16, padB = 16, xL = 78, xR = W - 96;
        int plotH = h - padT - padB;

        // Collision pass: labels sorted by value descending, kept ≥ 15px apart
        // and re-seated inside the plot when the pushing overran the floor.
        List<Level> sorted = new ArrayList<>(levels);
        sorted.sort((a, b) -> Double.compare(b.value(), a.value()));
        double[] raw = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            raw[i] = padT + (1 - (sorted.get(i).value() - min) / span) * plotH;
        }
        double[] ys = ladderYs(raw, 15, padT, padT + plotH);

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

    private ChartFigure performanceFigure(CompanyDeepDive.PerformanceStats p,
            de.bsommerfeld.wsbg.terminal.handelsblatt.HandelsblattInstrument hb) {
        if (p == null) return null;
        // The Consorsbank ladder stops at one year. Handelsblatt carries the
        // year-to-date and the FIVE-year move for the same ISIN - two rungs
        // that change what the ladder is even about.
        Double ytd = hb == null ? null : hb.yearToDatePercent();
        Double y5 = hb == null ? null : hb.fiveYearPercent();
        String[] names = {"1W", "4W", "3M", "6M", "52W", de ? "YTD" : "YTD", "5J"};
        if (!de) names[6] = "5Y";
        double[] vals = {p.perf1w(), p.perf4w(), p.perf3m(), p.perf6m(), p.perf52w(),
                ytd == null ? Double.NaN : ytd, y5 == null ? Double.NaN : y5};
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < vals.length; i++) {
            if (Double.isFinite(vals[i])) idx.add(i);
        }
        if (idx.size() < 2) return null;

        int h = 210, padT = 28, padB = 32, padL = 20, padR = 20;
        int plotH = h - padT - padB;
        // Zero baseline placed proportionally to the data's sign balance.
        double posMax = idx.stream().mapToDouble(i -> Math.max(0, vals[i])).max().orElse(0);
        double negMax = idx.stream().mapToDouble(i -> Math.max(0, -vals[i])).max().orElse(0);
        double totalSpan = posMax + negMax == 0 ? 1 : posMax + negMax;
        double baseY = padT + posMax / totalSpan * plotH;

        int n = idx.size();
        double slot = (double) (W - padL - padR) / n;
        double barW = clamp(slot * 0.42, 12, 64);

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

    // ---- 30. delivery record: estimate vs actual per quarter — Fundamentaldaten ----

    /**
     * The question the outlook hangs on: does this company deliver what it
     * promised? Consensus and reported EPS side by side per quarter, eight
     * quarters deep. The NASDAQ surprise strip answers the same question in
     * percent and four quarters; this one shows the two NUMBERS themselves.
     */
    private ChartFigure cnbcDeliveryFigure(
            List<de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter> quarters) {
        if (quarters == null || quarters.isEmpty()) return null;
        var rows = quarters.stream()
                .filter(q -> q.epsEstimate() != null || q.epsActualAdjusted() != null)
                .sorted(java.util.Comparator
                        .<de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient.EarningsQuarter>
                                comparingInt(q -> q.fiscalYear())
                        .thenComparingInt(q -> q.quarter()))
                .toList();
        if (rows.size() < 2) return null;
        if (rows.size() > 8) rows = rows.subList(rows.size() - 8, rows.size());

        String legend = de ? "Konsens|Berichtet" : "Consensus|Reported";
        StringBuilder svg = groupedColumns(rows.stream().map(q -> new Group(
                        "Q" + q.quarter() + "/" + (q.fiscalYear() % 100),
                        new double[]{q.epsEstimate() == null ? Double.NaN : q.epsEstimate(),
                                q.epsActualAdjusted() == null ? Double.NaN : q.epsActualAdjusted()},
                        // A quarter with no reported figure yet is still ahead.
                        q.epsActualAdjusted() == null)).toList(),
                new String[]{MUTE, S1}, legend.split("\\|"), 2);
        String title = de ? "Lieferbilanz je Quartal (Gewinn je Aktie: Konsens gegen Berichtet)"
                : "Delivery record by quarter (EPS: consensus vs reported)";
        return svg == null ? null : new ChartFigure(SEC_FUNDAMENTALS, title, "CNBC", svg.toString());
    }

    // ---- 31. relative strength against the sector proxy — Lage ----

    /**
     * A name's move means little without the field it moved in: the subject
     * and its sector proxy over the same window, BOTH indexed to 100 at the
     * start so one scale carries both honestly. The gap at the right edge is
     * the whole reading.
     */
    private ChartFigure sectorRelativeFigure(MarketSnapshot subject, MarketSnapshot sector,
            String sectorSymbol, String sectorName) {
        if (subject == null || sector == null) return null;
        List<Double> a = finiteCloses(subject);
        List<Double> b = finiteCloses(sector);
        if (a.size() < 3 || b.size() < 3) return null;
        // Same window for both, counted back from the newest close.
        int n = Math.min(a.size(), b.size());
        a = a.subList(a.size() - n, a.size());
        b = b.subList(b.size() - n, b.size());
        double a0 = a.get(0), b0 = b.get(0);
        if (!(a0 > 0) || !(b0 > 0)) return null;

        int h = 210, padL = 16, padR = 118, padT = 30, padB = 22;
        int plotW = W - padL - padR, plotH = h - padT - padB;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            for (double v : new double[]{a.get(i) / a0 * 100, b.get(i) / b0 * 100}) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        double span = max - min == 0 ? 1 : max - min;

        StringBuilder svg = open(h);
        for (int g = 0; g <= 2; g++) {
            double gy = padT + g * plotH / 2.0;
            line(svg, padL, gy, padL + plotW, gy, GRID, 1);
        }
        String[] tones = {S1, MUTE};
        List<List<Double>> series = List.of(a, b);
        double[] bases = {a0, b0};
        for (int s = 0; s < 2; s++) {
            StringBuilder pts = new StringBuilder();
            for (int i = 0; i < n; i++) {
                double x = padL + (double) i / (n - 1) * plotW;
                double y = padT + (1 - (series.get(s).get(i) / bases[s] * 100 - min) / span) * plotH;
                if (i > 0) pts.append(' ');
                pts.append(r1(x)).append(',').append(r1(y));
            }
            svg.append("<polyline points=\"").append(pts).append("\" fill=\"none\" stroke=\"")
                    .append(tones[s]).append("\" stroke-width=\"2\" stroke-linejoin=\"round\" ")
                    .append("stroke-linecap=\"round\"")
                    .append(s == 1 ? " stroke-dasharray=\"5 4\"" : "").append("/>");
        }
        // End labels: the indexed level AND the move it stands for, each on its
        // own line, nudged apart when the two series finished close together.
        double endA = a.get(n - 1) / a0 * 100, endB = b.get(n - 1) / b0 * 100;
        double yA = padT + (1 - (endA - min) / span) * plotH;
        double yB = padT + (1 - (endB - min) / span) * plotH;
        if (Math.abs(yA - yB) < 16) {
            if (yA <= yB) yB = yA + 16; else yA = yB + 16;
        }
        text(svg, padL + plotW + 10, clamp(yA + 4, padT + 6, h - 6), "start", 12, INK,
                pct(endA - 100), true);
        text(svg, padL + plotW + 10, clamp(yB + 4, padT + 6, h - 6), "start", 11, MUTE,
                pct(endB - 100), false);
        // Legend: which line is whom.
        double lx = padL;
        String subjectName = subject.symbol() != null ? subject.symbol()
                : (de ? "Subjekt" : "subject");
        String proxy = sectorName != null && !sectorName.isBlank() ? sectorName
                : sectorSymbol != null ? sectorSymbol : (de ? "Sektor" : "sector");
        String[] names = {subjectName, proxy};
        for (int s = 0; s < 2; s++) {
            svg.append("<rect x=\"").append(r1(lx)).append("\" y=\"5\" width=\"10\" height=\"10\" ")
                    .append("rx=\"2\" fill=\"").append(tones[s]).append("\"/>");
            text(svg, lx + 15, 14, "start", 11, MUTE, fit(names[s], 240, 11), false);
            lx += 15 + textW(fit(names[s], 240, 11), 11) + 22;
        }
        svg.append("</svg>");
        String title = de
                ? "Relative Stärke gegen den Sektor (" + n + " Handelstage, indexiert auf 100)"
                : "Relative strength vs the sector (" + n + " trading days, indexed to 100)";
        String note = sectorSymbol == null ? "Yahoo" : "Yahoo · " + sectorSymbol;
        return new ChartFigure(SEC_SITUATION, title, note, svg.toString());
    }

    /** A snapshot's usable close series: daily closes, else the spark. */
    private static List<Double> finiteCloses(MarketSnapshot s) {
        List<Double> src = s.dailyCloses().size() >= 3 ? s.dailyCloses() : s.spark();
        return src.stream().filter(v -> v != null && Double.isFinite(v) && v > 0).toList();
    }

    // ---- 32. the attention curve (Wikipedia pageviews) — Lage ----

    /**
     * The only daily series in the report that is not a price: how many people
     * looked the company up, day by day. The mean of the window rides as a
     * reference line, so a spike is visibly a spike and not just a wiggle.
     */
    private ChartFigure attentionFigure(
            List<de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint> curve) {
        if (curve == null || curve.size() < 5) return null;
        var rows = curve.stream()
                .filter(p -> p.day() != null && p.views() >= 0)
                .sorted(java.util.Comparator.comparing(p -> p.day()))
                .toList();
        if (rows.size() < 5) return null;

        int h = 200, padL = 16, padR = 96, padT = 20, padB = 26;
        int plotW = W - padL - padR, plotH = h - padT - padB;
        double max = rows.stream().mapToDouble(p -> p.views()).max().orElse(1);
        double mean = rows.stream().mapToDouble(p -> p.views()).average().orElse(0);
        if (max <= 0) return null;
        int n = rows.size();

        StringBuilder svg = open(h);
        // Bars, not a line: pageviews are counts per day, and a count has a
        // baseline of zero — a line would invite reading a trend into noise.
        double slot = (double) plotW / n;
        double barW = Math.max(slot - 2, 1);
        for (int i = 0; i < n; i++) {
            double v = rows.get(i).views();
            double len = v / max * plotH;
            svg.append(roundedTopBar(padL + slot * i + 1, padT + plotH - len, barW, len, S1));
        }
        line(svg, padL, padT + plotH, padL + plotW, padT + plotH, AXIS, 1);
        double meanY = padT + plotH - mean / max * plotH;
        line(svg, padL, meanY, padL + plotW, meanY, AXIS, 1);
        // Pageviews are COUNTS: full grouped digits below a million, so the
        // peak day reads as "9.000", not as a rounded "9 Tsd.".
        text(svg, padL + plotW + 10, meanY + 4, "start", 11, MUTE,
                (de ? "Ø " : "avg ") + units(Math.round(mean)), false);
        // The peak day, named — that is the day worth asking about.
        int peak = 0;
        for (int i = 1; i < n; i++) {
            if (rows.get(i).views() > rows.get(peak).views()) peak = i;
        }
        double peakX = padL + slot * peak + barW / 2 + 1;
        text(svg, clamp(peakX, padL + 40, padL + plotW - 40), padT - 6, "middle", 11, INK,
                units(rows.get(peak).views()) + " · " + isoDate(rows.get(peak).day().toString()),
                true);
        text(svg, padL, h - 6, "start", 11, MUTE, isoDate(rows.get(0).day().toString()), false);
        text(svg, padL + plotW, h - 6, "end", 11, MUTE,
                isoDate(rows.get(n - 1).day().toString()), false);
        svg.append("</svg>");
        String title = de
                ? "Aufmerksamkeit je Tag (Wikipedia-Aufrufe, " + n + " Tage)"
                : "Attention per day (Wikipedia pageviews, " + n + " days)";
        return new ChartFigure(SEC_SITUATION, title, "Wikimedia", svg.toString());
    }

    // ---- 33. the market regime the measurement was taken in — Lage ----

    /**
     * The weather around the reading: the Fear-and-Greed composite as a 0-100
     * meter carrying today, a week ago and a month ago, plus the anchors a
     * valuation actually hangs on — option positioning, the risk-free rate and
     * the dollar. Not about the subject, and that is exactly the point.
     */
    private ChartFigure regimeFigure(Regime rg) {
        if (rg == null) return null;
        var fg = rg.fearGreed();
        record Tile(String label, String value, String sub) {}
        List<Tile> tiles = new ArrayList<>();
        if (rg.putCall() != null) {
            var pc = rg.putCall();
            if (Double.isFinite(pc.total()) && pc.total() > 0) {
                tiles.add(new Tile(de ? "PUT/CALL" : "PUT/CALL", fmt(pc.total(), 2),
                        Double.isFinite(pc.equity()) && pc.equity() > 0
                                ? (de ? "Aktien " : "equity ") + fmt(pc.equity(), 2) : null));
            }
            if (Double.isFinite(pc.vix()) && pc.vix() > 0) {
                tiles.add(new Tile("VIX", fmt(pc.vix(), 2), null));
            }
        }
        if (rg.bundYield() != null && Double.isFinite(rg.bundYield().percent())) {
            Double prev = rg.bundYield().previousPercent();
            // The previous LEVEL, not a delta: a yield moving 2,49 → 2,54
            // moved five basis points, not "+0,05 %", and pct() would have
            // printed exactly that unit confusion into a finance document.
            tiles.add(new Tile(de ? "BUND 10J" : "BUND 10Y",
                    fmt(rg.bundYield().percent(), 2) + " %",
                    prev != null && Double.isFinite(prev)
                            ? (de ? "Vortag " : "prev ") + fmt(prev, 2) + " %" : null));
        }
        if (rg.eurUsdRate() != null && Double.isFinite(rg.eurUsdRate())) {
            tiles.add(new Tile("EUR/USD", fmt(rg.eurUsdRate(), 4), null));
        }
        if (rg.dollarIndex() != null && Double.isFinite(rg.dollarIndex())) {
            tiles.add(new Tile(de ? "DOLLAR-INDEX" : "DOLLAR INDEX",
                    fmt(rg.dollarIndex(), 2), null));
        }
        boolean meter = fg != null && Double.isFinite(fg.score());
        if (!meter && tiles.size() < 2) return null;

        int meterH = meter ? 96 : 0;
        // Five anchors fit one row at this width — a lone tile on a second row
        // reads like something went missing.
        int cols = Math.max(1, Math.min(tiles.size(), 5));
        int rowsN = tiles.isEmpty() ? 0 : (tiles.size() + cols - 1) / cols;
        int h = meterH + rowsN * 66 + 10;
        StringBuilder svg = open(h);

        if (meter) {
            int xL = 20, xR = W - 20, trackY = 52, trackH = 14;
            // The 0-100 track as five named zones, drawn as one band with the
            // house green/red convention at its ends.
            String[] zoneFill = {NEG, NEG, MUTE, POS, POS};
            double[] zoneAlpha = {1, 0.55, 0.4, 0.55, 1};
            double zoneW = (double) (xR - xL) / 5;
            for (int z = 0; z < 5; z++) {
                svg.append("<rect x=\"").append(r1(xL + z * zoneW)).append("\" y=\"")
                        .append(trackY).append("\" width=\"").append(r1(zoneW - 2))
                        .append("\" height=\"").append(trackH).append("\" rx=\"3\" fill=\"")
                        .append(zoneFill[z]).append("\" opacity=\"").append(zoneAlpha[z])
                        .append("\"/>");
            }
            text(svg, xL, trackY + trackH + 18, "start", 11, MUTE,
                    de ? "Extreme Angst" : "Extreme fear", false);
            text(svg, xR, trackY + trackH + 18, "end", 11, MUTE,
                    de ? "Extreme Gier" : "Extreme greed", false);
            // Today, a week ago, a month ago — the direction is the reading.
            record Mark(String name, double score, boolean now) {}
            List<Mark> marks = new ArrayList<>();
            marks.add(new Mark(de ? "heute" : "today", fg.score(), true));
            if (fg.previousWeek() != null && Double.isFinite(fg.previousWeek())) {
                marks.add(new Mark(de ? "vor 1 Wo." : "1w ago", fg.previousWeek(), false));
            }
            if (fg.previousMonth() != null && Double.isFinite(fg.previousMonth())) {
                marks.add(new Mark(de ? "vor 1 Mon." : "1m ago", fg.previousMonth(), false));
            }
            // Today's mark rides ABOVE the track, the past marks BELOW it, so
            // a tape that barely moved cannot print "vor 1 Wo." through its own
            // headline. Past labels that would still collide with each other
            // give way — the tick stays, only the caption goes.
            double[] usedBelow = new double[marks.size()];
            int usedN = 0;
            for (Mark mk : marks) {
                double x = xL + clamp(mk.score(), 0, 100) / 100.0 * (xR - xL);
                if (mk.now()) {
                    svg.append("<circle cx=\"").append(r1(x)).append("\" cy=\"")
                            .append(r1(trackY + trackH / 2.0)).append("\" r=\"9\" fill=\"")
                            .append(INK).append("\" stroke=\"").append(SURFACE)
                            .append("\" stroke-width=\"2.5\"/>");
                    String head = fmt(mk.score(), 0)
                            + (fg.rating() == null ? "" : " · " + fg.rating());
                    text(svg, clamp(x, textW(head, 15) / 2 + 12,
                            W - textW(head, 15) / 2 - 12), trackY - 12, "middle", 15, INK,
                            head, true);
                    continue;
                }
                line(svg, x, trackY - 4, x, trackY + trackH + 4, MUTE, 2);
                String cap = mk.name() + " " + fmt(mk.score(), 0);
                double half = textW(cap, 11) / 2;
                // The extremes below the track are spoken for by the zone names.
                double lx = clamp(x, xL + 118 + half, xR - 118 - half);
                boolean clash = false;
                for (int u = 0; u < usedN; u++) {
                    if (Math.abs(usedBelow[u] - lx) < half * 2 + 10) clash = true;
                }
                if (clash) continue;
                usedBelow[usedN++] = lx;
                text(svg, lx, trackY + trackH + 18, "middle", 11, MUTE, cap, false);
            }
        }
        double colW = (double) W / cols;
        double inner = colW - 16;
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            double x = (i % cols) * colW + 4;
            double y = meterH + (i / cols) * 66;
            text(svg, x, y + 13, "start", 11, MUTE, fit(t.label(), inner, 11), false);
            text(svg, x, y + 38, "start", fitSize(t.value(), inner, 19), INK,
                    fit(t.value(), inner, MIN_FONT), true);
            if (t.sub() != null) {
                text(svg, x, y + 55, "start", 11, MUTE, fit(t.sub(), inner, 11), false);
            }
        }
        svg.append("</svg>");
        String title = de ? "Marktlage rundherum (nicht subjektbezogen)"
                : "The tape around it (not subject-specific)";
        String note = meter ? (de ? "CNN · CBOE · Bundesbank" : "CNN · CBOE · Bundesbank")
                : "CBOE · Bundesbank";
        return new ChartFigure(SEC_SITUATION, title, note, svg.toString());
    }

    // ---- 34. the consolidated forward docket — Ausblick ----

    /**
     * When does it get busy? Every dated leg the material carries on ONE
     * forward axis, one lane per kind — issuer dates, macro prints, central
     * bank decisions, statistics releases, regulatory deadlines. Each lane
     * says how many it holds; the prose names the ones that matter. Only
     * dates from today forward: a docket is a forecast, not a diary.
     */
    private ChartFigure docketFigure(Docket d) {
        if (d == null) return null;
        record Lane(String name, List<java.time.LocalDate> dates, String tone) {}
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate horizon = today.plusDays(120);

        java.util.function.Function<java.util.stream.Stream<java.time.LocalDate>,
                List<java.time.LocalDate>> window = st -> st
                .filter(java.util.Objects::nonNull)
                .filter(x -> !x.isBefore(today) && !x.isAfter(horizon))
                .sorted()
                .distinct()
                .toList();

        List<Lane> lanes = new ArrayList<>();
        lanes.add(new Lane(de ? "Emittent" : "Issuer", window.apply(java.util.stream.Stream.concat(
                d.issuerEvents().stream().map(e -> e.startDate() == null ? null
                        : java.time.LocalDate.ofInstant(e.startDate(),
                                java.time.ZoneId.systemDefault())),
                d.euEvents().stream().map(e -> e.date()))), S1));
        lanes.add(new Lane(de ? "Makro" : "Macro", window.apply(d.macroDocket().stream()
                .map(e -> e.whenEpochSeconds() <= 0 ? null
                        : java.time.LocalDate.ofInstant(
                                java.time.Instant.ofEpochSecond(e.whenEpochSeconds()),
                                java.time.ZoneId.systemDefault()))), S2));
        lanes.add(new Lane(de ? "Zentralbank" : "Central bank",
                window.apply(d.cbDecisions().stream().map(e -> e.date())), NEG));
        lanes.add(new Lane(de ? "Statistik" : "Statistics",
                window.apply(d.statsDocket().stream().map(e -> e.date())), MUTE));
        lanes.add(new Lane(de ? "Regulierung" : "Regulatory",
                window.apply(d.regDocket().stream().map(e -> e.date())), MUTE));
        lanes = lanes.stream().filter(l -> !l.dates().isEmpty()).toList();
        if (lanes.isEmpty()) return null;

        java.time.LocalDate last = lanes.stream().flatMap(l -> l.dates().stream())
                .max(java.time.LocalDate::compareTo).orElse(horizon);
        long spanDays = Math.max(java.time.temporal.ChronoUnit.DAYS.between(today, last), 1);

        int laneH = 34, padT = 16, xL = 148, xR = W - 76;
        int h = padT + lanes.size() * laneH + 30;
        final java.time.LocalDate from = today;
        final long days = spanDays;
        java.util.function.Function<java.time.LocalDate, Double> dayX = x ->
                xL + (double) java.time.temporal.ChronoUnit.DAYS.between(from, x)
                        / days * (xR - xL);

        StringBuilder svg = open(h);
        for (int i = 0; i < lanes.size(); i++) {
            Lane l = lanes.get(i);
            double y = padT + i * laneH + laneH / 2.0;
            line(svg, xL, y, xR, y, GRID, 1);
            text(svg, xL - 12, y + 4, "end", 12, MUTE, fit(l.name(), xL - 20, 12), false);
            for (java.time.LocalDate dt : l.dates()) {
                double x = dayX.apply(dt);
                svg.append("<circle cx=\"").append(r1(x)).append("\" cy=\"").append(r1(y))
                        .append("\" r=\"5\" fill=\"").append(l.tone()).append("\" stroke=\"")
                        .append(SURFACE).append("\" stroke-width=\"1.5\" opacity=\"0.85\"/>");
            }
            text(svg, xR + 12, y + 4, "start", 11, MUTE, String.valueOf(l.dates().size()), false);
        }
        // The axis beneath: month starts inside the window, thinned to fit.
        double axisY = padT + lanes.size() * laneH + 6;
        line(svg, xL, axisY, xR, axisY, AXIS, 1);
        List<java.time.LocalDate> months = new ArrayList<>();
        java.time.LocalDate m = today.withDayOfMonth(1).plusMonths(1);
        while (!m.isAfter(last)) {
            months.add(m);
            m = m.plusMonths(1);
        }
        text(svg, xL, axisY + 18, "start", 11, MUTE, de ? "heute" : "today", false);
        double labelW = textW("Mai 26", 11) + 14;
        int step = Math.max(1, (int) Math.ceil(months.size()
                / Math.max(1.0, Math.floor((xR - xL) / labelW))));
        boolean spansYears = today.getYear() != last.getYear();
        for (int i = 0; i < months.size(); i += step) {
            java.time.LocalDate mo = months.get(i);
            double x = dayX.apply(mo);
            line(svg, x, axisY, x, axisY + 5, AXIS, 1);
            text(svg, clamp(x, xL + 30, xR - 14), axisY + 18, "middle", 11, MUTE,
                    monthShort(mo.getMonthValue())
                            + (spansYears ? " " + (mo.getYear() % 100) : ""), false);
        }
        svg.append("</svg>");
        int total = lanes.stream().mapToInt(l -> l.dates().size()).sum();
        String title = de
                ? "Terminkalender voraus (" + total + " Termine bis "
                        + isoDate(last.toString()) + ")"
                : "The docket ahead (" + total + " dates through "
                        + isoDate(last.toString()) + ")";
        return new ChartFigure(SEC_OUTLOOK, title,
                de ? "alle Kalender-Quellen" : "all calendar feeds", svg.toString());
    }

    // ---- 35. eight fiscal years (boerse.de) — Fundamentaldaten ----

    /**
     * The cycle, not the snapshot: eight fiscal years of revenue and net
     * income. The Consorsbank series next to it reaches back two or three
     * years - long enough for a trend, far too short for a cycle.
     */
    private ChartFigure boerseYearsFigure(
            List<de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.FundamentalYear> years) {
        if (years == null || years.size() < 3) return null;
        var rows = years.stream()
                .sorted(java.util.Comparator.comparingInt(y -> y.year()))
                .filter(y -> y.get("Umsatz") != null || y.get("Jahresüberschuss") != null)
                .toList();
        if (rows.size() < 3) return null;
        if (rows.size() > 10) rows = rows.subList(rows.size() - 10, rows.size());

        String legend = de ? "Umsatz|Jahresüberschuss" : "Revenue|Net income";
        StringBuilder svg = groupedColumns(rows.stream().map(y -> new Group(
                        String.valueOf(y.year()),
                        new double[]{y.get("Umsatz") == null ? Double.NaN : y.get("Umsatz"),
                                y.get("Jahresüberschuss") == null ? Double.NaN
                                        : y.get("Jahresüberschuss")},
                        false)).toList(),
                new String[]{S1, S2}, legend.split("\\|"), 1);
        String title = de
                ? "Umsatz und Jahresüberschuss über " + rows.size() + " Geschäftsjahre (Mio.)"
                : "Revenue and net income over " + rows.size() + " fiscal years (millions)";
        return svg == null ? null
                : new ChartFigure(SEC_FUNDAMENTALS, title, "boerse.de", svg.toString());
    }

    // ---- 36. the estimate path (finanzen.net) — Fundamentaldaten ----

    /**
     * The DE/EU twin of the delivery record: consensus against what was
     * actually reported, period by period, with the periods still ahead
     * carrying their expectation de-emphasized. The NASDAQ surprise strip and
     * the CNBC ledger only ever answer this for US listings.
     */
    private ChartFigure estimatePathFigure(
            List<de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.EstimateRow> rows) {
        if (rows == null || rows.isEmpty()) return null;
        var eps = rows.stream()
                .filter(r -> r.periodLabel() != null && r.meanEstimate() != null)
                .filter(r -> r.metric() == null || r.metric().toLowerCase(Locale.ROOT).contains("eps")
                        || r.metric().toLowerCase(Locale.ROOT).contains("gewinn"))
                .sorted(java.util.Comparator.comparing(
                        r -> r.periodEnd() == null ? java.time.LocalDate.MAX : r.periodEnd()))
                .toList();
        if (eps.size() < 2) return null;
        if (eps.size() > 8) eps = eps.subList(0, 8);

        String legend = de ? "Konsens|Berichtet" : "Consensus|Reported";
        StringBuilder svg = groupedColumns(eps.stream().map(r -> new Group(
                        truncate(r.periodLabel(), 10),
                        new double[]{r.meanEstimate(),
                                r.actual() == null ? Double.NaN : r.actual()},
                        r.actual() == null)).toList(),
                new String[]{MUTE, S1}, legend.split("\\|"), 2);
        String title = de ? "Schätzungspfad je Periode (Gewinn je Aktie)"
                : "Estimate path by period (earnings per share)";
        return svg == null ? null
                : new ChartFigure(SEC_FUNDAMENTALS, title, "finanzen.net", svg.toString());
    }

    // ---- 37. who owns it (onvista) — Bewertung ----

    /**
     * A fifth of the shares in free float is a different instrument from one
     * with ninety: one anchor holder can move it, and a squeeze needs far less
     * volume. One hundred percent as one bar - anchors named, free float last.
     */
    private ChartFigure ownershipFigure(
            de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.CompanySnapshot c) {
        if (c == null) return null;
        record Slice(String name, double pct, boolean floatSlice) {}
        List<Slice> slices = new ArrayList<>();
        if (c.shareholders() != null) {
            for (var h : c.shareholders()) {
                if (h.name() == null || !(h.percentage() > 0)) continue;
                slices.add(new Slice(h.name(), h.percentage(), false));
                if (slices.size() >= 6) break;
            }
        }
        double free = c.freeFloatPct();
        boolean hasFree = Double.isFinite(free) && free > 0;
        double named = slices.stream().mapToDouble(Slice::pct).sum();
        if (hasFree) {
            slices.add(new Slice(de ? "Streubesitz" : "Free float", free, true));
        } else if (named > 0 && named < 100) {
            slices.add(new Slice(de ? "Übrige" : "Other", 100 - named, true));
        }
        if (slices.size() < 2) return null;
        double total = slices.stream().mapToDouble(Slice::pct).sum();
        if (!(total > 0)) return null;

        int h = 150, barH = 34, padT = 22, padL = 20, padR = 20;
        int plotW = W - padL - padR;
        StringBuilder svg = open(h);
        double x = padL;
        for (int i = 0; i < slices.size(); i++) {
            Slice s = slices.get(i);
            double w = Math.max(s.pct() / total * plotW - 2, 1);
            svg.append("<rect x=\"").append(r1(x)).append("\" y=\"").append(padT)
                    .append("\" width=\"").append(r1(w)).append("\" height=\"").append(barH)
                    .append("\" rx=\"3\" fill=\"").append(s.floatSlice() ? MUTE : S1)
                    .append("\" opacity=\"").append(s.floatSlice() ? 0.45
                            : Math.max(0.45, 1.0 - i * 0.13)).append("\"/>");
            if (w >= 34) {
                text(svg, x + w / 2, padT + barH / 2.0 + 4, "middle", 11,
                        s.floatSlice() ? INK : SURFACE, fmt(s.pct(), 1) + " %", true);
            }
            x += w + 2;
        }
        // The legend below carries the names; the bar carries the shares.
        double ly = padT + barH + 24;
        double lx = padL;
        for (Slice s : slices) {
            String name = fit(s.name(), 200, 11);
            if (lx + textW(name, 11) + 30 > W - padR) {
                lx = padL;
                ly += 20;
                if (ly > h - 8) break;
            }
            svg.append("<rect x=\"").append(r1(lx)).append("\" y=\"").append(r1(ly - 9))
                    .append("\" width=\"10\" height=\"10\" rx=\"2\" fill=\"")
                    .append(s.floatSlice() ? MUTE : S1).append("\"/>");
            text(svg, lx + 15, ly, "start", 11, MUTE, name, false);
            lx += 15 + textW(name, 11) + 22;
        }
        svg.append("</svg>");
        String title = de
                ? "Eigentümerstruktur" + (hasFree ? " (Streubesitz " + fmt(free, 1) + " %)" : "")
                : "Ownership" + (hasFree ? " (free float " + fmt(free, 1) + " %)" : "");
        return new ChartFigure(SEC_VALUATION, title, "onvista", svg.toString());
    }

    // ---- 38. the measured fit against the index (onvista) — Lage ----

    /**
     * The only MEASURED relative figures in the whole material: how much of
     * the index the name actually carries (beta), how tightly it follows it
     * (correlation) and what it did on top of it. The sector-proxy figure
     * computes a gap by hand; this one is the venue's own arithmetic.
     */
    private ChartFigure benchmarkFigure(
            de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.BenchmarkFit b) {
        if (b == null) return null;
        String[] names = {"1W", "1M", "1Y"};
        double[] vals = {b.outperformance1W(), b.outperformance1M(), b.outperformance1Y()};
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < vals.length; i++) {
            if (Double.isFinite(vals[i])) idx.add(i);
        }
        boolean tiles = Double.isFinite(b.beta250()) || Double.isFinite(b.correlation250());
        if (idx.isEmpty() && !tiles) return null;

        int barsH = idx.isEmpty() ? 0 : 170;
        int h = barsH + (tiles ? 76 : 0);
        StringBuilder svg = open(h);
        if (!idx.isEmpty()) {
            int padT = 28, padB = 30, padL = 20, padR = 20;
            int plotH = barsH - padT - padB;
            double posMax = idx.stream().mapToDouble(i -> Math.max(0, vals[i])).max().orElse(0);
            double negMax = idx.stream().mapToDouble(i -> Math.max(0, -vals[i])).max().orElse(0);
            double span = posMax + negMax == 0 ? 1 : posMax + negMax;
            double baseY = padT + posMax / span * plotH;
            double slot = (double) (W - padL - padR) / idx.size();
            double barW = clamp(slot * 0.34, 14, 60);
            line(svg, padL, baseY, W - padR, baseY, AXIS, 1);
            for (int k = 0; k < idx.size(); k++) {
                double v = vals[idx.get(k)];
                double x = padL + slot * k + (slot - barW) / 2;
                double len = Math.abs(v) / span * plotH;
                String tone = v >= 0 ? POS : NEG;
                if (v >= 0) {
                    svg.append(roundedTopBar(x, baseY - len, barW, len, tone));
                    text(svg, x + barW / 2, baseY - len - 7, "middle", 11, INK, pct(v), false);
                    text(svg, x + barW / 2, baseY + 16, "middle", 11, MUTE, names[idx.get(k)], false);
                } else {
                    svg.append(roundedBottomBar(x, baseY, barW, len, tone));
                    text(svg, x + barW / 2, baseY + len + 16, "middle", 11, INK, pct(v), false);
                    text(svg, x + barW / 2, baseY - 7, "middle", 11, MUTE, names[idx.get(k)], false);
                }
            }
        }
        if (tiles) {
            record Tile(String label, String value) {}
            List<Tile> t = new ArrayList<>();
            if (Double.isFinite(b.beta250())) {
                t.add(new Tile(de ? "BETA 250T" : "BETA 250D", fmt(b.beta250(), 2)));
            }
            if (Double.isFinite(b.beta30())) {
                t.add(new Tile(de ? "BETA 30T" : "BETA 30D", fmt(b.beta30(), 2)));
            }
            if (Double.isFinite(b.correlation250())) {
                t.add(new Tile(de ? "KORRELATION 250T" : "CORRELATION 250D",
                        fmt(b.correlation250(), 2)));
            }
            if (Double.isFinite(b.correlation30())) {
                t.add(new Tile(de ? "KORRELATION 30T" : "CORRELATION 30D",
                        fmt(b.correlation30(), 2)));
            }
            int cols = Math.max(1, t.size());
            double colW = (double) W / cols;
            for (int i = 0; i < t.size(); i++) {
                double x = (i % cols) * colW + 4;
                text(svg, x, barsH + 20, "start", 11, MUTE, fit(t.get(i).label(), colW - 16, 11), false);
                text(svg, x, barsH + 46, "start", 19, INK, t.get(i).value(), true);
            }
        }
        svg.append("</svg>");
        String index = b.index() == null ? null : String.valueOf(b.index());
        String title = de ? "Gemessener Gleichlauf mit dem Index"
                : "Measured fit against the index";
        return new ChartFigure(SEC_SITUATION, title,
                index == null || index.isBlank() ? "onvista" : "onvista", svg.toString());
    }

    // ---- 39. peer targets (finanzen.net) — Bewertung ----

    /**
     * The peer scatter asks how the field is VALUED; this one asks what the
     * street still expects OF it. Sorted by upside, the subject marked.
     */
    private ChartFigure peerTargetsFigure(
            List<de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.PeerConsensus> peers,
            MarketSnapshot s) {
        if (peers == null) return null;
        var rows = peers.stream()
                .filter(p -> p.name() != null && p.upsidePercent() != null
                        && Double.isFinite(p.upsidePercent()))
                .sorted((a, b) -> Double.compare(b.upsidePercent(), a.upsidePercent()))
                .limit(10)
                .toList();
        if (rows.size() < 3) return null;
        double max = rows.stream().mapToDouble(p -> Math.abs(p.upsidePercent())).max().orElse(0);
        if (!(max > 0)) return null;
        String self = s == null ? null : s.symbol();

        int barH = 18, rowGap = 12, padT = 10, labelW = 220, valueW = 92;
        int h = padT + rows.size() * (barH + rowGap);
        double fieldL = labelW + 18, fieldR = W - valueW - 12;
        double axisX = (fieldL + fieldR) / 2, arm = axisX - fieldL;
        StringBuilder svg = open(h);
        line(svg, axisX, padT - 4, axisX, h - 6, AXIS, 1);
        for (int i = 0; i < rows.size(); i++) {
            var p = rows.get(i);
            double y = padT + i * (barH + rowGap);
            boolean mine = self != null && p.name().toUpperCase(Locale.ROOT)
                    .contains(self.toUpperCase(Locale.ROOT));
            double w = Math.max(Math.abs(p.upsidePercent()) / max * arm, 2);
            String tone = p.upsidePercent() >= 0 ? POS : NEG;
            if (p.upsidePercent() >= 0) svg.append(roundedRightBar(axisX, y, w, barH, tone));
            else svg.append(roundedLeftBar(axisX - w, y, w, barH, tone));
            text(svg, labelW, y + barH / 2.0 + 4, "end", 12, mine ? INK : MUTE,
                    fit(p.name(), labelW - 14, 12), mine);
            text(svg, fieldR + 12, y + barH / 2.0 + 4, "start", 12, mine ? INK : MUTE,
                    pct(p.upsidePercent()), mine);
            if (mine) {
                svg.append("<rect x=\"4\" y=\"").append(r1(y)).append("\" width=\"3\" height=\"")
                        .append(barH).append("\" rx=\"1.5\" fill=\"var(--ddc-sun,#c99a1e)\"/>");
            }
        }
        svg.append("</svg>");
        String title = de ? "Kurspotenzial im Branchenvergleich"
                : "Upside across the peer field";
        return new ChartFigure(SEC_VALUATION, title, "finanzen.net", svg.toString());
    }

    // ---- 40. where it actually trades (finanzen.net) — Lage ----

    /**
     * The trading picture reads ONE venue. This one asks where the paper is
     * really liquid - the difference between a name you can leave and one you
     * cannot.
     */
    private ChartFigure venueVolumeFigure(
            List<de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.VenueQuote> venues) {
        if (venues == null) return null;
        var rows = venues.stream()
                .filter(v -> v.venue() != null && v.volume() != null && v.volume() > 0)
                .sorted((a, b) -> Long.compare(b.volume(), a.volume()))
                .limit(8)
                .toList();
        if (rows.size() < 2) return null;
        long max = rows.get(0).volume();

        int barH = 20, rowGap = 14, padT = 10, labelW = 200;
        int h = padT + rows.size() * (barH + rowGap);
        int plotW = W - labelW - 190;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            var v = rows.get(i);
            double y = padT + i * (barH + rowGap);
            text(svg, labelW - 10, y + barH / 2.0 + 4, "end", 12, MUTE,
                    fit(v.venue(), labelW - 14, 12), i == 0);
            double w = Math.max((double) v.volume() / max * plotW, 2);
            svg.append(roundedRightBar(labelW, y, w, barH, S1));
            String tail = units(v.volume()) + (de ? " Stück" : " units")
                    + (v.last() == null ? "" : " · " + fmt(v.last(), 2));
            text(svg, labelW + w + 10, y + barH / 2.0 + 4, "start", 11, INK, tail, false);
        }
        svg.append("</svg>");
        String title = de ? "Wo das Papier gehandelt wird (Tagesvolumen je Platz)"
                : "Where the paper trades (day volume by venue)";
        return new ChartFigure(SEC_SITUATION, title, "finanzen.net", svg.toString());
    }

    // ---- 41. the German analyst trail (boerse.de) — Bewertung ----

    /**
     * The MarketBeat action timeline is a US register; for a German name it is
     * simply empty. This is its dpa-AFX twin: one dated row per call, house
     * and rating as written.
     */
    private ChartFigure deskCallsFigure(
            List<de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.AnalystCall> calls) {
        if (calls == null) return null;
        var rows = calls.stream()
                .filter(c -> c.date() != null && c.house() != null)
                .sorted((a, b) -> b.date().compareTo(a.date()))
                .limit(10)
                .toList();
        if (rows.size() < 2) return null;

        int rowH = 30, padT = 10;
        int h = padT + rows.size() * rowH;
        double xDate = 6, xHouse = 96, xRating = 260;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            var c = rows.get(i);
            double y = padT + i * rowH + 14;
            text(svg, xDate, y + 4, "start", 11, MUTE, isoDate(c.date().toString()), false);
            text(svg, xHouse, y + 4, "start", 11, INK,
                    fit(c.house(), xRating - xHouse - 10, 11), false);
            String rating = c.rating() == null ? "" : c.rating();
            String tone = ratingTone(rating);
            if (!rating.isBlank()) {
                svg.append("<rect x=\"").append(r1(xRating)).append("\" y=\"").append(r1(y - 9))
                        .append("\" width=\"3\" height=\"14\" rx=\"1.5\" fill=\"")
                        .append(tone).append("\"/>");
            }
            text(svg, xRating + 12, y + 4, "start", 11, INK,
                    fit(rating, W - xRating - 24, 11), false);
        }
        svg.append("</svg>");
        String title = de ? "Analysten-Urteile (deutscher Ticker)"
                : "Analyst calls (German tape)";
        return new ChartFigure(SEC_VALUATION, title, "boerse.de · dpa-AFX", svg.toString());
    }

    /** A rating word's house colour - buy green, sell red, anything else neutral. */
    private static String ratingTone(String rating) {
        String r = rating == null ? "" : rating.toLowerCase(Locale.ROOT);
        if (r.contains("buy") || r.contains("kauf") || r.contains("outperform")
                || r.contains("overweight") || r.contains("übergewicht")) {
            return POS;
        }
        if (r.contains("sell") || r.contains("verkauf") || r.contains("underperform")
                || r.contains("underweight") || r.contains("untergewicht")) {
            return NEG;
        }
        return MUTE;
    }

    // ---- 42. directors' dealings with names and dates (boerse.de) — Katalysatoren ----

    /**
     * The BaFin figure aggregates every insider filing into two bars and loses
     * the time axis entirely. This one keeps it: one column per filing, buys
     * up, sells down, sized by volume - WHEN somebody bought is most of the
     * signal.
     */
    private ChartFigure deskInsiderFigure(
            List<de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.InsiderDeal> deals) {
        if (deals == null) return null;
        var rows = deals.stream()
                .filter(d -> d.date() != null && d.volume() != null && d.volume() > 0)
                .sorted((a, b) -> a.date().compareTo(b.date()))
                .limit(24)
                .toList();
        if (rows.size() < 2) return null;
        double max = rows.stream().mapToDouble(d -> d.volume()).max().orElse(0);
        if (!(max > 0)) return null;

        int h = 220, padT = 28, padB = 40, padL = 20, padR = 20;
        int plotH = h - padT - padB;
        double baseY = padT + plotH / 2.0;
        double slot = (double) (W - padL - padR) / rows.size();
        double barW = clamp(slot * 0.6, 5, 34);
        StringBuilder svg = open(h);
        line(svg, padL, baseY, W - padR, baseY, AXIS, 1);
        for (int i = 0; i < rows.size(); i++) {
            var d = rows.get(i);
            double x = padL + slot * i + (slot - barW) / 2;
            double len = Math.max(d.volume() / max * (plotH / 2.0 - 6), 2);
            if (d.isBuy()) svg.append(roundedTopBar(x, baseY - len, barW, len, POS));
            else svg.append(roundedBottomBar(x, baseY, barW, len, NEG));
        }
        text(svg, padL, h - 8, "start", 11, MUTE, isoDate(rows.get(0).date().toString()), false);
        text(svg, W - padR, h - 8, "end", 11, MUTE,
                isoDate(rows.get(rows.size() - 1).date().toString()), false);
        long buys = rows.stream().filter(d -> d.isBuy()).count();
        text(svg, padL, 16, "start", 11, MUTE,
                (de ? "Käufe " : "buys ") + buys + " · " + (de ? "Verkäufe " : "sells ")
                        + (rows.size() - buys), false);
        svg.append("</svg>");
        String title = de ? "Directors' Dealings über die Zeit (Höhe = Volumen)"
                : "Directors' dealings over time (height = volume)";
        return new ChartFigure(SEC_CATALYSTS, title, "boerse.de", svg.toString());
    }

    // ---- 43. the US filing register (EDGAR) — Katalysatoren ----

    /** What the company itself had to file, dated: one lane per 8-K class. */
    private ChartFigure edgarFigure(
            List<de.bsommerfeld.wsbg.terminal.edgar.EdgarClient.EdgarEvent> events) {
        if (events == null) return null;
        var rows = events.stream()
                .filter(e -> e.date() != null)
                .sorted((a, b) -> a.date().compareTo(b.date()))
                .toList();
        if (rows.size() < 2) return null;
        java.util.LinkedHashMap<String, List<java.time.LocalDate>> lanes =
                new java.util.LinkedHashMap<>();
        for (var e : rows) {
            String cls = e.eventClass() == null || e.eventClass().isBlank()
                    ? (de ? "Meldung" : "Filing") : e.eventClass();
            lanes.computeIfAbsent(cls, k -> new ArrayList<>()).add(e.date());
        }
        if (lanes.size() > 6) {
            var trimmed = new java.util.LinkedHashMap<String, List<java.time.LocalDate>>();
            lanes.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                    .limit(6)
                    .forEach(en -> trimmed.put(en.getKey(), en.getValue()));
            lanes = trimmed;
        }
        java.time.LocalDate from = rows.get(0).date();
        java.time.LocalDate to = rows.get(rows.size() - 1).date();
        if (from.equals(to)) to = to.plusDays(1);
        long span = Math.max(java.time.temporal.ChronoUnit.DAYS.between(from, to), 1);

        int laneH = 32, padT = 14, xL = 210, xR = W - 60;
        int h = padT + lanes.size() * laneH + 28;
        final java.time.LocalDate start = from;
        StringBuilder svg = open(h);
        int i = 0;
        for (var en : lanes.entrySet()) {
            double y = padT + i * laneH + laneH / 2.0;
            line(svg, xL, y, xR, y, GRID, 1);
            text(svg, xL - 12, y + 4, "end", 12, MUTE, fit(en.getKey(), xL - 20, 12), false);
            for (java.time.LocalDate d : en.getValue()) {
                double x = xL + (double) java.time.temporal.ChronoUnit.DAYS.between(start, d)
                        / span * (xR - xL);
                svg.append("<circle cx=\"").append(r1(x)).append("\" cy=\"").append(r1(y))
                        .append("\" r=\"5\" fill=\"").append(S1).append("\" stroke=\"")
                        .append(SURFACE).append("\" stroke-width=\"1.5\" opacity=\"0.85\"/>");
            }
            text(svg, xR + 12, y + 4, "start", 11, MUTE,
                    String.valueOf(en.getValue().size()), false);
            i++;
        }
        double axisY = padT + lanes.size() * laneH + 4;
        line(svg, xL, axisY, xR, axisY, AXIS, 1);
        text(svg, xL, axisY + 18, "start", 11, MUTE, isoDate(from.toString()), false);
        text(svg, xR, axisY + 18, "end", 11, MUTE, isoDate(to.toString()), false);
        svg.append("</svg>");
        String title = de ? "Pflichtmeldungen je Art (" + rows.size() + ")"
                : "Mandatory filings by kind (" + rows.size() + ")";
        return new ChartFigure(SEC_CATALYSTS, title, "SEC EDGAR", svg.toString());
    }

    // ---- 44. how loudly the press covered it, per year — Lage ----

    /**
     * The monthly density figure reads ONE US register and caps at forty
     * entries. This is its long twin: one bar per calendar year over the whole
     * archive, which is the only place the multi-year arc becomes a shape.
     */
    private ChartFigure archiveYearsFigure(List<RawNewsItem> history) {
        if (history == null || history.size() < 6) return null;
        java.util.Map<Integer, Integer> perYear = new java.util.TreeMap<>();
        for (RawNewsItem item : history) {
            if (item.publishedAt() == null) continue;
            int y = java.time.LocalDate.ofInstant(item.publishedAt(),
                    java.time.ZoneId.systemDefault()).getYear();
            perYear.merge(y, 1, Integer::sum);
        }
        if (perYear.size() < 3) return null;
        int peak = perYear.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        int h = 200, padT = 28, padB = 34, padL = 20, padR = 20;
        int plotH = h - padT - padB;
        int n = perYear.size();
        double slot = (double) (W - padL - padR) / n;
        double barW = clamp(slot * 0.62, 8, 54);
        StringBuilder svg = open(h);
        line(svg, padL, padT + plotH, W - padR, padT + plotH, AXIS, 1);
        int i = 0;
        int step = slot < 42 ? 2 : 1;
        for (var en : perYear.entrySet()) {
            double x = padL + slot * i + (slot - barW) / 2;
            double len = Math.max((double) en.getValue() / peak * plotH, 2);
            svg.append(roundedTopBar(x, padT + plotH - len, barW, len, S1));
            if (i % step == 0 || i == n - 1) {
                text(svg, x + barW / 2, padT + plotH - len - 6, "middle", 10, MUTE,
                        String.valueOf(en.getValue()), false);
                text(svg, x + barW / 2, h - 10, "middle", 11, MUTE,
                        String.valueOf(en.getKey()), false);
            }
            i++;
        }
        svg.append("</svg>");
        String title = de
                ? "Presse-Aufmerksamkeit je Jahr (" + history.size() + " Archiv-Einträge)"
                : "Press attention per year (" + history.size() + " archive entries)";
        return new ChartFigure(SEC_SITUATION, title,
                de ? "Presse-Archiv" : "press archive", svg.toString());
    }

    // ---- 45. the input-price and rate boards — Lage ----

    /**
     * An industrial name lives on input prices and a levered one on rates.
     * The day's movers on both boards, sorted - not about the subject, and
     * that is the point: it is the weather its costs are made of.
     */
    private ChartFigure boardsFigure(Boards boards) {
        if (boards == null) return null;
        record Row(String name, double pct, boolean bond) {}
        List<Row> rows = new ArrayList<>();
        if (boards.commodities() != null) {
            for (var q : boards.commodities()) {
                if (q.name() != null && Double.isFinite(q.percentChange())) {
                    rows.add(new Row(q.name(), q.percentChange(), false));
                }
            }
        }
        if (boards.bonds() != null) {
            for (var q : boards.bonds()) {
                if (q.name() != null && Double.isFinite(q.percentChange())) {
                    rows.add(new Row(q.name(), q.percentChange(), true));
                }
            }
        }
        if (rows.size() < 3) return null;
        rows.sort((a, b) -> Double.compare(b.pct(), a.pct()));
        if (rows.size() > 12) rows = rows.subList(0, 12);
        double max = rows.stream().mapToDouble(r -> Math.abs(r.pct())).max().orElse(0);
        if (!(max > 0)) return null;

        int barH = 16, rowGap = 11, padT = 10, labelW = 220, valueW = 92;
        int h = padT + rows.size() * (barH + rowGap);
        double fieldL = labelW + 18, fieldR = W - valueW - 12;
        double axisX = (fieldL + fieldR) / 2, arm = axisX - fieldL;
        StringBuilder svg = open(h);
        line(svg, axisX, padT - 4, axisX, h - 6, AXIS, 1);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            double y = padT + i * (barH + rowGap);
            double w = Math.max(Math.abs(r.pct()) / max * arm, 2);
            String tone = r.pct() >= 0 ? POS : NEG;
            if (r.pct() >= 0) svg.append(roundedRightBar(axisX, y, w, barH, tone));
            else svg.append(roundedLeftBar(axisX - w, y, w, barH, tone));
            text(svg, labelW, y + barH / 2.0 + 4, "end", 11, MUTE,
                    fit(r.name(), labelW - 14, 11), false);
            text(svg, fieldR + 12, y + barH / 2.0 + 4, "start", 11, MUTE, pct(r.pct()), false);
        }
        svg.append("</svg>");
        String title = de ? "Rohstoffe und Anleihen heute" : "Commodities and bonds today";
        return new ChartFigure(SEC_SITUATION, title, "Trading Economics", svg.toString());
    }

    // ---- 27. calendar-year performance (the long arc) — Lage ----

    /**
     * The question no other figure in the report answers: how did this name do
     * in 2008, in 2020, in 2022? One diverging column per calendar year, the
     * year's own move direct-labeled. The performance leg reaches back to the
     * venue's history start, so this is the report's only multi-cycle picture.
     */
    private ChartFigure calendarYearsFigure(
            List<de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient.YearPerformance> years) {
        if (years == null || years.isEmpty()) return null;
        var rows = years.stream()
                .filter(y -> Double.isFinite(y.performanceRel()))
                .sorted(java.util.Comparator.comparingInt(a -> a.year()))
                .toList();
        if (rows.size() < 2) return null;
        if (rows.size() > 14) rows = rows.subList(rows.size() - 14, rows.size());

        // padB carries the year ticks AND the headroom a losing year's value
        // label needs beneath its own column.
        int h = 250, padT = 30, padB = 56, padL = 20, padR = 20;
        int plotH = h - padT - padB;
        double posMax = rows.stream().mapToDouble(y -> Math.max(0, y.performanceRel())).max().orElse(0);
        double negMax = rows.stream().mapToDouble(y -> Math.max(0, -y.performanceRel())).max().orElse(0);
        double totalSpan = posMax + negMax == 0 ? 1 : posMax + negMax;
        double baseY = padT + posMax / totalSpan * plotH;
        int n = rows.size();
        double slot = (double) (W - padL - padR) / n;
        double barW = clamp(slot * 0.6, 8, 56);

        StringBuilder svg = open(h);
        line(svg, padL, baseY, W - padR, baseY, AXIS, 1);
        // With many years the labels stop fitting; thin them rather than
        // printing a smear (the marks stay, every one of them).
        int step = slot < 42 ? 2 : 1;
        for (int i = 0; i < n; i++) {
            var y = rows.get(i);
            double v = y.performanceRel();
            double x = padL + slot * i + (slot - barW) / 2;
            double len = Math.abs(v) / totalSpan * plotH;
            String tone = v >= 0 ? POS : NEG;
            boolean label = i % step == 0 || i == n - 1;
            if (v >= 0) {
                svg.append(roundedTopBar(x, baseY - len, barW, len, tone));
                if (label) text(svg, x + barW / 2, baseY - len - 7, "middle", 10, INK, pct(v), false);
            } else {
                svg.append(roundedBottomBar(x, baseY, barW, len, tone));
                if (label) text(svg, x + barW / 2, baseY + len + 16, "middle", 10, INK, pct(v), false);
            }
            if (label) {
                text(svg, x + barW / 2, h - 8, "middle", 11, MUTE,
                        String.valueOf(y.year()), false);
            }
        }
        svg.append("</svg>");
        String title = de
                ? "Kalenderjahres-Performance (" + rows.get(0).year() + "-"
                        + rows.get(n - 1).year() + ")"
                : "Calendar-year performance (" + rows.get(0).year() + "-"
                        + rows.get(n - 1).year() + ")";
        return new ChartFigure(SEC_SITUATION, title, "onvista", svg.toString());
    }

    // ---- 28. sector standings of the day — Lage ----

    /**
     * Where the subject's own sector stood today: the heatmap's sector
     * containers as sorted diverging bars, the subject's sector emphasized.
     * A single name's move means little without the field it moved in.
     */
    private ChartFigure sectorBoardFigure(List<HeatmapService.Node> board, String subjectSector,
            String universe) {
        if (board == null || board.isEmpty()) return null;
        // Sector containers carry no parent and no symbol; leaves carry both.
        List<HeatmapService.Node> sectors = board.stream()
                .filter(nd -> nd.parent() == null && nd.symbol() == null)
                .filter(nd -> nd.name() != null && Double.isFinite(nd.performance()))
                .sorted((a, b) -> Double.compare(b.performance(), a.performance()))
                .limit(12)
                .toList();
        if (sectors.size() < 3) return null;
        double max = sectors.stream()
                .mapToDouble(nd -> Math.abs(nd.performance())).max().orElse(0);
        if (max <= 0) return null;

        // Names left, value labels right, and the diverging field BETWEEN them
        // with the zero line at its centre — a loss bar must never grow back
        // over the name that says whose loss it is.
        int barH = 18, rowGap = 12, padT = 10, labelW = 220, valueW = 92;
        int h = padT + sectors.size() * (barH + rowGap);
        double fieldL = labelW + 18, fieldR = W - valueW - 12;
        double axisX = (fieldL + fieldR) / 2;
        double arm = axisX - fieldL;
        StringBuilder svg = open(h);
        line(svg, axisX, padT - 4, axisX, h - 6, AXIS, 1);
        for (int i = 0; i < sectors.size(); i++) {
            HeatmapService.Node nd = sectors.get(i);
            double y = padT + i * (barH + rowGap);
            boolean mine = subjectSector != null && subjectSector.equalsIgnoreCase(nd.name());
            double w = Math.max(Math.abs(nd.performance()) / max * arm, 2);
            String tone = nd.performance() >= 0 ? POS : NEG;
            if (nd.performance() >= 0) {
                svg.append(roundedRightBar(axisX, y, w, barH, tone));
            } else {
                svg.append(roundedLeftBar(axisX - w, y, w, barH, tone));
            }
            text(svg, labelW, y + barH / 2.0 + 4, "end", 12, mine ? INK : MUTE,
                    fit(nd.name(), labelW - 14, 12), mine);
            text(svg, fieldR + 12, y + barH / 2.0 + 4, "start", 12, mine ? INK : MUTE,
                    pct(nd.performance()), mine);
            if (mine) { // the subject's own field gets a marker, not just weight
                svg.append("<rect x=\"4\" y=\"").append(r1(y)).append("\" width=\"3\" height=\"")
                        .append(barH).append("\" rx=\"1.5\" fill=\"var(--ddc-sun,#c99a1e)\"/>");
            }
        }
        svg.append("</svg>");
        String title = de ? "Sektoren heute" : "Sectors today";
        String note = universe == null || universe.isBlank()
                ? (de ? "Haus-Heatmap" : "house heatmap")
                : (de ? "Haus-Heatmap · " : "house heatmap · ") + universe;
        return new ChartFigure(SEC_SITUATION, title, note, svg.toString());
    }

    // ---- 29. the consensus arc over a year — Bewertung ----

    /**
     * What the street thought a year ago, half a year ago and today: the same
     * stacked-tier grammar as the analyst vote, but as an ARC — the direction
     * of a consensus says more than its level.
     */
    private ChartFigure consensusArcFigure(
            List<de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.ConsensusTrend> trend) {
        if (trend == null || trend.isEmpty()) return null;
        var rows = trend.stream()
                .filter(t -> t.label() != null && t.buy() + t.hold() + t.sell() > 0)
                .limit(4)
                .toList();
        if (rows.size() < 2) return null;
        int scaleMax = rows.stream().mapToInt(t -> t.buy() + t.hold() + t.sell()).max().orElse(0);
        if (scaleMax <= 0) return null;

        String[] tierNames = (de ? "Kaufen|Halten|Verkaufen" : "Buy|Hold|Sell").split("\\|");
        String[] fills = {POS, MUTE, NEG};
        double[] alpha = {1, 0.5, 1};

        int barH = 24, rowGap = 18, labelW = 128, padT = 12, legendH = 30;
        int h = padT + rows.size() * (barH + rowGap) + legendH;
        int plotW = W - labelW - 90;
        StringBuilder svg = open(h);
        for (int r = 0; r < rows.size(); r++) {
            var t = rows.get(r);
            double y = padT + r * (barH + rowGap);
            int[] tiers = {t.buy(), t.hold(), t.sell()};
            text(svg, labelW - 10, y + barH / 2.0 + 4, "end", 12, MUTE,
                    fit(t.label(), labelW - 14, 12), r == 0);
            double x = labelW;
            for (int i = 0; i < 3; i++) {
                if (tiers[i] <= 0) continue;
                double w = Math.max((double) tiers[i] / scaleMax * plotW - 2, 1);
                svg.append("<rect x=\"").append(r1(x)).append("\" y=\"").append(r1(y))
                        .append("\" width=\"").append(r1(w)).append("\" height=\"").append(barH)
                        .append("\" rx=\"2\" fill=\"").append(fills[i])
                        .append("\" opacity=\"").append(alpha[i]).append("\"/>");
                if (w >= 20) {
                    text(svg, x + w / 2, y + barH / 2.0 + 4, "middle", 11,
                            alpha[i] >= 1 ? SURFACE : INK, String.valueOf(tiers[i]), false);
                }
                x += w + 2;
            }
            text(svg, x + 10, y + barH / 2.0 + 4, "start", 11, MUTE,
                    String.valueOf(tiers[0] + tiers[1] + tiers[2]), false);
        }
        double lx = labelW;
        double ly = padT + rows.size() * (barH + rowGap) + 16;
        for (int i = 0; i < 3; i++) {
            svg.append("<rect x=\"").append(r1(lx)).append("\" y=\"").append(r1(ly - 9))
                    .append("\" width=\"10\" height=\"10\" rx=\"2\" fill=\"").append(fills[i])
                    .append("\" opacity=\"").append(alpha[i]).append("\"/>");
            text(svg, lx + 15, ly, "start", 11, MUTE, tierNames[i], false);
            lx += 15 + textW(tierNames[i], 11) + 18;
        }
        svg.append("</svg>");
        String title = de ? "Konsens-Bogen (Analystenstimmen im Zeitverlauf)"
                : "Consensus arc (analyst votes over time)";
        return new ChartFigure(SEC_VALUATION, title, "finanzen.net", svg.toString());
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

        int h = 128, xL = 30, xR = W - 30;
        double trackY = 74, trackW = xR - xL;
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
        int tileH = 66, padT = 10;
        int h = padT + tileH;
        double colW = (double) W / cols;
        double inner = colW - 16;
        StringBuilder svg = open(h);
        for (int i = 0; i < tiles.size() && i < cols; i++) {
            Tile tile = tiles.get(i);
            double x = (i % cols) * colW + 4;
            double y = padT;
            text(svg, x, y + 13, "start", 11, MUTE, fit(tile.label(), inner, 11), false);
            text(svg, x, y + 38, "start", fitSize(tile.value(), inner, 19), INK,
                    fit(tile.value(), inner, MIN_FONT), true);
            if (tile.sub() != null) {
                text(svg, x, y + 55, "start", 11, MUTE, fit(tile.sub(), inner, 11), false);
            }
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
        int h = 250, padT = 16, padB = 26, xL = 116, xR = W - 108;
        int plotH = h - padT - padB;

        record Rung(String name, double value, boolean isPrice, boolean isPoc) {}
        List<Rung> rungs = new ArrayList<>();
        rungs.add(new Rung(de ? "Profil-Hoch" : "Profile high", p.high(), false, false));
        if (Double.isFinite(p.vah())) rungs.add(new Rung("VAH", p.vah(), false, false));
        rungs.add(new Rung("POC", p.poc(), false, true));
        if (Double.isFinite(p.val())) rungs.add(new Rung("VAL", p.val(), false, false));
        rungs.add(new Rung(de ? "Profil-Tief" : "Profile low", p.low(), false, false));
        if (Double.isFinite(price)) rungs.add(new Rung(de ? "Kurs" : "Price", price, true, false));

        // The scale spans what the ladder actually DRAWS, not just the profile
        // envelope: a provider VAH sitting above its own profile high (live
        // fixture: VAH 1.001 over a 987 high) plotted at a negative y and had
        // its label sliced off by the canvas edge.
        double min = rungs.stream().mapToDouble(Rung::value).min().orElse(0);
        double max = rungs.stream().mapToDouble(Rung::value).max().orElse(1);
        double span = max - min == 0 ? 1 : max - min;

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
        // Collision pass (the srFigure grammar): sorted by value, kept apart,
        // the whole stack re-seated when it overran the plot floor.
        List<Rung> sorted = new ArrayList<>(rungs);
        sorted.sort((a, b) -> Double.compare(b.value(), a.value()));
        double[] raw = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            raw[i] = padT + (1 - (sorted.get(i).value() - min) / span) * plotH;
        }
        double[] ys = ladderYs(raw, 15, padT, padT + plotH);
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
        int barH = 18, rowGap = 11, padT = 34;
        int h = padT + rows * (barH + rowGap);
        double centerX = W / 2.0;
        double bx = centerX - 76, ax = centerX + 76; // bar baselines beside the price spine
        double half = bx - 120;                       // leave room for the outer text
        StringBuilder svg = open(h);
        text(svg, bx, 16, "end", 11, MUTE, de ? "GELD (Kauf)" : "BID (buy)", true);
        text(svg, ax, 16, "start", 11, MUTE, de ? "BRIEF (Verkauf)" : "ASK (sell)", true);
        line(svg, centerX, padT - 6, centerX, h - 4, AXIS, 1);
        for (int i = 0; i < rows; i++) {
            double y = padT + i * (barH + rowGap);
            double cy = y + barH / 2.0 + 4;
            if (i < bids.size()) {
                OrderBookSnapshot.Level l = bids.get(i);
                double w = Math.max((double) l.units() / maxUnits * half, 2);
                svg.append(roundedLeftBar(bx - w, y, w, barH, POS));
                text(svg, centerX - 8, cy, "end", 11, INK, fmt(l.price(), 2), i == 0);
                text(svg, bx - w - 8, cy, "end", 11, MUTE, levelLabel(l), false);
            }
            if (i < asks.size()) {
                OrderBookSnapshot.Level l = asks.get(i);
                double w = Math.max((double) l.units() / maxUnits * half, 2);
                svg.append(roundedRightBar(ax, y, w, barH, NEG));
                text(svg, centerX + 8, cy, "start", 11, INK, fmt(l.price(), 2), i == 0);
                text(svg, ax + w + 8, cy, "start", 11, MUTE, levelLabel(l), false);
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
        // Chronological, so the lane allocator below only ever walks forward.
        rows.sort(java.util.Comparator.comparing(Ev::date));
        java.time.LocalDate min = rows.stream().map(Ev::date)
                .min(java.time.LocalDate::compareTo).orElseThrow();
        java.time.LocalDate max = rows.stream().map(Ev::date)
                .max(java.time.LocalDate::compareTo).orElseThrow();
        if (min.equals(max)) max = max.plusDays(1);
        long spanDays = java.time.temporal.ChronoUnit.DAYS.between(min, max);

        int h = 175, xL = 24, xR = W - 24;
        double axisY = 142;
        StringBuilder svg = open(h);
        line(svg, xL, axisY, xR, axisY, AXIS, 1);
        // THREE label lanes above the axis, and a lane only accepts a label
        // that clears the last one it took. Two lanes at 8-unit type used to
        // print neighbouring event classes straight through each other; the
        // label that would still collide is dropped, its mark stays.
        double[] laneY = {axisY - 108, axisY - 74, axisY - 40};
        double[] laneRight = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY};
        for (int i = 0; i < rows.size(); i++) {
            Ev ev = rows.get(i);
            MarketEventRecord e = ev.rec();
            double x = xL + (double) java.time.temporal.ChronoUnit.DAYS.between(min, ev.date())
                    / spanDays * (xR - xL);
            String tone = e.carEvent() == null ? MUTE : e.carEvent() >= 0 ? POS : NEG;
            svg.append("<circle cx=\"").append(r1(x)).append("\" cy=\"").append(r1(axisY))
                    .append("\" r=\"5\" fill=\"").append(tone).append("\" stroke=\"")
                    .append(SURFACE).append("\" stroke-width=\"1.5\"/>");
            String cls = truncate(e.eventClass() == null ? "?" : e.eventClass(), 16);
            double half = textW(cls, 10) / 2;
            double lx = clamp(x, xL + half, xR - half);
            int lane = -1;
            for (int k = 0; k < laneY.length; k++) {
                if (lx - half - 6 >= laneRight[k]) { lane = k; break; }
            }
            if (lane < 0) continue; // no lane free at this date — the mark speaks alone
            laneRight[lane] = lx + half;
            double labelY = laneY[lane];
            // The leader runs to the LABEL's x, not the mark's, so a clamped
            // label never sits at the end of a line pointing somewhere else.
            line(svg, x, axisY - 7, lx, labelY + (e.carEvent() != null ? 16 : 5), GRID, 1);
            text(svg, lx, labelY, "middle", 10, MUTE, cls, false);
            if (e.carEvent() != null) {
                text(svg, lx, labelY + 13, "middle", 11, INK, pct(e.carEvent()), false);
            }
        }
        // Edge dates beneath the axis.
        text(svg, xL, axisY + 20, "start", 10, MUTE, isoDate(min.toString()), false);
        text(svg, xR, axisY + 20, "end", 10, MUTE, isoDate(max.toString()), false);
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

        // Text columns budgeted, then the arrow track takes what is left — the
        // old layout pinned the track at a hardcoded x=306 that a long rating
        // pair ("Overweight → Outperform") ran straight into.
        int rowH = 30, padT = 10;
        int h = padT + rows.size() * rowH;
        double xDate = 6, xBroker = 96, xRating = 236;
        double xS = 420, xE = W - 78;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            AnalystActions.Action a = rows.get(i);
            double y = padT + i * rowH + 14;
            text(svg, xDate, y + 4, "start", 11, MUTE, isoDate(a.dateIso()), false);
            text(svg, xBroker, y + 4, "start", 11, INK,
                    fit(a.brokerage(), xRating - xBroker - 10, 11), false);
            String rating = a.ratingOld() != null && a.ratingNew() != null
                    ? a.ratingOld() + " → " + a.ratingNew()
                    : a.ratingNew() != null ? a.ratingNew()
                    : a.ratingOld() != null ? a.ratingOld()
                    : a.actionType() != null ? a.actionType() : "";
            text(svg, xRating, y + 4, "start", 11, MUTE, fit(rating, xS - xRating - 14, 11), false);
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
                text(svg, xE + 12, y + 4, "start", 11, INK, fmt(a.targetNew(), 2), true);
            } else if (hasOld || hasNew) {
                double v = hasNew ? a.targetNew() : a.targetOld();
                double x = xS + (v - min) / span * (xE - xS);
                line(svg, x, y - 6, x, y + 6, AXIS, 2);
                text(svg, xE + 12, y + 4, "start", 11, MUTE, fmt(v, 2), false);
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

        int h = 205, padL = 18, padR = 110, padT = 30, padB = 28;
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

        // padB carries BOTH the quarter tick labels and the headroom a deep
        // miss needs for its own value label, which is drawn below its dot.
        int h = 215, padT = 32, padB = 56, padL = 20, padR = 20;
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
            text(svg, x, v >= 0 ? dotY - 11 : dotY + 18, "middle", 11, INK, pct(v), false);
            text(svg, x, h - 8, "middle", 11, MUTE, fit(q.fiscalQuarter(), slot - 8, 11), false);
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

        int h = 250, padL = 20, padR = 92, padT = 18;
        int lineH = 120;
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
        double flowBase = padT + lineH + 46;
        double flowMax = 1;
        for (HedgeFundPopularity.QuarterPoint q : rows) {
            flowMax = Math.max(flowMax, Math.max(q.newPositions(), q.closedPositions()));
        }
        double slot = (double) plotW / n;
        double barW = clamp(slot * 0.42, 7, 30);
        line(svg, padL, flowBase, padL + plotW, flowBase, AXIS, 1);
        // With many quarters the flow counts stop fitting side by side; label
        // every other bar rather than printing an unreadable digit hedge.
        int flowStep = slot < 34 ? 2 : 1;
        for (int i = 0; i < n; i++) {
            HedgeFundPopularity.QuarterPoint q = rows.get(i);
            double x = padL + slot * i + (slot - barW) / 2;
            boolean label = i % flowStep == 0 || i == n - 1;
            if (q.newPositions() > 0) {
                double len = q.newPositions() / flowMax * 30;
                svg.append(roundedTopBar(x, flowBase - len, barW, len, POS));
                if (label) {
                    text(svg, x + barW / 2, flowBase - len - 5, "middle", 10, MUTE,
                            "+" + q.newPositions(), false);
                }
            }
            if (q.closedPositions() > 0) {
                double len = q.closedPositions() / flowMax * 30;
                svg.append(roundedBottomBar(x, flowBase, barW, len, NEG));
                if (label) {
                    text(svg, x + barW / 2, flowBase + len + 12, "middle", 10, MUTE,
                            "−" + q.closedPositions(), false);
                }
            }
        }
        text(svg, padL, h - 5, "start", 10, MUTE, rows.get(0).quarterLabel(), false);
        text(svg, padL + plotW, h - 5, "end", 10, MUTE, last.quarterLabel(), false);
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
    private ChartFigure streetBandFigure(UsListingStats us, AnalystView av, MarketSnapshot s,
            de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.AnalystConsensus
                    consensus) {
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
        } else if (consensus != null && Double.isFinite(consensus.avgTargetPrice())
                && Double.isFinite(consensus.minTargetPrice())
                && Double.isFinite(consensus.maxTargetPrice())
                && consensus.minTargetPrice() > 0
                && consensus.maxTargetPrice() >= consensus.minTargetPrice()) {
            // The German band. Without this branch a DE name fell through to
            // the Consorsbank MEAN alone and the ladder degraded to two rungs -
            // the full high/consensus/low band only ever existed for US
            // listings, through the NASDAQ panel above.
            lo = consensus.minTargetPrice();
            mean = consensus.avgTargetPrice();
            hi = consensus.maxTargetPrice();
            cur = consensus.targetCurrency();
            source = "onvista";
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
        int h = 225, padT = 18, padB = 18, xL = 128, xR = W - 104;
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
        double[] raw = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            raw[i] = padT + (1 - (sorted.get(i).value() - min) / span) * plotH;
        }
        double[] ys = ladderYs(raw, 15, padT, padT + plotH);
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
        int h = 260, padL = 62, padR = 32, padT = 24, padB = 36;
        int plotW = W - padL - padR, plotH = h - padT - padB;
        String gold = "var(--ddc-sun,#c99a1e)";

        StringBuilder svg = open(h);
        for (int g = 0; g <= 2; g++) {
            double gy = padT + g * plotH / 2.0;
            line(svg, padL, gy, padL + plotW, gy, GRID, 1);
        }
        text(svg, padL - 8, padT + 4, "end", 11, MUTE, (de ? "KGV " : "P/E ") + fmt(yMax, 0), false);
        text(svg, padL - 8, padT + plotH + 4, "end", 11, MUTE, fmt(yMin, 0), false);
        text(svg, padL, h - 8, "start", 11, MUTE, compactEur(Math.pow(10, xMin)), false);
        text(svg, padL + plotW, h - 8, "end", 11, MUTE, compactEur(Math.pow(10, xMax)), false);
        // Every mark first, so no point ends up buried under a later label.
        for (Pt p : pts) {
            double x = padL + (Math.log10(p.mcap()) - xMin) / xSpan * plotW;
            double y = padT + (1 - (p.pe() - yMin) / ySpan) * plotH;
            svg.append("<circle cx=\"").append(r1(x)).append("\" cy=\"").append(r1(y))
                    .append("\" r=\"").append(p.subject() ? 7 : 5).append("\" fill=\"")
                    .append(p.subject() ? gold : S1).append("\" stroke=\"").append(SURFACE)
                    .append("\" stroke-width=\"1.5\"/>");
        }
        // Names second, with a real placement pass — above the point, else
        // below it, else dropped. Peers clustering at a similar market cap AND
        // a similar P/E used to print their names straight on top of each
        // other; an unreadable stack of three names is worse than two names.
        // The subject is placed FIRST so its own name is never the one to go.
        record Box(double x1, double y1, double x2, double y2) {}
        List<Box> placed = new ArrayList<>();
        List<Pt> order = new ArrayList<>(pts);
        order.sort((a, b) -> Boolean.compare(b.subject(), a.subject()));
        for (Pt p : order) {
            double x = padL + (Math.log10(p.mcap()) - xMin) / xSpan * plotW;
            double y = padT + (1 - (p.pe() - yMin) / ySpan) * plotH;
            String name = truncate(p.name(), 20);
            double half = textW(name, 11) / 2;
            double lx = clamp(x, padL + half, padL + plotW - half);
            for (double ly : new double[]{y - 12, y + 21}) {
                if (ly < padT + 2 || ly > padT + plotH + 6) continue;
                Box box = new Box(lx - half - 3, ly - 12, lx + half + 3, ly + 4);
                boolean hit = placed.stream().anyMatch(o -> box.x1() < o.x2() && box.x2() > o.x1()
                        && box.y1() < o.y2() && box.y2() > o.y1());
                if (hit) continue;
                placed.add(box);
                text(svg, lx, ly, "middle", 11, p.subject() ? INK : MUTE, name, p.subject());
                break;
            }
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

        int h = 150, xL = 22, xR = W - 22;
        double axisY = 112, barMaxH = 78;
        final java.time.LocalDate from = min, to = max;
        final long days = spanDays;
        java.util.function.Function<java.time.LocalDate, Double> dayX = d ->
                xL + (double) java.time.temporal.ChronoUnit.DAYS.between(from, d)
                        / days * (xR - xL);

        // Coverage per calendar month: the bar carries the COUNT, which is what
        // "wie dicht wurde berichtet" actually asks. The bare tick strip this
        // replaces drew marks on an empty axis and said nothing at all.
        java.util.Map<java.time.YearMonth, Integer> perMonth = new java.util.TreeMap<>();
        for (java.time.LocalDate d : dates) {
            perMonth.merge(java.time.YearMonth.from(d), 1, Integer::sum);
        }
        int peak = perMonth.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        StringBuilder svg = open(h);
        for (var entry : perMonth.entrySet()) {
            java.time.YearMonth ym = entry.getKey();
            java.time.LocalDate start = ym.atDay(1).isBefore(from) ? from : ym.atDay(1);
            java.time.LocalDate end = ym.atEndOfMonth().isAfter(to) ? to : ym.atEndOfMonth();
            double x1 = dayX.apply(start), x2 = dayX.apply(end);
            double bw = Math.max(x2 - x1, 4);
            double bh = Math.max((double) entry.getValue() / peak * barMaxH, 3);
            svg.append(roundedTopBar(x1, axisY - bh, bw, bh, S1));
            if (bw >= 18) {
                text(svg, x1 + bw / 2, axisY - bh - 6, "middle", 10, MUTE,
                        String.valueOf(entry.getValue()), false);
            }
        }
        line(svg, xL, axisY, xR, axisY, AXIS, 1);
        // The exact days on top of the density, so a single dated headline is
        // still locatable and a pile-up still darkens.
        for (java.time.LocalDate d : dates) {
            double x = dayX.apply(d);
            svg.append("<line x1=\"").append(r1(x)).append("\" y1=\"").append(r1(axisY - 5))
                    .append("\" x2=\"").append(r1(x)).append("\" y2=\"").append(r1(axisY + 5))
                    .append("\" stroke=\"").append(INK)
                    .append("\" stroke-width=\"1.5\" opacity=\"0.5\"/>");
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
            text(svg, xL, axisY + 20, "start", 10, MUTE, isoDate(min.toString()), false);
            text(svg, xR, axisY + 20, "end", 10, MUTE, isoDate(max.toString()), false);
        }
        boolean spansYears = min.getYear() != max.getYear();
        // Thin by what actually FITS: a month label is ~5 characters wide.
        double labelW = textW(spansYears ? "Mai 26" : "Mai", 10) + 12;
        int step = Math.max(1, (int) Math.ceil(months.size() / Math.max(1.0,
                Math.floor((xR - xL) / labelW))));
        for (int i = 0; i < months.size(); i += step) {
            java.time.LocalDate mo = months.get(i);
            double x = dayX.apply(mo);
            line(svg, x, axisY, x, axisY + 5, AXIS, 1);
            text(svg, clamp(x, xL + 14, xR - 14), axisY + 20, "middle", 10, MUTE,
                    monthShort(mo.getMonthValue())
                            + (spansYears ? " " + (mo.getYear() % 100) : ""), false);
        }
        svg.append("</svg>");
        String title = de
                ? "Presse-Dichte je Monat (" + dates.size() + " datierte Schlagzeilen)"
                : "Press coverage per month (" + dates.size() + " dated headlines)";
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

        int rowH = 32, padT = 10, textX = 46;
        int h = padT + rows.size() * rowH;
        StringBuilder svg = open(h);
        for (int i = 0; i < rows.size(); i++) {
            String line = rows.get(i);
            double cy = padT + i * rowH + rowH / 2.0 - 1;
            signalGlyph(svg, 22, cy, line);
            text(svg, textX, cy + 4, "start", 11, INK, fit(line, W - textX - 12, 11), false);
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

        // Geometry in BANDS, so nothing can ever be drawn into anything else:
        // legend band on top, then the value-label headroom, then the plot,
        // then the group-label band. The old layout put the legend baseline at
        // y=14 and the plot top at y=22 — the tallest column's value label
        // (drawn at barTop − 5) landed AT y=17, i.e. inside the legend row,
        // every single time (live: "40,0" printed over "Eigenkapitalquote").
        int padL = 20, padR = 20;
        int legendBandH = 22;          // legend baseline sits at 13 inside it
        int labelBand = 28;            // headroom the tallest value label needs, staggered
        int groupBandH = 26;           // fiscal-year tick labels beneath the axis
        int plotTop = legendBandH + labelBand;
        int h = 260;
        int plotH = h - plotTop - groupBandH - labelBand; // labelBand again for negative caps
        double baseY = plotTop + posMax / totalSpan * plotH;
        int n = rows.size();
        double slot = (double) (W - padL - padR) / n;
        // Columns FILL their slot instead of standing lost in it: a pair may
        // take three quarters of the slot, capped so two years don't render as
        // two slabs. The old cap of 20 left ~85 % of the slot empty.
        double barW = clamp((slot * 0.72 - 4) / 2, 6, 54);

        StringBuilder svg = open(h);
        // Legend (two series → always present), swatches + muted text.
        double lx = padL;
        for (int sIdx = 0; sIdx < 2; sIdx++) {
            svg.append("<rect x=\"").append(r1(lx)).append("\" y=\"").append(legendBandH - 21)
                    .append("\" width=\"10\" height=\"10\" rx=\"2\" fill=\"").append(fills[sIdx]).append("\"/>");
            text(svg, lx + 15, legendBandH - 9, "start", 11, MUTE, legendNames[sIdx], false);
            lx += 15 + textW(legendNames[sIdx], 11) + 22;
        }
        line(svg, padL, baseY, W - padR, baseY, AXIS, 1);

        for (int k = 0; k < n; k++) {
            Group g = rows.get(k);
            double groupX = padL + slot * k + (slot - (barW * 2 + 4)) / 2;
            double opacity = g.deEmphasized() ? 0.55 : 1.0;
            // A pair whose two value labels are wider than the pair itself gets
            // them STAGGERED in height rather than overprinted: each label
            // still sits directly over its own column, just on two levels.
            String[] labels = new String[2];
            for (int sIdx = 0; sIdx < 2; sIdx++) {
                double v = g.values()[sIdx];
                labels[sIdx] = Double.isFinite(v)
                        ? (thousandsEur ? compactFromThousands(v) : fmt(v, decimals)) : null;
            }
            boolean stagger = labels[0] != null && labels[1] != null
                    && (textW(labels[0], 11) + textW(labels[1], 11)) / 2 > barW + 4;
            for (int sIdx = 0; sIdx < 2; sIdx++) {
                double v = g.values()[sIdx];
                if (!Double.isFinite(v)) continue;
                double x = groupX + sIdx * (barW + 4); // 4px surface gap between the pair
                double len = Math.abs(v) / totalSpan * plotH;
                String bar = v >= 0 ? roundedTopBar(x, baseY - len, barW, len, fills[sIdx])
                        : roundedBottomBar(x, baseY, barW, len, fills[sIdx]);
                if (opacity < 1) {
                    bar = bar.replace("/>", " opacity=\"" + opacity + "\"/>");
                }
                svg.append(bar);
                double lift = stagger && sIdx == 0 ? 13 : 0;
                double ly = v >= 0 ? baseY - len - 6 - lift : baseY + len + 15 + lift;
                text(svg, x + barW / 2, ly, "middle", 11, INK, labels[sIdx], false);
            }
            text(svg, groupX + barW + 2, h - 8, "middle", 11,
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
                .append("\" text-anchor=\"").append(anchor)
                .append("\" font-size=\"").append(Math.max(size, MIN_FONT))
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

    /**
     * Rough advance width of a label — enough to DECIDE a collision, not to
     * typeset one. Inter's mixed-case average sits near 0.55 em, digits and
     * separators a touch above; 0.58 keeps the estimate on the safe side.
     */
    private static double textW(String s, int size) {
        return s == null ? 0 : s.length() * Math.max(size, MIN_FONT) * 0.58;
    }

    /**
     * The ladder collision pass shared by every level figure: y positions
     * sorted by value descending, pushed at least {@code gap} apart, then the
     * WHOLE stack shifted back up when the pushing ran it past the plot floor
     * (the old pass only ever pushed down, so a crowded ladder walked its
     * lowest rung out of the figure).
     */
    private static double[] ladderYs(double[] raw, double gap, double top, double bottom) {
        double[] ys = raw.clone();
        for (int i = 1; i < ys.length; i++) {
            if (ys[i] - ys[i - 1] < gap) ys[i] = ys[i - 1] + gap;
        }
        double overflow = ys.length == 0 ? 0 : ys[ys.length - 1] - bottom;
        if (overflow > 0) {
            double shift = Math.min(overflow, ys[0] - top);
            if (shift > 0) {
                for (int i = 0; i < ys.length; i++) ys[i] -= shift;
            }
        }
        return ys;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1).stripTrailing() + "…";
    }

    /**
     * Truncates a label to what actually FITS a box of the given width — the
     * tile strips used to set their big values with no budget at all, so a
     * long one simply ran into the next column.
     */
    /**
     * The largest size in {@code [MIN_FONT, max]} at which the label still fits
     * the box. A tile's VALUE is a number, and a truncated number is a lie
     * ("991,80 / 992,…"): it gives up type size before it gives up digits.
     */
    private static int fitSize(String s, double width, int max) {
        for (int size = max; size > MIN_FONT; size--) {
            if (textW(s, size) <= width) return size;
        }
        return MIN_FONT;
    }

    private static String fit(String s, double width, int size) {
        if (s == null) return "";
        int max = (int) Math.floor(width / (Math.max(size, MIN_FONT) * 0.58));
        return max < 2 ? "" : truncate(s, max);
    }

    private static String r1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
