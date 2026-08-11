package de.bsommerfeld.wsbg.terminal.web.impl.sources.googlenews;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.impl.text.TextMatch;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of Google News' RSS search reply — fixture is a live response excerpt
 * (query {@code "Rheinmetall Aktie"}, 2026-07-13) trimmed to 4 items: 3 whose
 * titles name Rheinmetall plus one generic roundup that must be
 * relevance-filtered. Ported 1:1 from the module world.
 */
class GoogleNewsSourceTest {

    private static String fixture() {
        try (InputStream in = GoogleNewsSourceTest.class
                .getResourceAsStream("/google-news-rheinmetall.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    @Test
    void parseKeepsTitleRelevantItemsOnly() {
        List<Article> items = GoogleNewsSource.parse(fixture(), "Rheinmetall AG");
        assertEquals(3, items.size(),
                "the generic roundup (no 'Rheinmetall' in title) is filtered");

        Article first = items.get(0);
        assertEquals("Rheinmetall-Aktie: Dritter Tag mit großen Verlusten", first.title(),
                "Google's ' - <publisher>' title suffix is stripped");
        assertEquals("WELT", first.publisher());
        assertTrue(first.link().startsWith("https://news.google.com/rss/articles/"),
                "Google's redirect link is kept as-is");
        assertEquals(Instant.parse("2026-07-10T14:45:16Z"), first.publishedAt());

        for (Article item : items) {
            assertTrue(item.title().toLowerCase().contains("rheinmetall"), item.title());
        }
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(GoogleNewsSource.parse("<html><body>captcha wall</body></html>", "Rheinmetall")
                .isEmpty(), "an HTML answer has no items");
        assertTrue(GoogleNewsSource.parse("not xml at all { \"json\": true }", "Rheinmetall")
                .isEmpty(), "garbage never throws");
        assertTrue(GoogleNewsSource.parse("", "Rheinmetall").isEmpty());
        assertTrue(GoogleNewsSource.parse(null, "Rheinmetall").isEmpty());
        String torn = fixture().substring(0, fixture().indexOf("<pubDate>") + 20);
        assertTrue(GoogleNewsSource.parse(torn, "Rheinmetall").isEmpty());
    }

    @Test
    void titleMatchingIsWordLevelAndUmlautTolerantWithNonLatinEscape() {
        var words = TextMatch.significantWords("Münchener Rück");
        assertTrue(GoogleNewsSource.titleMatches("Muenchener Rueck hebt Dividende an", words));
        assertFalse(GoogleNewsSource.titleMatches("Allianz hebt Dividende an", words));
        // A non-Latin title cannot be tested against a Latin name — the query
        // carried the precision, the item is kept.
        assertTrue(GoogleNewsSource.titleMatches("ラインメタル、業績を上方修正", words));
        // Empty words = no filter (ISIN / free query).
        assertTrue(GoogleNewsSource.titleMatches("anything", java.util.Set.of()));
    }

    @Test
    void cleanNameStripsLegalSuffixesForTheQuery() {
        assertEquals("NVIDIA", GoogleNewsSource.cleanName("NVIDIA Corporation"));
        assertEquals("Amazon.com", GoogleNewsSource.cleanName("Amazon.com, Inc."));
        assertEquals("Meta Wolf", GoogleNewsSource.cleanName("Meta Wolf AG"));
        assertEquals("Rheinmetall", GoogleNewsSource.cleanName("Rheinmetall"));
    }

    @Test
    void instrumentFanCapsAndCacheAnswersBurstsWithOneFetch() {
        AtomicInteger fetches = new AtomicInteger();
        WebFetcher fake = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                fetches.incrementAndGet();
                assertEquals(FetchUtil.DIRECT, modes[0], "declared mode order rides the fetch");
                return WebResponse.text(200, fixture(), Map.of());
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
        GoogleNewsSource source = new GoogleNewsSource(fake);
        ResolvedInstrument rheinmetall = ResolvedInstrument.ofName("Rheinmetall AG");

        List<Article> capped = source.newsFor(rheinmetall, 2);
        assertEquals(2, capped.size());
        assertEquals(1, fetches.get());

        source.newsFor(rheinmetall, 3);
        assertEquals(1, fetches.get(), "a burst on the same query is served from the cache");
    }

    @Test
    void archiveWindowRidesAfterBeforeQueryOperators() throws Exception {
        AtomicReference<String> asked = new AtomicReference<>();
        WebFetcher fake = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                asked.set(url);
                return WebResponse.text(200, fixture(), Map.of());
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
        GoogleNewsSource source = new GoogleNewsSource(fake);

        List<Article> items = source.newsForWindow(ResolvedInstrument.ofName("Rheinmetall AG"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1), 10);

        assertEquals("Rheinmetall Aktie after:2020-01-01 before:2021-01-01",
                java.net.URLDecoder.decode(
                        asked.get().substring(asked.get().indexOf("?q=") + 3,
                                asked.get().indexOf("&hl=")),
                        StandardCharsets.UTF_8),
                "the window rides Google's after:/before: query operators");
        assertEquals(3, items.size(), "the same relevance filter guards the archive leg");
    }
}
