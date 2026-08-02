package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the GDELT 2.0 export table against a live-probed row layout (2026-07-17):
 * 61 tab-separated fields, the violent CAMEO families at columns 28, the action's
 * own geocode at 51..57. The layer exists for PRECISION — a country-centroid row
 * must never reach the map, and a swarm of wire copies about one strike must land
 * as one marker.
 */
class GdeltConflictClientTest {

    /** Builds one export row with the fields the client actually reads. */
    private static String row(String rootCode, String geoType, String place,
            String lat, String lon, String articles, String goldstein, String url) {
        return row(rootCode, "1", geoType, place, lat, lon, articles, goldstein, url);
    }

    /** A row carrying an actor PAIR: who acted (35..41) on whom (43..49). */
    private static String pairRow(String quadClass, String fromPlace, String fromLat, String fromLon,
            String toPlace, String toLat, String toLon, String articles, String url) {
        String[] c = new String[61];
        for (int i = 0; i < c.length; i++) c[i] = "";
        c[0] = "1316511208";
        c[25] = "1";
        c[28] = "04";
        c[29] = quadClass;
        c[33] = articles;
        c[35] = "1";
        c[36] = fromPlace;
        c[40] = fromLat;
        c[41] = fromLon;
        c[43] = "1";
        c[44] = toPlace;
        c[48] = toLat;
        c[49] = toLon;
        c[60] = url;
        return String.join("\t", c);
    }

    private static String row(String rootCode, String isRootEvent, String geoType, String place,
            String lat, String lon, String articles, String goldstein, String url) {
        String[] c = new String[61];
        for (int i = 0; i < c.length; i++) c[i] = "";
        c[0] = "1316511207";
        c[25] = isRootEvent;
        c[28] = rootCode;
        c[30] = goldstein;
        c[33] = articles;
        c[51] = geoType;
        c[52] = place;
        c[56] = lat;
        c[57] = lon;
        c[60] = url;
        return String.join("\t", c);
    }

    @Test
    void keepsTheViolentFamiliesAtTheirActualCoordinates() {
        String table = String.join("\n",
                row("19", "4", "Kharkiv, Kharkivska Oblast, Ukraine", "49.98", "36.25", "31", "-10.0", "https://a.example/1"),
                row("18", "3", "Portland, Oregon, United States", "45.52", "-122.68", "4", "-9.0", "https://b.example/2"),
                row("20", "4", "Kandahar, Afghanistan", "31.61", "65.71", "12", "-10.0", "https://c.example/3"),
                row("04", "4", "Geneva, Switzerland", "46.20", "6.14", "88", "7.0", "https://d.example/4"));
        List<GdeltConflictClient.Attack> out = new GdeltConflictClient().parse(table);

        assertEquals(3, out.size(), "consultation (root 04) is not an attack");
        GdeltConflictClient.Attack loudest = out.get(0);
        assertEquals("FIGHT", loudest.kind(), "the loudest event leads");
        assertEquals("Kharkiv, Kharkivska Oblast, Ukraine", loudest.place());
        assertEquals(49.98, loudest.lat(), 1e-9);
        assertEquals(36.25, loudest.lon(), 1e-9);
        assertEquals(31, loudest.articles());
        assertEquals(-10.0, loudest.goldstein(), 1e-9);
        assertEquals("https://a.example/1", loudest.sourceUrl());
        assertEquals("MASS_VIOLENCE", out.get(1).kind());
        assertEquals("ASSAULT", out.get(2).kind());
    }

    @Test
    void dropsCountryPrecisionRowsTheLayerExistsToReplaceThem() {
        String table = String.join("\n",
                row("19", "1", "Sudan", "15.0", "30.0", "40", "-10.0", "https://a.example/1"),
                row("19", "", "Nowhere", "1.0", "2.0", "9", "-10.0", "https://b.example/2"),
                row("19", "4", "Aleppo, Syria", "36.2", "37.1", "3", "-10.0", "https://c.example/3"));
        List<GdeltConflictClient.Attack> out = new GdeltConflictClient().parse(table);
        assertEquals(1, out.size(), "a centroid and a missing precision both stay off the map");
        assertEquals("Aleppo, Syria", out.get(0).place());
    }

    @Test
    void oneStrikeIsOneMarkerHoweverLoudlyItIsCopied() {
        String table = String.join("\n",
                row("19", "4", "Kharkiv", "49.9812", "36.2510", "6", "-10.0", "https://a.example/1"),
                row("19", "4", "Kharkiv", "49.9834", "36.2489", "27", "-10.0", "https://b.example/2"),
                row("19", "4", "Kharkiv", "49.9805", "36.2533", "11", "-10.0", "https://c.example/3"),
                // same spot, DIFFERENT family — a separate story, a separate marker
                row("18", "4", "Kharkiv", "49.9812", "36.2510", "5", "-9.0", "https://d.example/4"));
        List<GdeltConflictClient.Attack> out = new GdeltConflictClient().parse(table);
        assertEquals(2, out.size());
        assertEquals(27, out.get(0).articles(), "the loudest report of the spot survives");
        assertEquals("https://b.example/2", out.get(0).sourceUrl());
        assertEquals("ASSAULT", out.get(1).kind());
    }

