package de.bsommerfeld.tinyrss.api;

import de.bsommerfeld.tinyrss.FeedFixtures;
import de.bsommerfeld.tinyrss.model.Enclosure;
import de.bsommerfeld.tinyrss.model.Entry;
import de.bsommerfeld.tinyrss.model.Feed;
import de.bsommerfeld.tinyrss.model.FeedFormat;
import de.bsommerfeld.tinyrss.model.FeedLink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedParserTest {

    private static final String TAGESSCHAU = "https://www.tagesschau.de/wirtschaft/index~rss2.xml";

    @Test
    void rssChannel() throws Exception {
        Feed feed = FeedParser.parse(FeedFixtures.RSS, TAGESSCHAU);

        assertEquals(FeedFormat.RSS, feed.format());
        assertEquals("tagesschau.de - Wirtschaft", feed.title(), "not the image's title");
        assertEquals("https://www.tagesschau.de/wirtschaft", feed.link());
        assertEquals("Wirtschaftsnachrichten", feed.description());
        assertEquals("de", feed.language());
        assertEquals(Instant.parse("2026-09-30T10:00:00Z"), feed.updated());
    }

    @Test
    void rssItemTakesTheFirstUsableSourceOfEachField() throws Exception {
        Entry dax = FeedParser.parse(FeedFixtures.RSS, TAGESSCHAU).entries().getFirst();

        assertEquals("dax-100", dax.id(), "the guid - not the publisher's metadata:id");
        assertEquals("DAX schließt im Plus", dax.title());
        assertEquals("https://www.tagesschau.de/wirtschaft/finanzen/dax-100.html", dax.link(), "not the atom:link self");
        assertEquals("Der DAX legt zu – Anleger atmen auf.", dax.summaryText());
        assertEquals("Der DAX legt zu.", dax.contentText());
        assertEquals(Instant.parse("2026-09-30T09:30:00Z"), dax.published(), "pubDate before its dc:date twin");
        assertEquals(List.of("Anna Beispiel"), dax.authors());
        assertEquals(List.of("Börse", "DAX"), dax.categories());
        assertEquals(List.of("https://images.tagesschau.de/dax-1920.jpg", "https://images.tagesschau.de/dax-256.jpg",
                "https://www.tagesschau.de/bilder/dax.jpg"), dax.images(), "declared first, then the HTML's, resolved");
    }

    @Test
    void rssItemWithoutLinkUsesItsPermalinkGuid() throws Exception {
        Entry oil = FeedParser.parse(FeedFixtures.RSS, TAGESSCHAU).entries().get(1);

        assertEquals("https://www.tagesschau.de/wirtschaft/oel-102.html", oil.link());
        assertEquals(oil.link(), oil.id());
        assertEquals(List.of("Ben Muster"), oil.authors(), "the address RSS wraps around the name is dropped");
        assertEquals(List.of(new Enclosure("https://www.tagesschau.de/oel.jpg", "image/jpeg", 12345)), oil.enclosures());
        assertEquals(List.of("https://www.tagesschau.de/oel.jpg"), oil.images());
        assertEquals("", oil.summaryHtml());
    }

    @Test
    void anEntryListedTwiceIsKeptOnce() throws Exception {
        List<Entry> entries = FeedParser.parse(FeedFixtures.RSS, TAGESSCHAU).entries();
        assertEquals(2, entries.size());
        assertEquals("DAX schließt im Plus", entries.getFirst().title(), "the first appearance wins");
    }

    @Test
    void rdf() throws Exception {
        Feed feed = FeedParser.parse(FeedFixtures.RDF, "https://www.ndr.de/home/index-rss.xml");

        assertEquals(FeedFormat.RDF, feed.format());
        assertEquals("NDR Nachrichten", feed.title());
        assertEquals(Instant.parse("2026-09-30T10:00:00Z"), feed.updated());
        assertEquals(2, feed.entries().size(), "items beside the channel");

        Entry harbour = feed.entries().getFirst();
        assertEquals("https://www.ndr.de/a.html", harbour.id());
        assertEquals(Instant.parse("2026-09-30T09:45:00Z"), harbour.published(), "dc:date, as RSS 1.0 dates");
        assertEquals(List.of("Hamburg"), harbour.categories());
        assertEquals("Umschlag gestiegen.", harbour.summaryText());
        assertEquals("https://www.ndr.de/b.html", feed.entries().get(1).link(), "rdf:about when there is no link");
    }

    @Test
    void atom() throws Exception {
        Feed feed = FeedParser.parse(FeedFixtures.ATOM, "https://www.heise.de/rss/heise-atom.xml");

        assertEquals(FeedFormat.ATOM, feed.format());
        assertEquals("heise online & mehr", feed.title(), "type=html read as HTML");
        assertEquals("https://www.heise.de/", feed.link(), "the alternate, not self, against xml:base");
        assertEquals("de", feed.language());

        Entry chips = feed.entries().getFirst();
        assertEquals("tag:heise.de,2026:chips-1", chips.id());
        assertEquals("Chipfertigung <3nm>", chips.title(), "type=text exactly as written");
        assertEquals("https://www.heise.de/news/chips-1.html", chips.link(), "the alternate, not the replies");
        assertEquals("TSMC & Co.", chips.summaryText());
        assertEquals("<p>Mehr <b>Text</b></p><img src=\"/bild.png\"/>", chips.contentHtml(), "xhtml without its div");
        assertEquals(Instant.parse("2026-09-30T09:00:00Z"), chips.published());
        assertEquals(Instant.parse("2026-09-30T09:30:00Z"), chips.updated());
        assertEquals(List.of("heise online"), chips.authors(), "inherited from the feed");
        assertEquals(List.of("Halbleiter"), chips.categories());
        assertEquals(List.of(new Enclosure("https://www.heise.de/chips.pdf", "application/pdf", 999)), chips.enclosures());
        assertEquals(List.of("https://www.heise.de/bild.png"), chips.images());
    }

    @Test
    void atomEntryWithOnlyAnIdAndAnUpdate() throws Exception {
        Entry dated = FeedParser.parse(FeedFixtures.ATOM, null).entries().get(1);

        assertEquals("https://www.heise.de/news/datum-2.html", dated.link(), "an id that is a web address");
        assertNull(dated.published());
        assertEquals(Instant.parse("2026-09-30T09:00:00Z"), dated.time().orElseThrow(), "falls back to updated");
        assertEquals(List.of("Carla Autorin"), dated.authors(), "its own author, not the feed's");
    }

    @Test
    void youTubeKeepsItsTextInTheMediaGroup() throws Exception {
        Entry video = FeedParser.parse(FeedFixtures.YOUTUBE, null).entries().getFirst();

        assertEquals("https://www.youtube.com/watch?v=abc123", video.link());
        assertEquals("Heute: Zinsen & Inflation.", video.summaryText());
        assertEquals(List.of("https://i2.ytimg.com/vi/abc123/hqdefault.jpg"), video.images(), "the player is no picture");
    }

    @Test
    void tagSoupStillYieldsEveryEntry() throws Exception {
        Feed feed = FeedParser.parse(FeedFixtures.TAG_SOUP, null);

        assertEquals(2, feed.entries().size());
        Entry first = feed.entries().getFirst();
        assertEquals("F&O-Verfall \"heute\"", first.title(), "escaped twice, read twice");
        assertEquals("Beratungen & Prüfer: Ergebnis - Balkonien €", first.summaryText());
        assertEquals(Instant.parse("2026-09-30T07:00:00Z"), first.published());
    }

    @Test
    void byteOrderMarkAndAScriptAfterTheRoot() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        body.write(FeedFixtures.RSS.getBytes(StandardCharsets.UTF_8));
        body.write("<script>(function(){if(a<b&&c){}})();</script>".getBytes(StandardCharsets.UTF_8));

        Feed feed = FeedParser.parse(body.toByteArray(), "application/rss+xml", TAGESSCHAU);
        assertEquals(2, feed.entries().size());
        assertEquals("DAX schließt im Plus", feed.entries().getFirst().title());
    }

    @Test
    void whatIsNoFeedSaysWhatItIs() {
        NotAFeedException html = assertThrows(NotAFeedException.class, () -> FeedParser.parse(
                "<!DOCTYPE html><html><head><title>Börse</title></head><body>Hallo</body></html>",
                "https://www.boerse-frankfurt.de/rss"));
        assertEquals("an HTML page, not a feed (https://www.boerse-frankfurt.de/rss)", html.getMessage());

        assertTrue(assertThrows(NotAFeedException.class, () -> FeedParser.parse("{\"items\": []}", null))
                .getMessage().startsWith("JSON"));
        assertTrue(assertThrows(NotAFeedException.class, () -> FeedParser.parse(" \n", null))
                .getMessage().startsWith("empty"));
        assertTrue(assertThrows(NotAFeedException.class, () -> FeedParser.parse("<error>quota</error>", null))
                .getMessage().contains("<error>"));
    }

    @Test
    void anEmptyFeedIsAFeed() throws Exception {
        Feed empty = FeedParser.parse("<rss><channel><title>Leer</title></channel></rss>", null);
        assertEquals("Leer", empty.title());
        assertTrue(empty.entries().isEmpty());
    }

    @Test
    void externalEntitiesAreNeverResolved() throws Exception {
        Feed feed = FeedParser.parse("""
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <rss><channel><item><guid>x</guid><title>&secret;</title></item></channel></rss>""", null);
        assertEquals("&secret;", feed.entries().getFirst().title());
    }

    @Test
    void entriesWithoutIdOrLinkGetAStableDigest() throws Exception {
        String feed = """
                <rss><channel>
                <item><title>Eins</title><description>Text</description><pubDate>Wed, 30 Sep 2026 10:00:00 GMT</pubDate></item>
                <item><title>Zwei</title><description>Text</description></item>
                <item></item>
                </channel></rss>""";
        List<Entry> first = FeedParser.parse(feed, null).entries();
        List<Entry> second = FeedParser.parse(feed, null).entries();

        assertEquals(2, first.size(), "an item with nothing in it is no entry");
        assertTrue(first.getFirst().id().startsWith("urn:sha256:"));
        assertEquals(first.getFirst().id(), second.getFirst().id());
        assertNotEquals(first.get(0).id(), first.get(1).id());
    }

    @Test
    void feedBurnerOriginalLinkWins() throws Exception {
        Entry entry = FeedParser.parse("""
                <rss xmlns:feedburner="http://rssnamespace.org/feedburner/ext/1.0"><channel><item>
                <title>T</title><link>https://feedproxy.google.com/~r/x/~3/abc/</link>
                <feedburner:origLink>https://techcrunch.com/2026/09/30/t/</feedburner:origLink>
                </item></channel></rss>""", null).entries().getFirst();
        assertEquals("https://techcrunch.com/2026/09/30/t/", entry.link());
    }

    @Test
    void autodiscoveryReadsThePagesAlternates() {
        String page = """
                <!doctype html><html><head><base href="https://www.heise.de/">
                <link rel="stylesheet" href="/s.css">
                <link rel="alternate" type="application/rss+xml" title="heise RSS" href="rss/heise.rdf">
                <link rel=alternate type=application/atom+xml title="heise Atom" href=/rss/heise-atom.xml>
                <link rel="alternate" hreflang="en" href="https://www.heise.de/en/">
                <link rel="alternate" type="application/rss+xml" href="rss/heise.rdf">
                </head><body></body></html>""";
        List<FeedLink> links = FeedParser.links(page.getBytes(StandardCharsets.UTF_8), "text/html", "https://www.heise.de/news/");

        assertEquals(List.of(
                new FeedLink("https://www.heise.de/rss/heise.rdf", "heise RSS", "application/rss+xml"),
                new FeedLink("https://www.heise.de/rss/heise-atom.xml", "heise Atom", "application/atom+xml")), links);
    }

    @Test
    void autodiscoveryOfAFeedIsTheFeed() {
        List<FeedLink> links = FeedParser.links(FeedFixtures.ATOM.getBytes(StandardCharsets.UTF_8), null,
                "https://www.heise.de/rss/heise-atom.xml");
        assertEquals(List.of(new FeedLink("https://www.heise.de/rss/heise-atom.xml", "heise online & mehr",
                "application/atom+xml")), links);
    }
}
