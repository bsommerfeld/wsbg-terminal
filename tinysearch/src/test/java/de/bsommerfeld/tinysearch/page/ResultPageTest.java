package de.bsommerfeld.tinysearch.page;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The readers against the engines' own pages: a search for "SAP Aktie"
 * ({@code *-hits.html}) and for nonsense ({@code *-none.html}), fetched
 * through TinyFetch on 2026-09-30 and slimmed (scripts, styles, images
 * and comments dropped - the markup the readers use is untouched).
 */
class ResultPageTest {

    /** The engines' own click redirects; no hit may still point at one. */
    private static final Set<String> REDIRECT_HOSTS = Set.of("www.bing.com", "duckduckgo.com", "r.search.yahoo.com");

    static Stream<Arguments> engines() {
        return Stream.of(
                Arguments.of("bing", ResultPage.BING, 10, 8),
                Arguments.of("brave", ResultPage.BRAVE, 20, 20),
                Arguments.of("duckduckgo", ResultPage.DUCKDUCKGO, 10, 10),
                Arguments.of("ecosia", ResultPage.ECOSIA, 10, 0),
                Arguments.of("startpage", ResultPage.STARTPAGE, 10, 0),
                Arguments.of("yahoo", ResultPage.YAHOO, 7, 7),
                Arguments.of("yandex", ResultPage.YANDEX, 10, 3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void readsTheHitsInTheEnginesOrder(String name, ResultPage page, int hitCount, int nonsenseCount) throws Exception {
        List<PageHit> hits = page.read(fixture(name + "-hits.html"), page.address("SAP Aktie"));

        assertEquals(hitCount, hits.size());
        for (PageHit hit : hits) {
            assertTrue(Set.of("http", "https").contains(hit.url().getScheme()), hit.toString());
            assertFalse(REDIRECT_HOSTS.contains(hit.url().getHost()), "redirect not resolved: " + hit.url());
            assertFalse(hit.title().isBlank(), hit.toString());
        }
        assertTrue(hits.stream().anyMatch(hit -> hit.title().toLowerCase().contains("sap")), hits.toString());
        assertTrue(hits.stream().anyMatch(hit -> !hit.snippet().isEmpty()), "no snippet read at all");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSearchWithoutHitsIsStillAResultPage(String name, ResultPage page, int hitCount, int nonsenseCount)
            throws Exception {
        // Bing, Brave, DuckDuckGo, Yahoo and Yandex answer nonsense with loose matches;
        // Ecosia and Startpage say they found nothing.
        List<PageHit> hits = page.read(fixture(name + "-none.html"), page.address("qxzvbnm wlkjzzqq pfftrgh"));

        assertEquals(nonsenseCount, hits.size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aCheckPageIsNoResultPage(String name, ResultPage page, int hitCount, int nonsenseCount) {
        // Mojeek's JavaScript challenge, as it came back on 2026-09-30.
        String challenge = """
                <html><head><title>Captcha</title></head><body>
                <p>JavaScript is required to complete this challenge. Please enable it and reload the page.</p>
                </body></html>""";

        UnreadablePageException unreadable = assertThrows(UnreadablePageException.class,
                () -> page.read(challenge, page.address("SAP Aktie")));
        assertEquals("not a result page: \"Captcha\"", unreadable.getMessage());
    }

    @Test
    void bingLeadsThroughItsRedirect() throws Exception {
        PageHit first = ResultPage.BING.read(fixture("bing-hits.html"), "https://www.bing.com/search?q=SAP+Aktie")
                .getFirst();

        assertEquals(URI.create("https://www.finanzen.net/aktien/sap-aktie"), first.url());
        assertEquals("SAP AKTIE | SAP | Aktienkurs | DE0007164600 | News | 716460", first.title());
        assertTrue(first.snippet().startsWith("Finanzen.net bietet Informationen zur SAP Aktie"), first.snippet());
    }

    @Test
    void braveKeepsAHitWithoutSnippet() throws Exception {
        PageHit second = ResultPage.BRAVE.read(fixture("brave-hits.html"), "https://search.brave.com/search?q=x")
                .get(1);

        assertEquals(URI.create("https://www.boerse.de/aktien/SAP-Aktie/DE0007164600"), second.url());
        assertEquals("", second.snippet());
    }

    @Test
    void theQueryIsFormEncoded() {
        assertEquals("https://search.brave.com/search?q=SAP+%26+B%C3%B6rse&source=web",
                ResultPage.BRAVE.address("SAP & Börse"));
        assertEquals("https://yandex.com/search/?text=DAX", ResultPage.YANDEX.address("DAX"));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = ResultPageTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("no fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
