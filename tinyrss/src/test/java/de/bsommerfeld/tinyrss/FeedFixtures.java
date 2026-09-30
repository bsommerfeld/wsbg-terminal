package de.bsommerfeld.tinyrss;

/**
 * Feeds in the shapes the real ones come in, cut down to what matters. Each
 * is hand-built after a feed of the corpus measured on 2026-09-30 (127
 * feeds of news houses, agencies, forums, central banks, video and podcast
 * hosts); the source of the shape is named on each.
 */
public final class FeedFixtures {

    private FeedFixtures() {
    }

    /**
     * RSS 2.0 as the big houses write it: content:encoded beside description,
     * Media RSS, an atom:link self next to the item link, a guid that is no
     * permalink, dates in RFC 822 - after tagesschau, Handelsblatt, CNBC
     * (whose {@code metadata:id} sits beside the guid).
     */
    public static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:atom="http://www.w3.org/2005/Atom"
                 xmlns:media="http://search.yahoo.com/mrss/" xmlns:metadata="https://www.cnbc.com/rss/metadata/">
              <channel>
                <title>tagesschau.de - Wirtschaft</title>
                <link>https://www.tagesschau.de/wirtschaft</link>
                <atom:link href="https://www.tagesschau.de/wirtschaft/index~rss2.xml" rel="self" type="application/rss+xml"/>
                <description>Wirtschaftsnachrichten</description>
                <language>de</language>
                <lastBuildDate>Wed, 30 Sep 2026 12:00:00 +0200</lastBuildDate>
                <image><title>Logo</title><url>https://www.tagesschau.de/logo.png</url></image>
                <item>
                  <title>DAX schließt im Plus</title>
                  <link>https://www.tagesschau.de/wirtschaft/finanzen/dax-100.html</link>
                  <atom:link href="https://www.tagesschau.de/api/dax-100.json" rel="self"/>
                  <description><![CDATA[<p>Der DAX legt zu &ndash; Anleger atmen auf.</p>]]></description>
                  <content:encoded><![CDATA[<p>Der DAX legt zu.</p><p><img src="/bilder/dax.jpg" alt="DAX"/></p>]]></content:encoded>
                  <guid isPermaLink="false">dax-100</guid>
                  <metadata:id>108012345</metadata:id>
                  <pubDate>Wed, 30 Sep 2026 11:30:00 +0200</pubDate>
                  <dc:date>2026-09-30T07:00:00Z</dc:date>
                  <category>Börse</category>
                  <category>DAX</category>
                  <dc:creator>Anna Beispiel</dc:creator>
                  <media:content url="https://images.tagesschau.de/dax-1920.jpg" medium="image" width="1920"/>
                  <media:thumbnail url="https://images.tagesschau.de/dax-256.jpg"/>
                </item>
                <item>
                  <title>Ölpreis fällt</title>
                  <guid>https://www.tagesschau.de/wirtschaft/oel-102.html</guid>
                  <author>redaktion@tagesschau.de (Ben Muster)</author>
                  <pubDate>Wed, 30 Sep 2026 10:00:00 GMT</pubDate>
                  <enclosure url="https://www.tagesschau.de/oel.jpg" type="image/jpeg" length="12345"/>
                </item>
                <item>
                  <title>Doppelt gelistet</title>
                  <link>https://www.tagesschau.de/wirtschaft/dax-100.html</link>
                  <guid isPermaLink="false">dax-100</guid>
                </item>
              </channel>
            </rss>
            """;

    /** RSS 1.0: items beside the channel, dates only as dc:date - after Nikkei (wor.jp) and NDR. */
    public static final String RDF = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://www.ndr.de/">
                <title>NDR Nachrichten</title>
                <link>https://www.ndr.de/nachrichten/</link>
                <description>Aktuelles aus dem Norden</description>
                <dc:date>2026-09-30T12:00:00+02:00</dc:date>
                <items><rdf:Seq><rdf:li rdf:resource="https://www.ndr.de/a.html"/></rdf:Seq></items>
              </channel>
              <item rdf:about="https://www.ndr.de/a.html">
                <title>Hafen meldet Rekord</title>
                <link>https://www.ndr.de/a.html</link>
                <description>Umschlag gestiegen.</description>
                <dc:date>2026-09-30T11:45:00+02:00</dc:date>
                <dc:subject>Hamburg</dc:subject>
              </item>
              <item rdf:about="https://www.ndr.de/b.html">
                <title>Werft baut Stellen ab</title>
                <dc:date>2026-09-30T10:00:00+02:00</dc:date>
              </item>
            </rdf:RDF>
            """;

