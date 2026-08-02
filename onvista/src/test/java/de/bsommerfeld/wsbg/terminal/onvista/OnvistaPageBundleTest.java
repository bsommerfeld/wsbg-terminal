package de.bsommerfeld.wsbg.terminal.onvista;

import org.junit.jupiter.api.Test;

import static de.bsommerfeld.wsbg.terminal.onvista.OnvistaFixtures.ScriptedFetcher;
import static de.bsommerfeld.wsbg.terminal.onvista.OnvistaFixtures.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-fetch road: the public instrument page carries the whole desk in its
 * {@code __NEXT_DATA__} island. Fixture is a trimmed live capture of
 * {@code www.onvista.de/aktien/…-Aktie-DE0007164600} (2026-08-02).
 */
class OnvistaPageBundleTest {

    @Test
    void oneFetchYieldsEverySection() {
        var b = OnvistaPageBundle.parse(raw("page-sap.html")).orElseThrow();

        assertEquals("DE0007164600", b.isin());
        assertEquals("STOCK/82849", b.entity().pathPair());

        // company + figures come back as the very records the dedicated
        // clients produce, so a caller can switch roads without changing code.
        assertNotNull(b.company());
        assertEquals("SAP SE", b.company().officialName());
        assertFalse(b.company().documents().isEmpty());
        assertEquals("2025", b.figures().orElseThrow().lastActual().orElseThrow().label());
        assertEquals(2, b.figures().orElseThrow().estimates().size());

        // The venue list rides along, RLT/DLY intact — no second request needed.
        assertEquals(3, b.quotes().size());
        assertTrue(b.quotes().stream().anyMatch(q -> q.realtime()));
        assertTrue(b.quotes().stream().anyMatch(q -> !q.realtime()));

        assertEquals(3, b.eodHistory().orElseThrow().bars().size());
        assertEquals(1, b.calendarEvents().size());
        assertNotNull(b.calendarEvents().get(0).categoryType());
        assertTrue(b.forum().orElseThrow().hitsToday() > 0);
        assertFalse(b.holdingFunds().isEmpty());
    }

    @Test
    void peersCarryEveryIdentifierNeededToAddressThem() {
        var b = OnvistaPageBundle.parse(raw("page-sap.html")).orElseThrow();
        assertEquals(3, b.peers().size());
        var msft = b.peers().get(0);
        assertEquals("Microsoft", msft.instrument().name());
        assertEquals("US5949181045", msft.instrument().isin());
        assertEquals("870747", msft.instrument().wkn());
        assertEquals("MSF", msft.instrument().symbol());
        // Straight back into the other clients without a second resolve.
        assertEquals("STOCK/87089", msft.instrument().pathPair());
        assertTrue(msft.marketCap() > 0);
    }

    @Test
    void newsBucketsAreFlattenedAndDeduplicated() {
        var b = OnvistaPageBundle.parse(raw("page-sap.html")).orElseThrow();
        assertFalse(b.news().isEmpty());
        var first = b.news().get(0);
        assertNotNull(first.articleId());
        assertNotNull(first.headline());
        assertNotNull(first.published());
        assertEquals(b.news().stream().map(n -> n.articleId()).distinct().count(),
                b.news().size(), "buckets overlap - ids must not repeat");
    }

    @Test
    void aPageWithoutTheIslandIsNoBundle() {
        assertTrue(OnvistaPageBundle.parse("<html><body>nothing here</body></html>").isEmpty());
        assertTrue(OnvistaPageBundle.parse("").isEmpty());
        assertTrue(OnvistaPageBundle.parse(null).isEmpty());
        // Present but unparseable island: still no bundle, never an exception.
        assertTrue(OnvistaPageBundle.parse(
                "<script id=\"__NEXT_DATA__\" type=\"application/json\">{oops</script>").isEmpty());
    }

    @Test
    void fetchFailureIsAnEmptyBundleNotAnException() {
        var bundle = new OnvistaPageBundle(new ScriptedFetcher((String) null));
        assertTrue(bundle.byIsin("DE0007164600").isEmpty());
        assertTrue(bundle.byIsin(null).isEmpty());
        assertTrue(bundle.byIsin("  ").isEmpty());
    }

    @Test
    void bundleReadsTheLivePageInOneRequest() {
        var fetcher = new ScriptedFetcher(raw("page-sap.html"));
        var b = new OnvistaPageBundle(fetcher).byIsin("DE0007164600").orElseThrow();
        assertEquals(1, fetcher.urls.size(), "the whole desk must cost ONE fetch");
        assertTrue(fetcher.urls.get(0).endsWith("-Aktie-DE0007164600"));
        assertEquals("82849", b.entity().entityValue());
    }
}
