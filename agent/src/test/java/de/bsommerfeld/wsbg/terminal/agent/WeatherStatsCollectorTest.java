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
}
