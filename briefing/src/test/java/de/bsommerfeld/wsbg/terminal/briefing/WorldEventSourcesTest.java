package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the world-event sources against live-probed shapes (2026-08-02): NASA's
 * EONET event tracks, the Launch Library manifest, Open-Meteo's air-quality
 * raster, IODA's country alerts and the ISS fix. Each layer answers an honest
 * empty on garbage - a failing leg costs its layer, never the tick.
 */
class WorldEventSourcesTest {

    // ---- NASA EONET ---------------------------------------------------------

    @Test
    void eonetTakesTheNEWESTPointOfAnEventTrack() {
        // An iceberg reports a new position per day; the last one is where it is.
        String body = """
                {"events":[{"id":"EONET_1","title":"Iceberg B-09F",
                  "link":"https://eonet/1",
                  "categories":[{"id":"seaLakeIce","title":"Sea and Lake Ice"}],
                  "geometry":[
                    {"date":"2026-07-01T00:00:00Z","type":"Point","coordinates":[150.0,-66.0]},
                    {"date":"2026-08-01T00:00:00Z","type":"Point","coordinates":[151.5,-66.4]}]}]}
                """;
        List<EonetClient.NaturalEvent> out = new EonetClient().parse(body);
        assertEquals(1, out.size());
        EonetClient.NaturalEvent berg = out.get(0);
        assertEquals("seaLakeIce", berg.category());
        assertEquals("Iceberg B-09F", berg.title());
        assertEquals(-66.4, berg.lat(), 1e-9, "latitude is the SECOND coordinate");
        assertEquals(151.5, berg.lon(), 1e-9);
        assertEquals("2026-08-01T00:00:00Z", berg.atIso());
    }

    @Test
    void eonetLeavesWildfiresToTheSatelliteFeed() {
        String body = """
                {"events":[
                  {"id":"E1","title":"Wildfire Camden","categories":[{"id":"wildfires"}],
                   "geometry":[{"date":"d","type":"Point","coordinates":[-81.6,30.9]}]},
                  {"id":"E2","title":"Kilauea","categories":[{"id":"volcanoes"}],
                   "geometry":[{"date":"d","type":"Point","coordinates":[-155.2,19.4]}]}]}
                """;
        List<EonetClient.NaturalEvent> out = new EonetClient().parse(body);
        assertEquals(1, out.size(), "the raw satellite detections own fire, not this register");
        assertEquals("volcanoes", out.get(0).category());
    }

    @Test
    void eonetSkipsEventsWithoutADrawablePoint() {
        String body = """
                {"events":[{"id":"E1","title":"Area storm","categories":[{"id":"severeStorms"}],
                  "geometry":[{"date":"d","type":"Polygon","coordinates":[[[1,2],[3,4],[5,6]]]}]}]}
                """;
        assertTrue(new EonetClient().parse(body).isEmpty(),
                "an area is never reduced to a fake centre");
        assertTrue(new EonetClient().parse("<html>wall</html>").isEmpty());
    }

    // ---- Launch Library 2 ---------------------------------------------------

    @Test
    void launchesReadPadCoordinatesThatArriveAsStrings() {
        String body = """
                {"results":[{"name":"Falcon 9 | Starlink 17-53","net":"2026-08-04T14:00:00Z",
                  "status":{"abbrev":"Go"},
                  "launch_service_provider":{"name":"SpaceX"},
                  "pad":{"latitude":"34.632","longitude":"-120.611",
                         "location":{"name":"Vandenberg SFB, CA, USA"}}}]}
                """;
        List<LaunchPadClient.Launch> out = new LaunchPadClient()
                .parse(body, Instant.parse("2026-08-02T12:00:00Z"));
        assertEquals(1, out.size());
        LaunchPadClient.Launch l = out.get(0);
        assertEquals("SpaceX", l.provider());
        assertEquals("Go", l.status());
        assertEquals(34.632, l.lat(), 1e-9);
        assertEquals(-120.611, l.lon(), 1e-9);
        assertEquals("Vandenberg SFB, CA, USA", l.pad());
    }

    @Test
    void launchesBeyondTheHorizonAreCalendarEntriesNotWorldEvents() {
        String body = """
                {"results":[
                  {"name":"Soon","net":"2026-08-04T14:00:00Z","status":{"abbrev":"Go"},
                   "pad":{"latitude":"34.6","longitude":"-120.6"}},
                  {"name":"Far away","net":"2026-11-30T14:00:00Z","status":{"abbrev":"TBD"},
                   "pad":{"latitude":"28.5","longitude":"-80.5"}},
                  {"name":"No pad","net":"2026-08-05T14:00:00Z","status":{"abbrev":"Go"},
                   "pad":{"location":{"name":"unknown"}}}]}
                """;
        List<LaunchPadClient.Launch> out = new LaunchPadClient()
                .parse(body, Instant.parse("2026-08-02T12:00:00Z"));
        assertEquals(1, out.size());
        assertEquals("Soon", out.get(0).name());
    }

    // ---- Open-Meteo air quality --------------------------------------------

