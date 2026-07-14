package de.bsommerfeld.wsbg.terminal.yahoofinance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v8/chart OHLCV column parse behind the market memory: null-close
 * padding skipped, missing open/high/low become NaN, missing volume 0,
 * timestamps map to UTC dates.
 */
class YahooBarsParserTest {

    @Test
    void parsesColumnsSkipsNullCloseRows() {
        // Three timestamps: a full bar, a null-close pad (skipped), a bar with
        // missing open/volume. 1600732800 = 2020-09-22T00:00Z.
        String body = """
            {"chart":{"result":[{
              "meta":{"symbol":"SAP.DE"},
              "timestamp":[1600732800,1600819200,1600905600],
              "indicators":{"quote":[{
                "open":[132.1,null,null],
                "high":[135.0,null,134.2],
                "low":[131.5,null,132.8],
                "close":[134.6,null,133.9],
                "volume":[1200000,null,null]
              }]}
            }],"error":null}}
            """;
        List<Bar> bars = YahooResponseParser.parseBars(body);
        assertEquals(2, bars.size());
        Bar first = bars.get(0);
        assertEquals(LocalDate.of(2020, 9, 22), first.date());
        assertEquals(132.1, first.open(), 1e-9);
        assertEquals(134.6, first.close(), 1e-9);
        assertEquals(1_200_000L, first.volume());
        Bar second = bars.get(1);
        assertEquals(133.9, second.close(), 1e-9);
        assertTrue(Double.isNaN(second.open()));
        assertEquals(0L, second.volume());
    }

    @Test
    void emptyOrErrorBodiesYieldEmpty() {
        assertTrue(YahooResponseParser.parseBars("{\"chart\":{\"result\":[],\"error\":null}}").isEmpty());
        assertTrue(YahooResponseParser.parseBars("{\"chart\":{\"result\":[{\"meta\":{}}],\"error\":null}}").isEmpty());
        assertTrue(YahooResponseParser.parseBars("not json").isEmpty());
    }
}
