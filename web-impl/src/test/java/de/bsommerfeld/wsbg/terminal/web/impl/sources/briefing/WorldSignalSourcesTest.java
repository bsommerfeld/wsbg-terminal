package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live-probed shapes of the world-context sources (2026-07-17): NOAA's
 * OVATION aurora grid (0..359 east, thinned), the CPC ONI text table, GDACS'
 * georss RSS and Open-Meteo's bulk raster reply. Each parser must answer an
 * honest empty on garbage — a failing leg costs its layer, never the tick.
 * Ported from the module world, including the FIRMS and statuspage pins.
 */
class WorldSignalSourcesTest {

    /** Parse-only tests never fetch — the seam just satisfies the constructors. */
    private static WebFetcher noFetch() {
        return new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return false;
            }
        };
    }

    // ---- NOAA OVATION aurora oval ------------------------------------------

    @Test
    void auroraKeepsGlowingCellsOnTheStrideAndSpeaksMinus180To180() {
        // (0,0) below the probability floor, (3,2) keeps, (6,3) off the lat
        // stride, (183,2) is an eastern longitude the map must see as -177.
        String body = """
                {"Forecast Time":"2026-07-17T20:10Z","coordinates":[
                  [0,0,5],[3,2,40],[6,3,90],[183,2,25],[4,2,80]]}
                """;
        Optional<AuroraOvalClient.AuroraOval> oval = new AuroraOvalClient(noFetch()).parse(body);
        assertTrue(oval.isPresent());
        assertEquals("2026-07-17T20:10Z", oval.get().forecastTime());
        List<double[]> pts = oval.get().points();
        assertEquals(2, pts.size(), "floor, lat stride and lon stride each drop a cell");
        assertArrayEqualsish(new double[]{3, 2, 40}, pts.get(0));
        assertArrayEqualsish(new double[]{-177, 2, 25}, pts.get(1));
    }

    @Test
    void auroraQuietSkyIsAValidAnswerNotAFailure() {
        Optional<AuroraOvalClient.AuroraOval> oval = new AuroraOvalClient(noFetch())
                .parse("{\"Forecast Time\":\"t\",\"coordinates\":[[0,0,0]]}");
        assertTrue(oval.isPresent(), "an all-quiet sky caches like any other picture");
        assertTrue(oval.get().points().isEmpty());
    }

    @Test
    void auroraGarbageIsEmpty() {
        assertTrue(new AuroraOvalClient(noFetch()).parse("<html>wall</html>").isEmpty());
        assertTrue(new AuroraOvalClient(noFetch()).parse("{\"coordinates\":\"nope\"}").isEmpty());
    }

    // ---- NOAA CPC ONI (El Niño) --------------------------------------------

    @Test
    void oniTakesTheNewestSeasonAndClassifiesThePhase() {
        String body = """
                SEAS YR TOTAL ANOM
                DJF 2025 26.50 -0.61
                JFM 2026 26.90 0.12
                MJJ 2026 27.40 0.94
                """;
        Optional<EnsoClient.Oni> oni = new EnsoClient(noFetch()).parse(body);
        assertTrue(oni.isPresent());
        assertEquals("MJJ 2026", oni.get().season());
        assertEquals(0.94, oni.get().anomaly(), 1e-9);
        assertEquals("EL_NINO", oni.get().phase());
    }

    @Test
    void oniPhaseFollowsNoaasHalfDegreeConvention() {
        EnsoClient client = new EnsoClient(noFetch());
        assertEquals("EL_NINO", client.parse("MAM 2026 27.0 0.50").orElseThrow().phase());
        assertEquals("NEUTRAL", client.parse("MAM 2026 27.0 0.49").orElseThrow().phase());
        assertEquals("NEUTRAL", client.parse("MAM 2026 27.0 -0.49").orElseThrow().phase());
        assertEquals("LA_NINA", client.parse("MAM 2026 27.0 -0.50").orElseThrow().phase());
    }

    @Test
    void oniHeaderOnlyOrGarbageIsEmpty() {
        assertTrue(new EnsoClient(noFetch()).parse("SEAS YR TOTAL ANOM").isEmpty());
        assertTrue(new EnsoClient(noFetch()).parse(null).isEmpty());
        assertTrue(new EnsoClient(noFetch()).parse("<html>wall</html>").isEmpty());
    }

    // ---- GDACS disaster alerts ---------------------------------------------

    @Test
    void gdacsReadsKindLevelAndTheGeorssPointAsLatThenLon() {
        String xml = """
                <rss><channel>
                <item>
                  <title><![CDATA[Red alert Tropical Cyclone ANA]]></title>
                  <gdacs:eventtype>TC</gdacs:eventtype>
                  <gdacs:alertlevel>Red</gdacs:alertlevel>
                  <georss:point>-18.2 45.6</georss:point>
                </item>
                <item>
                  <title>Flood in Pakistan</title>
                  <gdacs:eventtype>FL</gdacs:eventtype>
                  <gdacs:alertlevel>Orange</gdacs:alertlevel>
                  <georss:point>27.8 68.4</georss:point>
                </item>
                <item><title>No position</title><gdacs:eventtype>EQ</gdacs:eventtype></item>
                </channel></rss>
                """;
        List<GdacsClient.GdacsEvent> events = new GdacsClient(noFetch()).parse(xml);
        assertEquals(2, events.size(), "an item without a point is not drawable");
        GdacsClient.GdacsEvent tc = events.get(0);
        assertEquals("TC", tc.kind());
        assertEquals("RED", tc.level());
        assertEquals("Red alert Tropical Cyclone ANA", tc.title(), "the CDATA wrapper never reaches the tooltip");
        assertEquals(-18.2, tc.lat(), 1e-9);
        assertEquals(45.6, tc.lon(), 1e-9);
        assertEquals("ORANGE", events.get(1).level());
    }

    @Test
    void gdacsTitlesArriveDecodedNotSpelled() {
        String xml = """
                <rss><channel><item>
                  <title>Green earthquake in Canada, 10 thousand in MMI&gt;=III &amp; rising</title>
                  <gdacs:eventtype>EQ</gdacs:eventtype>
                  <gdacs:alertlevel>Green</gdacs:alertlevel>
                  <georss:point>49.6 -129.4</georss:point>
                </item></channel></rss>
                """;
        assertEquals("Green earthquake in Canada, 10 thousand in MMI>=III & rising",
                new GdacsClient(noFetch()).parse(xml).get(0).title());
    }

    @Test
    void gdacsGarbageIsEmpty() {
        assertTrue(new GdacsClient(noFetch()).parse(null).isEmpty());
        assertTrue(new GdacsClient(noFetch()).parse("<html>wall</html>").isEmpty());
    }

    // ---- NASA FIRMS wildfires ----------------------------------------------

    @Test
    void firmsBinsDetectionsIntoOneDegreeCellsAndKeepsTheStrongestPower() {
        // Three detections inside the same 1° cell, two in a neighbour cell
        // (below the noise floor), one malformed row.
        String csv = """
                country_id,latitude,longitude,bright_ti4,scan,track,acq_date,frp,daynight
                CAN,55.20,-114.30,330.1,0.4,0.4,2026-07-17,12.5,D
                CAN,55.60,-114.80,340.2,0.4,0.4,2026-07-17,48.0,D
                CAN,55.90,-114.10,335.0,0.4,0.4,2026-07-17,7.25,N
                BRA,-9.10,-60.20,320.0,0.4,0.4,2026-07-17,5.0,D
                BRA,-9.40,-60.60,321.0,0.4,0.4,2026-07-17,6.0,D
                BRA,nope,-60.60,321.0,0.4,0.4,2026-07-17,6.0,D
                """;
        List<WildfireClient.FireCluster> clusters = new WildfireClient(noFetch()).parse(csv);
        assertEquals(1, clusters.size(), "two detections are farm-scale noise, not a fire field");
        WildfireClient.FireCluster fire = clusters.get(0);
        assertEquals(3, fire.detections());
        assertEquals(48.0, fire.maxFrp(), 1e-9);
        assertEquals(55.567, fire.lat(), 0.01, "the cluster sits at the mean of its detections");
        assertEquals(-114.4, fire.lon(), 0.01);
    }

    @Test
    void firmsWithoutTheCoordinateColumnsIsEmpty() {
        assertTrue(new WildfireClient(noFetch()).parse("a,b,c\n1,2,3").isEmpty());
        assertTrue(new WildfireClient(noFetch()).parse(null).isEmpty());
    }

    // ---- Open-Meteo global raster ------------------------------------------

    @Test
    void weatherGridMapsAnswersOntoTheRequestedCellsInOrder() throws Exception {
        String body = """
                [
                  {"current":{"time":"t","temperature_2m":31.4,"precipitation":0.0,"cloud_cover":12}},
                  {"current":{"time":"t","temperature_2m":8.2,"precipitation":2.4,"cloud_cover":95}}
                ]
                """;
        List<double[]> batch = List.of(new double[]{50, 10}, new double[]{60, 20});
        List<GlobalWeatherGridClient.Cell> cells =
                new GlobalWeatherGridClient(noFetch()).parse(body, batch);
        assertEquals(2, cells.size());
        assertEquals(50, cells.get(0).lat(), 1e-9);
        assertEquals(10, cells.get(0).lon(), 1e-9);
        assertEquals(31.4, cells.get(0).temperatureC(), 1e-9);
        assertEquals(0.0, cells.get(0).precipitationMm(), 1e-9);
        assertEquals(12.0, cells.get(0).cloudCoverPercent(), 1e-9);
        assertEquals(95.0, cells.get(1).cloudCoverPercent(), 1e-9);
    }

    @Test
    void weatherGridAcceptsTheBareObjectAOneCellBatchAnswersWith() throws Exception {
        List<GlobalWeatherGridClient.Cell> cells = new GlobalWeatherGridClient(noFetch())
                .parse("{\"current\":{\"temperature_2m\":5.0}}", List.of(new double[]{0, 0}));
        assertEquals(1, cells.size());
        assertEquals(5.0, cells.get(0).temperatureC(), 1e-9);
        assertNull(cells.get(0).precipitationMm(), "a missing field stays an honest null, never a zero");
    }

    // ---- statuspage.io infrastructure boards -------------------------------

    @Test
    void statusBoardReportsOnlyDegradedServicesWithTheirNewestIncident() {
        String body = """
                {"status":{"indicator":"major","description":"Partial outage"},
                 "incidents":[{"name":"Elevated error rates"},{"name":"Older one"}]}
                """;
        Optional<ServiceStatusClient.ServiceStatus> s =
                new ServiceStatusClient(noFetch()).parseBoard("OpenAI", body);
        assertTrue(s.isPresent());
        assertEquals("OpenAI", s.get().name());
        assertEquals("major", s.get().indicator());
        assertEquals("Elevated error rates", s.get().note());
    }

    @Test
    void statusAllGreenBoardStaysSilent() {
        assertTrue(new ServiceStatusClient(noFetch())
                .parseBoard("GitHub", "{\"status\":{\"indicator\":\"none\"},\"incidents\":[]}")
                .isEmpty(), "silence IS the all-green statement");
    }

    @Test
    void statusDegradedWithoutAnOpenIncidentStillCounts() {
        Optional<ServiceStatusClient.ServiceStatus> s = new ServiceStatusClient(noFetch())
                .parseBoard("Cloudflare", "{\"status\":{\"indicator\":\"minor\"},\"incidents\":[]}");
        assertTrue(s.isPresent());
        assertNotNull(s.get().name());
        assertNull(s.get().note());
    }

    @Test
    void statusGarbageIsSilentNotAnError() {
        assertTrue(new ServiceStatusClient(noFetch()).parseBoard("X", "<html>wall</html>").isEmpty());
        assertFalse(new ServiceStatusClient(noFetch()).parseBoard("X", "").isPresent());
    }

    private static void assertArrayEqualsish(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-9, "component " + i);
        }
    }
}
