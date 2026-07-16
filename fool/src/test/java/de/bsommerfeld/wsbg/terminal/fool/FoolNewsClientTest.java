package de.bsommerfeld.wsbg.terminal.fool;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing and merging of The Motley Fool's two public feeds — fixtures are
 * live response excerpts (2026-07-13): the news sitemap trimmed to 3 entries
 * (one multi-ticker, one two-ticker, one ticker-less that also appears in the
 * firehose) and the foolwatch RSS trimmed to 2 items (the sitemap twin plus a
 * firehose-only prediction piece).
 */
class FoolNewsClientTest {

    private static String fixture(String name) {
        try (InputStream in = FoolNewsClientTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    @Test
    void sitemapParsesTickersImageAndDate() {
        List<RawNewsItem> items = FoolNewsClient.parseSitemap(fixture("fool-news-sitemap.xml"));
        assertEquals(3, items.size());

        RawNewsItem meta = items.get(0);
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

        RawNewsItem socialSecurity = items.get(2);
        assertTrue(socialSecurity.relatedTickers().isEmpty(),
                "a ticker-less entry is kept — it still answers name queries");
    }

    @Test
    void feedParsesTeaserAndCanonicalGuid() {
        List<RawNewsItem> items = FoolNewsClient.parseFeed(fixture("foolwatch.xml"));
        assertEquals(2, items.size());

        RawNewsItem first = items.get(0);
        assertEquals("The Optimal Age to Claim Social Security Benefits May Surprise You",
                first.title());
        assertEquals("https://www.fool.com/retirement/2026/07/12/the-optimal-age-to-claim-social-security-benefits/",
                first.link(), "guid (the clean canonical URL) is used, not the tracking link");
        assertEquals(first.link(), first.uuid());
        assertEquals("Most retirees don't claim Social Security at the right age, and it could cost them.",
                first.summary(), "description is the teaser");
        assertEquals(Instant.parse("2026-07-12T23:00:00Z"), first.publishedAt(),
                "RFC-1123 pubDate with numeric offset parses");
        assertTrue(first.relatedTickers().isEmpty(), "foolwatch carries no ticker tags");
    }

    @Test
    void mergeJoinsBothFeedsOnCanonicalLink() {
        List<RawNewsItem> merged = FoolNewsClient.merge(
                FoolNewsClient.parseSitemap(fixture("fool-news-sitemap.xml")),
                FoolNewsClient.parseFeed(fixture("foolwatch.xml")));

        // 3 sitemap + 2 feed, one shared article → 4 distinct.
        assertEquals(4, merged.size());

        RawNewsItem joined = merged.stream()
                .filter(it -> it.title().startsWith("The Optimal Age"))
                .findFirst().orElseThrow();
        assertEquals("Most retirees don't claim Social Security at the right age, and it could cost them.",
                joined.summary(), "the firehose twin contributes its teaser");
        assertEquals("https://g.foolcdn.com/editorial/images/877882/person-reviewing-financial-paperwork.jpg",
                joined.imageUrl(), "the sitemap side keeps its image");

        RawNewsItem firehoseOnly = merged.stream()
                .filter(it -> it.title().startsWith("Prediction"))
                .findFirst().orElseThrow();
        assertEquals("One chipmaker looks poised to outperform on accelerating data center demand.",
                firehoseOnly.summary());
    }

    @Test
    void transcriptsParseFromListingCards() {
        List<RawNewsItem> items = FoolNewsClient.parseTranscripts(fixture("fool-transcripts.html"));
        assertEquals(3, items.size(),
                "each card once, despite the doubled link (image + title anchor)");

        RawNewsItem pep = items.get(0);
        assertEquals("PepsiCo (PEP) Q2 2026 Earnings Call Transcript", pep.title());
        assertEquals("https://www.fool.com/earnings/call-transcripts/2026/07/09/pepsico-pep-q2-2026-earnings-call-transcript/",
                pep.link());
        assertEquals(List.of("PEP"), pep.relatedTickers(), "ticker from the title parens");
        assertEquals(Instant.parse("2026-07-09T00:00:00Z"), pep.publishedAt(),
                "date from the URL path, UTC midnight");

        RawNewsItem pg = items.get(2);
        assertEquals("Procter & Gamble Annual Meeting Transcript", pg.title(),
                "HTML entities in the card title are decoded");
        assertTrue(pg.relatedTickers().isEmpty(),
                "a title without parens yields no ticker — name queries still find it");

        assertTrue(FoolNewsClient.parseTranscripts("<html>no cards</html>").isEmpty());
        assertTrue(FoolNewsClient.parseTranscripts(null).isEmpty());
    }

    @Test
    void appendNewSkipsArticlesThePoolAlreadyCarries() {
        List<RawNewsItem> pool = FoolNewsClient.parseSitemap(fixture("fool-news-sitemap.xml"));
        List<RawNewsItem> transcripts = FoolNewsClient.parseTranscripts(fixture("fool-transcripts.html"));

        List<RawNewsItem> appended = FoolNewsClient.appendNew(pool, transcripts);
        assertEquals(pool.size() + transcripts.size(), appended.size(),
                "no collisions today: all transcripts are appended");

        List<RawNewsItem> again = FoolNewsClient.appendNew(appended, transcripts);
        assertEquals(appended.size(), again.size(),
                "an already-pooled canonical link is never duplicated");
    }

    @Test
    void tickerParsingHandlesPrefixesAndGarbage() {
        assertEquals(List.of("WELL", "SBRA"), FoolNewsClient.parseTickers("NYSE:WELL,NASDAQ:SBRA"));
        assertEquals(List.of("BRK.B"), FoolNewsClient.parseTickers(" NYSE:BRK.B "));
        assertEquals(List.of("NVDA"), FoolNewsClient.parseTickers("nvda"),
                "a bare, lowercase symbol is normalised");
        assertTrue(FoolNewsClient.parseTickers("").isEmpty());
        assertTrue(FoolNewsClient.parseTickers(null).isEmpty());
    }

    @Test
    void parsersTolerateGarbageAnswers() {
        assertTrue(FoolNewsClient.parseSitemap("<html>bot wall</html>").isEmpty());
        assertTrue(FoolNewsClient.parseFeed("not xml at all <<<").isEmpty());
        assertTrue(FoolNewsClient.parseSitemap(null).isEmpty());
        assertTrue(FoolNewsClient.parseFeed("").isEmpty());
    }

    @Test
    void dateParsingIsLenientAboutFailure() {
        assertEquals(Instant.parse("2026-07-13T02:04:00Z"),
                FoolNewsClient.parseIsoDate("2026-07-13T02:04:00+00:00"));
        assertNull(FoolNewsClient.parseIsoDate("gestern"));
        assertNull(FoolNewsClient.parseRfc1123Date("gestern"));
    }

    @Test
    void titleRelevanceIsWordLevelWithGenericGuard() {
        Set<String> words = FoolNewsClient.significantWords("Welltower Inc.");
        assertEquals(Set.of("welltower"), words, "the legal suffix never matches alone");
        assertTrue(FoolNewsClient.titleMatches(
                "Better Senior Housing REIT: Sabra Health Care or Welltower?", words));
        assertFalse(FoolNewsClient.titleMatches("Why Meta Platforms Stock Surged This Week", words));
        assertTrue(FoolNewsClient.significantWords("The Stock Company Inc").isEmpty(),
                "an all-generic name yields no match words at all");
    }

    @Test
    void newsForJoinsByTickerAndCachesBursts() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        WebFetcher fake = new WebFetcher() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
                fetches.incrementAndGet();
                String body = switch (url) {
                    case FoolNewsClient.SITEMAP_URL -> fixture("fool-news-sitemap.xml");
                    case FoolNewsClient.TRANSCRIPTS_URL -> fixture("fool-transcripts.html");
                    default -> fixture("foolwatch.xml");
                };
                return new WebResponse(200, body, Map.of());
            }
        };
        FoolNewsClient client = new FoolNewsClient(fake);

