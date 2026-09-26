package de.bsommerfeld.tinyfetch.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Against real hosts, one request each - opt-in:
 * {@code mvn test -pl tinyfetch -Dtest.excludedGroups=visual}.
 *
 * <ul>
 *   <li>tls.peet.ws echoes the fingerprint it saw; it must be Chrome's.</li>
 *   <li>Lang &amp; Schwarz allows crawlers of any kind ({@code robots.txt:
 *       Allow: /}) - the friendly host the whole path is proven on.</li>
 * </ul>
 */
@Tag("live")
class TinyFetchLiveTest {

    /**
     * What libcurl-impersonate's own {@code curl_chrome150} produced against
     * tls.peet.ws on 2026-09-25 - the reference our header set must not move
     * away from. JA4 covers the TLS ClientHello, the Akamai hash the HTTP/2
     * SETTINGS, window update and pseudo-header order.
     */
    private static final String CHROME_150_JA4 = "t13d1516h2_8daaf6152771_806a8c22fdea";
    private static final String CHROME_150_AKAMAI = "52d84b11737d980aef856699f885ca86";

    /** What CEF 132 (the unlock engine) sent to tls.peet.ws on 2026-09-26. */
    private static final String JCEF_132_JA4 = "t13d1516h2_8daaf6152771_02713d6af862";

    @BeforeAll
    static void requireLibrary() {
        assumeTrue(TinyFetch.libraryAvailable(), "libcurl-impersonate not installed - run .script/natives.sh");
    }

    @Test
    void fingerprintIsChromes() throws Exception {
        try (TinyFetch fetch = TinyFetch.builder().build()) {
            FetchResponse response = fetch.fetch(FetchRequest.page("https://tls.peet.ws/api/all"));
            assertEquals(200, response.status());
            assertEquals("HTTP/2", response.httpVersion());
            String json = response.text();
            assertEquals(CHROME_150_JA4, field(json, "ja4"));
            assertEquals(CHROME_150_AKAMAI, field(json, "akamai_fingerprint_hash"));
            assertTrue(json.contains(fetch.userAgent()), "user agent echoed back");
        }
    }

    @Test
    void embeddedChromiumFingerprintIsCefs() throws Exception {
        try (TinyFetch fetch = TinyFetch.builder().browser(Browser.CHROMIUM_EMBEDDED).build()) {
            FetchResponse response = fetch.fetch(FetchRequest.page("https://tls.peet.ws/api/all"));
            String json = response.text();
            assertEquals(JCEF_132_JA4, field(json, "ja4"));
            assertEquals(CHROME_150_AKAMAI, field(json, "akamai_fingerprint_hash"), "same HTTP/2 profile");
        }
    }

    @Test
    void langUndSchwarzAnswersLikeItsOwnPageScript() throws Exception {
        try (TinyFetch fetch = TinyFetch.builder().build()) {
            FetchResponse response = fetch.fetch(FetchRequest
                    .data("https://www.ls-tc.de/_rpc/json/.lstc/instrument/search/main?localeId=2&q=SAP")
                    .referer("https://www.ls-tc.de/de/"));
            assertTrue(response.ok(), response.toString());
            assertEquals(Wall.NONE, response.wall());
            assertTrue(response.text().contains("DE0007164600"), "SAP's ISIN in the result");
        }
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
