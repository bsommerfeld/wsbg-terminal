package de.bsommerfeld.wsbg.terminal.web.impl.sources.googlenews;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edition fan against a fake fetcher: every edition is asked once for a
 * name query, every surviving item carries the sphere of the index that
 * produced it, and identical links across editions collapse to one item.
 */
class GoogleNewsWorldSourceTest {

    private static String fixture() {
        try (InputStream in = GoogleNewsWorldSourceTest.class
                .getResourceAsStream("/google-news-rheinmetall.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    @Test
    void nameFanAsksEveryEditionAndStampsTheirOrigins() {
        AtomicInteger fetches = new AtomicInteger();
        WebFetcher fake = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                fetches.incrementAndGet();
                assertEquals(FetchUtil.DIRECT, modes[0], "declared mode order rides the fetch");
                assertTrue(url.contains("ceid="), "every request names its edition: " + url);
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
        GoogleNewsWorldSource source = new GoogleNewsWorldSource(fake);

        List<Article> items = source.newsFor(ResolvedInstrument.ofName("Rheinmetall AG"), 50);

        assertEquals(GoogleNewsWorldSource.EDITIONS.size(), fetches.get(),
                "one request per edition, name leg only (no ISIN resolved)");
        // The fake serves the SAME feed to every edition, so the cross-edition
        // link dedup collapses the fan to the fixture's items.
        assertEquals(3, items.size(), String.valueOf(items));
        for (Article item : items) {
            assertTrue(item.origin().known(),
                    "an item arrived without the sphere of its edition: " + item.title());
        }
        // Union in edition order: the first edition's origin won the dedup.
        assertEquals("US", items.get(0).origin().sphere());
    }

    @Test
    void archiveWindowFansEveryEditionWithAfterBeforeOperators() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        WebFetcher fake = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                fetches.incrementAndGet();
                String query = java.net.URLDecoder.decode(
                        url.substring(url.indexOf("?q=") + 3, url.indexOf("&hl=")),
                        StandardCharsets.UTF_8);
                assertTrue(query.contains(" after:2020-01-01 before:2021-01-01"),
                        "every edition's query carries the window: " + query);
                assertTrue(query.startsWith("Rheinmetall "),
                        "the edition's own finance suffix rides between name and window: "
                                + query);
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
        GoogleNewsWorldSource source = new GoogleNewsWorldSource(fake);

        List<Article> items = source.newsForWindow(ResolvedInstrument.ofName("Rheinmetall AG"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1), 50);

        assertEquals(GoogleNewsWorldSource.EDITIONS.size(), fetches.get(),
                "the archive window is fanned once per edition");
        assertEquals(3, items.size(), "cross-edition link dedup holds on the archive leg too");
        for (Article item : items) {
            assertTrue(item.origin().known(),
                    "an archive item arrived without the sphere of its edition: " + item.title());
        }
    }
}
