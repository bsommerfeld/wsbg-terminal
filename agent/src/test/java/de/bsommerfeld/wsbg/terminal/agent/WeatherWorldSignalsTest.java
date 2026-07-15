package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ChokepointStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CivicStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CyberStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.FreightStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HealthStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HolidayStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OilStockStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PolicyStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PollStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PowerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SpaceWxStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldSignals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fishing-net world-signal layer (2026-07-15): candidate lines are built
 * deterministically from the frozen {@code WorldSignals}, each with its home
 * shelf (state signals → Großwetterlage, timed policy/civic → their day-part
 * window, sport/holidays → outlook), and the shelf block renders only the
 * triage survivors of ONE shelf.
 */
class WeatherWorldSignalsTest {

    private static WorldSignals fullSignals() {
        return new WorldSignals(
                List.of(new ChokepointStat("Suez Canal", "2026-07-13", 32, -17.9),
                        new ChokepointStat("Panama Canal", "2026-07-13", null, null)),
                new OilStockStat("2026-07-10", 421.3, -3.1, 403.0, 0.3,
                        232.1, -1.0, 104.2, 2.3),
                new FreightStat(1423.0, 1398.0, "2026-07-10"),
                new PowerStat(87.4, 45.1, 142.0, 89.2, 62.0, "Solar"),
                new SpaceWxStat(0, 0, 1, 2),
                List.of(new PolicyStat("FED", "Statement on bank capital", "14:30"),
                        new PolicyStat("FEDERAL_REGISTER", "Executive order on tariffs", null)),
                List.of(new PollStat("Bundestag", "Forsa", "2026-07-11",
                        "CDU/CSU 29 %, AfD 24 %")),
                List.of(new CivicStat("BLAULICHT", "POL-K", "Brand in Raffinerie", "09:12"),
                        new CivicStat("FINANZEN", "Musterfirma AG", "Werk stillgelegt", "17:40")),
                new HealthStat(71.2, 512.0, "KW28", List.of("2026-07-10 - Cholera")),
                List.of(new CyberStat("CVE-2026-1234", "Microsoft Windows", "2026-07-14")),
                List.of("1. Bundesliga: A - B (15:30)"),
                new HolidayStat("Tag der Deutschen Einheit", "2026-10-03", false,
                        List.of("BY", "BW")));
    }

    @Test
    void candidatesRouteToTheirHomeShelves() {
        List<WeatherMaterial.WorldSignal> c =
                WeatherMaterial.worldSignalCandidates(fullSignals());

        // The transit-less Panama row builds no candidate.
        assertTrue(c.stream().noneMatch(s -> s.line().contains("Panama")));
        // Ordinals are 1-based and consecutive (the triage judges by them).
        for (int i = 0; i < c.size(); i++) {
            assertEquals(i + 1, c.get(i).i());
        }
        // State signals home in the Großwetterlage.
        assertShelf(c, "Suez Canal", WeatherMaterial.SEC_PICTURE);
        assertShelf(c, "US-Öllager", WeatherMaterial.SEC_PICTURE);
        assertShelf(c, "Harpex", WeatherMaterial.SEC_PICTURE);
        assertShelf(c, "EUR/MWh", WeatherMaterial.SEC_PICTURE);
        assertShelf(c, "Weltraumwetter", WeatherMaterial.SEC_PICTURE);
        assertShelf(c, "Sonntagsfrage", WeatherMaterial.SEC_PICTURE);
        assertShelf(c, "Intensivbetten", WeatherMaterial.SEC_PICTURE);
        assertShelf(c, "CVE-2026-1234", WeatherMaterial.SEC_PICTURE);
        assertShelf(c, "Schulferien", WeatherMaterial.SEC_PICTURE);
        // Timed items route by HH:mm: 14:30 → midday, 17:40 → evening,
        // 09:12 → morning; untimed policy homes in the Großwetterlage.
        assertShelf(c, "bank capital", WeatherMaterial.SEC_MIDDAY);
        assertShelf(c, "Executive order", WeatherMaterial.SEC_PICTURE);
        assertShelf(c, "Raffinerie", WeatherMaterial.SEC_MORNING);
        assertShelf(c, "Werk stillgelegt", WeatherMaterial.SEC_EVENING);
        // Schedule facts home in the outlook.
        assertShelf(c, "Fußball morgen", WeatherMaterial.SEC_OUTLOOK);
        assertShelf(c, "Nächster Feiertag", WeatherMaterial.SEC_OUTLOOK);
    }

