package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounded best-first walk of a company site: the frontier reaches sections
 * the single-hop scout never saw, autodiscovery (sitemap + declared feed) adds
 * what the site itself publishes, and the budgets/host family hold.
 */
class CompanySiteCrawlerTest {

    private static final String HOME = "https://www.example-ag.de/";

    /** A canned site — every fetch is recorded, everything unknown answers 404. */
    private static final class FakeSite implements WebFetcher {

        private final Map<String, String> pages = new HashMap<>();
        private final Set<String> fetched = new LinkedHashSet<>();

        FakeSite put(String url, String body) {
            pages.put(url, body);
            return this;
        }

        @Override
        public String name() {
            return "fake-site";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            fetched.add(url);
            String body = pages.get(url);
            return body == null
                    ? new WebResponse(404, "", Map.of())
                    : new WebResponse(200, body, Map.of());
        }
    }

    private static CompanySiteCrawler crawler(FakeSite site) {
        return new CompanySiteCrawler(site, "test-agent");
    }

    @Test
    void frontierReachesTheSecondHopTheSingleHopScoutNeverSaw() {
        FakeSite site = new FakeSite()
                .put(HOME, """
                        <a href="/karriere">Karriere</a>
                        <a href="/produkte">Produkte</a>
                        <a href="/presse">Presse</a>
                        <a href="/investor-relations">Investor Relations</a>
                        <a href="https://evil.example.org/presse">Fremde Presse</a>""")
                .put("https://www.example-ag.de/presse", """
                        <a href="/presse/pressemitteilungen">Pressemitteilungen 2026</a>""")
                .put("https://www.example-ag.de/presse/pressemitteilungen", """
                        <a href="/presse/2026/umbau">Example AG ordnet ihre Konzernstruktur neu
                          und bündelt die Cloud-Sparte</a>""")
                .put("https://www.example-ag.de/investor-relations", """
                        <a href="/ir/q1-2026">Quartalsmitteilung Q1 2026</a>""");

        CompanySiteCrawler.Crawl crawl = crawler(site).crawl(HOME);

        Set<String> urls = new LinkedHashSet<>();
        for (CompanySiteCrawler.Page p : crawl.pages()) urls.add(p.url());
        assertTrue(urls.contains("https://www.example-ag.de/presse/pressemitteilungen"),
                "the second hop must be walked, not just the first: " + urls);
        assertTrue(urls.contains("https://www.example-ag.de/investor-relations"),
                "press AND IR both get walked now, not one best link: " + urls);
        assertFalse(urls.contains("https://www.example-ag.de/karriere"),
                "career pages are chrome, never editorial: " + urls);
        assertFalse(site.fetched.contains("https://evil.example.org/presse"),
                "the walk never leaves the company's host family");
    }

    @Test
    void harvestsHeadlinesAndReportsFromTheWholeWalkInRelevanceOrder() {
        FakeSite site = new FakeSite()
                .put(HOME, """
                        <a href="/presse">Presse</a>
                        <a href="/finanzberichte">Finanzberichte</a>""")
                .put("https://www.example-ag.de/presse", """
                        <a href="/presse/2026/umbau">Example AG ordnet ihre Konzernstruktur neu
                          und bündelt die Cloud-Sparte</a>
                        <a href="/presse/2026/quartal">Example AG legt Zahlen zum zweiten
                          Quartal 2026 vor</a>""")
                .put("https://www.example-ag.de/finanzberichte", """
                        <a href="/ir/gb-2025.pdf">Geschäftsbericht 2025</a>
                        <a href="/ir/q1-2026.pdf">Quartalsmitteilung Q1 2026</a>""");

        CompanySiteCrawler.Crawl crawl = crawler(site).crawl(HOME);
        CompanyPressScout scout = new CompanyPressScout(crawler(site));

        List<RawNewsItem> press = scout.pressItems(crawl, 6);
        assertEquals(2, press.size(), String.valueOf(press));
        assertTrue(press.get(0).title().startsWith("Example AG ordnet"), press.get(0).title());
        assertEquals("www.example-ag.de (IR/Presse)", press.get(0).publisher());

        List<CompanyPressScout.IrEntry> ir = scout.irEntries(crawl, 12);
        assertEquals(2, ir.size(), String.valueOf(ir));
        assertEquals("2025", ir.get(0).dateIso());
        assertTrue(ir.get(1).url().endsWith("/ir/q1-2026.pdf"),
                "a PDF report is a valid entry even though it is never crawled into");
    }

