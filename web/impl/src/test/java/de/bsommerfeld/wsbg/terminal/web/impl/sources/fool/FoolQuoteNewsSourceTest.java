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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing and venue resolution of The Motley Fool's per-symbol quote pages —
 * every fixture is a trimmed live response (2026-08-02):
 * {@code fool-quote-nvda.html} carries BOTH article surfaces (the JSON-LD
 * {@code ItemList} and the escaped {@code __next_f} payload, one article
 * shared between them, one undated evergreen guide as a decoy);
 * {@code fool-quote-ko.html} is the NYSE counterpart behind a 404 on the
 * nasdaq probe. Ported from the module world's FoolNewsClientTest quote legs.
 */
class FoolQuoteNewsSourceTest {

    private static String fixture(String name) {
        try (InputStream in = FoolQuoteNewsSourceTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    private static ResolvedInstrument instrument(String symbol, String name) {
        return new ResolvedInstrument(Optional.empty(), Ticker.parse(symbol), name);
    }

    /** A fetcher over the quote-page fixtures: two pages answer, 404 elsewhere. */
    private static final class QuoteFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        final List<String> urls = new java.util.ArrayList<>();

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            assertEquals(FetchUtil.BROWSER, modes[0], "declared mode order rides the fetch");
            fetches.incrementAndGet();
            urls.add(url);
            String body = switch (url) {
                case "https://www.fool.com/quote/nasdaq/nvda/" -> fixture("fool-quote-nvda.html");
                case "https://www.fool.com/quote/nyse/ko/" -> fixture("fool-quote-ko.html");
                default -> null;
            };
            return body == null
                    ? WebResponse.text(404, "", Map.of())
                    : WebResponse.text(200, body, Map.of());
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

    @Test
    void quotePageArticlesComeFromBothSurfacesAndDedupe() {
        List<Article> items =
                FoolQuoteNewsSource.parseQuoteArticles(fixture("fool-quote-nvda.html"), "nvda");
        assertEquals(5, items.size(),
                "3 JSON-LD entries + 3 payload entries, one shared — and the undated "
                        + "evergreen guide in the same payload rail is no article");

        Article rich = items.get(0);
        assertEquals("What Nvidia Could Be Worth on a $1,000 Investment if History Repeats Itself",
                rich.title());
        assertEquals("https://www.fool.com/investing/2026/08/02/what-nvidia-could-be-worth-on-a-1000-investment-if/",
                rich.link());
        assertEquals(Instant.parse("2026-08-02T20:00:00Z"), rich.publishedAt());
        assertTrue(rich.imageUrl() != null && rich.imageUrl().startsWith("https://g.foolcdn.com/"),
                "the JSON-LD leg carries the article image");
        assertEquals(List.of("NVDA"), rich.relatedTickers(),
                "the page IS the ticker join — every item is tagged with the queried symbol");

        Article payloadOnly = items.stream()
                .filter(it -> it.link().endsWith("/nobodys-talking-about-the-1-biggest-risk-facing-nv/"))
                .findFirst().orElseThrow();
        assertEquals("Nobody's Talking About the 1 Biggest Risk Facing Nvidia", payloadOnly.title());
        assertNull(payloadOnly.imageUrl(), "the wide payload leg carries no image");
        assertEquals(Instant.parse("2026-08-02T13:49:00Z"), payloadOnly.publishedAt());

        assertEquals(items.size(), items.stream().map(Article::link).distinct().count(),
                "the article both surfaces list appears once");
        assertTrue(items.stream().noneMatch(it -> it.link().contains("/stock-market/")),
                "undated evergreen guides stay out of the news answer");
    }

    /**
     * The two Fool legs have different REACH, so they are two sources: the
     * chronological pool is the wire's fresh window, the quote page the
     * dossier's archive (2026-08-03).
     */
    @Test
    void quotePageIsTheArchiveLegAndAnswersByTickerOnly() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolQuoteNewsSource source = new FoolQuoteNewsSource(fake);

        List<Article> deep = source.newsFor(instrument("NVDA", "Nvidia"), 20);
        assertEquals(5, deep.size(), "the quote page reaches weeks further back");
        assertTrue(deep.stream().anyMatch(it -> it.title().startsWith("Nobody's Talking About")),
                "it contributes what no chronological surface carries");
        assertTrue(deep.get(0).publishedAt().isAfter(deep.get(deep.size() - 1).publishedAt()),
                "newest first");
        assertEquals(2, source.newsFor(instrument("NVDA", "Nvidia"), 2).size(),
                "limit caps the answer");
        assertTrue(source.newsFor(instrument("NVDA", "Nvidia"), 0).isEmpty());
        assertTrue(source.newsFor(ResolvedInstrument.ofName("Nvidia"), 20).isEmpty(),
                "the quote page is a path segment per ticker — a bare name cannot address it");
        assertTrue(source.newsFor(null, 20).isEmpty());

        int fetched = fake.fetches.get();
        source.newsFor(instrument("NVDA", "Nvidia"), 20);
        assertEquals(fetched, fake.fetches.get(), "a burst is served from the page cache");
    }

    @Test
    void exchangeIsProbedVenueByVenueAndThenRemembered() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolQuoteNewsSource source = new FoolQuoteNewsSource(fake);

        assertFalse(source.newsFor(instrument("KO", "Coca-Cola"), 10).isEmpty(),
                "nasdaq is probed first and 404s, nyse answers");
        assertEquals(List.of("https://www.fool.com/quote/nasdaq/ko/",
                "https://www.fool.com/quote/nyse/ko/"), fake.urls);

        int afterProbe = fake.fetches.get();
        assertFalse(source.newsFor(instrument("KO", "Coca-Cola"), 10).isEmpty(),
                "the answer is memoised");
        assertEquals(afterProbe, fake.fetches.get(),
                "the resolved page is cached — no second probe, no second page fetch");
    }

