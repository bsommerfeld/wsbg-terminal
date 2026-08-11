package de.bsommerfeld.wsbg.terminal.web.impl.sources.sharedeals;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sharedeals.de wp-json posts - fixtures are live response excerpts
 * (2026-07-17): {@code sharedeals-posts.json} = three Almonty chart notes
 * (search=Almonty), {@code sharedeals-window.json} = the BioNTech full-text
 * search whose SIX hits never name BioNTech in the title
 * (Encavis/Arena/Valneva/Novavax passing mentions) - the live proof that the
 * title-precision cut is mandatory on a server-side full-text search.
 * Ported from the module world, including the archive-window leg.
 */
class SharedealsClientTest {

    private static String fixture(String name) {
        return SourceTestSupport.fixture("sources/sharedeals/" + name);
    }

    private static FakeWebFetcher serving(String fixtureName) {
        return new FakeWebFetcher().on("wp-json/wp/v2/posts", fixture(fixtureName));
    }

    private static ResolvedInstrument named(String name) {
        return ResolvedInstrument.ofName(name);
    }

    @Test
    void parseMapsTheLivePostFields() {
        List<Article> items = SharedealsClient.parse(fixture("sharedeals-posts.json"));
        assertEquals(3, items.size(), "parse is unfiltered - all posts map");

        Article first = items.get(0);
        assertEquals("sharedeals-224168", first.uuid(), "uuid keys on the wp post id");
        assertEquals("Almonty-Aktie mit -44% – erst Korrektur, dann Allzeithoch?",
                first.title(), "the en dash survives as text");
        assertEquals("sharedeals.de", first.publisher());
        assertEquals("https://www.sharedeals.de/"
                + "almonty-aktie-mit-44-erst-korrektur-dann-allzeithoch/", first.link());
        assertEquals(Instant.parse("2026-07-16T03:51:18Z"), first.publishedAt(),
                "date_gmt is zoneless UTC");
        assertNull(first.isin(), "the site tags no instruments");
        assertFalse(first.sponsored());
        assertTrue(first.summary().contains("über -11% an Wert verloren"),
                "the excerpt rides as the summary");
        assertFalse(first.summary().contains("<"), "HTML tags are stripped");
        assertTrue(first.summary().endsWith("[…]"),
                "WP's [&hellip;] truncation marker decodes: " + first.summary());

        assertTrue(items.get(1).summary().contains("Global Tungsten & Powders"),
                "&amp; decodes in the excerpt");
    }

    @Test
    void parseToleratesGarbageAndErrorShapes() {
        assertTrue(SharedealsClient.parse("<html><body>404</body></html>").isEmpty());
        assertTrue(SharedealsClient.parse("not json at all").isEmpty());
        assertTrue(SharedealsClient.parse("").isEmpty());
        assertTrue(SharedealsClient.parse(null).isEmpty());
        // A WP error answers an OBJECT, not the posts array.
        assertTrue(SharedealsClient.parse(
                "{\"code\":\"rest_invalid_param\",\"message\":\"...\"}").isEmpty());
    }

    @Test
    void anzeigenCategoryRidesAsTheSponsoredFlag() {
        List<Article> items = SharedealsClient.parse("[{\"id\":1,"
                + "\"date_gmt\":\"2026-07-01T10:00:00\",\"link\":\"https://x/\","
                + "\"title\":{\"rendered\":\"Testaktie: Kursrakete?\"},"
                + "\"excerpt\":{\"rendered\":\"<p>x</p>\"},\"categories\":[146,21]}]");
        assertEquals(1, items.size());
        assertTrue(items.get(0).sponsored(),
                "wp category 146 (Anzeigen) = the site's own paid-placement drawer");
    }

    @Test
    void nameFanKeepsOnlyTitledMentions() {
        SharedealsClient covered = new SharedealsClient(serving("sharedeals-posts.json"));
        List<Article> almonty = covered.newsFor(named("Almonty Industries"), 10);
        assertEquals(3, almonty.size(), "titled coverage passes the precision cut");

        // The BioNTech search answers six FULL-TEXT hits, none titled - the
        // precision cut must drop them all (else Valneva notes pollute BioNTech).
        SharedealsClient passing = new SharedealsClient(serving("sharedeals-window.json"));
        assertTrue(passing.newsFor(named("BioNTech"), 10).isEmpty(),
                "passing full-text mentions never pass the title cut");
    }

    @Test
    void nameFanBuildsTheSearchUrl() {
        FakeWebFetcher fetcher = serving("sharedeals-posts.json");
        new SharedealsClient(fetcher).newsFor(named("Almonty"), 5);
        assertEquals(1, fetcher.total());
        String url = fetcher.calls.get(0);
        assertTrue(url.startsWith("https://www.sharedeals.de/wp-json/wp/v2/posts?"), url);
        assertTrue(url.contains("search=Almonty"), url);
        assertTrue(url.contains("per_page=50"),
                "the pool is fetched deeper than the emitted limit (precision cut)");
    }

