package de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The onvista articles-finder leg, network-free against trimmed live shapes
 * (query reply probed 2026-07-12, finder teaser probed 2026-07-16). The
 * behaviours that matter: STOCK-only entity pick (a FUND ISIN is a miss),
 * ISIN-first addressing with the name as fallback key, the
 * {@code urls.WEBSITE} link shape, and the defensive article-array read.
 */
class OnvistaNewsSourceTest {

    /** Trimmed live query reply: Rheinmetall by ISIN. */
    private static final String QUERY_STOCK = """
            {"searchValue":"DE0007030009","list":[
              {"type":"Instrument","entityType":"STOCK","entityValue":"82811",
               "name":"Rheinmetall","isin":"DE0007030009","wkn":"703000","symbol":"RHM"}]}""";

    /** Trimmed live query reply: an ETF resolves as FUND — definite miss. */
    private static final String QUERY_FUND = """
            {"searchValue":"IE00B4L5Y983","list":[
              {"type":"Instrument","entityType":"FUND","entityValue":"25096683",
               "name":"iShares Core MSCI World UCITS ETF USD Acc.","isin":"IE00B4L5Y983"}]}""";

    /** One finder page in the live teaser shape ({@code urls.WEBSITE}, publisher object). */
    private static final String FINDER_PAGE = """
            {"list":[
              {"entityType":"ARTICLE","entityValue":"26536937",
               "urls":{"WEBSITE":"https://www.onvista.de/news/2026/07-28-aktie-im-fokus"},
               "headline":"AKTIE IM FOKUS: Rheinmetall setzt Rally fort",
               "publisher":{"id":10,"name":"dpa-AFX"},
               "datetimePublication":"2026-07-28T15:30:26.000+00:00"},
              {"entityType":"ARTICLE","entityValue":"26536111",
               "urls":{"WEBSITE":"https://www.onvista.de/news/2026/07-27-eqs-news"},
               "headline":"EQS-News: Rheinmetall AG meldet Auftragseingang",
               "publisher":{"id":12,"name":"EQS Group"},
               "datetimePublication":"2026-07-27T08:00:00.000+00:00"},
              {"entityType":"ARTICLE","entityValue":"26535000",
               "headline":"Undatierter Teaser ohne Zeitstempel"}]}""";

    private static final String EMPTY_PAGE = "{\"list\":[]}";

    /**
     * A fetcher that answers a scripted queue of bodies in order, recording the
     * URLs it was asked for. A body of {@code null} means HTTP 500 so failure
     * paths stay testable.
     */
    private static final class ScriptedFetcher implements WebFetcher {
        private final Deque<String> bodies;
        final List<String> urls = new ArrayList<>();

        ScriptedFetcher(String... bodies) {
            this.bodies = new ArrayDeque<>();
            for (String b : bodies) this.bodies.add(b == null ? "" : b);
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            urls.add(url);
            String body = bodies.poll();
            if (body == null || body.isEmpty()) return WebResponse.text(500, "", Map.of());
            return WebResponse.text(200, body, Map.of());
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
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
    }

    @Test
    void picksStockEntityByIsin() {
        assertEquals("82811", OnvistaNewsSource.parseStockEntity(QUERY_STOCK, "DE0007030009"));
        assertNull(OnvistaNewsSource.parseStockEntity(QUERY_STOCK, "US0000000000"),
                "an ISIN key must match the row's ISIN exactly");
        assertEquals("82811", OnvistaNewsSource.parseStockEntity(QUERY_STOCK, "Rheinmetall"),
                "a name key takes the first STOCK row - onvista ranks its own index");
    }

    @Test
    void fundIsAMiss() {
        assertNull(OnvistaNewsSource.parseStockEntity(QUERY_FUND, "IE00B4L5Y983"));
        assertNull(OnvistaNewsSource.parseStockEntity(QUERY_FUND, "iShares Core MSCI World"));
    }

    @Test
    void garbledQueryBodiesNeverThrow() {
        assertNull(OnvistaNewsSource.parseStockEntity("not json", "DE0007030009"));
        assertNull(OnvistaNewsSource.parseStockEntity("{\"error\":\"oops\"}", "DE0007030009"));
    }

