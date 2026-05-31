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
}
