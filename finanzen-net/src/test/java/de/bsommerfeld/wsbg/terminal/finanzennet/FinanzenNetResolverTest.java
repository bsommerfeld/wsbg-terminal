package de.bsommerfeld.wsbg.terminal.finanzennet;

import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetResolver.InstrumentMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Suggest-parser and resolution tests against live fixtures (2026-08-02). */
class FinanzenNetResolverTest {

    @Test
    void resolvesNameToEveryIdentifierAndTheSlug() throws Exception {
        List<InstrumentMatch> hits =
                FinanzenNetResolver.parseSuggest(Fixtures.load("fn-suggest-sap.txt"));

        assertFalse(hits.isEmpty());
        InstrumentMatch sap = hits.get(0);
        assertEquals("SAP SE", sap.name());
        assertEquals("sap", sap.slug());
        assertEquals("716460", sap.wkn());
        assertEquals("DE0007164600", sap.isin());
        assertEquals(List.of("SAPGF", "SAP", "SAPG"), sap.tickers());
        assertEquals("Aktien", sap.category());
        assertEquals(578, sap.internalId());
    }

    @Test
    void slugBuildsEverySubPageDeterministically() throws Exception {
        InstrumentMatch sap =
                FinanzenNetResolver.parseSuggest(Fixtures.load("fn-suggest-sap.txt")).get(0);

        assertEquals("https://www.finanzen.net/boersenplaetze/sap", sap.pageUrl("boersenplaetze"));
        assertEquals("https://www.finanzen.net/kursziele/sap", sap.pageUrl("kursziele"));
        assertEquals("https://www.finanzen.net/rss/sap-rss-feed", sap.rssUrl());
    }

    @Test
    void isinQueryResolvesToTheSlugOfASmallCap() throws Exception {
        FinanzenNetResolver resolver = new FinanzenNetResolver(new Fixtures.FakeFetcher(
                Map.of("SearchController_Suggest", Fixtures.load("fn-suggest-artec-isin.txt"))));

        Optional<InstrumentMatch> artec = resolver.resolveByIsin("DE0005209589");

        assertTrue(artec.isPresent());
        assertEquals("artec_technologies", artec.get().slug());
        assertEquals("520958", artec.get().wkn());
        assertEquals("artec technologies AG", artec.get().name());
        assertTrue(artec.get().tickers().isEmpty(), "artec carries no ticker slot");
    }

    @Test
    void wknAndTickerBothResolveToTheSameInstrument() throws Exception {
        FinanzenNetResolver byWkn = new FinanzenNetResolver(new Fixtures.FakeFetcher(
                Map.of("SearchController_Suggest", Fixtures.load("fn-suggest-sap.txt"))));
        FinanzenNetResolver byTicker = new FinanzenNetResolver(new Fixtures.FakeFetcher(
                Map.of("SearchController_Suggest", Fixtures.load("fn-suggest-nvda.txt"))));

        assertEquals("sap", byWkn.resolveByWkn("716460").orElseThrow().slug());
        assertEquals("nvidia", byTicker.resolveByTicker("NVDA").orElseThrow().slug());
        assertEquals("US67066G1040", byTicker.resolveByTicker("NVDA").orElseThrow().isin());
    }

    @Test
    void resolveRanksTheExactHitAboveTheSuggestOrder() throws Exception {
        FinanzenNetResolver resolver = new FinanzenNetResolver(new Fixtures.FakeFetcher(
                Map.of("SearchController_Suggest", Fixtures.load("fn-suggest-nvda.txt"))));

        List<InstrumentMatch> hits = resolver.resolve("NVDA");

        assertEquals("nvidia", hits.get(0).slug(), "the ticker owner must lead");
        assertTrue(hits.size() > 1, "the suggest tail (Thai Oil, Nedap …) is kept, just ranked down");
    }

    @Test
    void advertisingRowsAreDropped() throws Exception {
        String payload = Fixtures.load("fn-suggest-artec-isin.txt");
        assertTrue(payload.contains("Anzeige"), "fixture must carry paid slots");

        List<InstrumentMatch> hits = FinanzenNetResolver.parseSuggest(payload);

        assertEquals(1, hits.size(), "only the instrument survives");
        hits.forEach(m -> {
            assertFalse(m.slug().startsWith("http"), "an ad's IDs slot is a tracking URL");
            assertFalse(m.name().contains("<"), "an ad's name slot is markup");
        });
    }

