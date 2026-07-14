package de.bsommerfeld.wsbg.terminal.marketbeat;

import de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.Action;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActions.UsShortStats;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests against trimmed LIVE fixtures (fetched 2026-07-14):
 * /stocks/NASDAQ/AAPL/forecast|short-interest/, /stocks/LON/RR/forecast/,
 * /ratings/, /ratings/uk/, and the redirected ETR/RHM overview (the miss shape).
 */
class MarketBeatParserTest {

    private static String fixture(String name) {
        try (InputStream in = MarketBeatParserTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- forecast: analyst action history ----

    @Test
    void forecastRowsParse() {
        List<Action> actions = MarketBeatClient.parseForecastActions(fixture("forecast-aapl.html"));
        assertEquals(10, actions.size());
        Action first = actions.get(0);
        assertEquals("2026-07-13", first.dateIso());
        assertEquals("Citigroup", first.brokerage());
        assertEquals("Atif Malik", first.analyst());
        assertEquals("Reiterated Rating", first.actionType());
        assertEquals("Buy", first.ratingOld());
        assertEquals("Buy", first.ratingNew());
        assertEquals(315.00, first.targetOld(), 1e-9);
        assertEquals(365.00, first.targetNew(), 1e-9);
        assertEquals("USD", first.targetCurrency());
        assertNull(first.symbol());
        assertNull(first.companyName());
    }

    @Test
    void forecastRowWithoutAnalystOrTargets() {
        List<Action> actions = MarketBeatClient.parseForecastActions(fixture("forecast-aapl.html"));
        Action evercore = actions.get(1);
        assertEquals("Evercore", evercore.brokerage());
        assertNull(evercore.analyst());
        assertTrue(Double.isNaN(evercore.targetOld()));
        assertTrue(Double.isNaN(evercore.targetNew()));
        assertNull(evercore.targetCurrency());
    }

    @Test
    void forecastDowngradeSplitsOldNewAndZeroOldTargetIsNaN() {
        List<Action> actions = MarketBeatClient.parseForecastActions(fixture("forecast-aapl.html"));
        Action kgi = actions.get(3);
        assertEquals("KGI Securities", kgi.brokerage());
        assertEquals("Downgrade", kgi.actionType());
        assertEquals("Outperform", kgi.ratingOld());
        assertEquals("Hold", kgi.ratingNew());
        assertTrue(Double.isNaN(kgi.targetOld()), "$0.00 marks 'no prior target'");
        assertEquals(315.00, kgi.targetNew(), 1e-9);
        assertEquals("USD", kgi.targetCurrency());
    }

    @Test
    void forecastUpsellMarkupInAnalystCellKeepsCleanName() {
        List<Action> actions = MarketBeatClient.parseForecastActions(fixture("forecast-aapl.html"));
        Action bofa = actions.get(4);
        assertEquals("Bank of America", bofa.brokerage());
        assertEquals("Wamsi Mohan", bofa.analyst());
        assertEquals(380.00, bofa.targetNew(), 1e-9);
    }

    @Test
    void forecastGbxTargetsAndNaOldRating() {
        List<Action> actions = MarketBeatClient.parseForecastActions(fixture("forecast-lon-rr.html"));
        assertEquals(4, actions.size());
        Action jpm = actions.get(0);
        assertEquals("2026-06-29", jpm.dateIso());
        assertEquals("JPMorgan Chase & Co.", jpm.brokerage());
        assertEquals("Boost Target", jpm.actionType());
        assertNull(jpm.ratingOld(), "N/A half maps to null");
        assertEquals("Overweight", jpm.ratingNew());
        assertEquals(1500.0, jpm.targetOld(), 1e-9);
        assertEquals(1625.0, jpm.targetNew(), 1e-9);
        assertEquals("GBX", jpm.targetCurrency());
    }

    @Test
    void forecastUpsellRowIsSkippedNotFatal() {
        String table = "<table id=\"history-table\"><thead><tr><th>x</th></tr></thead><tbody>"
                + "<tr><td colspan=\"7\">Subscribe to All Access to unlock more rows</td></tr>"
                + "<tr><td data-sort-value=\"20260701093000\">7/1/2026</td>"
                + "<td data-clean=\"Wedbush|0\">Wedbush</td>"
                + "<td data-clean=\"|\"></td>"
                + "<td>Upgrade</td>"
                + "<td data-clean=\"Hold|Buy\">Hold &#x279D; Buy</td>"
                + "<td data-clean=\"$10.00|$20.00\">$10.00 &#x279D; $20.00</td>"
                + "</tr></tbody></table>";
        List<Action> actions = MarketBeatClient.parseForecastActions(table);
        assertEquals(1, actions.size());
        assertEquals("Wedbush", actions.get(0).brokerage());
        assertEquals("Upgrade", actions.get(0).actionType());
    }

    @Test
    void forecastMissingTableIsEmpty() {
        assertTrue(MarketBeatClient.parseForecastActions(fixture("overview-miss.html")).isEmpty());
    }

    // ---- forecast: consensus header ----

    @Test
    void consensusParsesUsd() {
        MarketBeatClient.Consensus c = MarketBeatClient.parseConsensus(fixture("forecast-aapl.html"));
        assertEquals("Moderate Buy", c.rating());
        assertEquals(316.32, c.target(), 1e-9);
        assertEquals("USD", c.currency());
    }

    @Test
    void consensusParsesGbx() {
        MarketBeatClient.Consensus c = MarketBeatClient.parseConsensus(fixture("forecast-lon-rr.html"));
        assertEquals("Moderate Buy", c.rating());
        assertEquals(1447.20, c.target(), 1e-9);
        assertEquals("GBX", c.currency());
    }

    @Test
    void consensusMissOnOverviewPage() {
        MarketBeatClient.Consensus c = MarketBeatClient.parseConsensus(fixture("overview-miss.html"));
        assertTrue(c.isEmpty());
    }

    // ---- short interest ----

    @Test
    void shortInterestFactsParse() {
        UsShortStats s = MarketBeatClient.parseShortInterest(fixture("short-interest-aapl.html"));
        assertNotNull(s);
        assertEquals(140_526_320L, s.currentShares());
        assertEquals(144_248_476L, s.priorShares());
        assertEquals(40.66e9, s.dollarVolumeUsd(), 1e3);
        assertEquals(1.7, s.daysToCover(), 1e-9);
        assertEquals(0.96, s.percentOfFloat(), 1e-9);
        assertEquals("2026-06-30", s.settlementDateIso());
    }

    @Test
    void shortInterestMissOnOverviewPage() {
        assertNull(MarketBeatClient.parseShortInterest(fixture("overview-miss.html")));
    }

    // ---- daily ratings table ----

    @Test
    void dailyTableUsParses() {
        List<Action> actions = MarketBeatClient.parseDailyTable(fixture("ratings-daily.html"), "2026-07-14");
        assertEquals(2, actions.size());
        Action crcl = actions.get(0);
        assertEquals("CRCL", crcl.symbol());
        assertEquals("Circle Internet Group", crcl.companyName());
        assertEquals("2026-07-14", crcl.dateIso());
        assertEquals("Target Set by", crcl.actionType());
        assertEquals("Mizuho", crcl.brokerage());
        assertEquals("Dan Dolev", crcl.analyst());
        assertTrue(Double.isNaN(crcl.targetOld()));
        assertEquals(50.00, crcl.targetNew(), 1e-9);
        assertEquals("USD", crcl.targetCurrency());
        assertNull(crcl.ratingOld(), "N/A rating maps to null");
        assertNull(crcl.ratingNew());
        Action swks = actions.get(1);
        assertEquals("SWKS", swks.symbol());
        assertEquals("Downgraded by", swks.actionType());
    }

    @Test
    void dailyTableUkParsesGbxAndZeroOldTarget() {
        List<Action> actions = MarketBeatClient.parseDailyTable(fixture("ratings-daily-uk.html"), "2026-07-14");
        assertEquals(2, actions.size());
        Action acg = actions.get(0);
        assertEquals("ACG", acg.symbol());
        assertEquals("ACG Acquisition", acg.companyName());
        assertEquals("Reiterated by", acg.actionType());
        assertEquals("Berenberg Bank", acg.brokerage());
        assertNull(acg.analyst());
        assertTrue(Double.isNaN(acg.targetOld()), "GBX 0 marks 'no prior target'");
        assertEquals(2300.0, acg.targetNew(), 1e-9);
        assertEquals("GBX", acg.targetCurrency());
        assertNull(acg.ratingOld());
        assertEquals("Buy", acg.ratingNew());
    }

    @Test
    void dailyTableGarbageRowSkippedNotFatal() {
        String table = "<table class=\"scroll-table sort-table\"><thead><tr><th>x</th></tr></thead><tbody>"
                + "<tr><td colspan=\"8\"><a href=\"/subscribe/\">Unlock all of today's ratings</a></td></tr>"
                + "</tbody></table>";
        assertTrue(MarketBeatClient.parseDailyTable(table, "2026-07-14").isEmpty());
        assertTrue(MarketBeatClient.parseDailyTable("<html>no table</html>", "2026-07-14").isEmpty());
    }

    // ---- routing ----

    @Test
    void routesUsShapesToNasdaqGuess() {
        assertEquals("NASDAQ/AAPL", MarketBeatClient.routePath("AAPL"));
        assertEquals("NASDAQ/OTLK", MarketBeatClient.routePath("otlk"));
        assertEquals("NASDAQ/BRK.B", MarketBeatClient.routePath("BRK.B"));
    }

    @Test
    void routesVenueSuffixes() {
        assertEquals("ETR/RHM", MarketBeatClient.routePath("RHM.DE"));
        assertEquals("ETR/ENR", MarketBeatClient.routePath("enr.de"));
        assertEquals("LON/RR", MarketBeatClient.routePath("RR.L"));
    }

    @Test
    void gatesGarbageSymbolsWithoutNetwork() {
        assertNull(MarketBeatClient.routePath(null));
        assertNull(MarketBeatClient.routePath(""));
        assertNull(MarketBeatClient.routePath("^GDAXI"));
        assertNull(MarketBeatClient.routePath("BTC-USD"));
        assertNull(MarketBeatClient.routePath("EURUSD=X"));
        assertNull(MarketBeatClient.routePath("CC=F"));
        assertNull(MarketBeatClient.routePath("005930.KS"));
        assertNull(MarketBeatClient.routePath("TOOLONGSYM"));
    }

    @Test
    void ratingsPathMapsCountries() {
        assertEquals("/ratings/", MarketBeatClient.ratingsPath(null));
        assertEquals("/ratings/", MarketBeatClient.ratingsPath(""));
        assertEquals("/ratings/", MarketBeatClient.ratingsPath("US"));
        assertEquals("/ratings/uk/", MarketBeatClient.ratingsPath("UK"));
        assertEquals("/ratings/canada/", MarketBeatClient.ratingsPath("Canada"));
        assertNull(MarketBeatClient.ratingsPath("../evil"));
    }

    // ---- value helpers ----

    @Test
    void moneyHalves() {
        assertEquals(315.00, MarketBeatClient.parseMoney("$315.00").value(), 1e-9);
        assertEquals("USD", MarketBeatClient.parseMoney("$315.00").currency());
        assertEquals(1500.0, MarketBeatClient.parseMoney("GBX 1,500").value(), 1e-9);
        assertEquals("GBX", MarketBeatClient.parseMoney("GBX 1,500").currency());
        assertEquals("CAD", MarketBeatClient.parseMoney("C$12.50").currency());
        assertEquals("EUR", MarketBeatClient.parseMoney("€10.50").currency());
        assertTrue(Double.isNaN(MarketBeatClient.parseMoney("$0.00").value()));
        assertTrue(Double.isNaN(MarketBeatClient.parseMoney("0").value()));
        assertTrue(Double.isNaN(MarketBeatClient.parseMoney("N/A").value()));
        assertTrue(Double.isNaN(MarketBeatClient.parseMoney("").value()));
        assertTrue(Double.isNaN(MarketBeatClient.parseMoney(null).value()));
    }

    @Test
    void usNumberAndDateQuirks() {
        assertEquals(140_526_320L, MarketBeatClient.parseShares("140,526,320 shares"));
        assertEquals(-1L, MarketBeatClient.parseShares("n/a"));
        assertEquals(40.66e9, MarketBeatClient.parseDollarAmount("$40.66 billion"), 1e3);
        assertEquals(12.5e6, MarketBeatClient.parseDollarAmount("$12.5 million"), 1e-3);
        assertEquals(1.7, MarketBeatClient.leadingDouble("1.7 Days to Cover"), 1e-9);
        assertEquals(0.96, MarketBeatClient.leadingDouble("0.96%"), 1e-9);
        assertEquals("2026-06-30", MarketBeatClient.parseLongDate("June 30, 2026"));
        assertEquals("2026-07-13", MarketBeatClient.parseLongDate("7/13/2026"));
        assertNull(MarketBeatClient.parseLongDate("soon"));
        assertEquals("2026-07-13", MarketBeatClient.dateFromSortValue("data-sort-value=\"20260713100028\""));
        assertNull(MarketBeatClient.dateFromSortValue("data-sort-value=\"3\""));
    }

    @Test
    void actionCapIsRunawayBackstop() {
        StringBuilder table = new StringBuilder(
                "<table id=\"history-table\"><thead><tr><th>x</th></tr></thead><tbody>");
        for (int i = 0; i < 40; i++) {
            table.append("<tr><td data-sort-value=\"20260701093000\">7/1/2026</td>")
                    .append("<td data-clean=\"Firm").append(i).append("|0\">Firm</td>")
                    .append("<td data-clean=\"|\"></td>")
                    .append("<td>Upgrade</td>")
                    .append("<td data-clean=\"Hold|Buy\">Hold</td>")
                    .append("<td data-clean=\"$10.00|$20.00\">$10</td></tr>");
        }
        table.append("</tbody></table>");
        assertEquals(25, MarketBeatClient.parseForecastActions(table.toString()).size());
        assertFalse(MarketBeatClient.parseForecastActions(table.toString()).isEmpty());
    }
}