    @Test
    void airQualityKeepsOnlyCellsAboveTheWhoGuideline() throws Exception {
        String body = """
                [
                  {"current":{"pm2_5":4.5,"us_aqi":43}},
                  {"current":{"pm2_5":88.2,"us_aqi":168}},
                  {"current":{"pm2_5":21.0}}
                ]
                """;
        List<double[]> batch = List.of(new double[]{50, 10}, new double[]{30, 110},
                new double[]{20, 77});
        List<AirQualityGridClient.AirCell> out = new AirQualityGridClient().parse(body, batch);
        assertEquals(2, out.size(), "clean air is the absence of a marker");
        assertEquals(30, out.get(0).lat(), 1e-9);
        assertEquals(110, out.get(0).lon(), 1e-9);
        assertEquals(88.2, out.get(0).pm25(), 1e-9);
        assertEquals(168, out.get(0).usAqi());
        assertNull(out.get(1).usAqi(), "a missing index stays null, never a zero");
    }

    // ---- IODA ---------------------------------------------------------------

    @Test
    void outagesKeepCriticalCountriesAndForgetTheRecoveries() {
        String body = """
                {"error":null,"data":[
                  {"datasource":"bgp","level":"critical","time":100,"value":1077,"historyValue":1551,
                   "entity":{"code":"SD","name":"Sudan","type":"country"}},
                  {"datasource":"bgp","level":"critical","time":100,"value":234,"historyValue":338,
                   "entity":{"code":"TV","name":"Tuvalu","type":"country"}},
                  {"datasource":"bgp","level":"normal","time":200,"value":1500,"historyValue":1551,
                   "entity":{"code":"SD","name":"Sudan","type":"country"}}]}
                """;
        List<InternetOutageClient.Outage> out = new InternetOutageClient().parse(body);
        assertEquals(1, out.size(), "Sudan came back - that is the END of the story");
        assertEquals("TV", out.get(0).countryCode());
        assertEquals("Tuvalu", out.get(0).country());
        assertEquals(234, out.get(0).value(), 1e-9);
        assertEquals(338, out.get(0).normalValue(), 1e-9);
    }

    @Test
    void outagesIgnoreACriticalFlagOnACountryThatIsAlreadyBack() {
        // Live-measured 2026-08-02: IODA keeps the critical level on Ethiopia
        // while reachability is at 94 % of normal. That is not an outage.
        String body = """
                {"error":null,"data":[
                  {"datasource":"bgp","level":"critical","time":100,"value":4392,"historyValue":4648,
                   "entity":{"code":"ET","name":"Ethiopia","type":"country"}},
                  {"datasource":"gtr","level":"critical","time":100,"value":74,"historyValue":106,
                   "entity":{"code":"MP","name":"Northern Mariana Islands","type":"country"}}]}
                """;
        List<InternetOutageClient.Outage> out = new InternetOutageClient().parse(body);
        assertEquals(1, out.size(), "only a real collapse below 80 % counts");
        assertEquals("MP", out.get(0).countryCode());
    }

    @Test
    void outagesAnErrorAnswerIsEmptyNotAnException() {
        assertTrue(new InternetOutageClient()
                .parse("{\"error\":\"'from' timestamp must be set\",\"data\":null}").isEmpty());
        assertTrue(new InternetOutageClient().parse("<html>wall</html>").isEmpty());
    }

    @Test
    void outagesAnAllConnectedWorldIsAnEmptyList() {
        assertTrue(new InternetOutageClient().parse("{\"error\":null,\"data\":[]}").isEmpty());
    }

    // ---- ISS ----------------------------------------------------------------

    @Test
    void stationReadsPositionAltitudeSpeedAndSunlight() {
        String body = """
                {"name":"iss","id":25544,"latitude":-15.75,"longitude":144.34,
                 "altitude":418.45,"velocity":27586.46,"visibility":"daylight",
                 "footprint":4499.57,"timestamp":1785705675,"units":"kilometers"}
                """;
        Optional<OrbitClient.Station> s = new OrbitClient().parse(body);
        assertTrue(s.isPresent());
        assertEquals(-15.75, s.get().lat(), 1e-9);
        assertEquals(144.34, s.get().lon(), 1e-9);
        assertEquals(418.45, s.get().altitudeKm(), 1e-9);
        assertEquals(27586.46, s.get().speedKmh(), 1e-9);
        assertEquals(1785705675L, s.get().atEpochSeconds());
        assertTrue(s.get().daylight());
    }

    @Test
    void stationInEarthsShadowIsNotSunlit() {
        Optional<OrbitClient.Station> s = new OrbitClient()
                .parse("{\"latitude\":1,\"longitude\":2,\"visibility\":\"eclipsed\"}");
        assertTrue(s.isPresent());
        assertFalse(s.get().daylight());
    }

    @Test
    void stationWithoutAFixIsEmpty() {
        assertTrue(new OrbitClient().parse("{\"name\":\"iss\"}").isEmpty());
        assertTrue(new OrbitClient().parse("<html>wall</html>").isEmpty());
    }
}