    /**
     * Atom 1.0 with xml:base, relative links, an xhtml content, an
     * enclosure, authors on the feed only - after heise and GitHub.
     */
    public static final String ATOM = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="de" xml:base="https://www.heise.de/">
              <title type="html">heise online &amp;amp; mehr</title>
              <subtitle>Nachrichten</subtitle>
              <link rel="self" href="rss/heise-atom.xml"/>
              <link rel="alternate" type="text/html" href="/"/>
              <updated>2026-09-30T12:00:00+02:00</updated>
              <author><name>heise online</name></author>
              <entry>
                <title>Chipfertigung &lt;3nm&gt;</title>
                <link rel="replies" href="news/chips-1.html#comments"/>
                <link rel="alternate" type="text/html" href="news/chips-1.html"/>
                <link rel="enclosure" type="application/pdf" length="999" href="https://www.heise.de/chips.pdf"/>
                <id>tag:heise.de,2026:chips-1</id>
                <published>2026-09-30T11:00:00+02:00</published>
                <updated>2026-09-30T11:30:00+02:00</updated>
                <summary type="html">&lt;p&gt;TSMC &amp;amp; Co.&lt;/p&gt;</summary>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Mehr <b>Text</b></p><img src="/bild.png"/></div></content>
                <category term="Halbleiter" label="Chips"/>
              </entry>
              <entry>
                <title type="text">Nur ein Datum</title>
                <id>https://www.heise.de/news/datum-2.html</id>
                <updated>2026-09-30T09:00:00Z</updated>
                <author><name>Carla Autorin</name></author>
              </entry>
            </feed>
            """;

    /** Atom as YouTube serves it: text and thumbnail inside media:group. */
    public static final String YOUTUBE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/"
                  xmlns="http://www.w3.org/2005/Atom">
              <title>Kanal</title>
              <entry>
                <id>yt:video:abc123</id>
                <yt:videoId>abc123</yt:videoId>
                <title>Marktbericht</title>
                <link rel="alternate" href="https://www.youtube.com/watch?v=abc123"/>
                <published>2026-09-30T06:00:00+00:00</published>
                <media:group>
                  <media:title>Marktbericht</media:title>
                  <media:content url="https://www.youtube.com/v/abc123?version=3" type="application/x-shockwave-flash"/>
                  <media:thumbnail url="https://i2.ytimg.com/vi/abc123/hqdefault.jpg" width="480" height="360"/>
                  <media:description>Heute: Zinsen &amp; Inflation.</media:description>
                </media:group>
              </entry>
            </feed>
            """;

    /**
     * Tag soup: a bare {@code &} and raw HTML in the description, a title
     * escaped twice - after the ariva forum, 4investors and wallstreet-online.
     * An XML parser loses the whole feed at the first {@code &}.
     */
    public static final String TAG_SOUP = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Forum</title>
            <item><title>F&amp;amp;O-Verfall &amp;quot;heute&amp;quot;</title>
            <link>https://www.ariva.de/forum/verfall</link>
            <description>Beratungen & Prüfer:<br />Ergebnis <b><i> - Balkonien</i></b>&nbsp;&euro;</description>
            <pubDate>Wed, 30 Sep 2026 09:00:00 +0200</pubDate></item>
            <item><title>Zweiter Beitrag</title><link>https://www.ariva.de/forum/zwei</link></item>
            </channel></rss>
            """;
}
