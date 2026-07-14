package de.bsommerfeld.wsbg.terminal.nasdaq;

import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.AnalystRatings;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.EarningsSurprise;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.InsiderTrade;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.InstitutionalOwnership;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats.ShortInterestPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free parse tests against trimmed live captures (probed 2026-07-14,
 * OTLK + AAPL). The behaviours that matter: the body-level rCode-400 definite
 * miss, string-numbers with {@code $}/{@code %}/commas beside raw JSON numbers
 * in the SAME document, {@code M/d/yyyy} dates with single-digit fields, the
 * "New" 13F position marker, and the bare-US-ticker gate.
 */
class NasdaqCompanyClientTest {

    /** Verbatim live reply for an unknown symbol — HTTP 200, body says 400. */
    private static final String MISS = """
            {"data":null,"message":null,"status":{"rCode":400,
             "bCodeMessage":[{"code":1001,"errorMessage":"Symbol not exists."}],"developerMessage":null}}""";

    private static final String INFO = """
            {"data":{"symbol":"OTLK","companyName":"Outlook Therapeutics, Inc. Common Stock",
              "stockType":"Common Stock","exchange":"NASDAQ-CM","isNasdaqListed":true,
              "primaryData":{"lastSalePrice":"$1.55","volume":"8,791,790"}},
             "message":null,"status":{"rCode":200,"bCodeMessage":null,"developerMessage":null}}""";

    private static final String SUMMARY = """
            {"data":{"symbol":"OTLK","summaryData":{
              "Exchange":{"label":"Exchange","value":"NASDAQ-CM"},
              "Sector":{"label":"Sector","value":"Health Care"},
              "Industry":{"label":"Industry","value":"Biotechnology: Biological Products (No Diagnostic Substances)"},
              "AverageVolume":{"label":"Average Volume","value":"21,844,550"},
              "MarketCap":{"label":"Market Cap","value":"217,073,486"},
              "Yield":{"label":"Current Yield","value":"N/A"}},
              "assetClass":"STOCKS"},
             "message":null,"status":{"rCode":200}}""";

    private static final String SHORT_INTEREST = """
            {"data":{"symbol":"otlk","shortInterestTable":{"rows":[
              {"settlementDate":"06/30/2026","interest":"14,014,673",
               "avgDailyShareVolume":"15,859,040","daysToCover":1.0},
              {"settlementDate":"05/15/2026","interest":"9,088,387",
               "avgDailyShareVolume":"4,150,596","daysToCover":2.189658}]}},
             "message":null,"status":{"rCode":200}}""";

    private static final String INSIDERS = """
            {"data":{
              "numberOfTrades":{"rows":[
                {"insiderTrade":"Number of Open Market Buys","months3":"6","months12":"6"},
                {"insiderTrade":"Number of Sells","months3":"0","months12":"0"},
                {"insiderTrade":"Total Insider Trades","months3":"6","months12":"6"}]},
              "numberOfSharesTraded":{"rows":[
                {"insiderTrade":"Number of Shares Bought","months3":"9,129,883","months12":"9,129,883"},
                {"insiderTrade":"Number of Shares Sold","months3":"0","months12":"0"},
                {"insiderTrade":"Net Activity","months3":"9,129,883","months12":"9,129,883"}]},
              "transactionTable":{"totalRecords":"8","table":{"rows":[
                {"insider":"HADDADIN YEZAN MUNTHER","relation":"Director","lastDate":"6/02/2026",
                 "transactionType":"Buy","ownType":"Direct","sharesTraded":"29,000",
                 "lastPrice":"$0.83","sharesHeld":"66,167"},
                {"insider":"SUKHTIAN GHIATH M.","relation":"Director","lastDate":"5/27/2025",
                 "transactionType":"Acquisition (Non Open Market)","ownType":"Indirect",
                 "sharesTraded":"4,285,714","lastPrice":"$1.40","sharesHeld":"13,552,359"}]}}},
             "message":null,"status":{"rCode":200}}""";

