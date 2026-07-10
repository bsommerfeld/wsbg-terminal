package de.bsommerfeld.wsbg.terminal.currency;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EurUsdClientTest {

    private final EurUsdClient client = new EurUsdClient(10);

    @Test
    void parsesYahooChartResponse() {
        String body = """
                {
                  "chart": {
                    "result": [
                      {
                        "meta": {
                          "symbol": "EURUSD=X",
                          "regularMarketPrice": 1.0876
                        }
                      }
                    ],
                    "error": null
                  }
                }
                """;

        Optional<Double> rate = client.parseYahoo(body);

        assertTrue(rate.isPresent());
        assertEquals(1.0876, rate.get(), 1e-6);
    }

    @Test
    void parsesFrankfurterResponse() {
        String body = """
                {
                  "amount": 1.0,
                  "base": "EUR",
                  "date": "2026-05-23",
                  "rates": { "USD": 1.0832 }
                }
                """;

        Optional<Double> rate = client.parseFrankfurter(body);

        assertTrue(rate.isPresent());
        assertEquals(1.0832, rate.get(), 1e-6);
    }

    @Test
    void rejectsYahooWithEmptyResult() {
        String body = """
                { "chart": { "result": [], "error": null } }
                """;

        assertTrue(client.parseYahoo(body).isEmpty());
    }

    @Test
    void rejectsYahooWithMissingPrice() {
        String body = """
                {
                  "chart": {
                    "result": [{ "meta": { "symbol": "EURUSD=X" } }]
                  }
                }
                """;

        assertTrue(client.parseYahoo(body).isEmpty());
    }

    @Test
    void rejectsFrankfurterWithoutUsdRate() {
        String body = """
                { "base": "EUR", "rates": { "GBP": 0.85 } }
                """;

        assertTrue(client.parseFrankfurter(body).isEmpty());
    }

    @Test
    void rejectsMalformedJson() {
        assertTrue(client.parseYahoo("not json").isEmpty());
        assertTrue(client.parseFrankfurter("not json").isEmpty());
    }

    @Test
    void rejectsOutOfBandRate() {
        // EUR/USD has never traded outside ~0.85–1.60; reject extremes.
        String absurd = """
                {
                  "chart": {
                    "result": [{ "meta": { "symbol": "EURUSD=X", "regularMarketPrice": 42.0 } }]
                  }
                }
                """;
        assertTrue(client.parseYahoo(absurd).isEmpty());

        String inverted = """
                { "base": "EUR", "rates": { "USD": 0.0009 } }
                """;
        assertTrue(client.parseFrankfurter(inverted).isEmpty());
    }

    @Test
    void parsesDetailedYahooChartWithSeriesAndMeta() {
        String body = """
                {
                  "chart": {
                    "result": [
                      {
                        "meta": {
                          "symbol": "EURUSD=X",
                          "regularMarketPrice": 1.1435,
                          "previousClose": 1.1401,
                          "regularMarketDayHigh": 1.1460,
                          "regularMarketDayLow": 1.1388,
                          "fiftyTwoWeekHigh": 1.1610,
                          "fiftyTwoWeekLow": 1.0180
                        },
                        "timestamp": [1751900000, 1751900300, 1751900600],
                        "indicators": { "quote": [ { "close": [1.1401, null, 1.1435] } ] }
                      }
                    ],
                    "error": null
                  }
                }
                """;

        var fx = client.parseYahooDetailed(body).orElseThrow();

        assertEquals(1.1435, fx.rate(), 1e-6);
        assertEquals(1.1401, fx.previousClose(), 1e-6);
        assertEquals(1.1460, fx.dayHigh(), 1e-6);
        assertEquals(1.1388, fx.dayLow(), 1e-6);
        assertEquals(1.1610, fx.week52High(), 1e-6);
        assertEquals(1.0180, fx.week52Low(), 1e-6);
        // The null close point is skipped, valid ones become [epochMs, rate] pairs.
        assertEquals(2, fx.spark().size());
        assertEquals(1751900000000.0, fx.spark().get(0)[0], 1e-3);
        assertEquals(1.1401, fx.spark().get(0)[1], 1e-6);
        assertEquals(1.1435, fx.spark().get(1)[1], 1e-6);
    }

    @Test
    void detailedParseDerivesDayRangeFromSeriesWhenMetaLacksIt() {
        String body = """
                {
                  "chart": {
                    "result": [
                      {
                        "meta": { "regularMarketPrice": 1.1435 },
                        "timestamp": [1, 2, 3],
                        "indicators": { "quote": [ { "close": [1.1400, 1.1470, 1.1435] } ] }
                      }
                    ]
                  }
                }
                """;

        var fx = client.parseYahooDetailed(body).orElseThrow();

        assertEquals(1.1470, fx.dayHigh(), 1e-6);
        assertEquals(1.1400, fx.dayLow(), 1e-6);
        assertEquals(null, fx.week52High());
    }

    @Test
    void detailedParseSurvivesMetaOnlyBody() {
        String body = """
                { "chart": { "result": [{ "meta": { "regularMarketPrice": 1.0876 } }] } }
                """;

        var fx = client.parseYahooDetailed(body).orElseThrow();

        assertEquals(1.0876, fx.rate(), 1e-6);
        assertTrue(fx.spark().isEmpty());
        assertEquals(null, fx.dayHigh());
    }

    @Test
    void parsesEcbHistoryChronologically() {
        String body = """
                {
                  "base": "EUR", "start_date": "2026-06-10", "end_date": "2026-06-12",
                  "rates": {
                    "2026-06-12": { "USD": 1.1567 },
                    "2026-06-10": { "USD": 1.1539 },
                    "2026-06-11": { "USD": 1.1537 }
                  }
                }
                """;

        var hist = client.parseEcbHistory(body).orElseThrow();

        assertEquals(3, hist.points().size());
        assertEquals(1.1539, hist.points().get(0)[1], 1e-6); // oldest first
        assertEquals("2026-06-12", hist.latestDate());
        assertEquals(1.1567, hist.latestRate(), 1e-6);
        assertTrue(hist.points().get(0)[0] < hist.points().get(2)[0]);
    }

    @Test
    void rejectsEcbHistoryWithoutRates() {
        assertTrue(client.parseEcbHistory("{ \"base\": \"EUR\", \"rates\": {} }").isEmpty());
        assertTrue(client.parseEcbHistory("not json").isEmpty());
    }
}