    @Test
    void windowFanCutsByTitleAndExactDateWindow() throws Exception {
        // The fixture is the BioNTech window answer; queried as Valneva it
        // carries three titled Valneva notes (2021-12-11, -17, -20).
        SharedealsClient client = new SharedealsClient(serving("sharedeals-window.json"));
        List<Article> year = client.newsForWindow(named("Valneva"),
                LocalDate.parse("2021-01-01"), LocalDate.parse("2022-01-01"), 10);
        assertEquals(3, year.size(), "only TITLED Valneva items pass");
        assertTrue(year.stream().allMatch(i -> i.title().contains("Valneva")));

        List<Article> narrow = client.newsForWindow(named("Valneva"),
                LocalDate.parse("2021-12-18"), LocalDate.parse("2021-12-21"), 10);
        assertEquals(1, narrow.size(), "the [from, to) cut is applied client-side on date_gmt");
        assertEquals(Instant.parse("2021-12-20T12:40:28Z"), narrow.get(0).publishedAt());

        List<Article> capped = client.newsForWindow(named("Valneva"),
                LocalDate.parse("2021-01-01"), LocalDate.parse("2022-01-01"), 2);
        assertEquals(2, capped.size(), "the limit caps after the cuts");
    }

    @Test
    void windowFanBuildsTheWindowedSearchUrl() throws Exception {
        FakeWebFetcher fetcher = serving("sharedeals-window.json");
        new SharedealsClient(fetcher).newsForWindow(named("Valneva"),
                LocalDate.parse("2021-01-01"), LocalDate.parse("2022-01-01"), 6);
        assertEquals(1, fetcher.total());
        String url = fetcher.calls.get(0);
        assertTrue(url.contains("search=Valneva"), url);
        assertTrue(url.contains("&after=2021-01-01T00:00:00"), url);
        assertTrue(url.contains("&before=2022-01-01T00:00:00"), url);
    }

    @Test
    void windowFanBlankQueriesNeverFetch() throws Exception {
        FakeWebFetcher fetcher = serving("sharedeals-window.json");
        SharedealsClient client = new SharedealsClient(fetcher);
        assertTrue(client.newsForWindow(named(""),
                LocalDate.parse("2021-01-01"), LocalDate.parse("2022-01-01"), 5).isEmpty());
        assertTrue(client.newsForWindow(named("Almonty"),
                LocalDate.parse("2021-01-01"), LocalDate.parse("2022-01-01"), 0).isEmpty());
        assertEquals(0, fetcher.total(), "blank window queries never fetch");
    }

    @Test
    void hardKeysAloneAndBlankQueriesNeverFetch() {
        FakeWebFetcher fetcher = serving("sharedeals-posts.json");
        SharedealsClient client = new SharedealsClient(fetcher);
        assertTrue(client.newsFor(new ResolvedInstrument(
                        Optional.empty(), Ticker.parse("ALM.TO"), ""), 10).isEmpty(),
                "the venue is name-addressed German - a symbol opens no door");
        assertTrue(client.newsFor(named(""), 10).isEmpty());
        assertTrue(client.newsFor(named("Almonty"), 0).isEmpty());
        assertTrue(client.newsFor(named("AG"), 10).isEmpty(),
                "a name that is ALL stop words never queries (no precision possible)");
        assertEquals(0, fetcher.total(), "no-op legs and blank queries never fetch");
    }

    @Test
    void entityDecodingHandlesTheWordPressShapes() {
        assertEquals("A – B … & <x>",
                SharedealsClient.decodeEntities("A &#8211; B &hellip; &amp; &lt;x&gt;"));
        assertEquals("€", SharedealsClient.decodeEntities("&#x20AC;"));
        assertNull(SharedealsClient.decodeEntities(null));
        assertEquals("a b", SharedealsClient.stripHtml("<p>a</p>\n<b>b</b>"));
        assertNull(SharedealsClient.stripHtml(null));
    }

    @Test
    void stampParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-16T15:43:10Z"),
                SharedealsClient.parseStamp("2026-07-16T15:43:10"));
        assertNull(SharedealsClient.parseStamp("gestern"));
        assertNull(SharedealsClient.parseStamp(""));
        assertNull(SharedealsClient.parseStamp(null));
    }

    @Test
    void sourceIsASentimentVenue() {
        SharedealsClient client = new SharedealsClient(new FakeWebFetcher());
        assertEquals("sharedeals", client.sourceName());
        assertTrue(client.socialSentiment(),
                "chart-opinion venue - rides the sentiment fan, never the press loom");
        assertEquals("DE", client.origin().sphere());
    }
}