    @Test
    void isinResolvesTheEntityAndTheFinderAnswersNewestFirst() throws Exception {
        ScriptedFetcher fetcher = new ScriptedFetcher(QUERY_STOCK, FINDER_PAGE, EMPTY_PAGE);
        OnvistaNewsSource source = new OnvistaNewsSource(fetcher);

        List<Article> items = source.newsFor(new ResolvedInstrument(
                Isin.parse("DE0007030009"), Optional.empty(), "Rheinmetall AG"), 10);

        assertEquals(2, items.size(), "the undated teaser is dropped, never guessed a timestamp");
        Article first = items.get(0);
        assertTrue(first.title().startsWith("AKTIE IM FOKUS"));
        assertEquals("dpa-AFX", first.publisher());
        assertEquals("https://www.onvista.de/news/2026/07-28-aktie-im-fokus", first.link());
        assertEquals(Instant.parse("2026-07-28T15:30:26Z"), first.publishedAt());
        assertTrue(first.uuid().startsWith("onvista-82811-"),
                "identity is entity + instant + headline hash - stable across refetches");

        assertTrue(fetcher.urls.get(0).contains("searchValue=DE0007030009"),
                "ISIN-addressed first - never a same-named twin");
        assertTrue(fetcher.urls.get(1).contains("entityValue=82811"));
        assertTrue(fetcher.urls.get(2).contains("page=1"),
                "the finder pages on until a page comes back empty or the limit fills");
    }

    @Test
    void limitStopsThePagingEarly() throws Exception {
        ScriptedFetcher fetcher = new ScriptedFetcher(QUERY_STOCK, FINDER_PAGE);
        OnvistaNewsSource source = new OnvistaNewsSource(fetcher);

        List<Article> items = source.newsFor(new ResolvedInstrument(
                Isin.parse("DE0007030009"), Optional.empty(), ""), 1);

        assertEquals(1, items.size());
        assertEquals(2, fetcher.urls.size(), "query + ONE finder page - the limit was full");
    }

    @Test
    void nameIsTheFallbackKeyWhenTheIsinMisses() throws Exception {
        // ISIN query resolves no STOCK (the FUND reply), name query hits.
        ScriptedFetcher fetcher = new ScriptedFetcher(
                QUERY_FUND, QUERY_STOCK, FINDER_PAGE, EMPTY_PAGE);
        OnvistaNewsSource source = new OnvistaNewsSource(fetcher);

        List<Article> items = source.newsFor(new ResolvedInstrument(
                Isin.parse("IE00B4L5Y983"), Optional.empty(), "Rheinmetall"), 5);

        assertEquals(2, items.size());
        assertTrue(fetcher.urls.get(1).contains("searchValue=Rheinmetall"),
                "the canonical name is the fallback key");
    }

    @Test
    void noKeyOrOutageAnswersEmptyNeverThrows() throws Exception {
        OnvistaNewsSource dead = new OnvistaNewsSource(new ScriptedFetcher((String) null));
        assertTrue(dead.newsFor(new ResolvedInstrument(
                Isin.parse("DE0007030009"), Optional.empty(), ""), 5).isEmpty());

        OnvistaNewsSource idle = new OnvistaNewsSource(new ScriptedFetcher());
        assertTrue(idle.newsFor(null, 5).isEmpty());
        assertTrue(idle.newsFor(ResolvedInstrument.ofName(""), 5).isEmpty());
        assertTrue(idle.newsFor(ResolvedInstrument.ofName("x"), 0).isEmpty());
    }

    @Test
    void articleArrayIsReadDefensively() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertEquals(1, OnvistaNewsSource.articleArray(
                json.readTree("{\"list\":[{\"headline\":\"a\"}]}")).size());
        assertEquals(1, OnvistaNewsSource.articleArray(
                json.readTree("{\"articles\":[{\"headline\":\"a\"}]}")).size());
        assertEquals(1, OnvistaNewsSource.articleArray(
                json.readTree("{\"whatever\":[{\"headline\":\"a\"}]}")).size(),
                "an unknown wrapper field still resolves when the rows carry headlines");
        assertNull(OnvistaNewsSource.articleArray(json.readTree("{\"foo\":1}")));
    }

    @Test
    void instantParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-28T15:30:26Z"),
                OnvistaNewsSource.parseArticleInstant("2026-07-28T15:30:26.000+00:00"));
        assertEquals(Instant.parse("2026-07-28T15:30:26Z"),
                OnvistaNewsSource.parseArticleInstant("2026-07-28T15:30:26Z"));
        assertNull(OnvistaNewsSource.parseArticleInstant("gestern"));
        assertNull(OnvistaNewsSource.parseArticleInstant(null));
    }
}
