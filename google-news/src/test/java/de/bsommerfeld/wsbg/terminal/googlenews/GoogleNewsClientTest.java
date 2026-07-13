package de.bsommerfeld.wsbg.terminal.googlenews;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of Google News' RSS search reply — fixture is a live response excerpt
 * (query {@code "Rheinmetall Aktie"}, 2026-07-13) trimmed to 4 items: 3 whose
 * titles name Rheinmetall plus one generic roundup (WirtschaftsWoche
 * "Handels-Star des ersten Halbjahrs") that must be relevance-filtered.
 */
class GoogleNewsClientTest {

    private static String fixture() {
        try (InputStream in = GoogleNewsClientTest.class
                .getResourceAsStream("/google-news-rheinmetall.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    @Test
    void parseKeepsTitleRelevantItemsOnly() {
        List<RawNewsItem> items = GoogleNewsClient.parse(fixture(), "Rheinmetall AG");
        assertEquals(3, items.size(),
                "the generic roundup (no 'Rheinmetall' in title) is filtered");

        RawNewsItem first = items.get(0);
        assertEquals("Rheinmetall-Aktie: Dritter Tag mit großen Verlusten", first.title(),
                "Google's ' - <publisher>' title suffix is stripped");
        assertEquals("WELT", first.publisher());
        assertTrue(first.link().startsWith("https://news.google.com/rss/articles/"),
                "Google's redirect link is kept as-is");
        assertEquals(first.uuid(), first.link().replace("https://news.google.com/rss/articles/", "")
                        .replace("?oc=5", ""),
                "uuid = guid (Google's guid is the article token of the link)");
        assertEquals(Instant.parse("2026-07-10T14:45:16Z"), first.publishedAt(),
                "RFC-1123 pubDate parses to the exact instant");

        // Every kept item's title names the subject.
        for (RawNewsItem item : items) {
            assertTrue(item.title().toLowerCase().contains("rheinmetall"), item.title());
        }
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(GoogleNewsClient.parse("<html><body>captcha wall</body></html>", "Rheinmetall")
                .isEmpty(), "an HTML answer has no items");
        assertTrue(GoogleNewsClient.parse("not xml at all { \"json\": true }", "Rheinmetall")
                .isEmpty(), "garbage never throws");
        assertTrue(GoogleNewsClient.parse("", "Rheinmetall").isEmpty());
        assertTrue(GoogleNewsClient.parse(null, "Rheinmetall").isEmpty());
        // Truncated mid-item (a torn response) — parses what it can, never throws.
        String torn = fixture().substring(0, fixture().indexOf("<pubDate>") + 20);
        assertTrue(GoogleNewsClient.parse(torn, "Rheinmetall").isEmpty());
    }

    @Test
    void pubDateParsing() {
        assertEquals(Instant.parse("2026-07-13T08:30:00Z"),
                GoogleNewsClient.parsePubDate("Mon, 13 Jul 2026 08:30:00 GMT"));
        assertEquals(Instant.parse("2026-07-13T08:30:00Z"),
                GoogleNewsClient.parsePubDate("Sun, 13 Jul 2026 08:30:00 GMT"),
                "a wrong day-of-week token is tolerated — the date wins");
        assertNull(GoogleNewsClient.parsePubDate("gestern"));
        assertNull(GoogleNewsClient.parsePubDate(""));
        assertNull(GoogleNewsClient.parsePubDate(null));
    }

    @Test
    void titleMatchingIsWordLevelAndUmlautTolerant() {
        var words = GoogleNewsClient.significantWords("Münchener Rück");
        assertTrue(GoogleNewsClient.titleMatches("Muenchener Rueck hebt Dividende an", words));
        assertFalse(GoogleNewsClient.titleMatches("Allianz hebt Dividende an", words));
        // A generic legal word never carries the match alone.
        assertTrue(GoogleNewsClient.significantWords("Meta Wolf AG").contains("meta"));
        assertFalse(GoogleNewsClient.significantWords("Meta Wolf AG").contains("ag"));
    }

    @Test
    void cleanNameStripsLegalSuffixesForTheQuery() {
        assertEquals("NVIDIA", GoogleNewsClient.cleanName("NVIDIA Corporation"));
        assertEquals("Amazon.com", GoogleNewsClient.cleanName("Amazon.com, Inc."));
        assertEquals("Meta Wolf", GoogleNewsClient.cleanName("Meta Wolf AG"));
        assertEquals("Rheinmetall", GoogleNewsClient.cleanName("Rheinmetall"));
    }

    @Test
    void limitCapsAndCacheAnswersBurstsWithOneFetch() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        WebFetcher fake = new WebFetcher() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
                fetches.incrementAndGet();
                assertTrue(url.contains("q=Rheinmetall+Aktie"), "query is '<name> Aktie': " + url);
                assertTrue(url.contains("hl=de") && url.contains("ceid=DE%3Ade")
                        || url.contains("ceid=DE:de"), url);
                return new WebResponse(200, fixture(), Map.of());
            }
        };
        GoogleNewsClient client = new GoogleNewsClient(fake);

        List<RawNewsItem> capped = client.newsForName("Rheinmetall AG", 2);
        assertEquals(2, capped.size(), "limit caps the relevance-filtered list");

        List<RawNewsItem> again = client.newsForName("Rheinmetall AG", 10);
        assertEquals(3, again.size(), "cache serves the uncapped list, capped per call");
        assertEquals(1, fetches.get(), "a burst of calls for one name makes ONE request");

        assertTrue(client.newsForName("", 5).isEmpty());
        assertTrue(client.newsForName("Rheinmetall", 0).isEmpty());
        assertTrue(client.newsFor("RHM.DE", 5).isEmpty(), "symbol leg is a no-op");
        assertEquals(1, fetches.get(), "blank name / zero limit / symbol leg never fetch");
    }
}
