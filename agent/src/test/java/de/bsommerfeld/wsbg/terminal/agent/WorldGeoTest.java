package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The geographic fact tables behind the world map (2026-07-15): projection
 * contract, chokepoint lookup, the longest-match country geocoder (the
 * conflict layer's honest country-level positioning) and the FAA airport
 * lookup.
 */
class WorldGeoTest {

    @Test
    void projectionMatchesTheJsContract() {
        // x=(lon+180)/360*1000, y=(90-lat)/180*500 — 0/0 lands mid-map.
        double[] center = WorldGeo.project(0, 0);
        assertEquals(500.0, center[0], 0.001);
        assertEquals(250.0, center[1], 0.001);
        double[] nw = WorldGeo.project(90, -180);
        assertEquals(0.0, nw[0], 0.001);
        assertEquals(0.0, nw[1], 0.001);
    }

    @Test
    void chokepointAndAirportLookups() {
        double[] hormuz = WorldGeo.chokepoint("Strait of Hormuz");
        assertNotNull(hormuz);
        // The user's case: Hormuz sits in the Persian Gulf (~26.4N, 56.9E).
        assertTrue(hormuz[0] > 24 && hormuz[0] < 29, "lat " + hormuz[0]);
        assertTrue(hormuz[1] > 54 && hormuz[1] < 60, "lon " + hormuz[1]);
        assertNull(WorldGeo.chokepoint("Nonexistent Strait"));
        double[] ewr = WorldGeo.airport("ewr");
        assertNotNull(ewr);
        assertTrue(ewr[0] > 40 && ewr[0] < 41);
        assertNull(WorldGeo.airport("XXX"));
    }

    @Test
    void countryGeocoderPrefersTheLongestMatch() {
        WorldGeo.Located sudan = WorldGeo.locate(
                "Clashes reported near the border of South Sudan.");
        assertNotNull(sudan);
        // "South Sudan" must beat the contained "Sudan".
        assertEquals("South Sudan", sudan.country());
        WorldGeo.Located iran = WorldGeo.locate(
                "Strikes on installations in Iran raise tanker insurance rates.");
        assertNotNull(iran);
        assertEquals("Iran", iran.country());
        assertNull(WorldGeo.locate("A sentence naming no state at all."));
        assertNull(WorldGeo.locate(null));
    }

    @Test
    void landPathIsPresentAndLean() {
        // The frozen figure embeds this path into every record — it must stay
        // small (the coarse variant, not the UI asset).
        assertTrue(WorldGeo.LAND_PATH_MINI.length() > 5_000);
        assertTrue(WorldGeo.LAND_PATH_MINI.length() < 40_000,
                "mini path grew past the freeze budget: "
                        + WorldGeo.LAND_PATH_MINI.length());
        assertTrue(WorldGeo.LAND_PATH_MINI.startsWith("M"));
    }
}
