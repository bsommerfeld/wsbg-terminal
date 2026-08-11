package de.bsommerfeld.wsbg.terminal.web.impl.sources.fool;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing and merging of The Motley Fool's public surfaces — every fixture is
 * a trimmed live response: the news sitemap trimmed to 3 entries (one
 * multi-ticker, one two-ticker, one ticker-less) and the transcripts listing
 * trimmed to 3 cards. Ported from the module world; the foolwatch RSS
 * firehose rides the curated feed catalog now and is no longer merged here.
 */
class FoolNewsSourceTest {

    private static String fixture(String name) {
        try (InputStream in = FoolNewsSourceTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    private static ResolvedInstrument byTicker(String symbol) {
        return new ResolvedInstrument(Optional.empty(), Ticker.parse(symbol), "");
    }

    @Test
    void sitemapParsesTickersImageAndDate() {
        List<Article> items = FoolNewsSource.parseSitemap(fixture("fool-news-sitemap.xml"));
        assertEquals(3, items.size());

        Article meta = items.get(0);
        assertEquals("Why Meta Platforms Stock Surged This Week", meta.title(),
                "news:title wins over image:title (same local name, different namespace)");
        assertEquals("The Motley Fool", meta.publisher());
        assertEquals("https://www.fool.com/investing/2026/07/12/why-meta-platforms-stock-surged-this-week/",
                meta.link(), "loc is link and identity");
        assertEquals(meta.link(), meta.uuid());
        assertEquals(List.of("META", "TSM", "NVDA", "AMD", "AVGO"), meta.relatedTickers(),
                "exchange prefixes are stripped");
        assertEquals(Instant.parse("2026-07-13T01:51:48Z"), meta.publishedAt());
        assertEquals("https://g.foolcdn.com/editorial/images/878592/meta-stock.png", meta.imageUrl(),
                "image:loc is kept, not confused with the article loc");

        Article socialSecurity = items.get(2);
        assertTrue(socialSecurity.relatedTickers().isEmpty(),
                "a ticker-less entry is kept — it still answers name queries");
    }

    @Test
    void transcriptsParseFromListingCards() {
        List<Article> items = FoolNewsSource.parseTranscripts(fixture("fool-transcripts.html"));
        assertEquals(3, items.size(),
                "each card once, despite the doubled link (image + title anchor)");

        Article pep = items.get(0);
        assertEquals("PepsiCo (PEP) Q2 2026 Earnings Call Transcript", pep.title());
        assertEquals("https://www.fool.com/earnings/call-transcripts/2026/07/09/pepsico-pep-q2-2026-earnings-call-transcript/",
                pep.link());
        assertEquals(List.of("PEP"), pep.relatedTickers(), "ticker from the title parens");
        assertEquals(Instant.parse("2026-07-09T00:00:00Z"), pep.publishedAt(),
                "date from the URL path, UTC midnight");

        Article pg = items.get(2);
        assertEquals("Procter & Gamble Annual Meeting Transcript", pg.title(),
                "HTML entities in the card title are decoded");
        assertTrue(pg.relatedTickers().isEmpty(),
                "a title without parens yields no ticker — name queries still find it");

        assertTrue(FoolNewsSource.parseTranscripts("<html>no cards</html>").isEmpty());
        assertTrue(FoolNewsSource.parseTranscripts(null).isEmpty());
    }

    @Test
    void appendNewSkipsArticlesThePoolAlreadyCarries() {
        List<Article> pool = FoolNewsSource.parseSitemap(fixture("fool-news-sitemap.xml"));
        List<Article> transcripts = FoolNewsSource.parseTranscripts(fixture("fool-transcripts.html"));

        List<Article> appended = FoolNewsSource.appendNew(pool, transcripts);
        assertEquals(pool.size() + transcripts.size(), appended.size(),
                "no collisions today: all transcripts are appended");

        List<Article> again = FoolNewsSource.appendNew(appended, transcripts);
        assertEquals(appended.size(), again.size(),
                "an already-pooled canonical link is never duplicated");
    }

    @Test
    void tickerParsingHandlesPrefixesAndGarbage() {
        assertEquals(List.of("WELL", "SBRA"), FoolNewsSource.parseTickers("NYSE:WELL,NASDAQ:SBRA"));
        assertEquals(List.of("BRK.B"), FoolNewsSource.parseTickers(" NYSE:BRK.B "));
        assertEquals(List.of("NVDA"), FoolNewsSource.parseTickers("nvda"),
                "a bare, lowercase symbol is normalised");
        assertTrue(FoolNewsSource.parseTickers("").isEmpty());
        assertTrue(FoolNewsSource.parseTickers(null).isEmpty());
    }

    @Test
    void parsersTolerateGarbageAnswers() {
        assertTrue(FoolNewsSource.parseSitemap("<html>bot wall</html>").isEmpty());
        assertTrue(FoolNewsSource.parseSitemap(null).isEmpty());
        assertTrue(FoolNewsSource.parseSitemap("not xml at all <<<").isEmpty());
    }

    @Test
    void dateParsingIsLenientAboutFailure() {
        assertEquals(Instant.parse("2026-07-13T02:04:00Z"),
                FoolNewsSource.parseIsoDate("2026-07-13T02:04:00+00:00"));
        assertNull(FoolNewsSource.parseIsoDate("gestern"));
    }

    @Test
    void newsForJoinsByTickerAndNameAndCachesBursts() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        WebFetcher fake = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                fetches.incrementAndGet();
                String body = switch (url) {
                    case FoolNewsSource.SITEMAP_URL -> fixture("fool-news-sitemap.xml");
                    case FoolNewsSource.TRANSCRIPTS_URL -> fixture("fool-transcripts.html");
                    default -> throw new AssertionError("unexpected fetch: " + url);
                };
                return WebResponse.text(200, body, Map.of());
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
        };
        FoolNewsSource source = new FoolNewsSource(fake);

        List<Article> nvda = source.newsFor(byTicker("nvda"), 10);
        assertEquals(1, nvda.size(), "NVDA is a co-ticker of the Meta piece");
        assertEquals("Why Meta Platforms Stock Surged This Week", nvda.get(0).title());

        assertTrue(source.newsFor(byTicker("SAP"), 10).isEmpty(), "unknown ticker → no items");

        List<Article> byName = source.newsFor(
                ResolvedInstrument.ofName("Sabra Health Care REIT, Inc."), 10);
        assertEquals(1, byName.size(), "name query matches the title");

        List<Article> capped = source.newsFor(ResolvedInstrument.ofName("Social Security"), 1);
        assertEquals(1, capped.size(), "limit caps");

        List<Article> pep = source.newsFor(byTicker("PEP"), 10);
        assertEquals(1, pep.size(), "the transcript leg answers the ticker query too");
        assertTrue(pep.get(0).title().endsWith("Earnings Call Transcript"));

        List<Article> both = source.newsFor(new ResolvedInstrument(
                Optional.empty(), Ticker.parse("NVDA"), "Meta Platforms, Inc."), 10);
        assertEquals(1, both.size(),
                "ticker leg and name leg landing on the same article dedupe by uuid");

        assertEquals(2, fetches.get(),
                "ONE fetch per surface for the whole burst (pool politeness cache)");
    }

    @Test
    void venueSuffixedTickersJoinOnTheBaseSymbol() throws Exception {
        WebFetcher fake = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                return WebResponse.text(200,
                        url.equals(FoolNewsSource.SITEMAP_URL)
                                ? fixture("fool-news-sitemap.xml") : "<html>no cards</html>",
                        Map.of());
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
        };
        // Fool tags bare US symbols — a venue-suffixed house symbol joins on its base.
        assertEquals(1, new FoolNewsSource(fake).newsFor(byTicker("NVDA.DE"), 10).size());
    }
}