    @Test
    void declaredFeedItemsBecomeHeadlinesWithoutAnyHtmlHeuristics() {
        FakeSite site = new FakeSite()
                .put(HOME, """
                        <link rel="alternate" type="application/rss+xml" href="/feed.xml">
                        <a href="/presse">Presse</a>""")
                .put("https://www.example-ag.de/feed.xml", """
                        <rss><channel>
                          <title>Example AG Newsroom</title>
                          <item>
                            <title><![CDATA[Example AG übernimmt Mitbewerber]]></title>
                            <link>https://www.example-ag.de/presse/2026/uebernahme</link>
                          </item>
                          <item>
                            <title>Example AG hebt die Jahresprognose an</title>
                            <link>https://www.example-ag.de/presse/2026/prognose</link>
                          </item>
                        </channel></rss>""");

        CompanySiteCrawler.Crawl crawl = crawler(site).crawl(HOME);
        assertEquals(2, crawl.feedItems().size(), String.valueOf(crawl.feedItems()));

        List<RawNewsItem> press = new CompanyPressScout(crawler(site)).pressItems(crawl, 6);
        assertEquals("Example AG übernimmt Mitbewerber", press.get(0).title(),
                "feed items lead - they are finished headlines");
        assertEquals("https://www.example-ag.de/presse/2026/prognose", press.get(1).link());
    }

    @Test
    void sitemapSeedsTheFrontierWithSectionUrlsOnly() {
        FakeSite site = new FakeSite()
                .put(HOME, "<a href=\"/ueber-uns\">Über uns</a>")
                .put("https://www.example-ag.de/robots.txt",
                        "User-agent: *\nSitemap: https://www.example-ag.de/sitemap.xml\n")
                .put("https://www.example-ag.de/sitemap.xml", """
                        <urlset>
                          <url><loc>https://www.example-ag.de/produkte/schrauben</loc></url>
                          <url><loc>https://www.example-ag.de/newsroom/2026</loc></url>
                          <url><loc>https://www.example-ag.de/ir/finanzkalender</loc></url>
                        </urlset>""")
                .put("https://www.example-ag.de/newsroom/2026", "<a href=\"/x\">x</a>")
                .put("https://www.example-ag.de/ir/finanzkalender", "<a href=\"/y\">y</a>");

        CompanySiteCrawler.Crawl crawl = crawler(site).crawl(HOME);

        Set<String> urls = new LinkedHashSet<>();
        for (CompanySiteCrawler.Page p : crawl.pages()) urls.add(p.url());
        assertTrue(urls.contains("https://www.example-ag.de/newsroom/2026"), String.valueOf(urls));
        assertTrue(urls.contains("https://www.example-ag.de/ir/finanzkalender"),
                String.valueOf(urls));
        assertFalse(site.fetched.contains("https://www.example-ag.de/produkte/schrauben"),
                "a product page carries no section smell and never enters the frontier");
    }

    @Test
    void budgetsHold() {
        FakeSite site = new FakeSite();
        StringBuilder home = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            String url = "https://www.example-ag.de/news/" + i;
            home.append("<a href=\"/news/").append(i).append("\">Newsroom Seite ")
                    .append(i).append("</a>");
            site.put(url, "<a href=\"/news/deep-" + i + "\">Newsroom Unterseite " + i + "</a>");
            site.put("https://www.example-ag.de/news/deep-" + i, "<html></html>");
        }
        site.put(HOME, home.toString());