        List<RawNewsItem> nvda = client.newsFor("nvda", 10);
        assertEquals(1, nvda.size(), "NVDA is a co-ticker of the Meta piece");
        assertEquals("Why Meta Platforms Stock Surged This Week", nvda.get(0).title());

        assertTrue(client.newsFor("SAP", 10).isEmpty(), "unknown ticker → no items");

        List<RawNewsItem> byName = client.newsForName("Sabra Health Care REIT, Inc.", 10);
        assertEquals(1, byName.size(), "name query matches the title");

        List<RawNewsItem> capped = client.newsForName("Social Security", 1);
        assertEquals(1, capped.size(), "limit caps");

        List<RawNewsItem> teaserOnly = client.newsForName("Chipmaker Inc", 10);
        assertEquals(1, teaserOnly.size(),
                "a name only the TEASER carries still matches — roundup titles "
                        + "name the companies in the body (mandate 2026-07-16)");
        assertTrue(teaserOnly.get(0).title().startsWith("Prediction"));

        List<RawNewsItem> pep = client.newsFor("PEP", 10);
        assertEquals(1, pep.size(), "the transcript leg answers the ticker query too");
        assertTrue(pep.get(0).title().endsWith("Earnings Call Transcript"));

        assertEquals(3, fetches.get(),
                "the whole burst is served from ONE fetch per feed (pool cache)");
    }
}
