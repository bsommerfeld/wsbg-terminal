package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arXiv shelf's parse surface - what a captured answer becomes. */
class ArxivSearchClientTest {

    /** A shortened arXiv Atom answer, kept in the shape the API returns it. */
    private static final String ATOM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>ArXiv Query: all:solid state battery</title>
              <entry>
                <id>http://arxiv.org/abs/2401.01234v1</id>
                <published>2024-01-02T18:30:00Z</published>
                <title>Interface Degradation in
                  Solid-State Cells</title>
                <summary>  We measure the interface layer
                  over 400 cycles.  </summary>
                <author><name>A. Autor</name></author>
                <link href="http://arxiv.org/abs/2401.01234v1" rel="alternate" type="text/html"/>
                <link title="pdf" href="http://arxiv.org/pdf/2401.01234v1" rel="related"/>
              </entry>
              <entry>
                <id>http://arxiv.org/abs/2402.00777v2</id>
                <published>kaputt</published>
                <title>Cathode Supply Chains</title>
                <summary>Second paper.</summary>
              </entry>
              <entry>
                <summary>An entry without a title falls.</summary>
              </entry>
            </feed>
            """;

    @Test
    void arxivAtomBecomesResearchItems() {
        List<Article> items = ArxivSearchClient.parseAtomArxiv(ATOM, 10);
        assertEquals(2, items.size(), "the titleless entry must fall");

        Article first = items.get(0);
        assertEquals("Interface Degradation in Solid-State Cells", first.title());
        assertEquals("http://arxiv.org/abs/2401.01234v1", first.link());
        assertEquals("arXiv", first.publisher());
        assertEquals("We measure the interface layer over 400 cycles.", first.summary());
        assertEquals(Instant.parse("2024-01-02T18:30:00Z"), first.publishedAt());

        Article second = items.get(1);
        assertEquals("Cathode Supply Chains", second.title());
        // No alternate link: the entry id carries the abs page.
        assertEquals("http://arxiv.org/abs/2402.00777v2", second.link());
        // An unreadable date costs the timestamp, never the find.
        assertTrue(second.publishedAt() == null);
    }

    @Test
    void theLimitCapsTheHarvestAndJunkStaysEmpty() {
        assertEquals(1, ArxivSearchClient.parseAtomArxiv(ATOM, 1).size());
        assertNotNull(ArxivSearchClient.parseAtomArxiv(null, 5));
        assertTrue(ArxivSearchClient.parseAtomArxiv(null, 5).isEmpty());
        assertTrue(ArxivSearchClient.parseAtomArxiv("<html>nope</html>", 5).isEmpty());
    }

    @Test
    void theQueryIsAskedOfTheAtomApiAndAnErrorStaysEmpty() {
        String[] gefragt = new String[1];
        WebFetcher fetcher = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                gefragt[0] = url;
                return WebResponse.text(200, ATOM, Map.of());
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return false;
            }
        };
        List<Article> items = new ArxivSearchClient(fetcher)
                .search("solid state battery", 10);
        assertEquals(2, items.size());
        assertEquals("http://export.arxiv.org/api/query?search_query=all:"
                + "solid+state+battery&max_results=10&sortBy=relevance", gefragt[0]);

        WebFetcher kaputt = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) throws Exception {
                throw new IllegalStateException("no route");
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return false;
            }
        };
        assertTrue(new ArxivSearchClient(kaputt).search("x", 5).isEmpty());
    }
}
