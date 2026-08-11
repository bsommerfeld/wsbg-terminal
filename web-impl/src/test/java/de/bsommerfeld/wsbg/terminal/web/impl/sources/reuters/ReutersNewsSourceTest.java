package de.bsommerfeld.wsbg.terminal.web.impl.sources.reuters;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reuters Arc news-sitemap — fixture is a live excerpt (2026-07-16) trimmed
 * to 5 entries: Csquare NYSE debut (business), Franco-German defence
 * (aerospace), LNG/S&P Global (energy), gold macro, and one Portuguese-desk
 * item (Abiec beef) that pins the deliberately uncurated ingestion. Ported
 * from the module world; the collector delivers the whole sitemap unfiltered
 * — pool caching and relevance filtering left with the old NewsSource fan.
 */
class ReutersNewsSourceTest {

    private static String fixture() {
        try (InputStream in = ReutersNewsSourceTest.class
                .getResourceAsStream("/reuters-news-sitemap.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    /** Fake transport answering a fixed sequence of bodies, counting fetches. */
    private static final class FakeFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        private final List<WebResponse> replies;

        FakeFetcher(WebResponse... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            assertEquals("https://www.reuters.com/arc/outboundfeeds/news-sitemap/"
                            + "?outputType=xml", url,
                    "ONE sitemap URL — this source is a firehose, never a search");
            assertEquals(FetchUtil.DIRECT, modes[0], "declared mode order rides the fetch");
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
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
    }

    private static WebResponse sitemap() {
        return WebResponse.text(200, fixture(), Map.of());
    }

    @Test
    void parseMapsTheLiveSitemapFields() {
        List<Article> items = ReutersNewsSource.parse(fixture());
        assertEquals(5, items.size(), "the answer is unfiltered — all entries parse, "
                + "including the non-English desks");

        Article csquare = items.get(0);
        assertEquals("Brookfield-backed Csquare valued at $3.24 billion in NYSE debut",
                csquare.title(), "the headline is the CDATA news:title");
        assertTrue(csquare.link().startsWith(
                        "https://www.reuters.com/business/brookfield-backed-csquare-valued"),
                "the link is the article <loc> — never the <image:loc> of the same entry");
        assertEquals(csquare.link(), csquare.uuid());
        assertEquals(Instant.parse("2026-07-16T16:54:42.616Z"), csquare.publishedAt(),
                "ISO publication_date with fractional seconds parses exactly");
        assertEquals("Reuters", csquare.publisher());
        assertNull(csquare.summary(), "a sitemap carries no teaser — the headline is the value");
        assertNull(csquare.isin());
        assertTrue(csquare.relatedTickers().isEmpty());
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(ReutersNewsSource.parse("<html><body>bot wall</body></html>").isEmpty());
        assertTrue(ReutersNewsSource.parse("not xml { }").isEmpty());
        assertTrue(ReutersNewsSource.parse("").isEmpty());
        assertTrue(ReutersNewsSource.parse(null).isEmpty());
        // Truncated mid-entry (a torn response) — parses what it can, never throws.
        String torn = fixture().substring(0, fixture().indexOf("publication_date") + 10);
        assertTrue(ReutersNewsSource.parse(torn).isEmpty());
    }

    @Test
    void collectDeliversTheWholeSitemap() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(sitemap());
        ReutersNewsSource source = new ReutersNewsSource(fetcher);
        assertEquals(5, source.collect().size(),
                "one pass hands the whole current window to the pool");
        assertEquals(1, fetcher.fetches.get());
        assertEquals("reuters", source.sourceName());
        assertFalse(source.socialSentiment(), "a wire is press, never the sentiment fan");
    }

    @Test
    void softTwoHundredTrapIsAMiss() throws Exception {
        // The bot wall answers 200-shaped HTML challenges — only content counts.
        FakeFetcher fetcher = new FakeFetcher(
                WebResponse.text(200, "<!doctype html>\n<html><body>Please verify you are "
                        + "a human</body></html>", Map.of()),
                sitemap());
        ReutersNewsSource source = new ReutersNewsSource(fetcher);

        assertTrue(source.collect().isEmpty(), "an HTML 200 is a miss, not a sitemap");
        assertEquals(5, source.collect().size(),
                "nothing was cached — the next pass refetches and succeeds");
        assertEquals(2, fetcher.fetches.get());
    }

    @Test
    void nonTwoHundredYieldsEmptyNeverThrows() throws Exception {
        ReutersNewsSource source = new ReutersNewsSource(
                new FakeFetcher(WebResponse.text(503, "", Map.of())));
        assertTrue(source.collect().isEmpty());
    }

    @Test
    void publicationDateParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-16T16:49:11.350Z"),
                ReutersNewsSource.parseDate("2026-07-16T16:49:11.35Z"));
        assertEquals(Instant.parse("2026-07-16T16:53:43.816Z"),
                ReutersNewsSource.parseDate("2026-07-16T16:53:43.816Z"));
        assertNull(ReutersNewsSource.parseDate("Thu, 16 Jul 2026"));
        assertNull(ReutersNewsSource.parseDate(""));
        assertNull(ReutersNewsSource.parseDate(null));
    }

    @Test
    void looksLikeSitemapAcceptsUrlsetsAndRejectsEverythingElse() {
        assertTrue(ReutersNewsSource.looksLikeSitemap(fixture()));
        assertTrue(ReutersNewsSource.looksLikeSitemap(
                "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>"));
        assertFalse(ReutersNewsSource.looksLikeSitemap(
                "<?xml version=\"1.0\"?><rss version=\"2.0\"/>"),
                "an RSS answer is NOT the sitemap — the endpoint changed shape");
        assertFalse(ReutersNewsSource.looksLikeSitemap("<!doctype html><html></html>"));
        assertFalse(ReutersNewsSource.looksLikeSitemap(null));
        assertFalse(ReutersNewsSource.looksLikeSitemap("   "));
    }
}
