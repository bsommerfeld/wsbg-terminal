package de.bsommerfeld.wsbg.terminal.sharedeals;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sharedeals.de wp-json posts - fixtures are live response excerpts
 * (2026-07-17): {@code sharedeals-posts.json} = three Almonty chart notes
 * (search=Almonty), {@code sharedeals-window.json} = the BioNTech full-text
 * search in a 2021 window whose SIX hits never name BioNTech in the title
 * (Encavis/Arena/Valneva/Novavax passing mentions) - the live proof that the
 * title-precision cut is mandatory on a server-side full-text search.
 */
class SharedealsClientTest {

    private static String fixture(String name) {
        try (InputStream in = SharedealsClientTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    /** Fake transport answering a fixed sequence of bodies, recording URLs. */
    private static final class FakeFetcher implements WebFetcher {
        final List<String> urls = new ArrayList<>();
        private final List<WebResponse> replies;

        FakeFetcher(WebResponse... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            urls.add(url);
            return replies.get(Math.min(urls.size() - 1, replies.size() - 1));
        }
    }

    private static WebResponse ok(String fixtureName) {
        return new WebResponse(200, fixture(fixtureName), Map.of());
    }

    @Test
    void parseMapsTheLivePostFields() {
        List<RawNewsItem> items = SharedealsClient.parse(fixture("sharedeals-posts.json"));
        assertEquals(3, items.size(), "parse is unfiltered - all posts map");

        RawNewsItem first = items.get(0);
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
        List<RawNewsItem> items = SharedealsClient.parse("[{\"id\":1,"
                + "\"date_gmt\":\"2026-07-01T10:00:00\",\"link\":\"https://x/\","
                + "\"title\":{\"rendered\":\"Testaktie: Kursrakete?\"},"
                + "\"excerpt\":{\"rendered\":\"<p>x</p>\"},\"categories\":[146,21]}]");
        assertEquals(1, items.size());
        assertTrue(items.get(0).sponsored(),
                "wp category 146 (Anzeigen) = the site's own paid-placement drawer");
    }

    @Test
    void nameFanKeepsOnlyTitledMentions() {
        SharedealsClient covered = new SharedealsClient(new FakeFetcher(ok("sharedeals-posts.json")));
        List<RawNewsItem> almonty = covered.newsForName("Almonty Industries", 10);
        assertEquals(3, almonty.size(), "titled coverage passes the precision cut");

        // The BioNTech search answers six FULL-TEXT hits, none titled - the
        // precision cut must drop them all (else Valneva notes pollute BioNTech).
        SharedealsClient passing = new SharedealsClient(new FakeFetcher(ok("sharedeals-window.json")));
        assertTrue(passing.newsForName("BioNTech", 10).isEmpty(),
                "passing full-text mentions never pass the title cut");
    }

    @Test
    void nameFanBuildsTheSearchUrl() {
        FakeFetcher fetcher = new FakeFetcher(ok("sharedeals-posts.json"));
        new SharedealsClient(fetcher).newsForName("Almonty", 5);
        assertEquals(1, fetcher.urls.size());
        String url = fetcher.urls.get(0);
        assertTrue(url.startsWith("https://www.sharedeals.de/wp-json/wp/v2/posts?"), url);
        assertTrue(url.contains("search=Almonty"), url);
        assertTrue(url.contains("per_page=50"),
                "the pool is fetched deeper than the emitted limit (precision cut)");
    }

    @Test
    void windowFanCutsByTitleAndExactDateWindow() {
        // The fixture is the BioNTech window answer; queried as Valneva it
        // carries three titled Valneva notes (2021-12-11, -17, -20).
        SharedealsClient client = new SharedealsClient(new FakeFetcher(ok("sharedeals-window.json")));
        List<RawNewsItem> year = client.newsForNameWindow(
                "Valneva", null, "2021-01-01", "2022-01-01", 10);
        assertEquals(3, year.size(), "only TITLED Valneva items pass");
        assertTrue(year.stream().allMatch(i -> i.title().contains("Valneva")));

        List<RawNewsItem> narrow = client.newsForNameWindow(
                "Valneva", null, "2021-12-18", "2021-12-21", 10);
        assertEquals(1, narrow.size(), "the [from, to) cut is applied client-side on date_gmt");
        assertEquals(Instant.parse("2021-12-20T12:40:28Z"), narrow.get(0).publishedAt());

        List<RawNewsItem> capped = client.newsForNameWindow(
                "Valneva", null, "2021-01-01", "2022-01-01", 2);
        assertEquals(2, capped.size(), "the limit caps after the cuts");
    }

    @Test
    void windowFanBuildsTheWindowedSearchUrl() {
        FakeFetcher fetcher = new FakeFetcher(ok("sharedeals-window.json"));
        new SharedealsClient(fetcher).newsForNameWindow(
                "Valneva", null, "2021-01-01", "2022-01-01", 6);
        assertEquals(1, fetcher.urls.size());
        String url = fetcher.urls.get(0);
        assertTrue(url.contains("search=Valneva"), url);
        assertTrue(url.contains("&after=2021-01-01T00:00:00"), url);
        assertTrue(url.contains("&before=2022-01-01T00:00:00"), url);
    }

    @Test
    void symbolLegIsANoOpAndBlankQueriesNeverFetch() {
        FakeFetcher fetcher = new FakeFetcher(ok("sharedeals-posts.json"));
        SharedealsClient client = new SharedealsClient(fetcher);
        assertTrue(client.newsFor("ALM.TO", 10).isEmpty(),
                "symbol leg is a no-op - the venue is name-addressed German");
        assertTrue(client.newsForName("", 10).isEmpty());
        assertTrue(client.newsForName("Almonty", 0).isEmpty());
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name that is ALL stop words never queries (no precision possible)");
        assertTrue(client.newsForNameWindow(null, null, "2021-01-01", "2022-01-01", 5).isEmpty());
        assertTrue(client.newsForNameWindow("Almonty", null, "2021-01-01", "2022-01-01", 0)
                .isEmpty());
        assertEquals(0, fetcher.urls.size(), "no-op legs and blank queries never fetch");
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
}