        CompanySiteCrawler.Crawl crawl = crawler(site).crawl(HOME);
        assertTrue(crawl.pages().size() <= CompanySiteCrawler.MAX_PAGES,
                "page budget: " + crawl.pages().size());
        assertTrue(site.fetched.size() <= CompanySiteCrawler.MAX_PAGES + 4,
                "fetch budget incl. autodiscovery: " + site.fetched.size());
    }

    /**
     * The item/section split, measured live on siemens.com 2026-08-03: the
     * homepage's press-release links (headline-shaped anchors) ate the whole
     * page budget, and harvesting those article pages yielded their nav chrome
     * ("Press | Company | Siemens") instead of stories.
     */
    @Test
    void headlineShapedLinksAreHarvestedNeverOpened() {
        FakeSite site = new FakeSite()
                .put(HOME, """
                        <a href="/presse">Presse</a>
                        <a href="/presse/2026/umbau">Example AG ordnet ihre Konzernstruktur neu
                          und bündelt die Cloud-Sparte</a>""")
                .put("https://www.example-ag.de/presse", "<html></html>")
                .put("https://www.example-ag.de/presse/2026/umbau", """
                        <a href="/presse">Presse | Unternehmen | Example AG - alle Meldungen</a>""");

        CompanySiteCrawler.Crawl crawl = crawler(site).crawl(HOME);
        assertFalse(site.fetched.contains("https://www.example-ag.de/presse/2026/umbau"),
                "the anchor already said what the article page would cost a fetch to learn");
        assertTrue(site.fetched.contains("https://www.example-ag.de/presse"),
                "the short label link is navigation - that is what the frontier is for");

        List<RawNewsItem> press = new CompanyPressScout(crawler(site)).pressItems(crawl, 6);
        assertEquals(1, press.size(), String.valueOf(press));
        assertTrue(press.get(0).title().startsWith("Example AG ordnet"), press.get(0).title());
    }

    @Test
    void entitiesAreDecodedOutOfTitles() {
        assertEquals("Geschäftsbericht 2025 für Österreich",
                CompanySiteCrawler.decodeEntities("Gesch&auml;ftsbericht 2025 f&#252;r &Ouml;sterreich"));
        assertEquals("Q2 & H1 \"stark\"",
                CompanySiteCrawler.decodeEntities("Q2 &amp; H1 &quot;stark&quot;"));
        assertEquals("&amp;lt; bleibt Text",
                CompanySiteCrawler.decodeEntities("&amp;amp;lt; bleibt Text"),
                "an escaped entity decodes exactly one level, never twice");
        assertEquals("nichts zu tun", CompanySiteCrawler.decodeEntities("nichts zu tun"));
    }

    @Test
    void hintScoreRanksTheSpecificSectionAboveTheGenericOne() {
        int specific = CompanySiteCrawler.hintScore(
                "https://www.example-ag.de/presse/pressemitteilungen", CompanySiteCrawler.PRESS_HINTS);
        int generic = CompanySiteCrawler.hintScore(
                "https://www.example-ag.de/news", CompanySiteCrawler.PRESS_HINTS);
        assertTrue(specific > generic, specific + " vs " + generic);
        assertEquals(0, CompanySiteCrawler.hintScore("https://www.example-ag.de/produkte",
                CompanySiteCrawler.PRESS_HINTS));
    }

    @Test
    void binariesAreLinkableButNeverCrawlable() {
        assertFalse(CompanySiteCrawler.crawlable("https://x.de/ir/q1-2026.pdf"));
        assertFalse(CompanySiteCrawler.crawlable("https://x.de/ir/report.PDF?v=2"));
        assertTrue(CompanySiteCrawler.crawlable("https://x.de/ir/berichte"));
    }

    @Test
    void canonicalCollapsesTheSamePageWrittenTwoWays() {
        assertEquals(CompanySiteCrawler.canonical("https://www.example-ag.de/Presse/"),
                CompanySiteCrawler.canonical("https://www.example-ag.de/presse"));
        assertEquals("https://www.example-ag.de/presse",
                CompanySiteCrawler.canonical("https://www.example-ag.de/presse#top"));
    }

    @Test
    void resolveAndHostFamilyStayStrict() {
        URI home = URI.create(HOME);
        assertEquals("https://news.example-ag.de/x",
                CompanySiteCrawler.resolve(home, "https://news.example-ag.de/x"));
        assertTrue(CompanySiteCrawler.sameHostFamily(home, "https://news.example-ag.de/x"));
        assertFalse(CompanySiteCrawler.sameHostFamily(home, "https://example-ag.de.evil.org/x"));
        assertEquals(null, CompanySiteCrawler.resolve(home, "javascript:void(0)"));
    }
}
