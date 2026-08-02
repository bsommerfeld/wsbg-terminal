package de.bsommerfeld.wsbg.terminal.onvista;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static de.bsommerfeld.wsbg.terminal.onvista.OnvistaFixtures.ScriptedFetcher;
import static de.bsommerfeld.wsbg.terminal.onvista.OnvistaFixtures.json;
import static de.bsommerfeld.wsbg.terminal.onvista.OnvistaFixtures.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ID trap and nothing else: every other endpoint is built on the
 * {@code entityValue} this resolver hands out, so a wrong pick here would
 * silently show the wrong company everywhere. Fixtures are trimmed live
 * captures (2026-08-02).
 */
class OnvistaEntityResolverTest {

    @Test
    void resolvesIsinToTheEntityBothApiFamiliesUse() {
        var res = OnvistaEntityResolver.parseQuery(
                json("query-sap.json"), "DE0007164600", "STOCK");
        OnvistaEntity e = res.entity();
        assertNotNull(e);
        // The one id that serves /v1/instruments/STOCK/… AND /v1/stocks/… alike.
        assertEquals("82849", e.entityValue());
        assertEquals("STOCK", e.entityType());
        assertEquals("STOCK/82849", e.pathPair());
        assertEquals("716460", e.wkn());
        assertEquals("SAP", e.symbol());
        assertFalse(res.structuredMiss());
    }

    /**
     * The trap itself: a NAME search returns a crowd of look-alikes — the ADR
     * (87829), the CDR, Sappi (86223), even a bond and a fund. Only the exact
     * ISIN + entityType pair may win; picking the first row would address a
     * different company on every subsequent call.
     */
    @Test
    void nameSearchNeverYieldsALookalike() {
        var body = json("query-name-sap.json");
        assertEquals("82849",
                OnvistaEntityResolver.parseQuery(body, "DE0007164600", "STOCK").entity().entityValue());
        // The ADR is a genuinely different instrument with its own id.
        assertEquals("87829",
                OnvistaEntityResolver.parseQuery(body, "US8030542042", "STOCK").entity().entityValue());
        assertEquals("86223",
                OnvistaEntityResolver.parseQuery(body, "ZAE000006284", "STOCK").entity().entityValue());
        // An id that belongs to another instrument entirely must never surface
        // for this ISIN — 86627 is Apple's, verified live 2026-08-02.
        var sap = OnvistaEntityResolver.parseQuery(body, "DE0007164600", "STOCK").entity();
        assertFalse("86627".equals(sap.entityValue()));
    }

    @Test
    void entityTypeMustMatchToo() {
        var body = json("query-name-sap.json");
        // A fund and a bond ride in the same reply — asking for a STOCK is a
        // definite, cacheable miss; asking for a FUND hits.
        var asStock = OnvistaEntityResolver.parseQuery(body, "LU0154398746", "STOCK");
        assertNull(asStock.entity());
        assertTrue(asStock.structuredMiss());
        assertEquals("7736499",
                OnvistaEntityResolver.parseQuery(body, "LU0154398746", "FUND").entity().entityValue());
        // A bond ISIN never becomes a stock either.
        assertNull(OnvistaEntityResolver.parseQuery(body, "XS3017017990", "STOCK").entity());
        assertEquals("292902061",
                OnvistaEntityResolver.parseQuery(body, "XS3017017990", "BOND").entity().entityValue());
    }

    @Test
    void unknownIsinIsAStructuredMissButGarbageIsNot() {
        var miss = OnvistaEntityResolver.parseQuery(
                json("query-sap.json"), "US0000000000", "STOCK");
        assertNull(miss.entity());
        assertTrue(miss.structuredMiss());

        // A parseable body without the documented list is a changed/error
        // shape — never cacheable, it must heal on the next call.
        var garbled = OnvistaEntityResolver.parseQuery(
                OnvistaApi.JSON.createObjectNode().put("error", "oops"), "DE0007164600", "STOCK");
        assertNull(garbled.entity());
        assertFalse(garbled.structuredMiss());
        assertFalse(OnvistaEntityResolver.parseQuery(null, "DE0007164600", "STOCK").structuredMiss());
    }

    /** End-to-end: a transport failure must NOT poison the session cache. */
    @Test
    void transportFailureIsNotCached() {
        var fetcher = new ScriptedFetcher(null, raw("query-sap.json"));
        var resolver = new OnvistaEntityResolver(fetcher);
        assertTrue(resolver.resolveStock("DE0007164600").isEmpty());
        // Second call goes back to the network and succeeds.
        assertEquals("82849", resolver.resolveStock("DE0007164600").orElseThrow().entityValue());
        assertEquals(2, fetcher.urls.size());
        assertTrue(fetcher.urls.get(0).contains("instruments/query?searchValue=DE0007164600"));
    }

    /** A resolved entity is answered from the session cache, not refetched. */
    @Test
    void hitIsCachedForTheSession() {
        var fetcher = new ScriptedFetcher(raw("query-sap.json"));
        var resolver = new OnvistaEntityResolver(fetcher);
        assertEquals("82849", resolver.resolveStock("de0007164600").orElseThrow().entityValue());
        assertEquals("82849", resolver.resolveStock("DE0007164600").orElseThrow().entityValue());
        assertEquals(1, fetcher.urls.size());
    }

    /** The page fallback reads the same entity out of the __NEXT_DATA__ island. */
    @Test
    void pageFallbackResolvesTheSameEntity() {
        Optional<OnvistaEntity> e = OnvistaEntityResolver.parsePageEntity(raw("page-sap.html"));
        assertEquals("82849", e.orElseThrow().entityValue());
        assertEquals("DE0007164600", e.orElseThrow().isin());
        assertTrue(OnvistaEntityResolver.parsePageEntity("<html>no island</html>").isEmpty());
    }
}
