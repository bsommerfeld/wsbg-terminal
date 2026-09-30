package de.bsommerfeld.tinysearch.api;

import de.bsommerfeld.tinyfetch.api.CooldownException;
import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyfetch.api.Wall;
import de.bsommerfeld.tinysearch.api.EngineReport.Outcome;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TinySearchTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-30T12:00:00Z"));
    private final ScriptedFetcher engines = new ScriptedFetcher();

    private TinySearch search() {
        return TinySearch.builder(engines).clock(now::get).build();
    }

    @Test
    void asksOnlyTheEnginesNamedAndMergesTheirHits() throws Exception {
        engines.answer("search.brave.com", page("""
                <main id="search-page">
                  <div class="snippet" data-type="web"><a href="https://shared.example/">
                    <div class="title">Shared at Brave</div></a>
                    <div class="generic-snippet"><div class="content">from brave</div></div></div>
                  <div class="snippet" data-type="web"><a href="https://brave.example/"><div class="title">Brave</div></a></div>
                </main>"""));
        engines.answer("www.startpage.com", page("""
                <div class="w-gl"><div class="result">
                  <a class="result-title" href="https://www.shared.example"><h2>Shared at Startpage</h2></a>
                  <p class="description">from startpage</p></div></div>"""));

        SearchResults results = search().search("  SAP Aktie ", SearchEngine.BRAVE, SearchEngine.STARTPAGE);

        assertEquals("SAP Aktie", results.query());
        assertEquals(Set.of("search.brave.com", "www.startpage.com"), engines.hostsAsked());
        assertEquals(List.of(URI.create("https://shared.example/"), URI.create("https://brave.example/")),
                results.urls(), "found by both, first on each - the first engine names it");
        assertEquals("from brave", results.hits().getFirst().snippet());
        assertEquals(Map.of(SearchEngine.BRAVE, 1, SearchEngine.STARTPAGE, 1), results.hits().getFirst().ranks());
        assertEquals(List.of(SearchEngine.BRAVE, SearchEngine.STARTPAGE), List.copyOf(results.reports().keySet()));
        assertEquals(new EngineReport(SearchEngine.BRAVE, Outcome.ANSWERED, 2, ""),
                results.reports().get(SearchEngine.BRAVE));
        assertEquals(2, results.hits(SearchEngine.BRAVE).size());
        assertEquals(1, results.hits(SearchEngine.STARTPAGE).size());
        assertEquals("https://search.brave.com/search?q=SAP+Aktie&source=web",
                engines.requests.stream().filter(request -> request.host().equals("search.brave.com"))
                        .findFirst().orElseThrow().uri().toString());
    }

    @Test
    void everyEngineWithoutAList() throws Exception {
        SearchResults results = search().search("DAX");

        assertEquals(EnumSet.allOf(SearchEngine.class), results.reports().keySet());
        assertEquals(EnumSet.allOf(SearchEngine.class).stream().map(SearchEngine::host).collect(Collectors.toSet()),
                engines.hostsAsked());
    }

    @Test
    void anEngineThatFailsLeavesTheOthersStanding() throws Exception {
        engines.answer("www.bing.com", response(429, "slow down", Wall.THROTTLED));
        engines.fail("search.brave.com", new CooldownException("search.brave.com",
                Instant.parse("2026-09-30T13:00:00Z"), Wall.CHALLENGE));
        engines.fail("html.duckduckgo.com", new FetchException("timeout"));
        engines.answer("www.ecosia.org", response(500, "oops", Wall.NONE));
        engines.answer("www.startpage.com", page("<div class=\"noresults\">Uh-oh, there are no results</div>"));

        SearchResults results = search().search("SAP Aktie", EnumSet.of(SearchEngine.BING, SearchEngine.BRAVE,
                SearchEngine.DUCKDUCKGO, SearchEngine.ECOSIA, SearchEngine.STARTPAGE));

        assertEquals(List.of(), results.hits());
        assertEquals(Outcome.REFUSED, outcome(results, SearchEngine.BING));
        assertEquals("HTTP 429 THROTTLED", results.reports().get(SearchEngine.BING).detail());
        assertEquals(Outcome.PAUSED, outcome(results, SearchEngine.BRAVE));
        assertEquals("host paused after CHALLENGE until 2026-09-30T13:00:00Z",
                results.reports().get(SearchEngine.BRAVE).detail());
        assertEquals(Outcome.FAILED, outcome(results, SearchEngine.DUCKDUCKGO));
        assertEquals(Outcome.FAILED, outcome(results, SearchEngine.ECOSIA));
        assertEquals("HTTP 500", results.reports().get(SearchEngine.ECOSIA).detail());
        assertEquals(new EngineReport(SearchEngine.STARTPAGE, Outcome.ANSWERED, 0, ""),
                results.reports().get(SearchEngine.STARTPAGE), "found nothing - but answered");
    }

    @Test
    void anUnreadablePageLeavesTheEngineAloneForTheDemotion() throws Exception {
        engines.answer("yandex.com", response(200,
                "<html><head><title>Are you not a robot?</title></head><body><form>captcha</form></body></html>",
                Wall.NONE));
        TinySearch search = search();

        SearchResults first = search.search("DAX", SearchEngine.YANDEX);
        assertEquals(Outcome.UNREADABLE, outcome(first, SearchEngine.YANDEX));
        assertEquals("not a result page: \"Are you not a robot?\" - left alone until 2026-09-30T12:30:00Z",
                first.reports().get(SearchEngine.YANDEX).detail());
        assertEquals(Instant.parse("2026-09-30T12:30:00Z"), search.demotedUntil(SearchEngine.YANDEX).orElseThrow());

        engines.requests.clear();
        SearchResults second = search.search("DAX", SearchEngine.YANDEX);
        assertEquals(Outcome.PAUSED, outcome(second, SearchEngine.YANDEX));
        assertTrue(engines.requests.isEmpty(), "not asked while left alone");

        now.set(Instant.parse("2026-09-30T12:30:01Z"));
        engines.answer("yandex.com", page("<div id=\"search-result\"></div>"));
        SearchResults third = search.search("DAX", SearchEngine.YANDEX);
        assertEquals(Outcome.ANSWERED, outcome(third, SearchEngine.YANDEX));
        assertTrue(search.demotedUntil(SearchEngine.YANDEX).isEmpty());
    }

    @Test
    void refusesWhatCannotBeSearched() {
        TinySearch search = search();

        assertThrows(IllegalArgumentException.class, () -> search.search("   "));
        assertThrows(IllegalArgumentException.class, () -> search.search("DAX", Set.of()));
    }

    @Test
    void everyEngineHasAPaceAndItsPageGoesToItsHost() {
        Map<String, ?> policies = TinySearch.hostPolicies();

        for (SearchEngine engine : SearchEngine.values()) {
            assertTrue(policies.containsKey(engine.host()), engine.toString());
        }
        assertEquals(SearchEngine.values().length, policies.size());
    }

    // ---- helpers ------------------------------------------------------------

    private static Outcome outcome(SearchResults results, SearchEngine engine) {
        return results.reports().get(engine).outcome();
    }

    private static FetchResponse page(String body) {
        return response(200, "<html><body>" + body + "</body></html>", Wall.NONE);
    }

    private static FetchResponse response(int status, String body, Wall wall) {
        return new FetchResponse(status, URI.create("https://unused.example/"),
                Map.of("content-type", List.of("text/html; charset=utf-8")),
                body.getBytes(StandardCharsets.UTF_8), wall);
    }

    /** Answers by host; a host without an answer gets an empty page of no engine. */
    private static final class ScriptedFetcher implements Fetcher {

        final List<FetchRequest> requests = new CopyOnWriteArrayList<>();
        private final Map<String, FetchResponse> answers = new ConcurrentHashMap<>();
        private final Map<String, FetchException> failures = new ConcurrentHashMap<>();

        void answer(String host, FetchResponse response) {
            answers.put(host, response);
        }

        void fail(String host, FetchException failure) {
            failures.put(host, failure);
        }

        Set<String> hostsAsked() {
            return requests.stream().map(FetchRequest::host).collect(Collectors.toSet());
        }

        @Override
        public FetchResponse fetch(FetchRequest request) throws FetchException {
            requests.add(request);
            FetchException failure = failures.get(request.host());
            if (failure != null) {
                throw failure;
            }
            FetchResponse answer = answers.get(request.host());
            return answer != null ? answer : page("");
        }
    }
}