    @Test
    void explainedNumbersAndSourceWords() {
        List<WeatherMaterial.WorldSignal> c =
                WeatherMaterial.worldSignalCandidates(fullSignals());
        String suez = lineContaining(c, "Suez Canal");
        // Value never naked: transits carry the unit AND the week comparison.
        assertTrue(suez.contains("32 Schiffspassagen/Tag"), suez);
        assertTrue(suez.contains("-17,9 % vs Vorwoche"), suez);
        String oil = lineContaining(c, "US-Öllager");
        assertTrue(oil.contains("421,3 Mio. Barrel"), oil);
        assertTrue(oil.contains("-3,1 zur Vorwoche"), oil);
        // Policy source tokens become readable desk words.
        assertTrue(lineContaining(c, "bank capital").startsWith("[Fed]"),
                lineContaining(c, "bank capital"));
        assertTrue(lineContaining(c, "Raffinerie").startsWith("[Blaulicht]"),
                lineContaining(c, "Raffinerie"));
        assertTrue(lineContaining(c, "Werk stillgelegt").startsWith("[OTS Finanzen]"),
                lineContaining(c, "Werk stillgelegt"));
    }

    @Test
    void quietSpaceWeatherBuildsNoCandidate() {
        WorldSignals quiet = new WorldSignals(List.of(), null, null, null,
                new SpaceWxStat(0, 0, 0, 0), List.of(), List.of(), List.of(),
                null, List.of(), List.of(), null);
        assertTrue(WeatherMaterial.worldSignalCandidates(quiet).isEmpty());
    }

    @Test
    void blockRendersOnlyItsShelf() {
        List<WeatherMaterial.WorldSignal> survivors = List.of(
                new WeatherMaterial.WorldSignal(1, WeatherMaterial.SEC_PICTURE, "state line"),
                new WeatherMaterial.WorldSignal(2, WeatherMaterial.SEC_EVENING, "evening line"));
        String picture = WeatherMaterial.worldSignalsBlock("of the day", survivors,
                WeatherMaterial.SEC_PICTURE);
        assertTrue(picture.contains("state line"));
        assertFalse(picture.contains("evening line"));
        assertTrue(picture.startsWith("WORLD SIGNALS of the day"));
        // A shelf with no survivors renders nothing, never an empty header.
        assertEquals("", WeatherMaterial.worldSignalsBlock("x", survivors,
                WeatherMaterial.SEC_MIDDAY));
        assertEquals("", WeatherMaterial.worldSignalsBlock("x", List.of(),
                WeatherMaterial.SEC_PICTURE));
    }

    @Test
    void nullSignalsYieldNoCandidates() {
        assertTrue(WeatherMaterial.worldSignalCandidates(null).isEmpty());
    }

    private static void assertShelf(List<WeatherMaterial.WorldSignal> c, String needle,
            int shelf) {
        WeatherMaterial.WorldSignal hit = c.stream()
                .filter(s -> s.line().contains(needle)).findFirst().orElse(null);
        assertTrue(hit != null, "no candidate line contains: " + needle);
        assertEquals(shelf, hit.shelf(), "wrong shelf for: " + hit.line());
    }

    private static String lineContaining(List<WeatherMaterial.WorldSignal> c, String needle) {
        return c.stream().filter(s -> s.line().contains(needle))
                .map(WeatherMaterial.WorldSignal::line).findFirst().orElse("");
    }
}
