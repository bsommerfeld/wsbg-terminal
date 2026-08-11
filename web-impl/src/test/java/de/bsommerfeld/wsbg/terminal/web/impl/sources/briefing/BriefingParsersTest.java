package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser pins for every briefing source, against response shapes captured
 * LIVE on 2026-07-13 (see the client javadocs) — the contract these clients
 * actually depend on. All network-free. Ported 1:1 from the module world.
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

    @Test
    void ftmoCalendarCarriesActualRestrictionAndScope() {
        String json = """
                {"view":{"displayActualValue":true},"items":[
                 {"title":"Core PCE m/m","impact":"high","instrument":"USD + US Indices + XAUUSD + DXY",
                  "restriction":true,"date":"2026-08-05T14:30:00+02:00","forecast":"0.2 %",
                  "previous":"0.3 %","actual":"0.1 %"},
                 {"title":"Crude Oil Inventories","impact":"medium","instrument":"Crude Oil",
                  "restriction":false,"date":"2026-08-06T16:30:00+02:00","forecast":"","previous":"",
                  "actual":null},
                 {"title":"broken","impact":"high","instrument":"EUR","date":"not-a-date"}]}""";
        List<EconCalendarClient.EconEvent> events = EconCalendarClient.parseFtmo(json);
        assertEquals(2, events.size());

        EconCalendarClient.EconEvent pce = events.get(0);
        // The lead code of a compound scope is the affected currency, and the
        // capitalised rating is what the downstream shelves compare against.
        assertEquals("USD", pce.country());
        assertEquals("High", pce.impact());
        assertEquals("USD + US Indices + XAUUSD + DXY", pce.instrument());
        assertEquals("0.1 %", pce.actual());
        assertTrue(pce.restricted());
        assertEquals(Instant.parse("2026-08-05T12:30:00Z").getEpochSecond(),
                pce.whenEpochSeconds());

        // A scope without a currency leaves country empty rather than inventing one.
        EconCalendarClient.EconEvent oil = events.get(1);
        assertEquals("", oil.country());
        assertEquals("Crude Oil", oil.instrument());
        assertEquals("", oil.actual());
        assertFalse(oil.restricted());
    }

    @Test
    void ftmoGarbageYieldsEmptyList() {
        assertTrue(EconCalendarClient.parseFtmo("<html>bot wall</html>").isEmpty());
        assertTrue(EconCalendarClient.parseFtmo("{\"errorCode\":\"DATE_RANGE_VIOLATED\"}").isEmpty());
        assertTrue(EconCalendarClient.parseFtmo(null).isEmpty());
    }

    // ---- FXStreet ----------------------------------------------------------

    @Test
    void fxStreetParsesTypedValuesAndSpotsThePriorRevision() {
        String json = """
                [{"id":"01e5739f","eventId":"9b7c0cb1","dateUtc":"2026-07-27T08:00:00Z",
                  "actual":86.6,"revised":85.7,"consensus":86.1,"previous":85.6,
                  "isBetterThanExpected":true,"name":"IFO - Business Climate","countryCode":"DE",
                  "currencyCode":"EUR","unit":null,"volatility":"MEDIUM","isTentative":false,
                  "isSpeech":false,"lastUpdated":1785139276},
                 {"id":"aa","eventId":"bb","dateUtc":"2026-07-28T12:30:00Z","actual":null,
                  "revised":null,"consensus":0.2,"previous":0.3,"name":"Core PCE","countryCode":"US",
                  "currencyCode":"USD","unit":"%","volatility":"HIGH","lastUpdated":0},
                 {"id":"cc","name":"broken","dateUtc":"not-a-date"}]""";
        List<FxStreetCalendarClient.FxsEvent> events = FxStreetCalendarClient.parse(json);
        assertEquals(2, events.size());

        FxStreetCalendarClient.FxsEvent ifo = events.get(0);
        assertEquals(86.6, ifo.actual());
        assertEquals(85.6, ifo.previous());
        // The prior print was corrected upwards - the fact a naked actual hides.
        assertTrue(ifo.priorRevised());
        assertTrue(ifo.released());
        assertEquals(Boolean.TRUE, ifo.betterThanExpected());
        assertEquals(Instant.parse("2026-07-27T08:00:00Z").getEpochSecond(),
                ifo.whenEpochSeconds());

        // Unreleased: numbers stay null rather than collapsing to zero.
        FxStreetCalendarClient.FxsEvent pce = events.get(1);
        assertFalse(pce.released());
        assertNull(pce.actual());
        assertFalse(pce.priorRevised());
        assertNull(pce.betterThanExpected());
    }

    @Test
    void fxStreetDetailStripsMarkupAndGarbageYieldsNothing() {
        String json = """
                {"urlSource":"https://www.ifo.de/en/survey","whyMatters":null,
                 "description":"Released by the <a href=\\"http://x\\">CESifo Group</a>  and closely watched.",
                 "source":"IFO Institute","nextReleaseDate":"2026-08-27T08:00:00Z"}""";
        FxStreetCalendarClient.FxsDetail detail = FxStreetCalendarClient.parseDetail(json);
        assertEquals("IFO Institute", detail.source());
        assertEquals("2026-08-27T08:00:00Z", detail.nextReleaseDateIso());
        assertTrue(detail.description().contains("CESifo Group"));
        assertFalse(detail.description().contains("<a href"));

        assertTrue(FxStreetCalendarClient.parse("<html>bot wall</html>").isEmpty());
        assertTrue(FxStreetCalendarClient.parse(null).isEmpty());
        assertNull(FxStreetCalendarClient.parseDetail("[]"));
    }

    // ---- wallstreet-online Unternehmenstermine ------------------------------

    /**
     * The real row markup, trimmed to one row. Kept as plain HTML and wrapped
     * into the RPC envelope programmatically — hand-escaping it into a JSON
     * literal is how you end up pinning a fixture that is not valid JSON.
     */
    private static final String WO_ROW = """
            <h3>Termine</h3>
            <div class="wrapper body mainRow mainRow0" rel="0">
            <div  class="time  datetime item"><div class='span' ><span rel="2026-08-03 22:00:00">03.08.2026</span></div></div>
            <div  class="country d-none inst_iso_2letterright  item"><div class='span' ><img alt="us" src="x" title="USA" /></div></div>
            <div  class="instrument  instrument_display_name item"><div class='span' ><a href="/aktien/palantir-aktie" title="Palantir Aktie" >Palantir</a></div></div>
            <div  class="  symbol item"><div class='span' ><span class="symbol"><span class="event_id none">UD_673638906</span><a href="/aktien/palantir-aktie">Quartalsmitteilung</a></span></div></div>
            <div  class="  actual_plus item"><div class='span' ></div></div>
            <div  class="  consensus_plus item"><div class='span' ><span class="d-inline-block d-sm-none">EPS Schätzung</span> 1.234,28<span class="curSymbol">$</span></div></div>
            <div  class="  beforeAfterMarket item"><div class='span' >nach Schluss</div></div></div>
            """;

    @Test
    void woCalendarParsesRowsAndGermanDecimalsWithCurrency() throws Exception {
        var envelope = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        envelope.put("status", 1).putObject("data").put("days", 1).put("html", WO_ROW);

        List<WoCompanyCalendarClient.CompanyDate> dates =
                WoCompanyCalendarClient.parse(envelope.toString());
        assertEquals(1, dates.size());
        WoCompanyCalendarClient.CompanyDate d = dates.get(0);
        assertEquals("Palantir", d.company());
        assertEquals("Quartalsmitteilung", d.event());
        assertEquals("US", d.country());
        assertEquals("UD_673638906", d.eventId());
        assertEquals("/aktien/palantir-aktie", d.slug());
        // German thousands and decimal separators, currency from the glyph.
        assertEquals(1234.28, d.epsEstimate());
        assertEquals("USD", d.currency());
        assertEquals("nach Schluss", d.slot());
        // Berlin wall-clock, not UTC.
        assertEquals(Instant.parse("2026-08-03T20:00:00Z").getEpochSecond(),
                d.whenEpochSeconds());
    }

    @Test
    void woCalendarGarbageYieldsEmptyList() {
        assertTrue(WoCompanyCalendarClient.parse("<html>bot wall</html>").isEmpty());
        assertTrue(WoCompanyCalendarClient.parse("{\"status\":0,\"data\":{\"html\":\"\"}}").isEmpty());
        assertTrue(WoCompanyCalendarClient.parse(null).isEmpty());
    }

    // ---- Borsa Italiana ------------------------------------------------------

    @Test
    void borsaItalianaReadsIsinOffTheLinkAndCutsTheRepeatedLabel() {
        // The live row repeats company and label after the dated one, and the
        // separating non-breaking spaces collapse - that repetition is the trap.
        String html = """
                <table><tr><th>Issuer</th></tr>
                <tr><td><a href="/borsa/azioni/elenco-completo-eventi.html?isin=IT0003188064&lang=en">
                BANCA IFIS S.p.A.</a>&nbsp;&nbsp;04.08.2026 - Half Year Report&nbsp;&nbsp;
                BANCA IFIS S.p.A. Half Year Report</td><td>04.08.2026</td></tr>
                <tr><td>no isin here 05.08.2026 - Whatever</td></tr>
                <tr><td><a href="?isin=NL0011794037&lang=en">Some N.V.</a>&nbsp;03.08.2026 - Annual General Meeting&nbsp;</td></tr>
                </table>""";
        List<BorsaItalianaEventsClient.EuEvent> events = BorsaItalianaEventsClient.parse(html);
        assertEquals(2, events.size());
        // Chronological, so the Dutch row leads.
        assertEquals("NL0011794037", events.get(0).isin());
        assertEquals("Annual General Meeting", events.get(0).label());

        BorsaItalianaEventsClient.EuEvent ifis = events.get(1);
        assertEquals(LocalDate.of(2026, 8, 4), ifis.date());
        assertEquals("IT0003188064", ifis.isin());
        assertEquals("BANCA IFIS S.p.A.", ifis.company());
        assertEquals("Half Year Report", ifis.label());
    }

    @Test
    void borsaItalianaGarbageYieldsEmptyList() {
        assertTrue(BorsaItalianaEventsClient.parse("<html>bot wall</html>").isEmpty());
        assertTrue(BorsaItalianaEventsClient.parse(null).isEmpty());
    }

    // ---- Handelskalender -----------------------------------------------------

    @Test
    void tradingCalendarReadsClosuresFromXetraAndHalfDaysFromTradegate() {
        String xetra = """
                <table>
                <tr><th></th><th>2026</th><th>2027</th></tr>
                <tr><td>Karfreitag</td><td>Freitag03.04.2026</td><td>Freitag26.03.2027</td></tr>
                <tr><td>@</td><td>31.07.2026</td><td></td></tr>
                </table>""";
        List<TradingCalendarClient.TradingBreak> xetraBreaks =
                TradingCalendarClient.parseXetra(xetra);
        // Both column dates land; the footnote row is not a holiday.
        assertEquals(2, xetraBreaks.size());
        assertEquals(LocalDate.of(2026, 4, 3), xetraBreaks.get(0).date());
        assertTrue(xetraBreaks.get(0).closed());

        String tradegate = """
                <div>01/01/2026</div><div>New Year's Day: no trading</div>
                <div>30/12/2026</div><div>Last trading day of the year: trading from 7:30 'til 2pm</div>
                <div>14/05/2026</div><div>Ascension Day: trading from 7:30 'til 8pm</div>
                <div>31/07/2026</div><div>@</div>""";
        List<TradingCalendarClient.TradingBreak> tg = TradingCalendarClient.parseTradegate(tradegate);
        assertEquals(3, tg.size());
        assertTrue(tg.get(0).closed());
        // "'til 2pm" is 14:00, not 2:00 - and the label loses its colon tail.
        assertEquals(Integer.valueOf(14), tg.get(1).closesAtHour());
        assertEquals("Last trading day of the year", tg.get(1).label());
        assertEquals(Integer.valueOf(20), tg.get(2).closesAtHour());
    }

    @Test
    void tradingCalendarReadsEurexCountryCalendars() {
        String csv = """
                Date,Day of Week,Calendar,Holiday Name,Type,Type of Holiday
                01/01/2027,Friday,EXCH,New Year's Day,Global,T2 Holiday
                03/26/2027,Friday,EXCH,Good Friday,Global,T2 Holiday
                07/05/2027,Monday,TUSA,Independence Day observed,Local,
                broken line without commas""";
        Map<String, Set<LocalDate>> cals = TradingCalendarClient.parseEurex(csv);
        assertEquals(Set.of("EXCH", "TUSA"), cals.keySet());
        assertEquals(2, cals.get("EXCH").size());
        // US dates are MM/dd/yyyy - 07/05 is the fifth of July, not the seventh of May.
        assertEquals(LocalDate.of(2027, 7, 5), cals.get("TUSA").iterator().next());
        assertTrue(TradingCalendarClient.parseEurex("<html>bot wall</html>").isEmpty());
    }

    // ---- Notenbanken der Welt (cbrates) --------------------------------------

    @Test
    void worldCentralBankTableCarriesTheYearInSeparatorRowsOnly() {
        String html = """
                <table>
                <tr><td>Last update: 30 July 2026</td></tr>
                <tr><td><img src="flags/japan.jpg"></td><td>Jul 31</td>                <td>Japan: Bank of Japan (BOJ)</td><td>Central Bank</td></tr>
                <tr><td>December &nbsp;</td></tr>
                <tr><td><img src="flags/tuerkei.jpg"></td><td>Dec 10</td>                <td>T\ufffdrkiye: Central Bank of T\ufffdrkiye (TCMB) / chart &amp; historical Rates</td>                <td>Central Bank</td></tr>
                <tr><td>2027 &nbsp;</td></tr>
                <tr><td><img src="flags/australien.jpg"></td><td>Feb 03</td>                <td>Australia: Reserve Bank of Australia (RBA)</td><td>Central Bank</td></tr>
                <tr><td><img src="flags/eu.jpg"></td><td>Mar 11</td>                <td>Euro Area: European Central Bank (ECB)</td><td>Central Bank</td></tr>
                </table>""";
        List<CentralBankCalendarClient.CbMeeting> m = CentralBankCalendarClient.parseWorld(html);
        // The ECB row is dropped - the primary leg owns that one.
        assertEquals(3, m.size());
        assertEquals(LocalDate.of(2026, 7, 31), m.get(0).date());
        assertEquals("BOJ", m.get(0).bank());
        assertEquals(LocalDate.of(2026, 12, 10), m.get(1).date());
        // The navigation tail is cut and the undecodable letter does not survive.
        assertEquals("TCMB", m.get(1).bank());
        assertEquals("Central Bank of Trkiye (Trkiye)", m.get(1).title());
        // The bare year row carries the following months into 2027.
        assertEquals(LocalDate.of(2027, 2, 3), m.get(2).date());

        assertTrue(CentralBankCalendarClient.parseWorld("<html>bot wall</html>").isEmpty());
        assertTrue(CentralBankCalendarClient.parseWorld(null).isEmpty());
    }

    // ---- Statistikämter ------------------------------------------------------

    @Test
    void statsCalendarReadsAllThreeOfficesAndKeepsTheSlipFlag() {
        String eurostat = """
                [{"recordid":"22493738","period":"June 2026","euroind":true,
                  "start":"2026-08-13T11:00Z","datasetCodes":"sts_inpr_m","title":"Industrial production"},
                 {"recordid":"x","start":"not-a-date","title":"broken"}]""";
        List<StatsReleaseCalendarClient.Release> eu =
                StatsReleaseCalendarClient.parseEurostat(eurostat);
        assertEquals(1, eu.size());
        assertEquals(LocalDate.of(2026, 8, 13), eu.get(0).date());
        assertEquals("sts_inpr_m", eu.get(0).datasets());
        assertEquals("June 2026", eu.get(0).period());

        // BEA ships every date of the year per series, past ones included.
        String bea = """
                {"Gross Domestic Product":{"release_dates":["2026-07-30T12:30:00+00:00",
                 "2026-08-27T12:30:00+00:00"]}}""";
        List<StatsReleaseCalendarClient.Release> us = StatsReleaseCalendarClient.parseBea(bea);
        assertEquals(2, us.size());
        assertEquals("Gross Domestic Product", us.get(0).title());

        String ons = """
                {"releases":[
                 {"description":{"title":"GDP first quarterly estimate",
                   "release_date":"2026-08-14T06:00:00.000Z","cancelled":false,
                   "postponed":true,"finalised":true}},
                 {"description":{"title":"Provisional one",
                   "release_date":"2026-09-01T06:00:00.000Z","cancelled":false,
                   "postponed":false,"finalised":false}}]}""";
        List<StatsReleaseCalendarClient.Release> uk = StatsReleaseCalendarClient.parseOns(ons);
        assertEquals(2, uk.size());
        // A release that slips its date is the news, so the flag survives.
        assertTrue(uk.get(0).slipped());
        assertFalse(uk.get(0).provisional());
        assertFalse(uk.get(1).slipped());
        assertTrue(uk.get(1).provisional());

        assertTrue(StatsReleaseCalendarClient.parseEurostat("<html>wall</html>").isEmpty());
        assertTrue(StatsReleaseCalendarClient.parseBea(null).isEmpty());
        assertTrue(StatsReleaseCalendarClient.parseOns("[]").isEmpty());
    }

    // ---- MFN (nordische Pflichtmitteilungen) ---------------------------------

    @Test
    void mfnKeepsOneReleasePerGroupAndPrefersEnglish() {
        String json = """
                {"version":"https://jsonfeed.org/version/1","items":[
                 {"news_id":"a","group_id":"g1","url":"https://mfn.se/a/sv",
                  "properties":{"lang":"sv","tags":[":regulatory","sub:report:interim"]},
                  "subjects":[{"name":"WPTG","isins":["SE0020203271"],"tickers":["FNSE:WPTG B"]}],
                  "content":{"title":"WPTG genomför riktad emission","publish_date":"2026-08-01T12:55:00Z",
                   "text":"Svensk text"}},
                 {"news_id":"b","group_id":"g1","url":"https://mfn.se/a/en",
                  "properties":{"lang":"en","tags":[":regulatory"]},
                  "subjects":[{"name":"WPTG","isins":["SE0020203271"],"tickers":["FNSE:WPTG B"]}],
                  "content":{"title":"WPTG carries out directed issue","publish_date":"2026-08-01T12:55:00Z",
                   "text":"English text"}},
                 {"news_id":"c","group_id":"g2","url":"https://mfn.se/c",
                  "properties":{"lang":"en","tags":["ext:nq"]},
                  "subjects":[{"name":"Coor","isins":["SE0007158829"],"tickers":["XSTO:COOR"]}],
                  "content":{"title":"Coor interim report","publish_date":"2026-08-02T09:41:21Z","text":"x"}},
                 {"news_id":"d","group_id":"g3","content":{"title":"broken","publish_date":"nope"}}]}""";
        List<MfnDisclosureClient.Disclosure> items = MfnDisclosureClient.parse(json);
        // Two groups survive, the Swedish twin of g1 is dropped, the broken one too.
        assertEquals(2, items.size());
        // Newest first.
        assertEquals("Coor interim report", items.get(0).title());
        MfnDisclosureClient.Disclosure wptg = items.get(1);
        assertEquals("WPTG carries out directed issue", wptg.title());
        assertEquals("en", wptg.language());
        assertEquals(List.of("SE0020203271"), wptg.isins());
        assertEquals(List.of("FNSE:WPTG B"), wptg.tickers());
        assertTrue(wptg.regulatory());
        assertFalse(items.get(0).regulatory());

        assertTrue(MfnDisclosureClient.parse("<html>wall</html>").isEmpty());
        assertTrue(MfnDisclosureClient.parse(null).isEmpty());
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
