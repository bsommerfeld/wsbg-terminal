package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The two walled venues' readers, against CAPTURED REAL PAGES. The strings
 * below are verbatim from the rendered documents of 2026-08-11 - a fixture
 * only in the sense that the page was fetched once; nothing here was invented,
 * which is the difference between a test and a wish.
 */
class WalledExchangeClientTest {

    /** Verbatim from the rendered SET overview, 2026-08-11. */
    private static final String SET_PAGE =
            "<div>SET50 SET50FF SET100 sSET SETCLMV mai Closed EN TH EN "
                    + "<span>Last 1,624.36 +12.36 (+0.77%)</span> "
                    + "<span>Last 1,624.36 +12.36 (+0.77%)</span> 11 Aug 2026 03:20:03 "
                    + "Value (M.Baht) 61,741.00</div>";

    /** Verbatim from the rendered Saudi Exchange front page, 2026-08-11. */
    private static final String TASI_PAGE =
            "<div>Sukuk &amp; Bonds Market Closed Prices are delayed "
                    + "<b>TASI 10,845.58 28.57 (0.26%)</b> Open - 10,832.78 "
                    + "Prev. Close - 10,817.01 135 Gainers 127 Losers "
                    + "Main Market Watch MT30 1,459.24 4.60 (0.32%)</div>";

    @Test
    @DisplayName("Bangkok's headline index is read with its change and percent")
    void bangkok() {
        var platz = WalledExchangeClient.PLAETZE.get(0);
        WalledExchangeClient.Indexstand s = WalledExchangeClient.parse(platz, SET_PAGE);
        assertNotNull(s, "the SET page did not yield its index");
        assertEquals("SET", s.index());
        assertEquals(1624.36, s.stand(), 1e-9);
        assertEquals(12.36, s.veraenderung(), 1e-9);
        assertEquals(0.77, s.prozent(), 1e-9);
    }

    @Test
    @DisplayName("Riyadh's headline index is read - and not the MT30 line below it")
    void riad() {
        var platz = WalledExchangeClient.PLAETZE.get(1);
        WalledExchangeClient.Indexstand s = WalledExchangeClient.parse(platz, TASI_PAGE);
        assertNotNull(s, "the Saudi page did not yield its index");
        assertEquals("TASI", s.index());
        assertEquals(10845.58, s.stand(), 1e-9);
        assertEquals(28.57, s.veraenderung(), 1e-9);
        assertEquals(0.26, s.prozent(), 1e-9);
    }

    @Test
    @DisplayName("a challenge page yields nothing rather than a number")
    void wandBleibtStumm() {
        var platz = WalledExchangeClient.PLAETZE.get(0);
        assertNull(WalledExchangeClient.parse(platz,
                "<html><head><title>Attention Required! | Cloudflare</title></head>"
                        + "<body>Please enable cookies.</body></html>"));
        // And the other venue's page must not answer for this one.
        assertNull(WalledExchangeClient.parse(platz, TASI_PAGE));
    }
}
