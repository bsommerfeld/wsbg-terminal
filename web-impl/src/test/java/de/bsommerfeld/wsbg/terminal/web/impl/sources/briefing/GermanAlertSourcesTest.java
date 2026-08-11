package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the German warning layer and the ADS-B sky against live-probed shapes
 * (2026-08-03). The DWD layer exists to travel LIGHT - it must read the
 * feature's bounding box and never the polygon - and the flight layer must
 * stay honest about an aircraft that is still standing on the apron.
 */
class GermanAlertSourcesTest {

    /** The parse methods never fetch — a fetcher that refuses proves it. */
    private static final WebFetcher NO_NET = new WebFetcher() {
        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            throw new UnsupportedOperationException("no network in a parse test");
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            throw new UnsupportedOperationException("no network in a parse test");
        }

        @Override
        public WebResponse post(String url, Map<String, String> headers, String body,
                String contentType, Duration timeout, FetchUtil... modes) {
            throw new UnsupportedOperationException("no network in a parse test");
        }

        @Override
        public boolean hostCoolingDown(String url) {
            return false;
        }
    };

    private static GermanWeatherAlertClient dwd() {
        return new GermanWeatherAlertClient(NO_NET);
    }

    private static FlightClient flights() {
        return new FlightClient(NO_NET);
    }

    // ---- DWD -----------------------------------------------------------------

    @Test
    void dwdPlacesAWarningAtTheCentreOfItsOwnBoundingBox() {
        String body = """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","bbox":[8.4717,50.0158,8.8002,50.2257],
                   "geometry":{"type":"Polygon","coordinates":[[[8.4,50.0],[8.8,50.0],[8.8,50.2],[8.4,50.0]]]},
                   "properties":{"EVENT":"STARKE HITZE","SEVERITY":"Minor",
                     "HEADLINE":"Amtliche WARNUNG vor HITZE","AREADESC":"Stadt Frankfurt am Main",
                     "ONSET":"2026-08-03T09:00:00Z","EXPIRES":"2026-08-03T17:00:00Z"}}]}
                """;
        List<GermanWeatherAlertClient.Alert> out = dwd().parse(body);
        assertEquals(1, out.size());
        GermanWeatherAlertClient.Alert a = out.get(0);
        assertEquals("STARKE HITZE", a.event());
        assertEquals("Minor", a.severity());
        assertEquals("Stadt Frankfurt am Main", a.area());
        assertEquals(50.12075, a.lat(), 1e-6, "latitude is the mean of the box's lat bounds");
        assertEquals(8.63595, a.lon(), 1e-6, "longitude is the mean of the box's lon bounds");
        assertEquals("2026-08-03T17:00:00Z", a.expiresIso());
    }

    @Test
    void dwdSkipsAFeatureWithoutABoxRatherThanUnpackItsPolygon() {
        String body = """
                {"features":[
                  {"geometry":{"type":"Polygon","coordinates":[[[8.4,50.0],[8.8,50.2]]]},
                   "properties":{"EVENT":"GEWITTER","SEVERITY":"Severe"}},
                  {"bbox":[13.0,52.3,13.8,52.7],
                   "properties":{"EVENT":"GEWITTER","SEVERITY":"Severe"}}]}
                """;
        List<GermanWeatherAlertClient.Alert> out = dwd().parse(body);
        assertEquals(1, out.size(), "no box, no marker - the polygon never travels");
        assertEquals(52.5, out.get(0).lat(), 1e-9);
    }

    @Test
    void dwdACalmGermanyIsAnEmptyListNotAFailure() {
        assertTrue(dwd()
                .parse("{\"type\":\"FeatureCollection\",\"features\":[]}").isEmpty());
        assertTrue(dwd().parse("<html>wall</html>").isEmpty());
    }

    // ---- ADS-B ---------------------------------------------------------------

    @Test
    void flightsReadPositionAltitudeAndHeading() {
        String body = """
                {"ac":[
                  {"hex":"407c6d","flight":"BAW199  ","r":"G-DRTJ","t":"B789",
                   "alt_baro":35000,"gs":518.5,"track":303.24,"lat":50.37886,"lon":7.391269}]}
                """;
        List<FlightClient.Aircraft> out = flights().parse(body, "SFO");
        assertEquals(1, out.size());
        FlightClient.Aircraft a = out.get(0);
        assertEquals("BAW199", a.callsign(), "the callsign arrives padded to eight characters");
        assertEquals("B789", a.type());
        assertEquals(35000, a.altitudeFt());
        assertEquals(518.5, a.groundSpeedKt(), 1e-9);
        assertEquals(303.24, a.trackDeg(), 1e-9);
        assertEquals("SFO", a.nearField());
    }

    @Test
    void anAircraftOnTheApronHasNoAltitudeNotAZero() {
        // The API answers the STRING "ground" here - a zero would claim a
        // measurement that was never taken.
        String body = """
                {"ac":[{"flight":"RYR22JQ ","t":"B738","alt_baro":"ground",
                        "gs":6.2,"lat":49.946915,"lon":7.26695}]}
                """;
        List<FlightClient.Aircraft> out = flights().parse(body, "FRA");
        assertEquals(1, out.size());
        assertNull(out.get(0).altitudeFt());
        assertEquals(6.2, out.get(0).groundSpeedKt(), 1e-9);
    }

    @Test
    void flightsWithoutAPositionAreNotDrawable() {
        String body = "{\"ac\":[{\"flight\":\"XX1\",\"alt_baro\":3000}]}";
        assertTrue(flights().parse(body, "FRA").isEmpty());
        assertTrue(flights().parse("<html>wall</html>", "FRA").isEmpty());
    }

    @Test
    void aRegistrationStandsInForAMissingCallsign() {
        String body = "{\"ac\":[{\"r\":\"D-ABYT \",\"lat\":50.0,\"lon\":8.5}]}";
        assertEquals("D-ABYT", flights().parse(body, null).get(0).callsign());
    }
}
