package de.bsommerfeld.wsbg.terminal.web.impl.feed;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedParserTest {

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Wire</title>
              <item>
                <title><![CDATA[Apple beats estimates]]></title>
                <link>https://example.com/apple</link>
                <guid isPermaLink="false">wire-1</guid>
                <pubDate>Thu, 16 Jul 2026 16:40:16 GMT</pubDate>
                <description><![CDATA[Apple Inc. reported earnings.]]></description>
              </item>
              <item>
                <title>No link — dropped</title>
              </item>
            </channel></rss>""";

    private static final String ATOM = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Board</title>
              <entry>
                <id>tag:board,2026:1</id>
                <title>SAP läuft</title>
                <link rel="alternate" href="https://example.com/sap"/>
                <updated>2026-07-16T16:40:16Z</updated>
                <summary>Ein &lt;b&gt;guter&lt;/b&gt; Tag.</summary>
              </entry>
            </feed>""";

    @Test
    void parsesRssItems() {
        List<Article> items = FeedParser.parse(RSS, "Wire");
        assertEquals(1, items.size());
        Article a = items.get(0);
        assertEquals("wire-1", a.uuid());
        assertEquals("Apple beats estimates", a.title());
        assertEquals("Wire", a.publisher());
        assertEquals("https://example.com/apple", a.link());
        assertEquals(Instant.parse("2026-07-16T16:40:16Z"), a.publishedAt());
        assertEquals("Apple Inc. reported earnings.", a.summary());
    }

    @Test
    void parsesAtomEntriesWithHrefLinksAndStripsMarkup() {
        List<Article> items = FeedParser.parse(ATOM, "Board");
        assertEquals(1, items.size());
        Article a = items.get(0);
        assertEquals("tag:board,2026:1", a.uuid());
        assertEquals("https://example.com/sap", a.link());
        assertEquals(Instant.parse("2026-07-16T16:40:16Z"), a.publishedAt());
        assertEquals("Ein guter Tag.", a.summary());
    }

    @Test
    void toleratesCdnTailBehindClosingRoot() {
        String withTail = RSS + "<script>challenge()</script>";
        assertEquals(1, FeedParser.parse(withTail, "Wire").size());
    }

    @Test
    void gatesNonFeedBodies() {
        assertTrue(FeedParser.looksLikeFeed(RSS));
        assertTrue(FeedParser.looksLikeFeed(ATOM));
        assertFalse(FeedParser.looksLikeFeed("<!doctype html><html>blocked</html>"));
        assertFalse(FeedParser.looksLikeFeed(null));
    }

    @Test
    void garbageYieldsEmptyNeverThrows() {
        assertTrue(FeedParser.parse("no xml at all", "X").isEmpty());
        assertTrue(FeedParser.parse(null, "X").isEmpty());
        assertTrue(FeedParser.parse("", "X").isEmpty());
    }

    @Test
    void twinDateAndSummaryElementsDoNotWeldTogether() {
        // Khoros/comdirect items carry <pubDate> AND a twin <dc:date>; many
        // feeds carry <description> AND <content:encoded>. The first element
        // of a kind wins — appending across twins would weld two
        // representations into one unparseable/doubled string.
        String twins = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"
                     xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel>
                  <item xmlns:metadata="http://search.cnbc.com/rss/2.0/modules/siteContentMetadata">
                    <title>Thread</title>
                    <link>https://example.com/t</link>
                    <guid isPermaLink="false">stable-1</guid>
                    <metadata:id>108464158</metadata:id>
                    <description>Teaser.</description>
                    <content:encoded>Full body.</content:encoded>
                    <pubDate>Thu, 9 Jul 2026 19:15:42 GMT</pubDate>
                    <dc:date>2026-07-09T19:15:42Z</dc:date>
                  </item>
                </channel></rss>""";
        Article a = FeedParser.parse(twins, "X").get(0);
        assertEquals("stable-1", a.uuid(),
                "the twin metadata:id must not weld onto the guid");
        assertEquals(Instant.parse("2026-07-09T19:15:42Z"), a.publishedAt(),
                "the twin dc:date must not weld onto the pubDate");
        assertEquals("Teaser.", a.summary(),
                "the twin content:encoded must not double the description");
    }

    @Test
    void dateFallbacksCoverTheWireVariants() {
        assertEquals(Instant.parse("2026-07-16T16:40:16Z"),
                FeedParser.parseDate("16 Jul 2026 16:40:16 GMT"));
        assertEquals(Instant.parse("2026-07-16T16:40:16Z"),
                FeedParser.parseDate("2026-07-16T16:40:16Z"));
        assertNull(FeedParser.parseDate("gestern"));
        assertNull(FeedParser.parseDate(null));
    }
}
