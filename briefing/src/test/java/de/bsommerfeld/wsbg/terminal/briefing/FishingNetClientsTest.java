package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free parser tests for the fishing-net world-data clients, every
 * fixture TRIMMED from a live probe 2026-07-14 (presseportal Blaulicht RSS,
 * WHO DON OData, DIVI laendertabelle, RKI ARE TSV, CISA KEV, openligadb bl1,
 * date.nager.at public holidays, ferien-api school windows, Autobahn
 * closure/warning, MVG messages). Each asserts the record shape + the parser's
 * unknown-value/garbage conventions.
 */
class FishingNetClientsTest {

    private static String fixture(String name) {
        try (InputStream in = FishingNetClientsTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "fixture missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- PresseportalClient -------------------------------------------------

    @Test
    void presseportalParsesBlaulichtWithOfficePrefix() {
        List<PresseportalClient.Item> items =
                PresseportalClient.parse(fixture("presseportal-blaulicht.xml"));
        assertEquals(3, items.size());

        PresseportalClient.Item first = items.get(0);
        assertTrue(first.title().startsWith("POL-PDNR"));
        assertEquals("POL-PDNR", first.office());
        assertNotNull(first.link());
        assertNotNull(first.teaser());
        assertNotNull(first.publishedAt());

        // FW-Schermbeck keeps its lowercase tail as a valid office token.
        assertEquals("FW-Schermbeck", items.get(1).office());
    }

    @Test
    void presseportalOfficePrefixRejectsNonPrefixTitles() {
        assertNull(PresseportalClient.officePrefix("Konzern meldet Rekordquartal"));
        assertNull(PresseportalClient.officePrefix("Kein Doppelpunkt hier"));
        assertEquals("POL-KA", PresseportalClient.officePrefix("POL-KA: Vermisste angetroffen"));
    }

    @Test
    void presseportalGarbageParsesToEmpty() {
        assertTrue(PresseportalClient.parse(null).isEmpty());
        assertTrue(PresseportalClient.parse("<html>wall</html>").isEmpty());
    }

    // ---- WhoOutbreakClient --------------------------------------------------

    @Test
    void whoParsesOutbreaksWithSummaryAndLink() {
        List<WhoOutbreakClient.Outbreak> items =
                WhoOutbreakClient.parse(fixture("who-don.json"));
        assertEquals(2, items.size());
        WhoOutbreakClient.Outbreak first = items.get(0);
        assertFalse(first.title().isBlank());
        assertNotNull(first.summary());
        assertNotNull(first.publishedAt());
        assertTrue(first.link().startsWith("https://www.who.int/emergencies/disease-outbreak-news/item"));
    }

    @Test
    void whoGarbageParsesToEmpty() {
        assertTrue(WhoOutbreakClient.parse(null).isEmpty());
        assertTrue(WhoOutbreakClient.parse("{\"value\":[]}").isEmpty());
        assertTrue(WhoOutbreakClient.parse("<html>").isEmpty());
    }

    // ---- DiviClient ---------------------------------------------------------

    @Test
    void diviParsesStateIcuRows() {
        List<DiviClient.StateIcu> states = DiviClient.parse(fixture("divi-laendertabelle.json"));
        assertEquals(3, states.size());
        DiviClient.StateIcu sh = states.get(0);
        assertEquals("SCHLESWIG_HOLSTEIN", sh.state());
        assertEquals(397, sh.occupied());
        assertEquals(483, sh.total());
        assertTrue(sh.occupancyPercent() > 80 && sh.occupancyPercent() < 90);
    }

    @Test
    void diviGarbageParsesToEmpty() {
        assertTrue(DiviClient.parse(null).isEmpty());
        assertTrue(DiviClient.parse("{\"data\":[]}").isEmpty());
    }

    // ---- RkiSurveillanceClient ---------------------------------------------

    @Test
    void rkiParsesNationwideAllAgesSeriesOldestToNewest() {
        List<RkiSurveillanceClient.WeeklyIncidence> series =
                RkiSurveillanceClient.parse(fixture("rki-are.tsv"));
        assertEquals(6, series.size(), "only Bundesweit/00+ rows are kept");
        assertEquals("2026-W22", series.get(0).isoWeek());
        assertEquals("2026-W27", series.get(series.size() - 1).isoWeek());
        assertEquals(578.0, series.get(series.size() - 1).incidence());
    }

    @Test
    void rkiGarbageParsesToEmpty() {
        assertTrue(RkiSurveillanceClient.parse(null).isEmpty());
        assertTrue(RkiSurveillanceClient.parse("").isEmpty());
    }

    // ---- CisaKevClient ------------------------------------------------------

    @Test
    void cisaParsesVulnerabilities() {
        List<CisaKevClient.Kev> kevs = CisaKevClient.parse(fixture("cisa-kev.json"));
        assertEquals(3, kevs.size());
        CisaKevClient.Kev k = kevs.get(0);
        assertTrue(k.cveID().startsWith("CVE-"));
        assertFalse(k.vendorProject().isBlank());
        assertNotNull(k.dateAdded());
        assertFalse(k.shortDescription().isBlank());
    }

    @Test
    void cisaGarbageParsesToEmpty() {
        assertTrue(CisaKevClient.parse(null).isEmpty());
        assertTrue(CisaKevClient.parse("{\"vulnerabilities\":[]}").isEmpty());
    }

    // ---- SportsCalendarClient ----------------------------------------------

    @Test
    void sportsParsesUpcomingFixtures() {
        List<SportsCalendarClient.Fixture> fixtures =
                SportsCalendarClient.parse("bl1", fixture("openligadb-bl1.json"));
        assertFalse(fixtures.isEmpty());
        SportsCalendarClient.Fixture f = fixtures.get(0);
        assertEquals("bl1", f.league());
        assertFalse(f.home().isBlank());
        assertFalse(f.away().isBlank());
        assertNotNull(f.kickoff());
        assertEquals("1. Spieltag", f.matchday());
    }

    @Test
    void sportsGarbageParsesToEmpty() {
        assertTrue(SportsCalendarClient.parse("bl1", null).isEmpty());
        assertTrue(SportsCalendarClient.parse("bl1", "[]").isEmpty());
    }

    // ---- HolidayCalendarClient ---------------------------------------------

    @Test
    void holidayParsesPublicHolidaysAscendingWithCounties() {
        List<HolidayCalendarClient.PublicHoliday> hols =
                HolidayCalendarClient.parsePublic(fixture("nager-holidays.json"));
        assertFalse(hols.isEmpty());
        HolidayCalendarClient.PublicHoliday first = hols.get(0);
        assertEquals(LocalDate.parse("2026-01-01"), first.date());
        assertEquals("Neujahr", first.name());
        assertTrue(first.nationwide());
        // Epiphany is state-specific with counties.
        HolidayCalendarClient.PublicHoliday epiphany = hols.stream()
                .filter(h -> h.name().contains("Drei")).findFirst().orElseThrow();
        assertFalse(epiphany.nationwide());
        assertTrue(epiphany.counties().contains("DE-BY"));
    }

    @Test
    void holidayParsesSchoolWindows() {
        List<HolidayCalendarClient.SchoolHoliday> wins =
                HolidayCalendarClient.parseSchool(fixture("ferien-api-by.json"));
        assertEquals(3, wins.size());
        HolidayCalendarClient.SchoolHoliday w = wins.get(0);
        assertEquals("BY", w.stateCode());
        assertEquals("winterferien", w.name());
        assertEquals(LocalDate.parse("2026-02-16"), w.start());
        assertEquals(LocalDate.parse("2026-02-21"), w.end());
    }

    @Test
    void holidayGarbageParsesToEmpty() {
        assertTrue(HolidayCalendarClient.parsePublic(null).isEmpty());
        assertTrue(HolidayCalendarClient.parseSchool("<html>").isEmpty());
    }

    // ---- TrafficClient ------------------------------------------------------

    @Test
    void trafficParsesAutobahnClosureWithJoinedDescription() {
        List<TrafficClient.RoadEvent> events = TrafficClient.parseAutobahn(
                "A1", TrafficClient.AutobahnKind.CLOSURE, fixture("autobahn-closure.json"));
        assertEquals(2, events.size());
        TrafficClient.RoadEvent e = events.get(0);
        assertEquals("A1", e.road());
        assertEquals(TrafficClient.AutobahnKind.CLOSURE, e.kind());
        assertFalse(e.title().isBlank());
        assertNotNull(e.direction());
        assertNotNull(e.description());
    }

    @Test
    void trafficParsesAutobahnWarningWithStartTimestamp() {
        List<TrafficClient.RoadEvent> events = TrafficClient.parseAutobahn(
                "A1", TrafficClient.AutobahnKind.WARNING, fixture("autobahn-warning.json"));
        assertEquals(1, events.size());
        assertNotNull(events.get(0).startedAt());
    }

    @Test
    void trafficParsesMvgMessagesWithLines() {
        List<TrafficClient.TransitMessage> msgs =
                TrafficClient.parseMvg(fixture("mvg-messages.json"));
        assertFalse(msgs.isEmpty());
        TrafficClient.TransitMessage m = msgs.get(0);
        assertFalse(m.title().isBlank());
        assertNotNull(m.description());
        assertNotNull(m.publishedAt());
        assertFalse(m.lines().isEmpty(), "affected line labels are deduped, not empty");
        // dedupe: fixture repeats "173"/"177" — each label appears once.
        assertEquals(m.lines().size(), m.lines().stream().distinct().count());
    }

    @Test
    void trafficGarbageParsesToEmpty() {
        assertTrue(TrafficClient.parseAutobahn("A1",
                TrafficClient.AutobahnKind.CLOSURE, null).isEmpty());
        assertTrue(TrafficClient.parseMvg("[]").isEmpty());
        assertTrue(TrafficClient.parseMvg("<html>").isEmpty());
    }
}
