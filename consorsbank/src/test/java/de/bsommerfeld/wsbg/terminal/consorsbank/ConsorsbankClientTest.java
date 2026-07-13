package de.bsommerfeld.wsbg.terminal.consorsbank;

import de.bsommerfeld.wsbg.terminal.core.price.AnalystView;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Consorsbank financial-info response shapes (probed live 2026-07-12):
 * a covered stock answers a root ARRAY with flat ALL-CAPS RecommendationV1
 * fields + EventsV1.ITEMS; an ETF/fund answers HTTP 200 with every field null
 * and an Info.Errors entry — a structured miss, not an error.
 */
class ConsorsbankClientTest {

    /** Live shape for Rheinmetall (DE0007030009), trimmed to two events. */
    private static final String HIT = """
            [{"EventsV1":{"AMOUNT":2,"ITEMS":[
              {"DATETIME_EVENT":"2026-08-06T00:00:00+0000","EVENT_TYPE":"RESULTS",
               "NAME_EVENT":"Geschäftsbericht","TITLE":"Rheinmetall AG: Bericht 2. Quartal 2026"},
              {"DATETIME_EVENT":"2026-11-05T00:00:00+0000","EVENT_TYPE":"RESULTS",
               "NAME_EVENT":"Geschäftsbericht","TITLE":"Rheinmetall AG: Bericht 3. Quartal 2026"}],
              "PAGE_INDEX":0,"PAGE_SIZE":10,"TOTAL_AMOUNT":2,"TOTAL_PAGES":1},
             "Info":{"ID":"DE0007030009"},
             "RecommendationV1":{"BUY_M3":16,"BUY_RECENT":19,"CONSENSUS":4,
              "DATETIME_LAST_UPDATE":"2026-07-10T22:00:00+0000","DOWN":3,
              "EXPECTED_PERFORMANCE_PCT":73.38709677419355,"HOLD_M3":6,"HOLD_RECENT":5,
              "ISO_CURRENCY":"EUR","OVERWEIGHT_M3":3,"OVERWEIGHT_RECENT":3,
              "SELL_M3":0,"SELL_RECENT":0,"TARGET_PRICE":1720.0,"TOTAL_RECENT":27,
              "UNCHANGED":20,"UNDERWEIGHT_M3":0,"UNDERWEIGHT_RECENT":0,"UP":4}}]""";

    /** Live shape for an ETF ISIN (IE00B4L5Y983): all-null fields + Info.Errors. */
    private static final String MISS = """
            [{"EventsV1":{"AMOUNT":0,"ITEMS":[],"PAGE_INDEX":0,"PAGE_SIZE":10,
              "TOTAL_AMOUNT":0,"TOTAL_PAGES":1},
             "Info":{"Errors":[{"ERROR_CODE":"IDMS",
              "ERROR_MESSAGE":"Error:MdgError (10000)(10000):10000:Missing parameter ID_COMPANY"}],
              "ID":"IE00B4L5Y983"},
             "RecommendationV1":{"BUY_M3":null,"BUY_RECENT":null,"CONSENSUS":null,
              "DATETIME_LAST_UPDATE":null,"DOWN":null,"EXPECTED_PERFORMANCE_PCT":null,
              "HOLD_M3":null,"HOLD_RECENT":null,"ISO_CURRENCY":null,"OVERWEIGHT_M3":null,
              "OVERWEIGHT_RECENT":null,"SELL_M3":null,"SELL_RECENT":null,"TARGET_PRICE":null,
              "TOTAL_RECENT":null,"UNCHANGED":null,"UNDERWEIGHT_M3":null,
              "UNDERWEIGHT_RECENT":null,"UP":null}}]""";

    private final ConsorsbankClient client = new ConsorsbankClient();

    @Test
    void parsesCoveredStock() {
        Optional<AnalystView> parsed = client.parse(HIT);
        assertTrue(parsed.isPresent());
        AnalystView v = parsed.get();
        assertTrue(v.hasRatings());
        assertEquals(27, v.total());
        assertEquals(19, v.buy());
        assertEquals(3, v.overweight());
        assertEquals(5, v.hold());
        assertEquals(0, v.underweight());
        assertEquals(0, v.sell());
        assertEquals(16, v.buy3m());
        assertEquals(4, v.upgrades());
        assertEquals(3, v.downgrades());
        assertEquals(1720.0, v.targetPrice(), 1e-9);
        assertEquals("EUR", v.targetCurrency());
        assertEquals(73.387, v.expectedUpsidePercent(), 1e-3);
        assertTrue(v.lastUpdateEpochSeconds() > 0);
        assertEquals(2, v.events().size());
        AnalystView.CorporateEvent next = v.events().get(0);
        assertEquals("RESULTS", next.type());
        assertEquals("Rheinmetall AG: Bericht 2. Quartal 2026", next.title());
        assertTrue(next.atEpochSeconds() > 0);
        // Events are sorted soonest-first and nextEvent respects the cutoff.
        assertNotNull(v.nextEvent(next.atEpochSeconds()));
        assertEquals(v.events().get(1), v.nextEvent(next.atEpochSeconds() + 1));
    }

