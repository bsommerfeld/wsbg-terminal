package de.bsommerfeld.tinyrss.markup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkupParserTest {

    private static Element root(String markup) {
        return MarkupParser.parse(markup, "https://example.com/feed/").elements().getFirst();
    }

    @Test
    void wellFormedXmlReadsAsWritten() {
        Element root = root("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- a comment -->
                <rss version="2.0"><channel><title>A &amp; B &lt;C&gt;</title>
                <description><![CDATA[<p>Hallo & Tschüss</p>]]></description></channel></rss>""");

        assertEquals("rss", root.name());
        Element channel = root.elements().getFirst();
        assertEquals("A & B <C>", channel.elements().get(0).text());
        assertEquals("<p>Hallo & Tschüss</p>", channel.elements().get(1).text(), "CDATA as it stands");
        assertEquals("2.0", root.attribute("VERSION"), "attributes any case");
    }

    @Test
    void htmlEntitiesAndBareAmpersandsDoNotStopTheParse() {
        Element root = root("<t>Kurs&nbsp;&euro; &auml;&#228;&#xE4; &#150; Gewinn & Verlust &unknown; &</t>");
        assertEquals("Kurs € äää – Gewinn & Verlust &unknown; &", root.text());
    }

    @Test
    void unescapedHtmlInADescriptionStaysInsideIt() {
        // the ariva forum's shape (measured 2026-09-30): raw <br />, <b><i>, a bare &
        Element item = root("""
                <item><title>Balkonien</title>
                <description>Beratungen & Prüfer:<br />Text <b><i> - Balkonien</i></b></description>
                <link>https://www.ariva.de/forum/x</link></item>""");

        List<Element> children = item.elements();
        assertEquals(List.of("title", "description", "link"), children.stream().map(Element::name).toList());
        assertEquals("Beratungen &amp; Prüfer:<br/>Text <b><i> - Balkonien</i></b>", children.get(1).innerMarkup(),
                "written back as valid markup");
    }

    @Test
    void unclosedAndStrayTagsAreRepairedLikeABrowserDoes() {
        Element item = root("<item><description><p>eins<p>zwei</div></description><title>T</title></item>");
        assertEquals(List.of("description", "title"), item.elements().stream().map(Element::name).toList());
        assertEquals("einszwei", item.elements().getFirst().text());
    }

    @Test
    void elementsThatNeverHaveContentOpenNothing() {
        Element item = root("""
                <item><enclosure url="a.mp3" type="audio/mpeg"><link href="https://x/a">\
                <guid>g1</guid><img src="i.png"><title>T</title></item>""");
        assertEquals(List.of("enclosure", "link", "guid", "img", "title"),
                item.elements().stream().map(Element::name).toList());
    }

    @Test
    void rssLinkWithTextIsNotEmpty() {
        Element item = root("<item><link>https://x/a</link><title>T</title></item>");
        assertEquals("https://x/a", item.elements().getFirst().text());
    }

    @Test
    void attributesInEveryNotation() {
        Element element = root("<a one=\"1\" two='2' three=3 four five = \"5\" one=\"ignored\" six=\"a&amp;b\">");
        assertEquals("1", element.attribute("one"));
        assertEquals("2", element.attribute("two"));
        assertEquals("3", element.attribute("three"));
        assertEquals("", element.attribute("four"));
        assertEquals("5", element.attribute("five"));
        assertEquals("a&b", element.attribute("six"));
    }

    @Test
    void namespacesResolveByDeclarationOrByWellKnownPrefix() {
        Element feed = root("""
                <rss xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:x="https://example.com/own">
                <item><content:encoded>c</content:encoded><media:thumbnail url="t.jpg"/><x:id>1</x:id>
                <atom:link href="https://x/self" rel="self"/></item></rss>""");
        List<Element> item = feed.elements().getFirst().elements();

        assertEquals(Namespace.CONTENT, item.get(0).namespace());
        assertEquals(Namespace.MEDIA, item.get(1).namespace(), "media: undeclared, as feeds do");
        assertEquals(Namespace.OTHER, item.get(2).namespace());
        assertEquals(Namespace.ATOM, item.get(3).namespace());
        assertEquals(Namespace.NONE, feed.namespace());
    }

    @Test
    void defaultNamespaceAndItsSpellings() {
        assertEquals(Namespace.ATOM, root("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>").namespace());
        assertEquals(Namespace.ATOM, root("<feed xmlns=\"http://purl.org/atom/ns#\"/>").namespace());
        assertEquals(Namespace.MEDIA, Namespace.ofUri("https://search.yahoo.com/mrss"));
        assertEquals(Namespace.DUBLIN_CORE, Namespace.ofUri("http://purl.org/dc/terms/"));
    }

    @Test
    void xmlBaseResolvesRelativeLinks() {
        Element feed = root("<feed xml:base=\"https://blog.example.org/2026/\"><entry><link href=\"post\"/></entry></feed>");
        Element entry = feed.elements().getFirst();
        assertEquals("https://blog.example.org/2026/", entry.base());
        assertEquals("https://blog.example.org/2026/post", Urls.resolve(entry.base(), "post"));
    }

    @Test
    void doctypeAndExternalEntitiesAreNeverRead() {
        Element feed = root("""
                <!DOCTYPE feed [<!ENTITY secret SYSTEM "file:///etc/passwd"> <!ENTITY x "]>">]>
                <feed><title>&secret;</title></feed>""");
        assertEquals("feed", feed.name());
        assertEquals("&secret;", feed.elements().getFirst().text());
    }

    @Test
    void garbageAroundTheRootStaysOutside() {
        // a PHP warning before the prolog, Benzinga's CDN script after the root (master, 2026-07)
        Element document = MarkupParser.parse("""
                <b>Warning</b>: something in /var/www<br />
                <?xml version="1.0"?><rss><channel/></rss><script>if (a<b && c) {}</script>""", null);
        assertEquals(List.of("b", "br", "rss", "script"), document.elements().stream().map(Element::name).toList());
        assertEquals("if (a<b && c) {}", document.elements().get(3).text(), "script is raw text");
    }

    @Test
    void textOutsideTagsAndForbiddenCharacters() {
        Element element = root("<t>a < b\r\nc\u0001d</t>");
        assertEquals("a < b\ncd", element.text());
    }

    @Test
    void truncatedDocumentsKeepWhatArrived() {
        Element feed = root("<rss><channel><item><title>ganz</title></item><item><title>halb");
        List<Element> items = feed.elements().getFirst().elements();
        assertEquals(2, items.size());
        assertEquals("halb", items.get(1).text());
    }

    @Test
    void deepTagSoupDoesNotOverflow() {
        Element root = root("<a>" + "<b>".repeat(100_000) + "x");
        assertFalse(root.text().isEmpty());
    }

    @Test
    void unterminatedTagsEndAtTheNextTag() {
        Element item = root("<item><title>T</title><link href=\"https://x/a\"<guid>g</guid></item>");
        assertEquals(List.of("title", "link", "guid"), item.elements().stream().map(Element::name).toList());
        assertTrue(item.elements().get(1).children().isEmpty());
    }

    @Test
    void relativeAndProtocolRelativeUrls() {
        assertEquals("https://example.com/a/b", Urls.resolve("https://example.com/a/", "b"));
        assertEquals("https://example.com/b", Urls.resolve("https://example.com", "/b"));
        assertEquals("https://cdn.example.com/i.jpg", Urls.resolve("https://example.com/", "//cdn.example.com/i.jpg"));
        assertEquals("https://example.com/a%20b?q=%5B1%5D", Urls.resolve(null, "https://example.com/a b?q=[1]"));
        assertEquals("https://www.reddit.com/r/x/comments/1/können/",
                Urls.resolve(null, "https://www.reddit.com/r/x/comments/1/können/"), "umlauts stay as written");
        assertEquals("https://example.com/100%25", Urls.resolve(null, "https://example.com/100%"));
        assertNull(Urls.resolve(null, "relative"));
        assertNull(Urls.resolve("https://example.com/", "  "));
    }
}
