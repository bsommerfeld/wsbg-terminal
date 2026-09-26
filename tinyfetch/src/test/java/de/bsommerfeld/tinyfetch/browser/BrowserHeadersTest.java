package de.bsommerfeld.tinyfetch.browser;

import de.bsommerfeld.tinyfetch.api.Browser;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.curl.NativePlatform;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserHeadersTest {

    private static final String LANGUAGE = "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7";

    private final BrowserHeaders mac = new BrowserHeaders(Browser.CHROME, NativePlatform.MACOS, LANGUAGE);

    @Test
    void typedNavigationIsChromesNavigationBlockInOrder() {
        List<String> lines = mac.build(FetchRequest.page("https://www.example.com/a"), Map.of());
        assertEquals(List.of(
                "sec-ch-ua",
                "sec-ch-ua-mobile",
                "sec-ch-ua-platform",
                "upgrade-insecure-requests",
                "user-agent",
                "accept",
                "sec-fetch-site",
                "sec-fetch-mode",
                "sec-fetch-user",
                "sec-fetch-dest",
                "accept-encoding",
                "accept-language",
                "priority"), names(lines));
        assertTrue(lines.contains("sec-fetch-site: none"));
        assertTrue(lines.contains("sec-ch-ua-platform: \"macOS\""));
        assertTrue(lines.contains("accept-language: " + LANGUAGE));
    }

    @Test
    void embeddedChromiumNamesChromiumAloneAtVersion132() {
        BrowserHeaders cef = new BrowserHeaders(Browser.CHROMIUM_EMBEDDED, NativePlatform.MACOS, "en-US,en;q=0.9");
        List<String> lines = cef.build(FetchRequest.page("https://www.reddit.com/"), Map.of());
        // What the unlock engine sent to tls.peet.ws on 2026-09-26: Chrome's order, CEF's brands.
        assertEquals("accept-language", names(lines).get(names(lines).size() - 2));
        assertTrue(lines.contains("sec-ch-ua: \"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\""));
        assertTrue(lines.contains("accept-language: en-US,en;q=0.9"));
        assertTrue(cef.userAgent().contains("Chrome/132.0.0.0"));
    }

    @Test
    void userAgentNamesTheRealOs() {
        String windows = new BrowserHeaders(Browser.CHROME, NativePlatform.WINDOWS, LANGUAGE).userAgent();
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/150.0.0.0 Safari/537.36", windows);
        assertTrue(mac.userAgent().contains("Macintosh; Intel Mac OS X 10_15_7"));
    }

    @Test
    void scriptFetchIsCorsModeFromItsOwnSite() {
        List<String> lines = mac.build(FetchRequest.data("https://www.example.com/api/x"), Map.of());
        assertEquals("sec-ch-ua-platform", names(lines).getFirst());
        assertTrue(lines.contains("sec-fetch-mode: cors"));
        assertTrue(lines.contains("sec-fetch-dest: empty"));
        assertTrue(lines.contains("sec-fetch-site: same-origin"));
        assertTrue(lines.contains("referer: https://www.example.com/"));
        assertTrue(lines.contains("priority: u=1, i"));
        assertFalse(names(lines).contains("origin"), "same-origin GET sends no origin");
    }

    @Test
    void crossOriginRefererShrinksToItsOriginAndAddsOrigin() {
        List<String> lines = mac.build(FetchRequest.data("https://api.example.com/v1")
                .referer("https://www.example.com/deep/page?x=1"), Map.of());
        assertTrue(lines.contains("referer: https://www.example.com/"));
        assertTrue(lines.contains("origin: https://www.example.com"));
        assertTrue(lines.contains("sec-fetch-site: same-site"));
    }

    @Test
    void sameOriginRefererStaysWhole() {
        List<String> lines = mac.build(FetchRequest.page("https://www.example.com/b")
                .referer("https://www.example.com/a?q=1"), Map.of());
        assertTrue(lines.contains("referer: https://www.example.com/a?q=1"));
        assertTrue(lines.contains("sec-fetch-site: same-origin"));
    }

    @Test
    void foreignSiteIsCrossSite() {
        List<String> lines = mac.build(FetchRequest.page("https://www.example.com/")
                .referer("https://news.other.org/"), Map.of());
        assertTrue(lines.contains("sec-fetch-site: cross-site"));
    }

    @Test
    void callerHeaderReplacesInPlaceOrJoinsAfterAccept() {
        List<String> lines = mac.build(FetchRequest.data("https://www.example.com/api")
                .header("accept", "application/json")
                .header("authorization", "bearer t"), Map.of());
        List<String> names = names(lines);
        assertEquals(1, names.stream().filter("accept"::equals).count());
        assertEquals(names.indexOf("accept") + 1, names.indexOf("authorization"));
        assertTrue(lines.contains("accept: application/json"));
    }

    @Test
    void validatorsSitBeforePriority() {
        Map<String, String> conditional = new LinkedHashMap<>();
        conditional.put("if-none-match", "\"v1\"");
        List<String> names = names(mac.build(FetchRequest.page("https://www.example.com/"), conditional));
        assertEquals(names.indexOf("priority") - 1, names.indexOf("if-none-match"));
    }

    @Test
    void postCarriesContentTypeAndOrigin() {
        List<String> lines = mac.build(FetchRequest.data("https://www.example.com/api")
                .post("application/json", "{}"), Map.of());
        assertTrue(lines.contains("content-type: application/json"));
        assertTrue(lines.contains("origin: https://www.example.com"));
    }

    @Test
    void registrableDomain() {
        assertEquals("reddit.com", BrowserHeaders.site("old.www.reddit.com"));
        assertEquals("example.co.uk", BrowserHeaders.site("www.example.co.uk"));
        assertEquals("ls-tc.de", BrowserHeaders.site("www.ls-tc.de"));
        assertEquals("localhost", BrowserHeaders.site("localhost"));
    }

    private static List<String> names(List<String> lines) {
        return lines.stream().map(line -> line.substring(0, line.indexOf(':'))).toList();
    }
}