    @Test
    void emptyIdentifierSlotsStayNullInsteadOfShiftingUp() throws Exception {
        List<InstrumentMatch> hits =
                FinanzenNetResolver.parseSuggest(Fixtures.load("fn-suggest-sap.txt"));

        InstrumentMatch adr = hits.stream()
                .filter(m -> "saputo_1".equals(m.slug()))
                .findFirst()
                .orElseThrow();
        assertNull(adr.wkn(), "the unsponsored ADR carries no WKN");
        assertEquals("US8029122046", adr.isin(), "the ISIN must not slide into the WKN slot");
        assertEquals(List.of("SAPUY"), adr.tickers());
    }

    @Test
    void venueQualifiedKeywordsAreNotMistakenForTickers() throws Exception {
        InstrumentMatch artec =
                FinanzenNetResolver.parseSuggest(Fixtures.load("fn-suggest-artec-isin.txt")).get(0);

        assertTrue(artec.tickers().isEmpty(),
                "'2595089,FRA' is a venue-local code, not a ticker");
    }

    @Test
    void resolverIsCachedPerQuery() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(
                Map.of("SearchController_Suggest", Fixtures.load("fn-suggest-sap.txt")));
        FinanzenNetResolver resolver = new FinanzenNetResolver(fetcher);

        resolver.resolve("SAP");
        resolver.resolve("SAP");
        resolver.slugFor("SAP");

        assertEquals(1, fetcher.count("SearchController_Suggest"));
    }

    @Test
    void suggestionsKeepTheServerOrderAndRespectTheLimit() throws Exception {
        FinanzenNetResolver resolver = new FinanzenNetResolver(new Fixtures.FakeFetcher(
                Map.of("SearchController_Suggest", Fixtures.load("fn-suggest-sap.txt"))));

        List<InstrumentMatch> raw = resolver.suggestions("SAP", 3);

        assertEquals(3, raw.size());
        assertEquals("sap", raw.get(0).slug());
        assertEquals("sap_1", raw.get(1).slug(), "server order, not re-ranked");
    }

    @Test
    void garbageAndMissesYieldEmptyNotException() {
        assertTrue(FinanzenNetResolver.parseSuggest("").isEmpty());
        assertTrue(FinanzenNetResolver.parseSuggest(null).isEmpty());
        assertTrue(FinanzenNetResolver.parseSuggest("<html>403 Forbidden</html>").isEmpty());
        assertTrue(FinanzenNetResolver.parseSuggest("mmSuggestDeliver(0, , 0);").isEmpty());

        FinanzenNetResolver resolver = new FinanzenNetResolver(new Fixtures.FakeFetcher(Map.of()));
        assertTrue(resolver.resolve(null).isEmpty());
        assertTrue(resolver.resolve("  ").isEmpty());
        assertTrue(resolver.resolveByIsin("not-an-isin").isEmpty());
        assertTrue(resolver.suggestions("SAP", 0).isEmpty());
    }

    @Test
    void isinLookupRefusesANearMiss() throws Exception {
        FinanzenNetResolver resolver = new FinanzenNetResolver(new Fixtures.FakeFetcher(
                Map.of("SearchController_Suggest", Fixtures.load("fn-suggest-sap.txt"))));

        // A well-formed ISIN that the suggest answer does not actually carry.
        assertTrue(resolver.resolveByIsin("DE0007164601").isEmpty(),
                "a wrong ISIN must never fall through to the first suggestion");
    }

    @Test
    void theWallKeyIsSentOnEveryRequest() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(
                Map.of("SearchController_Suggest", Fixtures.load("fn-suggest-sap.txt")));
        new FinanzenNetResolver(fetcher).resolve("SAP");

        Map<String, String> sent = fetcher.headers.get(0);
        for (String required : List.of("User-Agent", "Accept", "Accept-Language",
                "Accept-Encoding", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
                "Sec-Fetch-Dest", "Sec-Fetch-Mode", "Sec-Fetch-Site", "Sec-Fetch-User",
                "Upgrade-Insecure-Requests")) {
            assertTrue(sent.containsKey(required), "Akamai needs " + required);
        }
    }
}
