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
 * Parsing and merging of The Motley Fool's public surfaces — every fixture is
 * a trimmed live response.
 *
 * <p>Chronological legs (2026-07-13): the news sitemap trimmed to 3 entries
 * (one multi-ticker, one two-ticker, one ticker-less that also appears in the
 * firehose) and the foolwatch RSS trimmed to 2 items (the sitemap twin plus a
 * firehose-only prediction piece).
 *
 * <p>Per-symbol legs (2026-08-02): the quotes sitemap index with its three
 * venue legs trimmed to a handful of symbols each, and two quote pages —
 * {@code fool-quote-nvda.html} carrying BOTH article surfaces (the JSON-LD
 * {@code ItemList} and the escaped {@code __next_f} payload, one article shared
 * between them, one undated evergreen guide as a decoy) plus the site-wide
 * ticker bar that the profile parser must not mistake for the instrument;
 * {@code fool-quote-ko.html} as the NYSE counterpart.
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
                if (url.startsWith(FoolNewsClient.QUOTE_BASE)) {
                    return new WebResponse(404, "", Map.of()); // no quote fixture for these symbols
                }
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

        assertEquals(12, fetches.get(),
                "ONE fetch per feed for the whole burst (pool cache) — plus the "
                        + "quote leg probing nasdaq/nyse/crypto once per ticker query "
                        + "(3 tickers × 3 venues), all of them 404 here");
    }

    // ---------------------------------------------------------------- quotes

    /** A fetcher over the 2026-08-02 fixtures: quotes sitemap, two quote pages, 404 elsewhere. */
    private static final class QuoteFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        final List<String> urls = new java.util.ArrayList<>();

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            fetches.incrementAndGet();
            urls.add(url);
            String body = switch (url) {
                case FoolNewsClient.SITEMAP_URL -> fixture("fool-news-sitemap.xml");
                case FoolNewsClient.TRANSCRIPTS_URL -> fixture("fool-transcripts.html");
                case FoolNewsClient.FEED_URL -> fixture("foolwatch.xml");
                case FoolNewsClient.QUOTES_SITEMAP_URL -> fixture("fool-quotes-sitemap.xml");
                case FoolNewsClient.WAG_SITEMAP_URL -> fixture("fool-wag-sitemap.xml");
                case FoolNewsClient.MONEY_SITEMAP_URL -> fixture("fool-money-news-sitemap.xml");
                case "https://www.fool.com/quotes-sitemap/nasdaq.xml" -> fixture("fool-quotes-nasdaq.xml");
                case "https://www.fool.com/quotes-sitemap/nyse.xml" -> fixture("fool-quotes-nyse.xml");
                case "https://www.fool.com/quotes-sitemap/crypto.xml" -> fixture("fool-quotes-crypto.xml");
                case "https://www.fool.com/quote/nasdaq/nvda/" -> fixture("fool-quote-nvda.html");
                case "https://www.fool.com/quote/nyse/ko/" -> fixture("fool-quote-ko.html");
                default -> null;
            };
            return body == null
                    ? new WebResponse(404, "", Map.of())
                    : new WebResponse(200, body, Map.of());
        }
    }

    @Test
    void quotesSitemapIndexNamesItsVenueLegs() {
        List<String> legs = FoolNewsClient.parseSitemapLocs(fixture("fool-quotes-sitemap.xml"));
        assertEquals(List.of("https://www.fool.com/quotes-sitemap/nyse.xml",
                        "https://www.fool.com/quotes-sitemap/nasdaq.xml",
                        "https://www.fool.com/quotes-sitemap/crypto.xml"), legs,
                "the index uses <urlset>/<url>, not <sitemapindex> — read <loc> regardless");

        assertEquals("NVDA", FoolNewsClient.symbolOfQuoteUrl("https://www.fool.com/quote/nasdaq/nvda/"));
        assertEquals("nasdaq", FoolNewsClient.exchangeOfQuoteUrl("https://www.fool.com/quote/nasdaq/nvda/"));
        assertNull(FoolNewsClient.symbolOfQuoteUrl("https://www.fool.com/investing/2026/08/02/foo/"),
                "an article URL is not a quote URL");
        assertNull(FoolNewsClient.symbolOfQuoteUrl(null));
    }

    @Test
    void tickerMapJoinsEveryVenueLegIntoOneSymbolLandscape() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolNewsClient client = new FoolNewsClient(fake);

        Map<String, String> map = client.tickerMap();
        assertEquals(9, map.size(), "4 nasdaq + 3 nyse + 2 crypto rows");
        assertEquals("https://www.fool.com/quote/nasdaq/nvda/", map.get("NVDA"));
        assertEquals("https://www.fool.com/quote/nyse/ko/", map.get("KO"),
                "the NYSE leg lands in the same map — the venue rides in the URL");
        assertEquals("https://www.fool.com/quote/crypto/btc/", map.get("BTC"));
        assertNull(map.get("SAP"), "a symbol Fool doesn't cover is simply absent");
        assertEquals(4, fake.fetches.get(), "index + three venue legs");
    }

    @Test
    void tickerMapOutlivesTheArticlePool() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolNewsClient client = new FoolNewsClient(fake);

        client.tickerMap();
        int afterMap = fake.fetches.get();

        client.latest(50);                       // the 10-minute pool fills…
        int afterPool = fake.fetches.get();
        assertEquals(afterMap + 3, afterPool, "…costing its own three feed fetches");

        for (int i = 0; i < 5; i++) {
            assertEquals(9, client.tickerMap().size());
        }
        assertEquals(afterPool, fake.fetches.get(),
                "the map runs on a 12-hour rhythm, not the pool's 10 minutes — "
                        + "megabytes of near-static rows are never re-fetched on the news beat");
    }

    @Test
    void quotePageArticlesComeFromBothSurfacesAndDedupe() {
        List<RawNewsItem> items =
                FoolNewsClient.parseQuoteArticles(fixture("fool-quote-nvda.html"), "nvda");
        assertEquals(5, items.size(),
                "3 JSON-LD entries + 3 payload entries, one shared — and the undated "
                        + "evergreen guide in the same payload rail is no article");

        RawNewsItem rich = items.get(0);
        assertEquals("What Nvidia Could Be Worth on a $1,000 Investment if History Repeats Itself",
                rich.title());
        assertEquals("https://www.fool.com/investing/2026/08/02/what-nvidia-could-be-worth-on-a-1000-investment-if/",
                rich.link());
        assertEquals(Instant.parse("2026-08-02T20:00:00Z"), rich.publishedAt());
        assertTrue(rich.imageUrl() != null && rich.imageUrl().startsWith("https://g.foolcdn.com/"),
                "the JSON-LD leg carries the article image");
        assertEquals(List.of("NVDA"), rich.relatedTickers(),
                "the page IS the ticker join — every item is tagged with the queried symbol");

        RawNewsItem payloadOnly = items.stream()
                .filter(it -> it.link().endsWith("/nobodys-talking-about-the-1-biggest-risk-facing-nv/"))
                .findFirst().orElseThrow();
        assertEquals("Nobody's Talking About the 1 Biggest Risk Facing Nvidia", payloadOnly.title());
        assertNull(payloadOnly.imageUrl(), "the wide payload leg carries no image");
        assertEquals(Instant.parse("2026-08-02T13:49:00Z"), payloadOnly.publishedAt());

        assertEquals(items.size(), items.stream().map(RawNewsItem::link).distinct().count(),
                "the article both surfaces list appears once");
        assertTrue(items.stream().noneMatch(it -> it.link().contains("/stock-market/")),
                "undated evergreen guides stay out of the news answer");
    }

    @Test
    void quoteProfileReadsTheInstrumentNotTheSiteWideTickerBar() {
        FoolQuote nvda = FoolNewsClient.parseQuoteProfile(fixture("fool-quote-nvda.html"));
        assertEquals("NVDA", nvda.symbol());
        assertEquals("NASDAQ", nvda.exchange());
        assertEquals("Nvidia", nvda.name());
        assertEquals("Information Technology", nvda.sector());
        assertEquals(200.75, nvda.price(), 1e-9,
                "the page's OWN price — not the 7489.72 of the S&P row in the ticker bar "
                        + "that sits, byte-identical, on every quote page");
        assertEquals("USD", nvda.currency());
        assertEquals(0.02928, nvda.changePct(), 1e-9, "change arrives as a fraction, not a percentage");
        assertTrue(nvda.description().startsWith("Nvidia deals in programmable"));

        FoolQuote ko = FoolNewsClient.parseQuoteProfile(fixture("fool-quote-ko.html"));
        assertEquals("KO", ko.symbol());
        assertEquals("NYSE", ko.exchange());
        assertEquals("Consumer Staples", ko.sector());
        assertEquals("Beverages", ko.industry());
        assertEquals(87.59, ko.price(), 1e-9);
        assertEquals(26.39, ko.peRatio(), 1e-9);
        assertEquals(3.3191, ko.eps(), 1e-9);
        assertEquals(0.0237, ko.dividendYield(), 1e-9);
        assertEquals(376_860_354_500d, ko.marketCap(), 1e-3);
        assertEquals(65.3538, ko.week52Low(), 1e-9);
        assertEquals(90.92, ko.week52High(), 1e-9);
    }

    @Test
    void exchangeIsProbedVenueByVenueAndThenRemembered() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolNewsClient client = new FoolNewsClient(fake);

        assertEquals("nyse", client.exchangeFor("KO"),
                "nasdaq is probed first and 404s, nyse answers");
        assertEquals(List.of("https://www.fool.com/quote/nasdaq/ko/",
                "https://www.fool.com/quote/nyse/ko/"), fake.urls);

        int afterProbe = fake.fetches.get();
        assertEquals("nyse", client.exchangeFor("ko"),
                "case doesn't matter, and the answer is memoised");
        assertEquals(afterProbe, fake.fetches.get(),
                "the resolved page is cached — no second probe, no second page fetch");
    }

    @Test
    void aWarmTickerMapAnswersTheVenueWithoutProbing() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolNewsClient client = new FoolNewsClient(fake);

        client.tickerMap();
        fake.urls.clear();

        assertEquals("nyse", client.exchangeFor("KO"));
        assertEquals(List.of("https://www.fool.com/quote/nyse/ko/"), fake.urls,
                "the map is authoritative: the nasdaq 404 is never paid for");

        fake.urls.clear();
        assertNull(client.exchangeFor("SAP"),
                "a symbol the map doesn't carry is answered without a single request");
        assertTrue(fake.urls.isEmpty());
    }

    @Test
    void anUnknownSymbolIsProbedOnceAndThenLeftAlone() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolNewsClient client = new FoolNewsClient(fake);

        assertNull(client.exchangeFor("SAP"));
        assertEquals(3, fake.fetches.get(), "nasdaq, nyse, crypto — all 404");
        assertTrue(client.quoteNews("SAP", 10).isEmpty());
        assertNull(client.quote("SAP"));
        assertEquals(3, fake.fetches.get(),
                "the unresolvable verdict is remembered — a burst probes once, not once per call");
    }

    @Test
    void newsForMergesTheChronologicalPoolWithTheQuotePage() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolNewsClient client = new FoolNewsClient(fake);

        List<RawNewsItem> nvda = client.newsFor("nvda", 20);
        assertEquals(6, nvda.size(),
                "1 from the sitemap pool (NVDA is a co-ticker of the Meta piece) "
                        + "+ 5 the quote page reaches further back for");
        assertTrue(nvda.stream().anyMatch(it -> it.title().equals("Why Meta Platforms Stock Surged This Week")),
                "the pool leg survives — its co-ticker join is something the quote page cannot do");
        assertTrue(nvda.stream().anyMatch(it -> it.title().startsWith("Nobody's Talking About")),
                "the quote leg contributes what no chronological surface carries");
        assertEquals(nvda.size(), nvda.stream().map(RawNewsItem::link).distinct().count(),
                "joined on the canonical link");
        assertTrue(nvda.get(0).publishedAt().isAfter(nvda.get(nvda.size() - 1).publishedAt()),
                "newest first across both legs");

        assertEquals(2, client.newsFor("NVDA", 2).size(), "limit caps the merged answer");

        int fetched = fake.fetches.get();
        client.newsFor("NVDA", 20);
        assertEquals(fetched, fake.fetches.get(), "both legs are cached");
    }

    @Test
    void generalStreamHandsOutTheWholePoolNewestFirst() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolNewsClient client = new FoolNewsClient(fake);

        List<RawNewsItem> all = client.latest(50);
        assertEquals(7, all.size(), "3 sitemap + 1 firehose-only + 3 transcripts");
        assertEquals("Better Senior Housing REIT: Sabra Health Care or Welltower?",
                all.get(0).title(), "newest first, no ticker or name question in front of it");
        assertEquals(2, client.latest(2).size());
        assertTrue(client.latest(0).isEmpty());
        assertEquals(3, fake.fetches.get(), "the general stream is the pool — three feeds");
    }

    @Test
    void moneyStreamStandsBesideTheMarketPool() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolNewsClient client = new FoolNewsClient(fake);

        List<RawNewsItem> money = client.moneyNews(10);
        assertEquals(2, money.size());
        assertTrue(money.get(0).title().contains("High-Yield Savings"));
        assertTrue(money.stream().allMatch(it -> it.relatedTickers().isEmpty()),
                "the personal-finance sitemap carries no <news:stock_tickers> at all");
        assertEquals(1, fake.fetches.get());

        assertTrue(client.latest(50).stream().noneMatch(it -> it.link().contains("/money/")),
                "held out of the market pool on purpose — it answers no instrument question");
    }

    @Test
    void evergreenIndexDropsThePlaceholderRow() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolNewsClient client = new FoolNewsClient(fake);

        List<String> pages = client.evergreenPages();
        assertEquals(4, pages.size(), "the leading <loc>None</loc> placeholder is not a page");
        assertTrue(pages.contains("https://www.fool.com/terms/e/"), "the glossary is in there");
        assertTrue(pages.stream().noneMatch("None"::equals));

        client.evergreenPages();
        assertEquals(1, fake.fetches.get(), "near-static, so it shares the map's long TTL");
    }

    @Test
    void jsStringUnescapingSurvivesNestedEscapes() {
        assertEquals("{\"a\":\"b\"}", FoolNewsClient.unescapeJsString("{\\\"a\\\":\\\"b\\\"}"),
                "one level off: the payload is JSON inside a JS string literal");
        assertEquals("\"x\\\"y\"", FoolNewsClient.unescapeJsString("\\\"x\\\\\\\"y\\\""),
                "a headline containing a quote arrives triple-escaped and stays valid JSON");
        assertEquals("", FoolNewsClient.unescapeJsString(null));
    }

    @Test
    void quoteParsersTolerateGarbageAnswers() {
        assertTrue(FoolNewsClient.parseQuoteArticles("<html>bot wall</html>", "NVDA").isEmpty());
        assertTrue(FoolNewsClient.parseQuoteArticles(null, "NVDA").isEmpty());
        assertTrue(FoolNewsClient.parseQuoteArticles("", "NVDA").isEmpty());
        assertNull(FoolNewsClient.parseQuoteProfile("<html>bot wall</html>"));
        assertNull(FoolNewsClient.parseQuoteProfile(null));
        assertTrue(FoolNewsClient.parseSitemapLocs("not xml at all <<<").isEmpty());
        assertTrue(FoolNewsClient.parseSitemapLocs(null).isEmpty());

        assertTrue(FoolNewsClient.parseQuoteArticles(
                        "<script id=\"quote-page-related-schema\">{ broken</script>", "NVDA").isEmpty(),
                "a truncated schema block costs the leg, never an exception");
    }
}
