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
}
