package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser pins for every briefing source, against response shapes captured
 * LIVE on 2026-07-13 (see the client javadocs) — the contract these clients
 * actually depend on. All network-free.
 */
class BriefingParsersTest {

    // ---- finanznachrichten ------------------------------------------------

    private static final String FN_ADHOC_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:fn="https://www.finanznachrichten.de/">
            <channel>
            <item><title>EQS-Adhoc: PSI Software SE: Konzernabschluss mit eingeschränktem Testat</title>\
            <description> EQS-Ad-hoc: PSI Software SE / Schlagwort(e): Jahresabschluss</description>\
            <link>https://www.finanznachrichten.de/n-022.htm</link>\
            <pubDate>2026-07-10T14:39:00Z</pubDate><fn:isin>DE000A0Z1JH9</fn:isin></item>
            <item><title>EQS-Adhoc: Covestro AG: EBITDA-Prognose angehoben</title>\
            <description>Covestro AG (ISIN: DE0006062144) hebt die Prognose an.</description>\
            <link>https://www.finanznachrichten.de/n-023.htm</link>\
            <pubDate>2026-07-10T10:57:00Z</pubDate><fn:isin /></item>
            </channel></rss>""";

    @Test
    void fnAdhocsCarryDedicatedIsinAndIsoDate() {
        List<FnRssClient.AdhocItem> items = FnRssClient.parseAdhocs(FN_ADHOC_XML);
        assertEquals(2, items.size());
        assertEquals("PSI Software SE: Konzernabschluss mit eingeschränktem Testat",
                items.get(0).title());
        assertEquals("DE000A0Z1JH9", items.get(0).isin());
        assertEquals(Instant.parse("2026-07-10T14:39:00Z"), items.get(0).publishedAt());
        // Empty <fn:isin /> → the regex fallback finds the ISIN in the description.
        assertEquals("DE0006062144", items.get(1).isin());
    }

    @Test
    void fnAnalystActionsKeepTheirTitles() {
        String xml = """
                <rss><channel><item><title>JPMORGAN stuft BMW auf 'Overweight'</title>\
                <pubDate>2026-07-10T18:18:00Z</pubDate></item></channel></rss>""";
        List<FnRssClient.AnalystAction> actions = FnRssClient.parseAnalystActions(xml);
        assertEquals(1, actions.size());
        assertEquals("JPMORGAN stuft BMW auf 'Overweight'", actions.get(0).title());
    }

    @Test
    void garbageFeedsYieldEmptyLists() {
        assertTrue(FnRssClient.parseAdhocs("<html>bot wall</html>").isEmpty());
        assertTrue(FnRssClient.parseAdhocs(null).isEmpty());
    }

    // ---- ForexFactory calendar ---------------------------------------------

    @Test
    void econCalendarParsesOffsetDatesAndImpact() {
        String json = """
                [{"title":"Core CPI m/m","country":"USD","date":"2026-07-14T08:30:00-04:00",
                  "impact":"High","forecast":"0.2%","previous":"0.1%"},
                 {"title":"broken","country":"EUR","date":"not-a-date"}]""";
        List<EconCalendarClient.EconEvent> events = EconCalendarClient.parse(json);
        assertEquals(1, events.size());
        assertEquals("Core CPI m/m", events.get(0).title());
        assertEquals("High", events.get(0).impact());
        assertEquals(Instant.parse("2026-07-14T12:30:00Z").getEpochSecond(),
                events.get(0).whenEpochSeconds());
    }

    // ---- Destatis / ifo ----------------------------------------------------

    @Test
    void destatisKeywordFilterDropsTourismKeepsInflation() {
        String xml = """
                <rss><channel>
                <item><title>Tourismus in Deutschland im Mai 2026: 3,8 % mehr Übernachtungen</title>\
                <pubDate>Fri, 10 Jul 2026 08:00:00 +0200</pubDate></item>
                <item><title>Inflationsrate im Juni 2026 bei +2,3 %</title>\
                <pubDate>Fri, 10 Jul 2026 08:00:00 +0200</pubDate></item>
                </channel></rss>""";
        List<MacroPressClient.MacroActual> actuals = MacroPressClient.parseDestatis(xml);
        assertEquals(1, actuals.size());
        assertEquals("Inflationsrate im Juni 2026 bei +2,3 %", actuals.get(0).title());
        assertEquals("Destatis", actuals.get(0).source());
    }

    // ---- Bundesbank --------------------------------------------------------

    @Test
    void bundYieldReadsGermanDecimalCommaObservations() {
        String csv = """
                "";BBSIS.D.I.ZST.ZI.EUR...;FLAGS
                Einheit;Prozent;
                Stand vom;10.07.2026 12:27:33 Uhr;
                2026-07-09;3,17;
                2026-07-10;3,13;
                """;
        Optional<BundYieldClient.YieldPoint> point = BundYieldClient.parse(csv);
        assertTrue(point.isPresent());
        assertEquals("2026-07-10", point.get().dateIso());
        assertEquals(3.13, point.get().percent(), 1e-9);
        assertEquals(3.17, point.get().previousPercent(), 1e-9);
    }

    // ---- ApeWisdom -----------------------------------------------------------

    @Test
    void apeWisdomParsesRanksAndComputesClimb() {
        String json = """
                {"count":686,"results":[
                  {"rank":1,"ticker":"SPY","name":"SPDR S&amp;P 500 ETF Trust","mentions":97,
                   "upvotes":646,"rank_24h_ago":3,"mentions_24h_ago":63},
                  {"rank":22,"ticker":"DC","name":"Dakota Gold","mentions":40,"upvotes":12,
                   "rank_24h_ago":750,"mentions_24h_ago":1}]}""";
        List<ApeWisdomClient.SocialTicker> tickers = ApeWisdomClient.parse(json);
        assertEquals(2, tickers.size());
        assertEquals(728, tickers.get(1).rankClimb());
    }

    // ---- CoinGecko -----------------------------------------------------------

    @Test
    void coinGeckoGlobalAndTrendingParse() {
        Optional<CoinGeckoClient.CryptoGlobal> global = CoinGeckoClient.parseGlobal("""
                {"data":{"total_market_cap":{"usd":2274721368202.41},
                 "market_cap_change_percentage_24h_usd":-0.32,
                 "market_cap_percentage":{"btc":56.14}}}""");
        assertTrue(global.isPresent());
        assertEquals(56.14, global.get().btcDominancePercent(), 1e-9);

        List<CoinGeckoClient.TrendingCoin> coins = CoinGeckoClient.parseTrending("""
                {"coins":[{"item":{"name":"Cash Dog in Hood","symbol":"CASHDOG",
                 "market_cap_rank":439,
                 "data":{"price_change_percentage_24h":{"usd":6096.69}}}}]}""");
        assertEquals(1, coins.size());
        assertEquals("CASHDOG", coins.get(0).symbol());
        assertEquals(6096.69, coins.get(0).change24hPercent(), 1e-9);
    }

    // ---- Polymarket ------------------------------------------------------------

    @Test
    void polymarketDecodesStringEncodedArraysAndPrefersYes() {
        String json = """
                [{"question":"Will the Fed cut rates in September?",
                  "outcomes":"[\\"Yes\\", \\"No\\"]",
                  "outcomePrices":"[\\"0.78\\", \\"0.22\\"]",
                  "volume24hr":3936100.34}]""";
        List<PolymarketClient.PredictionMarket> markets = PolymarketClient.parse(json);
        assertEquals(1, markets.size());
        assertEquals("Yes", markets.get(0).outcome());
        assertEquals(78.0, markets.get(0).probabilityPercent(), 1e-9);
    }

    // ---- CBOE ------------------------------------------------------------------

    @Test
    void cboeRatiosPickTheHeadlineFive() {
        String json = """
                {"ratios":[
                  {"name":"TOTAL PUT/CALL RATIO","value":"0.81"},
                  {"name":"INDEX PUT/CALL RATIO","value":"1.01"},
                  {"name":"EQUITY PUT/CALL RATIO","value":"0.55"},
                  {"name":"CBOE VOLATILITY INDEX (VIX) PUT/CALL RATIO","value":"0.44"},
                  {"name":"SPX + SPXW PUT/CALL RATIO","value":"1.12"},
                  {"name":"MRUT PUT/CALL RATIO","value":"4.09"}]}""";
        Optional<CboePutCallClient.PutCallRatios> ratios =
                CboePutCallClient.parse(json, "2026-07-10");
        assertTrue(ratios.isPresent());
        assertEquals(0.55, ratios.get().equity(), 1e-9);
        assertEquals(1.12, ratios.get().spx(), 1e-9);
    }

    // ---- FINRA --------------------------------------------------------------------

    @Test
    void finraJoinsOnlyWantedSymbolsWithFractionalVolumes() {
        String file = """
                Date|Symbol|ShortVolume|ShortExemptVolume|TotalVolume|Market
                20260710|A|737987.005699|629|981401.791053|B,Q,N
                20260710|HOOD|6200000|0|10000000|B,Q,N
                20260710|AAAA|857|0|2510.478800|Q""";
        Map<String, FinraShortVolumeClient.ShortVolume> out =
                FinraShortVolumeClient.parse(file, Set.of("hood"), "2026-07-10");
        assertEquals(1, out.size());
        assertEquals(62.0, out.get("HOOD").shortPercent(), 1e-9);
    }

    // ---- NASDAQ ----------------------------------------------------------------------

    @Test
    void nasdaqEarningsRowsParse() {
        String json = """
                {"data":{"rows":[{"time":"time-pre-market","symbol":"JPM",
                 "name":"J P Morgan Chase & Co","marketCap":"$898,895,695,396",
                 "epsForecast":"$5.52","noOfEsts":"6"}]}}""";
        List<NasdaqCalendarClient.EarningsEntry> entries = NasdaqCalendarClient.parse(json);
        assertEquals(1, entries.size());
        assertEquals("JPM", entries.get(0).symbol());
        assertEquals("time-pre-market", entries.get(0).slot());
        assertEquals("$5.52", entries.get(0).epsForecast());
    }

    // ---- Pegel / derivatives / curiosities ------------------------------------------

    @Test
    void pegelReadsLevelAndState() {
        Optional<RhinePegelClient.PegelReading> reading = RhinePegelClient.parse(
                "{\"timestamp\":\"2026-07-13T03:30:00+02:00\",\"value\":54.0,\"stateMnwMhw\":\"low\"}");
        assertTrue(reading.isPresent());
        assertEquals(54.0, reading.get().centimeters(), 1e-9);
        assertEquals("low", reading.get().state());
    }

    @Test
    void cryptoDerivsParseAllThreeLegs() {
        assertEquals(0.003432, CryptoDerivsClient.parseFunding(
                "{\"symbol\":\"BTCUSDT\",\"lastFundingRate\":\"0.00003432\"}"), 1e-9);
        assertEquals(99958.0, CryptoDerivsClient.parseOpenInterest(
                "{\"openInterest\":\"99958.000\"}"), 1e-9);
        assertEquals(37.48, CryptoDerivsClient.parseDvol(
                "{\"jsonrpc\":\"2.0\",\"result\":{\"index_price\":37.48}}"), 1e-9);
    }

    @Test
    void curiositiesParseDebtAndWeather() {
        Optional<CuriositiesClient.UsDebt> debt = CuriositiesClient.parseDebt("""
                {"data":[{"record_date":"2026-07-09","tot_pub_debt_out_amt":"39414179016130.09"}]}""");
        assertTrue(debt.isPresent());
        assertEquals(3.941417901613009E13, debt.get().totalUsd(), 1.0);

        Optional<CuriositiesClient.ExchangeWeather> weather = CuriositiesClient.parseWeather("""
                {"weather":{"temperature":21.7,"condition":"dry","icon":"clear-night","cloud_cover":0}}""");
        assertTrue(weather.isPresent());
        assertEquals("clear-night", weather.get().icon());
    }

    // ---- moon ---------------------------------------------------------------------------

    @Test
    void moonPhaseIsSaneAtKnownFullMoon() {
        // 2026-07-29 ~14:35 UTC is a full moon (astronomical tables).
        MoonPhase.MoonInfo info = MoonPhase.at(Instant.parse("2026-07-29T14:00:00Z"));
        assertEquals("FULL_MOON", info.phase());
        assertTrue(info.illuminationPercent() >= 97,
                "full moon should be ~100% lit, was " + info.illuminationPercent());
        assertTrue(info.daysToFull() == 0 || info.daysToFull() >= 29,
                "on the full-moon day the distance is 0 (or wraps), was " + info.daysToFull());
    }

    @Test
    void moonPhaseCountsDownToFull() {
        MoonPhase.MoonInfo aWeekBefore = MoonPhase.at(Instant.parse("2026-07-22T14:00:00Z"));
        assertTrue(Math.abs(aWeekBefore.daysToFull() - 7) <= 1,
                "a week before full moon should read ~7 days, was " + aWeekBefore.daysToFull());
    }

    // ---- TradingView economic calendar ---------------------------------------------------

    @Test
    void tradingViewParsesNumericActualsAndSkipsGarbage() {
        String body = """
                {"status":"ok","result":[
                {"id":"417520","title":"6-Month Bubill Auction","country":"DE",
                 "indicator":"6 Month Bill Yield","period":"","referenceDate":null,
                 "source":"","actual":2.436,"previous":2.331,"forecast":null,
                 "actualRaw":2.436,"previousRaw":2.331,"forecastRaw":null,
                 "currency":"EUR","unit":"%","importance":-1,"date":"2026-07-13T09:30:00.000Z"},
                {"id":"420846","title":"Fed Bowman Speech","country":"US",
                 "indicator":"Interest Rate","source":"Federal Reserve",
                 "actualRaw":null,"forecastRaw":null,"previousRaw":null,
                 "currency":"USD","importance":0,"date":"2026-07-13T09:25:00.000Z"},
                {"id":"999","title":"broken date","country":"US","date":"not-a-date"}]}""";
        List<TradingViewCalendarClient.TvEvent> events = TradingViewCalendarClient.parse(body);
        assertEquals(2, events.size());
        TradingViewCalendarClient.TvEvent auction = events.get(0);
        assertEquals(2.436, auction.actual());
        assertEquals(2.331, auction.previous());
        assertNull(auction.forecast());
        assertEquals("%", auction.unit());
        assertEquals(-1, auction.importance());
        assertEquals(Instant.parse("2026-07-13T09:30:00Z"), auction.when());
        // A speech carries no figures — nulls, never zeros.
        assertNull(events.get(1).actual());
        assertTrue(TradingViewCalendarClient.parse("{\"status\":\"error\"}").isEmpty());
        assertTrue(TradingViewCalendarClient.parse(null).isEmpty());
    }

    // ---- EQS corporate events --------------------------------------------------------------

    @Test
    void eqsEventsCarryIsinAndDropDatelessRecords() {
        String body = """
                {"status":200,"records":[
                {"id":"a","exactDateUnknown":0,"eventType":"future",
                 "startDate":"2026-07-14T00:00:00+00:00","endDate":"2026-07-14 21:59:59",
                 "companyName":"JDC Group AG","isin":"DE000A0B9N37","headline":"Hauptversammlung"},
                {"id":"b","exactDateUnknown":1,"eventType":"future",
                 "startDate":"2026-07-15T00:00:00+00:00","companyName":"X AG",
                 "isin":"DE000X","headline":"Quartalsbericht"},
                {"id":"c","exactDateUnknown":0,"eventType":"future",
                 "startDate":"2026-07-16T00:00:00+00:00","companyName":"Y AG",
                 "isin":"","headline":"Roadshow"}],"error":[]}""";
        List<EqsEventsClient.CorporateEvent> events = EqsEventsClient.parse(body);
        assertEquals(2, events.size());
        assertEquals("DE000A0B9N37", events.get(0).isin());
        assertEquals("Hauptversammlung", events.get(0).headline());
        assertEquals(Instant.parse("2026-07-14T00:00:00Z"), events.get(0).startDate());
        assertNull(events.get(1).isin()); // blank ISIN normalizes to null
        assertTrue(EqsEventsClient.parse("<html>shell</html>").isEmpty());
    }

    // ---- EarningsWhispers --------------------------------------------------------------------

    @Test
    void earningsWhispersParsesEstimatesAndSlots() {
        String body = """
                [{"ticker":"JPM","company":"JPMorgan Chase & Co.","total":56,
                  "nextEPSDate":"2026-07-14T00:00:00","releaseTime":1,"qDate":"6/2026",
                  "q1RevEst":48710000000.0,"q1EstEPS":5.52,
                  "confirmDate":"2026-07-03T05:49:32.43"},
                 {"ticker":"XYZ","company":"No Numbers Inc","releaseTime":2,
                  "q1RevEst":null,"q1EstEPS":null,"confirmDate":""}]""";
        List<EarningsWhispersClient.EarningsEstimate> estimates =
                EarningsWhispersClient.parse(body);
        assertEquals(2, estimates.size());
        assertEquals(5.52, estimates.get(0).epsEstimate());
        assertEquals(4.871E10, estimates.get(0).revenueEstimate());
        assertEquals("pre-market", estimates.get(0).slot());
        assertTrue(estimates.get(0).confirmed());
        assertNull(estimates.get(1).epsEstimate());
        assertEquals("after-hours", estimates.get(1).slot());
        assertTrue(!estimates.get(1).confirmed());
    }

    // ---- central-bank calendars ------------------------------------------------------------

    @Test
    void ecbCalendarKeepsOnlyMonetaryDecisionDays() {
        String html = """
                <dl class="definition-list -zebra">
                <dt>22/07/2026</dt><dd>Governing Council of the ECB: monetary policy meeting\
                 in Frankfurt (Day 1)</dd>
                <dt>23/07/2026</dt><dd>Governing Council of the ECB: monetary policy meeting\
                 in Frankfurt (Day 2), followed by press conference</dd>
                <dt>23/07/2026</dt><dd>Press conference following the Governing Council\
                 meeting of the ECB in Frankfurt</dd>
                <dt>30/09/2026</dt><dd>Governing Council of the ECB: non-monetary policy\
                 meeting (virtual)</dd>
                </dl>""";
        List<CentralBankCalendarClient.CbMeeting> meetings =
                CentralBankCalendarClient.parseEcb(html);
        assertEquals(1, meetings.size());
        assertEquals(LocalDate.of(2026, 7, 23), meetings.get(0).date());
        assertEquals("EZB", meetings.get(0).bank());
    }

    @Test
    void fedCalendarReadsDecisionDaysBomTolerant() {
        String body = "\uFEFF" + """
                {"events":[
                {"title":"FOMC Meeting","time":"2:00 p.m.","month":"2026-09","days":"16",
                 "type":"FOMC","description":"&lt;p&gt;Two-day meeting&lt;/p&gt;"},
                {"title":"FOMC Minutes","time":"2:00 p.m.","month":"2026-10","days":"7",
                 "type":"FOMC"},
                {"title":"Speech by Governor X","month":"2026-09","days":"2","type":"Speeches"}]}""";
        List<CentralBankCalendarClient.CbMeeting> meetings =
                CentralBankCalendarClient.parseFed(body);
        assertEquals(1, meetings.size());
        assertEquals(LocalDate.of(2026, 9, 16), meetings.get(0).date());
        assertEquals("Fed", meetings.get(0).bank());
        // A "days" range picks the LAST day — the decision lands on day 2.
        assertEquals(LocalDate.of(2026, 9, 16),
                CentralBankCalendarClient.fedDate("2026-09", "15-16"));
    }

    // ---- Wikipedia current events ------------------------------------------------------------

    @Test
    void wikipediaKeepsCitedLeafBulletsInRelevantCategories() {
        String wikitext = """
                {{Current events|year=2026|month=07|day=12|content=
                '''Armed conflicts and attacks'''
                *[[Middle Eastern crisis (2023-present)|Middle Eastern crisis]]
                **[[2026 Hormuz crisis]]
                ***The [[IRGC Navy|IRGC Navy]] declares the [[Strait of Hormuz]] closed \
                until further notice, halting tanker traffic. \
                [https://example.org/a (''France 24'')]
                '''Business and economy'''
                *[[Brent Crude|Brent]] jumps twelve percent after the strait closure, \
                the largest one-day move since 2022. [https://example.org/b (''Reuters'')]
                '''Sports'''
                *Somebody wins a [[trophy]] final. [https://example.org/c (''ESPN'')]
                }}""";
        List<WikipediaCurrentEventsClient.WorldEvent> events =
                WikipediaCurrentEventsClient.parse(wikitext, 3);
        assertEquals(2, events.size()); // sports filtered, breadcrumb bullets dropped
        assertEquals("Armed conflicts and attacks", events.get(0).category());
        assertEquals("France 24", events.get(0).source());
        assertTrue(events.get(0).text().startsWith("The IRGC Navy declares the Strait of Hormuz"));
        assertEquals("Reuters", events.get(1).source());
        assertTrue(events.get(1).text().contains("Brent jumps twelve percent"));
    }

    @Test
    void wikipediaPerCategoryCapHolds() {
        String wikitext = """
                '''Business and economy'''
                *First event sentence long enough to count as prose here. [https://e.org/1 (''A'')]
                *Second event sentence long enough to count as prose here. [https://e.org/2 (''B'')]
                *Third event sentence long enough to count as prose here. [https://e.org/3 (''C'')]
                """;
        assertEquals(2, WikipediaCurrentEventsClient.parse(wikitext, 2).size());
    }
}