    private static final String INSTITUTIONAL = """
            {"data":{
              "ownershipSummary":{
                "SharesOutstandingPCT":{"label":"Institutional Ownership","value":"7.63%"},
                "ShareoutstandingTotal":{"label":"Total Shares Outstanding (millions)","value":"160"},
                "TotalHoldingsValue":{"label":"Total Value of Holdings (millions)","value":"$19"}},
              "activePositions":{"rows":[
                {"positions":"Increased Positions","holders":"35","shares":"8,856,462"},
                {"positions":"Total Institutional Shares","holders":"74","shares":"12,220,374"}]},
              "holdingsTransactions":{"totalRecords":"74","table":{"rows":[
                {"ownerName":"Armistice Capital, Llc","date":"3/31/2026","sharesHeld":"4,168,000",
                 "sharesChange":"4,168,000","sharesChangePCT":"New","marketValue":"$6,544"},
                {"ownerName":"Vanguard Group Inc","date":"12/31/2025","sharesHeld":"1,465,167",
                 "sharesChange":"122,540","sharesChangePCT":"9.127%","marketValue":"$2,300"}]}}},
             "message":null,"status":{"rCode":200}}""";

    private static final String RATINGS = """
            {"data":{"symbol":"aapl","meanRatingType":"Buy",
              "ratingsSummary":"Based on 29 analysts offering recommendations for 'AAPL'.",
              "upgradesDowngrades":[],"brokerNames":["GOLDMAN SACHS"]},
             "message":null,"status":{"rCode":200}}""";

    private static final String TARGET = """
            {"data":{"symbol":"aapl","consensusOverview":{
              "lowPriceTarget":250.0,"highPriceTarget":400.0,"priceTarget":327.2,
              "buy":17,"sell":1,"hold":11}},
             "message":null,"status":{"rCode":200}}""";

    private static final String EARNINGS = """
            {"data":{"symbol":"otlk","earningsSurpriseTable":{"rows":[
              {"fiscalQtrEnd":"Mar 2026","dateReported":"5/15/2026","eps":-0.16,
               "consensusForecast":"-0.12","percentageSurprise":"-33.33"},
              {"fiscalQtrEnd":"Dec 2025","dateReported":"2/17/2026","eps":-0.22,
               "consensusForecast":"-0.17","percentageSurprise":"-29.41"}]}},
             "message":null,"status":{"rCode":200}}""";

    @Test
    void gateAcceptsBareUsTickersOnly() {
        assertTrue(NasdaqCompanyClient.usTickerShaped("OTLK"));
        assertTrue(NasdaqCompanyClient.usTickerShaped("AAPL"));
        assertTrue(NasdaqCompanyClient.usTickerShaped("BRK.A"));
        assertFalse(NasdaqCompanyClient.usTickerShaped("SAP.DE"));   // venue suffix
        assertFalse(NasdaqCompanyClient.usTickerShaped("^GDAXI"));   // index
        assertFalse(NasdaqCompanyClient.usTickerShaped("CC=F"));     // future
        assertFalse(NasdaqCompanyClient.usTickerShaped("BTC-USD"));  // crypto pair
        assertFalse(NasdaqCompanyClient.usTickerShaped("005930.KS")); // digits + venue
        assertFalse(NasdaqCompanyClient.usTickerShaped("ALPHABET")); // too long for a symbol
    }

    @Test
    void nonUsSymbolAnswersEmptyWithoutNetwork() {
        // The gate fires before any fetch — a broken transport must never be touched.
        NasdaqCompanyClient client = new NasdaqCompanyClient(
                new de.bsommerfeld.wsbg.terminal.source.net.WebFetcher() {
                    @Override
                    public String name() {
                        return "boom";
                    }

                    @Override
                    public de.bsommerfeld.wsbg.terminal.source.net.WebResponse fetch(
                            String url, java.util.Map<String, String> headers,
                            java.time.Duration timeout) {
                        throw new AssertionError("network call for gated symbol: " + url);
                    }
                });
        assertTrue(client.statsFor("SAP.DE").isEmpty());
        assertTrue(client.statsFor("^GDAXI").isEmpty());
        assertTrue(client.statsFor(null).isEmpty());
    }

