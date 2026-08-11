package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the CelesTrak GP shape (live-probed 2026-08-03). The client hands the
 * page mean ELEMENTS, never positions - the propagation lives in
 * {@code web/js/map/orbits.js}, so the dots keep moving between pushes.
 */
class SatelliteClientTest {

    /** No network in a unit test: any fetch attempt is a test failure. */
    private static final WebFetcher NONE = new WebFetcher() {
        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            throw new AssertionError("a unit test must not fetch: " + url);
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            throw new AssertionError("a unit test must not fetch: " + url);
        }

        @Override
        public WebResponse post(String url, Map<String, String> headers, String body,
                String contentType, Duration timeout, FetchUtil... modes) {
            throw new AssertionError("a unit test must not fetch: " + url);
        }

        @Override
        public boolean hostCoolingDown(String url) {
            return false;
        }
    };

    @Test
    void elementsRideThroughVerbatim() {
        String body = """
                [{"OBJECT_NAME":"ISS (ZARYA)","OBJECT_ID":"1998-067A",
                  "EPOCH":"2026-08-02T12:09:08.796384","MEAN_MOTION":15.49313226,
                  "ECCENTRICITY":0.00071721,"INCLINATION":51.6315,
                  "RA_OF_ASC_NODE":70.8679,"ARG_OF_PERICENTER":4.7554,
                  "MEAN_ANOMALY":355.3502,"NORAD_CAT_ID":25544}]
                """;
        List<SatelliteClient.OrbitalElements> out = new SatelliteClient(NONE).parse(body);
        assertEquals(1, out.size());
        SatelliteClient.OrbitalElements el = out.get(0);
        assertEquals("ISS (ZARYA)", el.name());
        assertEquals(25544, el.noradId());
        assertEquals("2026-08-02T12:09:08.796384", el.epochIso());
        assertEquals(15.49313226, el.meanMotion(), 1e-9);
        assertEquals(0.00071721, el.eccentricity(), 1e-12);
        assertEquals(51.6315, el.inclination(), 1e-9);
        assertEquals(70.8679, el.raan(), 1e-9);
        assertEquals(4.7554, el.argOfPericenter(), 1e-9);
        assertEquals(355.3502, el.meanAnomaly(), 1e-9);
    }

    @Test
    void anObjectWithoutAMeanMotionCannotBePropagated() {
        String body = """
                [{"OBJECT_NAME":"BROKEN","NORAD_CAT_ID":1,"EPOCH":"2026-08-02T12:00:00"},
                 {"OBJECT_NAME":"FINE","NORAD_CAT_ID":2,"EPOCH":"2026-08-02T12:00:00",
                  "MEAN_MOTION":15.5}]
                """;
        List<SatelliteClient.OrbitalElements> out = new SatelliteClient(NONE).parse(body);
        assertEquals(1, out.size());
        assertEquals("FINE", out.get(0).name());
    }

    @Test
    void garbageIsEmpty() {
        assertTrue(new SatelliteClient(NONE).parse("<html>wall</html>").isEmpty());
        assertTrue(new SatelliteClient(NONE).parse("{\"not\":\"an array\"}").isEmpty());
    }
}