    @Test
    void etfIsACleanMiss() {
        assertTrue(client.parse(MISS).isEmpty());
    }

    /** EventsV2 shape: bare array, offset-less datetimes, dividend rows with NAME_EVENT only. */
    private static final String HIT_V2_EVENTS = """
            [{"EventsV2":[
              {"DATETIME_EVENT":"2026-05-15T02:00:00","EVENT_TYPE":"DIVIDENDS",
               "NAME_EVENT":"Jährlicher Dividendenzahlungstermin","TITLE":""},
              {"DATETIME_EVENT":"2026-08-06T02:00:00","EVENT_TYPE":"RESULTS",
               "NAME_EVENT":"Geschäftsbericht","TITLE":"Rheinmetall AG: Bericht 2. Quartal 2026"}],
             "Info":{"ID":"DE0007030009"},
             "RecommendationV1":{"BUY_RECENT":19,"OVERWEIGHT_RECENT":3,"HOLD_RECENT":5,
              "UNDERWEIGHT_RECENT":0,"SELL_RECENT":0,"TOTAL_RECENT":27,
              "BUY_M3":16,"OVERWEIGHT_M3":3,"HOLD_M3":6,"UNDERWEIGHT_M3":0,"SELL_M3":0,
              "UP":4,"DOWN":3,"TARGET_PRICE":1720.0,"ISO_CURRENCY":"EUR",
              "EXPECTED_PERFORMANCE_PCT":73.38,"DATETIME_LAST_UPDATE":"2026-07-10T22:00:00+0000"}}]""";

    @Test
    void parsesEventsV2WithDividendsAndOffsetlessDates() {
        Optional<AnalystView> parsed = client.parse(HIT_V2_EVENTS);
        assertTrue(parsed.isPresent());
        AnalystView v = parsed.get();
        assertEquals(2, v.events().size());
        AnalystView.CorporateEvent dividend = v.events().get(0);
        assertEquals("DIVIDENDS", dividend.type());
        // Blank TITLE falls back to NAME_EVENT so the dividend row stays readable.
        assertEquals("Jährlicher Dividendenzahlungstermin", dividend.title());
        assertTrue(dividend.atEpochSeconds() > 0);
    }

    /** Deep-dive shapes as probed live 2026-07-13 (trimmed to one entry per block). */
    private static final String DEEP = """
            [{"CompanyProfileV1":{
               "HEADQUARTERS_CITY":"Düsseldorf","HEADQUARTERS_COUNTRY":"Deutschland",
               "MARKET_CAPITALIZATION":46282450432,"NAME_COMPANY_FULL":"Rheinmetall AG",
               "NUMBER_SHARES":46655696,
               "PORTRAIT":"<p>Die Rheinmetall AG ist eine Management-Holding.<br>Quelle: AfU Research GmbH</p>",
               "URL":"https://www.rheinmetall.com"},
             "KeyFiguresV1":{
               "FIRST_YEAR":{"DATE":"2022","EARNINGS_PER_SHARE":10.82,"DIVIDEND_PER_SHARE":3.3,
                 "DIVIDEND_YIELD_PCT":2.311,"PRICE_EARNINGS_RATIO":17.2,
                 "PRICE_EARNINGS_2_GROWTH_RATIO":1.24,"BOOKVALUE_PER_SHARE":64.82,
                 "EBIT_MARGE_PCT":10.9,"EBITDA_MARGE_PCT":14.78,"EQUITY_RATIO_PCT":41.46,
                 "EMPLOYEES":25486,"ISO_CURRENCY":"EUR"},
               "EIGHTH_YEAR":{"DATE":"2029","EARNINGS_PER_SHARE":99.088,"DIVIDEND_PER_SHARE":41.18,
                 "DIVIDEND_YIELD_PCT":4.15,"PRICE_EARNINGS_RATIO":10.01,
                 "PRICE_EARNINGS_2_GROWTH_RATIO":0.335,"BOOKVALUE_PER_SHARE":null,
                 "EBIT_MARGE_PCT":null,"EBITDA_MARGE_PCT":null,"EQUITY_RATIO_PCT":null,
                 "EMPLOYEES":null,"ISO_CURRENCY":"EUR"}},
             "BalanceSheetV1":{
               "FIRST_YEAR":{"DATE":"2021","TURNOVER":5658000,"NET_INCOME":432000,
                 "EQUITY_CAPITAL":2621000,"LIABILITIES":5113000,"TOTAL_ASSETS":7734000,
                 "CASHFLOW_NET":690000,"RESEARCH_EXPENSES":337000,"ISO_CURRENCY":"EUR"}},
             "BoardMembersV1":{
               "EXECUTIVE_BOARD":{"ITEMS":[
                 {"BOARD_ROLE":"MEMBER","NAME_FIRST":"Armin","NAME_LAST":"Papperger","TITLE":null}]},
               "SUPERVISORY_BOARD":{"ITEMS":[
                 {"BOARD_ROLE":"CHAIRMAN","NAME_FIRST":"Ulrich","NAME_LAST":"Grillo","TITLE":null}]}},
             "TradingCentralV2":{
               "DATE_ANALYSIS":"2026-07-10T20:08:00+0000","OPINION_SHORTTERM":1,"OPINION_MEDIUMTERM":-1,
               "PIVOT":919.27,"SUPPORT_1":948.29,"SUPPORT_2":919.27,"SUPPORT_3":845.42,
               "RESISTANCE_1":1096.5,"RESISTANCE_2":1141.75,"RESISTANCE_3":1187,
               "TEXT":{"OPINION_TEXT":"<p>Unter 919 short.</p>",
                       "COMMENT_TEXT":"<p>der RSI liegt unter der Neutralitätszone von 50.</p>"}},
             "AlternativesV1":[
               {"ISIN":"DE0006292030","NAME_SECURITY":"KSB AG VZ","NAME_SECURITY_SHORT":"KSB Vz",
                "MARKET_CAPITALIZATION":1528558936,"PRICE_EARNING_RATIO":11.92,"DIV_YIELD_PCT":2.79}],
             "PerformanceV1":{
               "PERFORMANCE_PCT_W1":-9.2,"PERFORMANCE_PCT_W4":-17.1,"PERFORMANCE_PCT_M3":-33.0,
               "PERFORMANCE_PCT_M6":-47.6,"PERFORMANCE_PCT_W52":-46.1,
               "CN_VOLA30":71.76,"CN_VOLA250":44.92,
               "PRICE_W52_HIGH":2008.5,"DATETIME_W52_HIGH":"2025-10-02T22:00:00+0000",
               "PRICE_W52_LOW":845.0,"DATETIME_W52_LOW":"2026-06-24T22:00:00+0000"},
             "BasicV1":{"NAME_SECURITY":"RHEINMETALL AG","REF_INDEX":{"CONSORS_ID":"_20735"}},
             "Info":{"ID":"DE0007030009"}}]""";

