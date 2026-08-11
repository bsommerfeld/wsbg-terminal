package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live-probed Wikipedia/Wikidata shapes (fixtures fetched 2026-08-02):
 * the REST summary that ships the Q-id beside the lead, the pageprops batch
 * with its redirect and normalisation hops, the ISIN search that is the ONLY
 * safe way back into an item, the P414/P249 qualifier path that the near-empty
 * {@code wdt:P249} would have missed, and the daily pageviews.
 */
class WikidataClientTest {

    private static String fixture(String name) {
        try (InputStream in = WikidataClientTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    // ---- Wikipedia summary -------------------------------------------------

    @Test
    void summaryShipsTheQidBesideTheLeadWhichIsWhatMakesItTheSafeEntryPoint() {
        Optional<WikidataClient.PageSummary> page =
                WikidataClient.parseSummary(fixture("wikipedia-summary.json"));
        assertTrue(page.isPresent());
        assertEquals("SAP", page.get().title());
        assertEquals("Q552581", page.get().qid());
        assertEquals("deutscher Softwarehersteller", page.get().description());
        assertTrue(page.get().extract().startsWith("SAP ist ein"));
        assertEquals("https://de.wikipedia.org/wiki/SAP", page.get().url(),
                "the canonical link doubles as the CC BY-SA attribution");
    }

    @Test
    void summaryGarbageIsEmpty() {
        assertTrue(WikidataClient.parseSummary("<html>404</html>").isEmpty());
        assertTrue(WikidataClient.parseSummary("{\"type\":\"https://mediawiki.org/wiki/HyperSwitch/errors/not_found\"}").isEmpty());
        assertTrue(WikidataClient.parseSummary(null).isEmpty());
    }

    // ---- pageprops batch ---------------------------------------------------

    @Test
    void pagePropsResolvesTheBatchAndAnswersUnderTheCallersOwnSpelling() {
        Map<String, String> qids =
                WikidataClient.parsePageProps(fixture("wikipedia-pageprops.json")).orElseThrow();
        assertEquals("Q552581", qids.get("SAP"));
        assertEquals("Q552581", qids.get("SAP SE"), "the redirect source must find its own answer");
        assertEquals("Q125924224", qids.get("Nynomic"));
    }

    @Test
    void pagePropsDropsMissingPagesInsteadOfInventingAnItem() {
        Map<String, String> qids =
                WikidataClient.parsePageProps(fixture("wikipedia-pageprops.json")).orElseThrow();
        assertNull(qids.get("GibtEsNichtXY123"));
        assertNull(qids.get("siemens energy"),
                "titles are case-sensitive past the first letter - a normalised miss stays a miss");
    }

    @Test
    void pagePropsGarbageIsEmpty() {
        assertTrue(WikidataClient.parsePageProps("<html>wall</html>").isEmpty());
        assertTrue(WikidataClient.parsePageProps("{\"batchcomplete\":\"\"}").isEmpty());
        assertTrue(WikidataClient.parsePageProps(null).isEmpty());
    }

    // ---- ISIN → Q-id (the safe way back) -----------------------------------

    @Test
    void isinSearchIsTheOneLookupThatCannotReturnTheWrongCompany() {
        assertEquals(Optional.of("Q125924224"),
                WikidataClient.parseIsinSearch(fixture("wikidata-isin-search.json")),
                "DE000A0MSN11 is Nynomic - a small cap, and still findable this way");
    }

    @Test
    void isinSearchWithoutAHitIsEmptyNotAGuess() {
        assertTrue(WikidataClient.parseIsinSearch(
                "{\"query\":{\"searchinfo\":{\"totalhits\":0},\"search\":[]}}").isEmpty());
        assertTrue(WikidataClient.parseIsinSearch("<html>wall</html>").isEmpty());
    }

    // ---- the P414/P249 qualifier trap --------------------------------------

    @Test
    void tickersComeOffTheExchangeStatementQualifierNotTheBarePropertyPath() {
        List<WikidataClient.ExchangeTicker> tickers =
                WikidataClient.parseTickers(fixture("wikidata-tickers.json"));
        assertEquals(3, tickers.size(), "one listing per exchange, which wdt:P249 would never show");
        WikidataClient.ExchangeTicker nyse = tickers.get(0);
        assertEquals("Q13677", nyse.exchangeQid(), "the entity URI is cut back to the bare Q-id");
        assertEquals("New York Stock Exchange", nyse.exchange());
        assertEquals("SAP", nyse.ticker());
        assertTrue(tickers.stream().anyMatch(t -> t.exchange().equals("Börse Frankfurt")));
    }

    @Test
    void tickersDeduplicateAndSurviveABindingWithoutASymbol() {
        String body = """
                {"results":{"bindings":[
                  {"exchange":{"value":"http://www.wikidata.org/entity/Q13677"},
                   "exchangeLabel":{"value":"NYSE"},"ticker":{"value":"SAP"}},
                  {"exchange":{"value":"http://www.wikidata.org/entity/Q13677"},
                   "exchangeLabel":{"value":"NYSE"},"ticker":{"value":"SAP"}},
                  {"exchange":{"value":"http://www.wikidata.org/entity/Q151139"},
                   "exchangeLabel":{"value":"FWB"}}
                ]}}
                """;
        List<WikidataClient.ExchangeTicker> tickers = WikidataClient.parseTickers(body);
        assertEquals(1, tickers.size(), "a listing without a P249 qualifier carries no symbol to use");
        assertEquals("SAP", tickers.get(0).ticker());
    }

    @Test
    void tickersGarbageIsEmpty() {
        assertTrue(WikidataClient.parseTickers("<html>wall</html>").isEmpty());
        assertTrue(WikidataClient.parseTickers("{\"results\":{\"bindings\":[]}}").isEmpty());
        assertTrue(WikidataClient.parseTickers(null).isEmpty());
    }

    // ---- SPARQL facts ------------------------------------------------------

    @Test
    void factsReadTheOptionalPropertiesAsRawLiterals() {
        WikidataClient.CompanyFacts facts =
                WikidataClient.parseFacts("Q552581", fixture("wikidata-facts.json")).orElseThrow();
        assertEquals("Q552581", facts.qid());
        assertEquals("DE0007164600", facts.isin());
        assertEquals("529900D6BF99LW9R2E68", facts.lei());
        assertEquals("106043", facts.employees());
        assertEquals("31207000000", facts.revenue());
        assertEquals("1972-04-01T00:00:00Z", facts.inception());
        assertEquals("https://www.sap.com/", facts.website());
        assertTrue(facts.hasAnything());
    }

    @Test
    void factsOfAFragmentaryItemStayEmptyRatherThanPretend() {
        // Nynomic's shape: an ISIN and nothing else - and the all-empty binding
        // an item without a single one of these properties answers with.
        WikidataClient.CompanyFacts thin = WikidataClient.parseFacts("Q125924224",
                "{\"results\":{\"bindings\":[{\"isin\":{\"value\":\"DE000A0MSN11\"}}]}}").orElseThrow();
        assertEquals("DE000A0MSN11", thin.isin());
        assertEquals("", thin.employees());
        assertEquals("", thin.website());
        assertTrue(thin.hasAnything());
        assertTrue(WikidataClient.parseFacts("Q1", "{\"results\":{\"bindings\":[{}]}}").isEmpty(),
                "an item with none of the properties is not worth a card");
        assertTrue(WikidataClient.parseFacts("Q1", "<html>wall</html>").isEmpty());
    }

    // ---- pageviews ---------------------------------------------------------

    @Test
    void pageviewsBecomeDatedPointsInOrder() {
        List<WikidataClient.PageviewPoint> points =
                WikidataClient.parsePageviews(fixture("wikimedia-pageviews.json"));
        assertEquals(5, points.size());
        assertEquals(LocalDate.of(2026, 7, 28), points.get(0).day(),
                "the hour suffix of the timestamp is noise");
        assertTrue(points.get(0).views() > 0);
        assertEquals(LocalDate.of(2026, 8, 1), points.get(points.size() - 1).day());
    }

    @Test
    void pageviewsGarbageIsEmpty() {
        assertTrue(WikidataClient.parsePageviews("{\"type\":\".../not_found\"}").isEmpty());
        assertTrue(WikidataClient.parsePageviews("<html>wall</html>").isEmpty());
        assertTrue(WikidataClient.parsePageviews(null).isEmpty());
    }

    // ---- wiring ------------------------------------------------------------

    @Test
    void everyEndpointIsWiredAndSpeaksAContactableUserAgent() {
        RecordingFetcher seen = new RecordingFetcher();
        WikidataClient client = new WikidataClient(seen);
        assertTrue(client.summary("SAP SE").isEmpty());
        assertTrue(client.qidsFor(List.of("SAP", "Nynomic")).isEmpty());
        assertTrue(client.qidForIsin("de000a0msn11").isEmpty());
        assertTrue(client.facts("Q552581").isEmpty());
        assertTrue(client.tickers("http://www.wikidata.org/entity/Q552581").isEmpty());
        assertTrue(client.pageviews("SAP", LocalDate.of(2026, 7, 28), LocalDate.of(2026, 8, 1)).isEmpty());

        assertTrue(seen.urls.get(0).endsWith("/page/summary/SAP_SE"),
                "spaces travel as underscores on the REST path: " + seen.urls.get(0));
        assertTrue(seen.urls.get(1).contains("titles=SAP%7CNynomic"), seen.urls.get(1));
        assertTrue(seen.urls.get(1).contains("redirects=1"));
        assertTrue(seen.urls.get(2).contains("haswbstatement:P946=DE000A0MSN11"),
                "the ISIN is upper-cased before it is asked for: " + seen.urls.get(2));
        assertTrue(seen.urls.get(3).contains("wdt%3AP946"), seen.urls.get(3));
        assertTrue(seen.urls.get(4).contains("p%3AP414") && seen.urls.get(4).contains("pq%3AP249"),
                "the ticker query MUST walk the qualifier, never wdt:P249: " + seen.urls.get(4));
        assertFalse(seen.urls.stream().anyMatch(u -> u.contains("wbsearchentities")),
                "there is deliberately no guessing path in this client");
        assertTrue(seen.urls.get(5).endsWith("/SAP/daily/20260728/20260801"), seen.urls.get(5));
        assertTrue(seen.userAgents.stream().allMatch(ua -> ua.startsWith("wsbg-terminal/")),
                "Wikimedia asks for a speaking agent, not a random browser one");
    }

    @Test
    void aNameIsOnlyEverTrustedOnceAnIsinConfirmsIt() {
        // The H&M trap: an item that looks right by name but is a different
        // company must not come back as a resolution.
        WikidataClient client = new WikidataClient(new StubFetcher(Map.of(
                "page/summary/SAP", fixture("wikipedia-summary.json"),
                "query.wikidata.org", fixture("wikidata-facts.json"))));
        assertEquals(Optional.of("Q552581"), client.qidForNameVerifiedByIsin("SAP", "DE0007164600"));
        assertTrue(client.qidForNameVerifiedByIsin("SAP", "SE0000106270").isEmpty(),
                "the same article must NOT validate H&M's ISIN");
        assertTrue(client.qidForNameVerifiedByIsin("SAP", "").isEmpty());
    }

    /** A transport that answers nothing and remembers what was asked for. */
    private static final class RecordingFetcher
            implements de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher {

        private final List<String> urls = new ArrayList<>();
        private final List<String> userAgents = new ArrayList<>();

        @Override
        public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse fetch(
                String url, Map<String, String> headers, Duration timeout,
                de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil... modes) {
            urls.add(url);
            userAgents.add(headers.getOrDefault("User-Agent", ""));
            return null;
        }

        @Override
        public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse fetchBinary(
                String url, Map<String, String> headers, Duration timeout,
                de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse post(
                String url, Map<String, String> headers, String body, String contentType,
                Duration timeout, de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hostCoolingDown(String url) {
            return false;
        }
    }

    /** Answers a fixture for whichever URL fragment matches. */
    private static final class StubFetcher
            implements de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher {

        private final Map<String, String> bodies;

        StubFetcher(Map<String, String> bodies) {
            this.bodies = bodies;
        }

        @Override
        public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse fetch(
                String url, Map<String, String> headers, Duration timeout,
                de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil... modes) {
            for (Map.Entry<String, String> e : bodies.entrySet()) {
                if (url.contains(e.getKey())) {
                    return de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse.text(
                            200, e.getValue(), Map.of());
                }
            }
            return null;
        }

        @Override
        public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse fetchBinary(
                String url, Map<String, String> headers, Duration timeout,
                de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse post(
                String url, Map<String, String> headers, String body, String contentType,
                Duration timeout, de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hostCoolingDown(String url) {
            return false;
        }
    }
}