    @Test
    void dropsEventsTheArticleOnlyMentionsInPassing() {
        String table = String.join("\n",
                row("19", "0", "4", "New York, United States", "40.7", "-74.0", "40", "-10.0",
                        "https://a.example/opinion-column-that-references-a-killing"),
                row("19", "1", "4", "Kharkiv, Ukraine", "49.98", "36.25", "5", "-10.0",
                        "https://b.example/strike"));
        List<GdeltConflictClient.Attack> out = new GdeltConflictClient().parse(table);
        assertEquals(1, out.size(), "only the event the article is ABOUT plants a marker");
        assertEquals("Kharkiv, Ukraine", out.get(0).place());
    }

    @Test
    void shortOrGarbageRowsAreSkippedNotFatal() {
        assertTrue(new GdeltConflictClient().parse("too\tshort\trow").isEmpty());
        assertTrue(new GdeltConflictClient().parse(null).isEmpty());
        assertTrue(new GdeltConflictClient().parse("  ").isEmpty());
        // A row whose coordinates are unparseable carries no place to draw.
        assertTrue(new GdeltConflictClient()
                .parse(row("19", "4", "X", "n/a", "n/a", "3", "-10.0", "u")).isEmpty());
    }

    // ---- relation arcs ------------------------------------------------------

    @Test
    void relationsRunFromTheActorToTheActedUponWithCameosOwnStance() {
        GdeltConflictClient client = new GdeltConflictClient();
        String table = String.join("\n",
                pairRow("4", "Israel", "31.5", "34.75", "Iran", "32.0", "53.0", "44", "https://a/1"),
                pairRow("3", "Ottawa, Canada", "45.4", "-75.7", "Morocco", "32.0", "-6.0", "8", "https://b/2"),
                pairRow("1", "Algeria", "28.0", "3.0", "Russia", "60.0", "100.0", "2", "https://c/3"));
        List<GdeltConflictClient.Relation> out = client.parseRelations(client.rowsOf(table));
        assertEquals(3, out.size());
        GdeltConflictClient.Relation loudest = out.get(0);
        assertEquals("MATERIAL_CONFLICT", loudest.stance(), "the loudest relation leads");
        assertEquals("Israel", loudest.fromPlace());
        assertEquals("Iran", loudest.toPlace());
        assertEquals(31.5, loudest.fromLat(), 1e-9);
        assertEquals(53.0, loudest.toLon(), 1e-9);
        assertEquals("VERBAL_CONFLICT", out.get(1).stance());
        assertEquals("COOPERATION", out.get(2).stance(), "quad 1 and 2 are both cooperation");
    }

    @Test
    void aPlaceActingOnItselfIsDomesticNewsNotARelation() {
        GdeltConflictClient client = new GdeltConflictClient();
        String table = String.join("\n",
                pairRow("4", "Berlin", "52.52", "13.40", "Berlin", "52.52", "13.40", "30", "https://a/1"),
                pairRow("4", "Berlin", "52.52", "13.40", "Warsaw", "52.23", "21.01", "9", "https://b/2"));
        List<GdeltConflictClient.Relation> out = client.parseRelations(client.rowsOf(table));
        assertEquals(1, out.size(), "an arc needs two DIFFERENT ends");
        assertEquals("Warsaw", out.get(0).toPlace());
    }

    @Test
    void relationsNeedBothEndsGeocoded() {
        GdeltConflictClient client = new GdeltConflictClient();
        String table = pairRow("4", "Israel", "31.5", "34.75", "Nowhere", "", "", "44", "https://a/1");
        assertTrue(client.parseRelations(client.rowsOf(table)).isEmpty());
    }

    // ---- escalation memory --------------------------------------------------

    @Test
    void nothingEscalatesBeforeThereIsABaseline() {
        GdeltConflictClient.StoryTrend trend = new GdeltConflictClient.StoryTrend();
        Map<String, GdeltConflictClient.Volume> loud = Map.of("5252,1340",
                new GdeltConflictClient.Volume("Berlin", 52.52, 13.40, 400, "https://a/1"));
        // Four quiet windows first — the very first spike has nothing to spike against.
        for (int i = 0; i < 3; i++) {
            assertTrue(trend.observe(loud).isEmpty(),
                    "window " + i + " still has no baseline to judge against");
        }
    }