    @Test
    void bodyLevel400IsADefiniteMiss() {
        assertTrue(NasdaqCompanyClient.definiteMiss(MISS));
        assertFalse(NasdaqCompanyClient.definiteMiss(INFO));
        assertFalse(NasdaqCompanyClient.definiteMiss(null));
        assertFalse(NasdaqCompanyClient.definiteMiss("not json"));
    }

    @Test
    void nullDataLegsResolveEmpty() {
        assertNull(NasdaqCompanyClient.parseInfo(MISS));
        assertNull(NasdaqCompanyClient.parseSummary(MISS));
        assertNull(NasdaqCompanyClient.parseInsiders(null));
        assertNull(NasdaqCompanyClient.parseInstitutional(MISS));
        assertNull(NasdaqCompanyClient.parseAnalyst(MISS, null));
        assertTrue(NasdaqCompanyClient.parseShortInterest(MISS).isEmpty());
        assertTrue(NasdaqCompanyClient.parseEarnings(null).isEmpty());
    }

    @Test
    void parsesInfoAndSummary() {
        NasdaqCompanyClient.Info info = NasdaqCompanyClient.parseInfo(INFO);
        assertEquals("Outlook Therapeutics, Inc. Common Stock", info.companyName());
        assertEquals("NASDAQ-CM", info.exchange());

        NasdaqCompanyClient.Summary s = NasdaqCompanyClient.parseSummary(SUMMARY);
        assertEquals("Health Care", s.sector());
        assertTrue(s.industry().startsWith("Biotechnology"));
        assertEquals(217_073_486.0, s.marketCapUsd(), 1e-9);
        assertEquals(21_844_550L, s.avgDailyVolume());
        assertTrue(Double.isNaN(s.dividendYieldPercent())); // "N/A"
    }

    @Test
    void parsesShortInterestPoints() {
        List<ShortInterestPoint> pts = NasdaqCompanyClient.parseShortInterest(SHORT_INTEREST);
        assertEquals(2, pts.size());
        assertEquals("2026-06-30", pts.get(0).settlementDateIso());
        assertEquals(14_014_673L, pts.get(0).shortInterestShares());
        assertEquals(15_859_040L, pts.get(0).avgDailyShareVolume());
        assertEquals(1.0, pts.get(0).daysToCover(), 1e-9);
        assertEquals(2.189658, pts.get(1).daysToCover(), 1e-9);
    }

    @Test
    void parsesInsiderActivityAndTrades() {
        NasdaqCompanyClient.InsiderLeg leg = NasdaqCompanyClient.parseInsiders(INSIDERS);
        assertEquals(6, leg.activity().buys3m());
        assertEquals(0, leg.activity().sells3m());
        assertEquals(9_129_883L, leg.activity().netShares3m());
        assertEquals(9_129_883L, leg.activity().netShares12m());

        assertEquals(2, leg.trades().size());
        InsiderTrade buy = leg.trades().get(0);
        assertEquals("2026-06-02", buy.dateIso()); // single-digit day "6/02/2026"
        assertEquals("HADDADIN YEZAN MUNTHER", buy.insider());
        assertEquals("Director", buy.relation());
        assertEquals("buy", buy.direction());
        assertEquals(29_000L, buy.sharesTraded());
        assertEquals(0.83, buy.priceUsd(), 1e-9); // "$0.83"
        assertEquals(66_167L, buy.sharesHeld());

        InsiderTrade nonMarket = leg.trades().get(1);
        assertEquals("Acquisition (Non Open Market)", nonMarket.transaction());
        assertEquals("other", nonMarket.direction()); // NOT an open-market buy
    }

