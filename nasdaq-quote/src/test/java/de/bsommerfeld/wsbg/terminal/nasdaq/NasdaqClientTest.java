package de.bsommerfeld.wsbg.terminal.nasdaq;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the NASDAQ.com quote/info response (USD). No network. */
class NasdaqClientTest {

    private final NasdaqClient client = new NasdaqClient();

    @Test
    void parsesRegularSessionPrimaryData() {
        String body = """
            {"data":{"symbol":"AAPL","primaryData":{"lastSalePrice":"$214.25",
              "netChange":"+2.10","percentageChange":"+0.99%","deltaIndicator":"up","isRealTime":true},
              "secondaryData":null}}
            """;
        Optional<MarketSnapshot> s = client.parse(body, "AAPL");
        assertTrue(s.isPresent());
        assertEquals(214.25, s.get().price(), 1e-9);
        assertEquals(0.99, s.get().dayChangePercent(), 1e-9);
        assertEquals("USD", s.get().currency());
        assertEquals("NASDAQ", s.get().exchangeName());
    }

    @Test
    void prefersRealTimePrimaryOverClosedSecondary() {
        // Reality (Micron after-hours): primaryData is the LIVE after-hours print
        // (isRealTime:true); secondaryData is the prior CLOSE (isRealTime:false).
        String body = """
            {"data":{"symbol":"MU",
              "primaryData":{"lastSalePrice":"$1,214.40","percentageChange":"+15.89%","isRealTime":true},
              "secondaryData":{"lastSalePrice":"$1,047.92","percentageChange":"-0.37%","isRealTime":false}}}
            """;
        Optional<MarketSnapshot> s = client.parse(body, "MU");
        assertTrue(s.isPresent());
        assertEquals(1214.40, s.get().price(), 1e-9, "the live after-hours price, not the stale close");
        assertEquals(15.89, s.get().dayChangePercent(), 1e-9);
    }

    @Test
    void rejectsMissingPrice() {
        assertFalse(client.parse("{\"data\":{\"primaryData\":{\"lastSalePrice\":\"N/A\"}}}", "X").isPresent());
    }

    @Test
    void parsesChartSparkFromYAndZValue() {
        String body = """
            {"data":{"symbol":"AAPL","chart":[
               {"x":1,"y":214.0},{"x":2,"z":{"value":"215.50"}},{"x":3,"y":216.25}]}}
            """;
        assertEquals(List.of(214.0, 215.50, 216.25), NasdaqClient.parseChartSpark(body));
    }

    @Test
    void emptyChartYieldsNoSpark() {
        assertEquals(List.of(), NasdaqClient.parseChartSpark("{\"data\":{\"chart\":[]}}"));
        assertEquals(List.of(), NasdaqClient.parseChartSpark("garbage"));
    }

    @Test
    void skipsNonUsSymbols() {
        // dotted/suffixed symbols aren't bare US listings — no request is made.
        assertFalse(client.fetchSnapshot("RHM.DE").isPresent());
        assertFalse(client.fetchSnapshot("BTC-USD").isPresent());
    }
}