    @Test
    void aStoryThatKeepsClimbingBecomesAHotspot() {
        GdeltConflictClient.StoryTrend trend = new GdeltConflictClient.StoryTrend();
        Map<String, GdeltConflictClient.Volume> quiet = Map.of("5252,1340",
                new GdeltConflictClient.Volume("Berlin", 52.52, 13.40, 4, "https://a/quiet"));
        for (int i = 0; i < 5; i++) trend.observe(quiet);

        Map<String, GdeltConflictClient.Volume> spike = Map.of("5252,1340",
                new GdeltConflictClient.Volume("Berlin", 52.52, 13.40, 60, "https://a/spike"));
        List<GdeltConflictClient.Hotspot> out = trend.observe(spike);
        assertEquals(1, out.size());
        assertEquals("Berlin", out.get(0).place());
        assertEquals(60, out.get(0).articles());
        assertEquals(4, out.get(0).baseline());
        assertEquals(15.0, out.get(0).factor(), 1e-9);
    }

    @Test
    void aPlaceThatIsSimplyAlwaysLoudNeverCountsAsEscalating() {
        GdeltConflictClient.StoryTrend trend = new GdeltConflictClient.StoryTrend();
        Map<String, GdeltConflictClient.Volume> steady = Map.of("4071,-7400",
                new GdeltConflictClient.Volume("New York", 40.71, -74.0, 300, "https://a/1"));
        for (int i = 0; i < 6; i++) trend.observe(steady);
        assertTrue(trend.observe(steady).isEmpty(),
                "constant loudness is a big city, not an escalating story");
    }

    @Test
    void aQuietVillageWithThreeArticlesIsNotAWorldEvent() {
        GdeltConflictClient.StoryTrend trend = new GdeltConflictClient.StoryTrend();
        Map<String, GdeltConflictClient.Volume> almostNothing = Map.of("100,100",
                new GdeltConflictClient.Volume("Kleinkleckersdorf", 1.0, 1.0, 1, "https://a/1"));
        for (int i = 0; i < 6; i++) trend.observe(almostNothing);
        Map<String, GdeltConflictClient.Volume> stillTiny = Map.of("100,100",
                new GdeltConflictClient.Volume("Kleinkleckersdorf", 1.0, 1.0, 6, "https://a/2"));
        assertTrue(trend.observe(stillTiny).isEmpty(),
                "six articles is a slow news day, whatever the ratio says");
    }

    @Test
    void volumesCountEVERYTopicNotJustTheViolentOnes() {
        GdeltConflictClient client = new GdeltConflictClient();
        String table = String.join("\n",
                // Two different topics in the same city add up to that city's volume.
                row("14", "1", "4", "Berlin, Germany", "52.52", "13.40", "12", "-5.0", "https://a/1"),
                row("04", "1", "4", "Berlin, Germany", "52.52", "13.40", "7", "2.0", "https://b/2"),
                row("19", "1", "1", "Germany", "51.0", "9.0", "40", "-10.0", "https://c/3"));
        Map<String, GdeltConflictClient.Volume> out = client.volumesOf(client.rowsOf(table));
        assertEquals(1, out.size(), "the country-level row carries no place to watch");
        GdeltConflictClient.Volume berlin = out.values().iterator().next();
        assertEquals("Berlin, Germany", berlin.place());
        assertEquals(19, berlin.articles(), "a protest and a meeting both count as coverage");
    }

    @Test
    void pointerFileNamesTheEventFileNotTheMentionsOrGkgProducts() {
        String pointer = """
                39931 ecf0d23f http://data.gdeltproject.org/gdeltv2/20260802210000.export.CSV.zip
                61832 16a7c3be http://data.gdeltproject.org/gdeltv2/20260802210000.mentions.CSV.zip
                2665493 24fc831e http://data.gdeltproject.org/gdeltv2/20260802210000.gkg.csv.zip
                """;
        assertEquals("http://data.gdeltproject.org/gdeltv2/20260802210000.export.CSV.zip",
                GdeltConflictClient.exportUrlIn(pointer));
        assertNull(GdeltConflictClient.exportUrlIn("nothing here"));
        assertNull(GdeltConflictClient.exportUrlIn(null));
    }

    @Test
    void theArchiveIsUnpackedAsText() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("20260802210000.export.CSV"));
            zip.write("erste Zeile\tzweite Spalte".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertEquals("erste Zeile\tzweite Spalte",
                GdeltConflictClient.unzipFirstEntry(buffer.toByteArray()));
        assertEquals("", GdeltConflictClient.unzipFirstEntry(new byte[0]));
        assertEquals("", GdeltConflictClient.unzipFirstEntry(null));
    }
}
