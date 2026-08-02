package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the German infrastructure layers against live-probed shapes
 * (2026-08-03): the Autobahn GmbH incident API, whose coordinates arrive as
 * strings and whose roadworks are often announced for NEXT month, and the
 * waterways authority's gauge network, where only a reading outside its own
 * normal band is news.
 */
class GermanInfraSourcesTest {

    // ---- Autobahn ------------------------------------------------------------

    @Test
    void roadListComesFromTheApiNeverFromUs() {
        assertEquals(List.of("A1", "A2", "A3"),
                AutobahnClient.parseRoads("{\"roads\":[\"A1\",\"A2\",\"A3\"]}"));
        assertTrue(AutobahnClient.parseRoads("<html>wall</html>").isEmpty());
    }

    @Test
    void closuresCarryCoordinatesAsStringsAndSayWhetherAnythingGetsThrough() {
        String body = """
                {"closure":[{"title":"A3 | Kelsterbach - Johannespfad",
                  "subtitle":" Frankfurt -> Köln","isBlocked":true,"future":false,
                  "startTimestamp":"2026-08-03T22:00:00+02:00",
                  "coordinate":{"lat":"50.0311137202906","long":"8.504798542329036"}}]}
                """;
        List<AutobahnClient.RoadIncident> out =
                new AutobahnClient().parse(body, "closure", "CLOSURE", "A3");
        assertEquals(1, out.size());
        AutobahnClient.RoadIncident r = out.get(0);
        assertEquals("CLOSURE", r.kind());
        assertEquals("A3", r.road());
        assertEquals("A3 | Kelsterbach - Johannespfad", r.title());
        assertEquals("Frankfurt -> Köln", r.direction(), "the subtitle arrives with a leading space");
        assertTrue(r.blocked());
        assertEquals(50.0311137202906, r.lat(), 1e-9);
        assertEquals(8.504798542329036, r.lon(), 1e-9);
    }

    @Test
    void roadworksAnnouncedForNextMonthAreACalendarEntryNotACurrentEvent() {
        String body = """
                {"closure":[
                  {"title":"running now","future":false,"isBlocked":true,
                   "coordinate":{"lat":"50.0","long":"8.5"}},
                  {"title":"starts in September","future":true,"isBlocked":true,
                   "coordinate":{"lat":"51.0","long":"9.5"}},
                  {"title":"no position","future":false,"coordinate":{}}]}
                """;
        List<AutobahnClient.RoadIncident> out =
                new AutobahnClient().parse(body, "closure", "CLOSURE", "A7");
        assertEquals(1, out.size());
        assertEquals("running now", out.get(0).title());
    }

    @Test
    void anEmptyStretchIsEmptyNotAnError() {
        assertTrue(new AutobahnClient().parse("{\"warning\":[]}", "warning", "WARNING", "A1").isEmpty());
        assertTrue(new AutobahnClient().parse("<html>wall</html>", "warning", "WARNING", "A1").isEmpty());
    }

    // ---- PEGELONLINE ---------------------------------------------------------

    private static String station(String name, String water, String mnw, String nsw, double value) {
        return """
                {"shortname":"%s","longname":"%s","latitude":50.1,"longitude":7.6,
                 "water":{"shortname":"%s"},
                 "timeseries":[
                   {"shortname":"WT","currentMeasurement":{"value":19.0}},
                   {"shortname":"W","unit":"cm","currentMeasurement":{
                     "timestamp":"2026-08-03T00:30:00+02:00","value":%s,
                     "stateMnwMhw":"%s","stateNswHsw":"%s"}}]}
                """.formatted(name, name, water, value, mnw, nsw);
    }

    @Test
    void onlyGaugesOutOfTheirNormalBandAreNews() {
        String body = "[" + String.join(",",
                station("KAUB", "RHEIN", "low", "unknown", 78.0),
                station("CELLE", "ALLER", "normal", "normal", 120.0),
                station("DRESDEN", "ELBE", "unknown", "high", 640.0)) + "]";
        List<WaterLevelClient.Gauge> out = new WaterLevelClient().parse(body);
        assertEquals(2, out.size(), "a river sitting where it belongs is not a marker");
        assertEquals("KAUB", out.get(0).name());
        assertEquals("RHEIN", out.get(0).water());
        assertEquals("low", out.get(0).state());
        assertEquals(78.0, out.get(0).centimetres(), 1e-9);
        assertEquals("high", out.get(1).state());
    }

    @Test
    void highWaterOutranksLowWhenTheTwoScalesDisagree() {
        String body = "[" + station("X", "MOSEL", "low", "high", 500.0) + "]";
        assertEquals("high", new WaterLevelClient().parse(body).get(0).state(),
                "if either scale cries flood, that is the headline");
    }

    @Test
    void aStationWithoutAWaterLevelSeriesIsSkipped() {
        String body = """
                [{"shortname":"WIND","latitude":54.0,"longitude":8.0,
                  "water":{"shortname":"NORDSEE"},
                  "timeseries":[{"shortname":"WG","currentMeasurement":{"value":22.0}}]}]
                """;
        assertTrue(new WaterLevelClient().parse(body).isEmpty());
        assertTrue(new WaterLevelClient().parse("<html>wall</html>").isEmpty());
    }

    @Test
    void anAllNormalCountryIsAnEmptyList() {
        String body = "[" + station("CELLE", "ALLER", "normal", "normal", 120.0) + "]";
        assertFalse(new WaterLevelClient().parse(body).size() > 0);
    }
}