    @Test
    void parsesInstitutionalOwnership() {
        InstitutionalOwnership inst = NasdaqCompanyClient.parseInstitutional(INSTITUTIONAL);
        assertEquals(7.63, inst.ownershipPercent(), 1e-9); // "7.63%"
        assertEquals(74L, inst.totalHolders());
        assertEquals(12_220_374L, inst.totalSharesHeld());
        assertEquals(19.0, inst.totalValueMillionsUsd(), 1e-9); // "$19"
        assertEquals(2, inst.topHolders().size());
        assertEquals("Armistice Capital, Llc", inst.topHolders().get(0).name());
        assertEquals(4_168_000L, inst.topHolders().get(0).sharesHeld());
        assertEquals(6544.0, inst.topHolders().get(0).marketValueThousandsUsd(), 1e-9);
        assertEquals("2026-03-31", inst.topHolders().get(0).asOfDateIso());
    }

    @Test
    void foldsRatingsAndTargetIntoOneView() {
        AnalystRatings a = NasdaqCompanyClient.parseAnalyst(RATINGS, TARGET);
        assertEquals("Buy", a.consensusLabel());
        assertEquals(29, a.analystCount()); // from the ratingsSummary prose
        assertEquals(17, a.buy());
        assertEquals(11, a.hold());
        assertEquals(1, a.sell());
        assertEquals(327.2, a.meanPriceTargetUsd(), 1e-9);
        assertEquals(400.0, a.highPriceTargetUsd(), 1e-9);
        assertEquals(250.0, a.lowPriceTargetUsd(), 1e-9);
    }

    @Test
    void ratingsWithoutTargetStillAnswer() {
        AnalystRatings a = NasdaqCompanyClient.parseAnalyst(RATINGS, null);
        assertEquals("Buy", a.consensusLabel());
        assertEquals(29, a.analystCount());
        assertEquals(-1, a.buy());
        assertTrue(Double.isNaN(a.meanPriceTargetUsd()));
    }

    @Test
    void parsesEarningsSurprises() {
        List<EarningsSurprise> rows = NasdaqCompanyClient.parseEarnings(EARNINGS);
        assertEquals(2, rows.size());
        assertEquals("Mar 2026", rows.get(0).fiscalQuarter());
        assertEquals("2026-05-15", rows.get(0).reportedDateIso()); // "5/15/2026"
        assertEquals(-0.16, rows.get(0).epsActual(), 1e-9);        // raw JSON number
        assertEquals(-0.12, rows.get(0).epsConsensus(), 1e-9);     // string "-0.12"
        assertEquals(-33.33, rows.get(0).surprisePercent(), 1e-9); // string "-33.33"
    }

    @Test
    void usNumberHandlesDisplayQuirks() {
        assertEquals(1.58, NasdaqCompanyClient.parseUsNumber("$1.58"), 1e-9);
        assertEquals(1_234_567.0, NasdaqCompanyClient.parseUsNumber("1,234,567"), 1e-9);
        assertEquals(7.63, NasdaqCompanyClient.parseUsNumber("7.63%"), 1e-9);
        assertEquals(6544.0, NasdaqCompanyClient.parseUsNumber("$6,544"), 1e-9);
        assertEquals(-33.33, NasdaqCompanyClient.parseUsNumber("-33.33"), 1e-9);
        assertEquals(0.63, NasdaqCompanyClient.parseUsNumber("+0.63%"), 1e-9);
        assertTrue(Double.isNaN(NasdaqCompanyClient.parseUsNumber("N/A")));
        assertTrue(Double.isNaN(NasdaqCompanyClient.parseUsNumber("NA")));
        assertTrue(Double.isNaN(NasdaqCompanyClient.parseUsNumber("New"))); // fresh 13F position
        assertTrue(Double.isNaN(NasdaqCompanyClient.parseUsNumber("")));
        assertTrue(Double.isNaN(NasdaqCompanyClient.parseUsNumber(null)));
    }

    @Test
    void usDateHandlesSingleDigitFields() {
        assertEquals("2026-06-30", NasdaqCompanyClient.parseUsDate("06/30/2026"));
        assertEquals("2026-06-02", NasdaqCompanyClient.parseUsDate("6/02/2026"));
        assertEquals("2026-05-15", NasdaqCompanyClient.parseUsDate("5/15/2026"));
        assertNull(NasdaqCompanyClient.parseUsDate("Mar 2026"));
        assertNull(NasdaqCompanyClient.parseUsDate(null));
    }
}
