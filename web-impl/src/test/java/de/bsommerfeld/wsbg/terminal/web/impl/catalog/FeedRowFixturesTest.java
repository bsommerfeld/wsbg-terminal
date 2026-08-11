package de.bsommerfeld.wsbg.terminal.web.impl.catalog;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.impl.feed.FeedParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The feeds-wave catalog rows against the LIVE fixtures of the old modules
 * (benzinga, ariva, prnewswire, comdirect, cnbc, fool/foolwatch,
 * wso-boards): every migrated row must exist with its declared shape, and the
 * house {@link FeedParser} must actually digest each source's live response
 * excerpt — that is the contract the per-module StAX parsers used to carry.
 */
class FeedRowFixturesTest {

    private static String fixture(String name) {
        try (InputStream in = FeedRowFixturesTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    private static CatalogRow row(String name) {
        return SourceCatalog.load().stream()
                .filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("catalog row missing: " + name));
    }

    /** Parses the fixture with the row's publisher and sanity-checks the items. */
    private static List<Article> parsed(String rowName, String fixtureName) {
        CatalogRow row = row(rowName);
        String body = fixture(fixtureName);
        assertTrue(FeedParser.looksLikeFeed(body), fixtureName + " must gate as a feed");
        List<Article> items = FeedParser.parse(body, row.publisher());
        assertFalse(items.isEmpty(), fixtureName + " must yield items");
        for (Article a : items) {
            assertNotNull(a.uuid());
            assertFalse(a.title().isBlank());
            assertFalse(a.link().isBlank());
            assertEquals(row.publisher(), a.publisher());
        }
        return items;
    }

    @Test
    void benzingaRowAndFixture() {
        CatalogRow row = row("benzinga");
        assertEquals(3, row.urls().size(), "news + markets + why-is-it-moving");
        assertFalse(row.social());
        assertEquals(List.of(FetchUtil.DIRECT, FetchUtil.BROWSER), row.modes(),
                "the feeds answer a bare client — direct-first");
        // The live answer carries Cloudflare's email-decode <script> AFTER
        // </rss> — the generic root-close truncation must swallow it.
        parsed("benzinga", "benzinga-news.xml");
    }

    @Test
    void arivaRowsAndFixtures() {
        CatalogRow analysts = row("ariva-analysten");
        assertFalse(analysts.social(), "dpa-AFX ratings are press");
        assertEquals("dpa-AFX Analyser", analysts.publisher());
        parsed("ariva-analysten", "ariva-analysen.xml");

        CatalogRow forum = row("ariva-forum");
        assertTrue(forum.social(), "forum posts ride the sentiment fan");
        List<Article> posts = parsed("ariva-forum", "ariva-forum.xml");
        assertTrue(posts.get(0).link().contains("jumppos"),
                "the deep link with the jumppos anchor is the per-post identity");
    }

    @Test
    void prNewswireRowAndFixture() {
        CatalogRow row = row("prnewswire-uk");
        assertFalse(row.social());
        assertEquals(1, row.urls().size(), "the ONE all-releases feed");
        parsed("prnewswire-uk", "prnewswire-uk-all-news.xml");
    }

    @Test
    void comdirectRowAndFixture() {
        CatalogRow row = row("comdirect-community");
        assertTrue(row.social());
        assertEquals(4, row.urls().size(),
                "the 4 finance-relevant ACTIVE boards — Brokerboard stays out (dead since 2021)");
        assertTrue(row.urls().stream().allMatch(u -> u.contains("/rss/board?board.id=")),
                "ONLY the Khoros RSS endpoints pass the Cloudflare wall — never the HTML pages");
        assertTrue(row.interval().minMinutes() >= 15,
                "a walled, low-frequency forum polls slowly");
        parsed("comdirect-community", "comdirect-community-wertpapiere.xml");
    }

    @Test
    void cnbcRowAndFixture() {
        CatalogRow row = row("cnbc");
        assertFalse(row.social());
        assertEquals(12, row.urls().size(), "the 12 finance-relevant SECTION feeds");
        assertEquals(List.of(FetchUtil.BROWSER, FetchUtil.DIRECT), row.modes(),
                "CNBC rode the shared standard chain (browser → direct) in the old world");
        parsed("cnbc", "cnbc-earnings-rss.xml");
    }

    @Test
    void foolwatchRowAndFixture() {
        CatalogRow row = row("fool");
        assertFalse(row.social());
        assertEquals(1, row.urls().size(), "ONLY the foolwatch RSS leg rides the catalog");
        assertTrue(row.urls().get(0).contains("apikey=foolwatch-feed"),
                "Fool's own public feed key is part of the URL, not a secret");
        parsed("fool", "foolwatch.xml");
    }

    @Test
    void wsoBoardsRowAndFixture() {
        CatalogRow row = row("wso-boards");
        assertTrue(row.social());
        assertEquals(4, row.urls().size(),
                "slugs pinned verbatim from the live /rss index — never guessed "
                        + "(unknown slugs soft-200 into a default board)");
        List<Article> posts = parsed("wso-boards", "wso-board-hot-stocks.xml");
        assertTrue(posts.get(0).link().contains("#beitrag_"),
                "the per-post anchor keeps the same thread re-surfacing as posts land");
    }
}
