package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The encyclopaedia shelf: the REST search read and the page links it hands over. */
class WikipediaSearchClientTest {

    /** A shortened answer of {@code /w/rest.php/v1/search/page}. */
    private static final String JSON = """
            {"pages":[
              {"id":4711,"key":"SAP","title":"SAP","excerpt":"Softwarekonzern",
               "description":"deutsches Unternehmen","thumbnail":null},
              {"id":815,"key":"SAP_Arena","title":"SAP \\"Arena\\"","excerpt":"Halle",
               "description":"Mehrzweckhalle","thumbnail":null}
            ]}
            """;

    private static final class FakeWiki implements WebFetcher {

        String gefragt;

        @Override
        public String name() {
            return "fake-wiki";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            gefragt = url;
            return new WebResponse(200, JSON, Map.of());
        }
    }

    @Test
    void theSearchHandsOverPageLinksOfTheAskedLanguageEdition() {
        FakeWiki wiki = new FakeWiki();
        List<RawNewsItem> items = new WikipediaSearchClient(wiki, true).search("SAP SE", 5);
        assertEquals("https://de.wikipedia.org/w/rest.php/v1/search/page?limit=5&q=SAP+SE",
                wiki.gefragt);
        assertEquals(2, items.size());
        assertEquals("https://de.wikipedia.org/wiki/SAP", items.get(0).link());
        assertEquals("SAP", items.get(0).title());
        assertEquals("Wikipedia", items.get(0).publisher());
        // An escaped quotation mark in the title arrives unescaped.
        assertEquals("SAP \"Arena\"", items.get(1).title());
    }

    @Test
    void theEnglishEditionIsItsOwnHostAndAFailedCallStaysEmpty() {
        FakeWiki wiki = new FakeWiki();
        new WikipediaSearchClient(wiki, false).search("SAP SE", 5);
        assertTrue(wiki.gefragt.startsWith("https://en.wikipedia.org/"));

        WebFetcher kaputt = new WebFetcher() {
            @Override
            public String name() {
                return "kaputt";
            }

            @Override
            public WebResponse fetch(String url, Map<String, String> headers,
                    Duration timeout) {
                return new WebResponse(503, "", Map.of());
            }
        };
        assertTrue(new WikipediaSearchClient(kaputt, true).search("SAP", 5).isEmpty());
    }
}
