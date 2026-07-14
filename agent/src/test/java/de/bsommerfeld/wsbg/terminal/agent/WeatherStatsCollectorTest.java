package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ISIN shape guard that decides whether a frozen snapshot's symbol may be
 * sent to Tradegate: L&S snapshots carry a real ISIN there, Yahoo snapshots
 * carry Yahoo symbols ({@code ^GDAXI}, {@code BTC-USD}, {@code EURUSD=X}) that
 * must never turn into a venue call.
 */
class WeatherStatsCollectorTest {

    @Test
    void realIsinsPass() {
        assertTrue(WeatherStatsCollector.looksLikeIsin("US67066G1040")); // NVIDIA
        assertTrue(WeatherStatsCollector.looksLikeIsin("DE000ENER6Y0")); // Siemens Energy
    }

    @Test
    void yahooSymbolsAndJunkAreRejected() {
        assertFalse(WeatherStatsCollector.looksLikeIsin("^GDAXI"));
        assertFalse(WeatherStatsCollector.looksLikeIsin("BTC-USD"));
        assertFalse(WeatherStatsCollector.looksLikeIsin("EURUSD=X"));
        assertFalse(WeatherStatsCollector.looksLikeIsin("NVDA"));
        assertFalse(WeatherStatsCollector.looksLikeIsin(""));
        assertFalse(WeatherStatsCollector.looksLikeIsin(null));
        assertFalse(WeatherStatsCollector.looksLikeIsin("US67066G104X")); // check digit must be a digit
    }

    @org.junit.jupiter.api.Test
    void thesisSentenceReadsTheFirstSentenceOfTheThesisSection() {
        String report = "## Worum es geht\nEin Konzern.\n\n## These\nSAP bleibt der defensive"
                + " Anker des Käfigs, weil die Cloud-Erlöse zweistellig wachsen. Zweiter Satz.\n"
                + "\n## Lage\nText.";
        org.junit.jupiter.api.Assertions.assertEquals(
                "SAP bleibt der defensive Anker des Käfigs, weil die Cloud-Erlöse zweistellig"
                        + " wachsen.",
                WeatherStatsCollector.thesisSentence(report));
        org.junit.jupiter.api.Assertions.assertNull(
                WeatherStatsCollector.thesisSentence("## Lage\nKein These-Abschnitt."));
        org.junit.jupiter.api.Assertions.assertNull(WeatherStatsCollector.thesisSentence(null));
    }

    @org.junit.jupiter.api.Test
    void tnxNormalizationHandlesBothYahooEncodings() {
        // The CBOE definition: yield ×10 ("46.09" → 4.609 %).
        org.junit.jupiter.api.Assertions.assertEquals(4.609,
                WeatherStatsCollector.normalizeTnx(46.09), 1e-9);
        // The plain-yield encoding Yahoo served live 2026-07-13 ("4.61" → 4.61 %).
        org.junit.jupiter.api.Assertions.assertEquals(4.61,
                WeatherStatsCollector.normalizeTnx(4.61), 1e-9);
    }

    // --- the street-actions freeze (MarketBeat daily ratings) ---------------

    private static de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.Action action(
            String symbol, String company, String actionType, String brokerage,
            String ratingOld, String ratingNew, double targetOld, double targetNew) {
        return new de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.Action(
                symbol, company, "2026-07-14", brokerage, null, actionType,
                ratingOld, ratingNew, targetOld, targetNew,
                Double.isFinite(targetNew) || Double.isFinite(targetOld) ? "USD" : null);
    }