    @Test
    void anUnknownSymbolIsProbedOnceAndThenLeftAlone() {
        QuoteFetcher fake = new QuoteFetcher();
        FoolQuoteNewsSource source = new FoolQuoteNewsSource(fake);

        assertTrue(source.newsFor(instrument("SAP", "SAP SE"), 10).isEmpty());
        assertEquals(3, fake.fetches.get(), "nasdaq, nyse, crypto — all 404");
        assertTrue(source.newsFor(instrument("SAP", "SAP SE"), 10).isEmpty());
        assertEquals(3, fake.fetches.get(),
                "the unresolvable verdict is remembered — a burst probes once, not once per call");
    }

    @Test
    void indexSymbolsNeverReachTheQuotePath() {
        // '^' is illegal in a URL path — the three venue probes would each throw
        // instead of 404ing, once per caller. Fool carries no index pages anyway.
        assertFalse(FoolQuoteNewsSource.isQuotePathSymbol("^TECDAX"));
        assertFalse(FoolQuoteNewsSource.isQuotePathSymbol("^N225"));
        assertFalse(FoolQuoteNewsSource.isQuotePathSymbol("CL=F"));
        assertFalse(FoolQuoteNewsSource.isQuotePathSymbol(""));
        assertFalse(FoolQuoteNewsSource.isQuotePathSymbol(null));
        assertTrue(FoolQuoteNewsSource.isQuotePathSymbol("NVDA"));
        assertTrue(FoolQuoteNewsSource.isQuotePathSymbol("BRK-B"));
        assertTrue(FoolQuoteNewsSource.isQuotePathSymbol("BF.A"));
    }

    @Test
    void jsStringUnescapingSurvivesNestedEscapes() {
        assertEquals("{\"a\":\"b\"}", FoolQuoteNewsSource.unescapeJsString("{\\\"a\\\":\\\"b\\\"}"),
                "one level off: the payload is JSON inside a JS string literal");
        assertEquals("\"x\\\"y\"", FoolQuoteNewsSource.unescapeJsString("\\\"x\\\\\\\"y\\\""),
                "a headline containing a quote arrives triple-escaped and stays valid JSON");
        assertEquals("", FoolQuoteNewsSource.unescapeJsString(null));
    }

    @Test
    void quoteParsersTolerateGarbageAnswers() {
        assertTrue(FoolQuoteNewsSource.parseQuoteArticles("<html>bot wall</html>", "NVDA").isEmpty());
        assertTrue(FoolQuoteNewsSource.parseQuoteArticles(null, "NVDA").isEmpty());
        assertTrue(FoolQuoteNewsSource.parseQuoteArticles("", "NVDA").isEmpty());

        assertTrue(FoolQuoteNewsSource.parseQuoteArticles(
                        "<script id=\"quote-page-related-schema\">{ broken</script>", "NVDA").isEmpty(),
                "a truncated schema block costs the leg, never an exception");
    }
}
