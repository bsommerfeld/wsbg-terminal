package de.bsommerfeld.wsbg.terminal.finanzennet;

import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The header set IS the access to this source, so its internal consistency is
 * covered like a parser: a Firefox User-Agent paired with {@code sec-ch-ua} is
 * a browser that cannot exist, and Akamai answers such a request with 403.
 */
class FinanzenNetHttpTest {

    @Test
    void clientHintsAreDerivedFromTheChosenUserAgent() {
        FinanzenNetHttp.Identity chrome = FinanzenNetHttp.identityFor(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");

        assertTrue(chrome.secChUa().contains("\"Google Chrome\";v=\"130\""));
        assertTrue(chrome.secChUa().contains("\"Chromium\";v=\"130\""));
        assertEquals("Windows", chrome.platform());
    }

    @Test
    void edgeIsAnnouncedAsEdgeNotAsChrome() {
        FinanzenNetHttp.Identity edge = FinanzenNetHttp.identityFor(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0");

        assertTrue(edge.secChUa().contains("\"Microsoft Edge\";v=\"131\""));
        assertEquals("macOS", edge.platform());
    }

    @Test
    void nonChromiumAgentsAreReplacedRatherThanShippedWithImpossibleHints() {
        String firefox = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) "
                + "Gecko/20100101 Firefox/133.0";
        String safari = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15";

        assertEquals(FinanzenNetHttp.PINNED_UA, FinanzenNetHttp.identityFor(firefox).userAgent());
        assertEquals(FinanzenNetHttp.PINNED_UA, FinanzenNetHttp.identityFor(safari).userAgent());
        assertFalse(FinanzenNetHttp.isChromium(firefox));
        assertFalse(FinanzenNetHttp.isChromium(safari));
    }

    @Test
    void everyRandomDrawIsASelfConsistentChromiumIdentity() {
        for (int i = 0; i < 50; i++) {
            FinanzenNetHttp.Identity id = FinanzenNetHttp.randomIdentity();
            assertTrue(FinanzenNetHttp.isChromium(id.userAgent()), id.userAgent());
            String major = id.userAgent().replaceAll(".*Chrome/(\\d+).*", "$1");
            assertTrue(id.secChUa().contains("\"Chromium\";v=\"" + major + "\""),
                    "brand version must match the UA: " + id);
            assertEquals(FinanzenNetHttp.platformOf(id.userAgent()), id.platform());
            assertTrue(BrowserUserAgent.pool().contains(id.userAgent())
                            || FinanzenNetHttp.PINNED_UA.equals(id.userAgent()),
                    "identities come from the shared pool");
        }
    }

    @Test
    void theCompleteSetIsSentAndTheXhrVariantSwitchesTheFetchMetadata() {
        FinanzenNetHttp.Identity id = FinanzenNetHttp.identityFor(FinanzenNetHttp.PINNED_UA);

        Map<String, String> page = FinanzenNetHttp.headers(id, false);
        Map<String, String> xhr = FinanzenNetHttp.headers(id, true);

        assertEquals("none", page.get("Sec-Fetch-Site"));
        assertEquals("document", page.get("Sec-Fetch-Dest"));
        assertEquals("same-origin", xhr.get("Sec-Fetch-Site"));
        assertEquals("empty", xhr.get("Sec-Fetch-Dest"));
        assertEquals("identity", page.get("Accept-Encoding"),
                "the JDK client cannot decompress - asking for gzip would yield garbage");
        assertTrue(page.get("Accept-Language").startsWith("de-DE"));
        assertEquals("\"macOS\"", page.get("sec-ch-ua-platform"));
    }
}