    @org.junit.jupiter.api.Test
    void streetActionsKeepSubstantiveRowsOnly() {
        java.util.List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.StreetActionStat> out =
                WeatherStatsCollector.streetActions(java.util.List.of(
                        // Up-/downgrades and initiations count regardless of targets.
                        action("AAPL", "Apple", "Upgraded by", "Morgan Stanley",
                                "Hold", "Buy", Double.NaN, Double.NaN),
                        action("MSFT", "Microsoft", "Initiated Coverage", "Baird",
                                null, "Outperform", Double.NaN, Double.NaN),
                        // A target move needs BOTH halves...
                        action("WMT", "Walmart", "Boost Target", "UBS",
                                null, null, 95.0, 110.0),
                        // ...a lone new target is noise, a bare reiteration too.
                        action("KO", "Coca-Cola", "Set Target", "Citi",
                                null, null, Double.NaN, 70.0),
                        action("PEP", "PepsiCo", "Reiterated Rating", "Citi",
                                null, null, Double.NaN, Double.NaN),
                        // A symbol-less row can join nothing and is dropped.
                        action(null, "Ghost Corp", "Upgraded by", "Ghost",
                                null, "Buy", Double.NaN, Double.NaN)),
                        java.util.Set.of());
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("AAPL", "MSFT", "WMT"),
                out.stream().map(
                        de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.StreetActionStat::symbol)
                        .toList());
        // NaN targets arrive as null, present ones as boxed values.
        org.junit.jupiter.api.Assertions.assertNull(out.get(0).targetNew());
        org.junit.jupiter.api.Assertions.assertEquals(95.0, out.get(2).targetOld());
        org.junit.jupiter.api.Assertions.assertEquals(110.0, out.get(2).targetNew());
        org.junit.jupiter.api.Assertions.assertEquals("USD", out.get(2).targetCurrency());
    }

    @org.junit.jupiter.api.Test
    void streetActionsRankCageThenRatingMovesAndCapAtFifteen() {
        java.util.List<de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.Action> rows =
                new java.util.ArrayList<>();
        // 14 anonymous target moves lead the provider order...
        for (int i = 0; i < 14; i++) {
            rows.add(action("T" + i, "Target Corp " + i, "Boost Target", "Broker",
                    null, null, 10.0 + i, 12.0 + i));
        }
        // ...then a non-cage downgrade, and LAST a cage-discussed upgrade.
        rows.add(action("XOM", "Exxon", "Downgraded by", "Wells Fargo",
                "Overweight", "Equal Weight", 130.0, 115.0));
        rows.add(action("NVDA", "NVIDIA", "Upgraded by", "Melius",
                "Hold", "Buy", 120.0, 200.0));

        java.util.List<de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.StreetActionStat> out =
                WeatherStatsCollector.streetActions(rows, java.util.Set.of("NVDA"));

        // Cap 15 of 16 substantive rows; the cage paper leads, the rating move
        // outranks the target moves, the LAST provider-order target move drops.
        org.junit.jupiter.api.Assertions.assertEquals(15, out.size());
        org.junit.jupiter.api.Assertions.assertEquals("NVDA", out.get(0).symbol());
        org.junit.jupiter.api.Assertions.assertTrue(out.get(0).inKaefig());
        org.junit.jupiter.api.Assertions.assertEquals("XOM", out.get(1).symbol());
        org.junit.jupiter.api.Assertions.assertFalse(out.get(1).inKaefig());
        // Stable within the target-move tier: T0..T12 survive, T13 is the drop.
        org.junit.jupiter.api.Assertions.assertEquals("T0", out.get(2).symbol());
        org.junit.jupiter.api.Assertions.assertTrue(out.stream().noneMatch(
                a -> "T13".equals(a.symbol())));
    }

    @org.junit.jupiter.api.Test
    void substantiveActionReadsTheVerbatimLabels() {
        assertTrue(WeatherStatsCollector.substantiveAction(action("A", null, "Upgraded by",
                "X", null, "Buy", Double.NaN, Double.NaN)));
        assertTrue(WeatherStatsCollector.substantiveAction(action("A", null, "Downgraded by",
                "X", "Buy", "Hold", Double.NaN, Double.NaN)));
        assertTrue(WeatherStatsCollector.substantiveAction(action("A", null,
                "Initiated Coverage by", "X", null, "Buy", Double.NaN, Double.NaN)));
        assertTrue(WeatherStatsCollector.substantiveAction(action("A", null, "Target Set by",
                "X", null, null, 10.0, 12.0)));
        assertFalse(WeatherStatsCollector.substantiveAction(action("A", null, "Target Set by",
                "X", null, null, Double.NaN, 12.0)));
        assertFalse(WeatherStatsCollector.substantiveAction(action("A", null,
                "Reiterated Rating", "X", null, null, Double.NaN, Double.NaN)));
        assertFalse(WeatherStatsCollector.substantiveAction(action("A", null, null,
                "X", null, null, Double.NaN, Double.NaN)));
    }
}
