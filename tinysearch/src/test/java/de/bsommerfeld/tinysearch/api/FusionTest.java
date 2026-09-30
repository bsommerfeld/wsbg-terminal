package de.bsommerfeld.tinysearch.api;

import de.bsommerfeld.tinysearch.page.PageHit;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FusionTest {

    @Test
    void aPageFoundByTwoEnginesIsOneHitAndRisesAboveSingleFinds() {
        Map<SearchEngine, List<PageHit>> pages = new LinkedHashMap<>();
        pages.put(SearchEngine.BING, List.of(
                hit("https://bing-only.example/", "Bing only", "from bing"),
                hit("https://www.shared.example/page/", "Shared, Bing's title", "")));
        pages.put(SearchEngine.BRAVE, List.of(
                hit("http://shared.example/page", "Shared, Brave's title", "brave's snippet"),
                hit("https://brave-only.example/", "Brave only", "")));

        List<SearchHit> merged = Fusion.merge(pages);

        assertEquals(List.of("http://shared.example/page", "https://bing-only.example/", "https://brave-only.example/"),
                merged.stream().map(SearchHit::url).map(URI::toString).toList());
        SearchHit shared = merged.getFirst();
        assertEquals(Map.of(SearchEngine.BING, 2, SearchEngine.BRAVE, 1), shared.ranks());
        assertEquals("Shared, Brave's title", shared.title(), "the best place names it - and gives the address");
        assertEquals("brave's snippet", shared.snippet());
    }

    @Test
    void aMissingSnippetIsTakenFromAnotherEngine() {
        Map<SearchEngine, List<PageHit>> pages = new LinkedHashMap<>();
        pages.put(SearchEngine.BRAVE, List.of(hit("https://a.example/", "A", "")));
        pages.put(SearchEngine.YAHOO, List.of(hit("https://a.example/", "A at Yahoo", "yahoo's snippet")));

        SearchHit only = Fusion.merge(pages).getFirst();

        assertEquals("A", only.title());
        assertEquals("yahoo's snippet", only.snippet());
    }

    @Test
    void anEngineListingAPageTwiceCountsItOnce() {
        Map<SearchEngine, List<PageHit>> pages = Map.of(SearchEngine.BING, List.of(
                hit("https://a.example/", "A", ""),
                hit("https://a.example", "A again", ""),
                hit("https://b.example/", "B", "")));

        List<SearchHit> merged = Fusion.merge(pages);

        assertEquals(2, merged.size());
        assertEquals(Map.of(SearchEngine.BING, 2), merged.get(1).ranks(), "B moves up to second place");
    }

    @Test
    void theKeyIgnoresSchemeWwwTrailingSlashAndFragment() {
        assertEquals(Fusion.key(URI.create("http://www.Example.org/a/?q=1#top")),
                Fusion.key(URI.create("https://example.org/a?q=1")));
        assertEquals("example.org/a?q=1", Fusion.key(URI.create("https://example.org/a?q=1")));
    }

    private static PageHit hit(String url, String title, String snippet) {
        return new PageHit(URI.create(url), title, snippet);
    }
}