    @Test
    void parsesDeepDive() {
        var parsed = client.parseDeepDive("DE0007030009", DEEP,
                id -> "_20735".equals(id) ? "DAX PERFORMANCE INDEX" : null);
        assertTrue(parsed.isPresent());
        var d = parsed.get();
        assertEquals("https://www.rheinmetall.com", d.profile().website());
        // HTML is stripped from the portrait, the source attribution stays.
        assertEquals("Die Rheinmetall AG ist eine Management-Holding. Quelle: AfU Research GmbH",
                d.profile().portrait());
        assertEquals(46655696L, d.profile().sharesOutstanding());
        assertEquals(2, d.keyFigures().size());
        var actual = d.keyFigures().get(0);
        assertEquals("2022", actual.label());
        assertTrue(!actual.estimate());
        assertEquals(25486, actual.employees());
        var estimate = d.keyFigures().get(1);
        assertEquals("2029e", estimate.label());
        assertTrue(estimate.estimate());
        assertEquals(99.088, estimate.eps(), 1e-6);
        assertEquals(1, d.balanceSheet().size());
        assertEquals(5658000.0, d.balanceSheet().get(0).turnover(), 1e-9);
        assertEquals(2, d.board().size());
        assertEquals("Armin Papperger", d.board().get(0).name());
        assertEquals("Vorstand", d.board().get(0).board());
        assertEquals(919.27, d.technicalView().pivot(), 1e-9);
        assertEquals("Unter 919 short. der RSI liegt unter der Neutralitätszone von 50.",
                d.technicalView().commentText());
        assertEquals("2026-07-10", d.technicalView().asOfIso());
        assertEquals(1, d.peers().size());
        assertEquals("KSB Vz", d.peers().get(0).name());
        assertEquals(-46.1, d.performance().perf52w(), 1e-9);
        assertEquals("2026-06-24", d.performance().low52wDateIso());
        assertEquals("DAX PERFORMANCE INDEX", d.indexName());
    }

    @Test
    void deepDiveMissIsEmpty() {
        assertTrue(client.parseDeepDive("IE00B4L5Y983",
                "[{\"CompanyProfileV1\":null,\"KeyFiguresV1\":null,\"Info\":{\"ID\":\"IE00B4L5Y983\"}}]",
                id -> null).isEmpty());
        assertTrue(client.parseDeepDive("X", "<!doctype html>", id -> null).isEmpty());
    }

    @Test
    void garbageIsEmptyNotAnException() {
        assertTrue(client.parse("<!doctype html><html>wall</html>").isEmpty());
        assertTrue(client.parse("").isEmpty());
        assertTrue(client.parse("[]").isEmpty());
    }
}
