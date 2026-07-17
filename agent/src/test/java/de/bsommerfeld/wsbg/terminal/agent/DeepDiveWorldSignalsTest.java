package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.GlobalHazardsClient;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DD's fishing-net judge design (2026-07-15, user mandate "alles rein,
 * die KI sortiert aus"): the FULL catch becomes judgeable candidate lines
 * (ROOT locale, values explained), the exposure maps survive only as HINTS on
 * the lines they know, the judge's survivors reach the Lage shelf verbatim
 * (hint stripped), and no pre-filter ever hides a signal from the model.
 */
class DeepDiveWorldSignalsTest {

    private static WorldSignals fullSignals() {
        return new WorldSignals(
                List.of(new ChokepointStat("Suez Canal", "2026-07-13", 32, -17.9)),
                new OilStockStat("2026-07-10", 421.3, -3.1, 403.0, 0.3,
                        232.1, -1.0, 104.2, 2.3),
                new FreightStat(1423.0, 1398.0, "2026-07-10"),
                new PowerStat(87.4, 45.1, 142.0, 89.2, 62.0, "Solar"),
                new SpaceWxStat(0, 0, 2, 3),
                List.of(new PolicyStat("FED", "Statement on bank capital", "14:30")),
                List.of(new PollStat("Bundestag", "Forsa", "2026-07-11", "CDU/CSU 29 %")),
                List.of(new CivicStat("BLAULICHT", "POL-K", "Brand in Raffinerie", "09:12")),
                new HealthStat(71.2, 512.0, "KW28", List.of("2026-07-10 - Cholera")),
                List.of(new CyberStat("CVE-2026-1234", "Microsoft Windows", "2026-07-14")),
                List.of("1. Bundesliga: A - B (15:30)"),
                new HolidayStat("Tag der Deutschen Einheit", "2026-10-03", false,
                        List.of("BY", "BW")));
    }

    @Test
    void fullCatchBecomesCandidatesWithNothingPreFiltered() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Test AG";
        m.worldSignals = fullSignals();
        m.allHazards = List.of(new GlobalHazardsClient.Hazard("STORM",
                "Hurricane X, category 3, Gulf of Mexico", "HIGH"));
        // NO sector proxy — every domain must still be on the table.
        List<String> c = DeepDiveService.worldSignalCandidateLines(m);
        assertTrue(c.stream().anyMatch(l -> l.contains("US petroleum stocks")), c.toString());
        assertTrue(c.stream().anyMatch(l -> l.contains("Suez Canal")));
        assertTrue(c.stream().anyMatch(l -> l.contains("Harpex")));
        assertTrue(c.stream().anyMatch(l -> l.contains("EUR/MWh")));
        assertTrue(c.stream().anyMatch(l -> l.contains("Space weather")));
        assertTrue(c.stream().anyMatch(l -> l.contains("CVE-2026-1234")));
        assertTrue(c.stream().anyMatch(l -> l.startsWith("Policy wire [FED]")));
        assertTrue(c.stream().anyMatch(l -> l.startsWith("German election poll")));
        assertTrue(c.stream().anyMatch(l -> l.startsWith("Civic wire [BLAULICHT]")));
        assertTrue(c.stream().anyMatch(l -> l.startsWith("German public health")));
        assertTrue(c.stream().anyMatch(l -> l.startsWith("WHO disease outbreak")));
        assertTrue(c.stream().anyMatch(l -> l.startsWith("German football tomorrow")));
        assertTrue(c.stream().anyMatch(l -> l.startsWith("Next German public holiday")));
        assertTrue(c.stream().anyMatch(l -> l.startsWith("World hazard [STORM")));
        // Without a sector proxy no line carries a hint.
        assertTrue(c.stream().noneMatch(l -> l.contains("[hint:")), c.toString());
        // The transmission anchors ride on EVERY line regardless of the
        // instrument's sector (researched 2026-07-17): space weather names the
        // documented paths (grid, satellites, GPS) and honestly flags the
        // undocumented software link; policy, polls, civic, sports and holiday
        // lines carry anchors too — no world line goes to the judge naked.
        String spaceWx = c.stream().filter(l -> l.contains("Space weather"))
                .findFirst().orElseThrow();
        assertTrue(spaceWx.contains("[affects:"), spaceWx);
        assertTrue(spaceWx.contains("satellite"), spaceWx);
        assertTrue(spaceWx.contains("NOT documented"), spaceWx);
        assertTrue(c.stream().filter(l -> l.startsWith("World hazard"))
                .allMatch(l -> l.contains("[affects:")), c.toString());
        for (String prefix : List.of("Policy wire", "German election poll",
                "Civic wire", "German football tomorrow", "Next German public holiday")) {
            assertTrue(c.stream().filter(l -> l.startsWith(prefix))
                    .allMatch(l -> l.contains("[affects:")), prefix + ":\n" + c);
        }
    }

    /**
     * Researched exposure (2026-07-17): communication carries the documented
     * satellite space-weather channel; tech carries cyber but deliberately NOT
     * space weather (no documented episode).
     */
    @Test
    void sectorHintsFollowTheEvidence() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Software AG";
        m.sectorEtfSymbol = "XLK";
        m.worldSignals = fullSignals();
        List<String> c = DeepDiveService.worldSignalCandidateLines(m);
        String spaceWx = c.stream().filter(l -> l.contains("Space weather"))
                .findFirst().orElseThrow();
        String cyber = c.stream().filter(l -> l.contains("CVE-2026-1234"))
                .findFirst().orElseThrow();
        assertFalse(spaceWx.contains("[hint:"), spaceWx);
        assertTrue(cyber.contains("[hint:"), cyber);

        m.sectorEtfSymbol = "XLC";
        List<String> comm = DeepDiveService.worldSignalCandidateLines(m);
        String commSpaceWx = comm.stream().filter(l -> l.contains("Space weather"))
                .findFirst().orElseThrow();
        assertTrue(commSpaceWx.contains("[hint:"), commSpaceWx);
    }

    @Test
    void exposureMapsBecomeHintsNeverFilters() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Öl AG";
        m.sectorEtfSymbol = "XLE";
        m.worldSignals = fullSignals();
        m.allHazards = List.of(new GlobalHazardsClient.Hazard("STORM", "Hurricane X", "HIGH"),
                new GlobalHazardsClient.Hazard("QUAKE", "M6.1 offshore", "MEDIUM"));
        List<String> c = DeepDiveService.worldSignalCandidateLines(m);
        String oil = c.stream().filter(l -> l.contains("US petroleum stocks"))
                .findFirst().orElseThrow();
        String cyber = c.stream().filter(l -> l.contains("CVE-2026-1234"))
                .findFirst().orElseThrow();
        String storm = c.stream().filter(l -> l.contains("Hurricane X"))
                .findFirst().orElseThrow();
        String quake = c.stream().filter(l -> l.contains("M6.1"))
                .findFirst().orElseThrow();
        // XLE trades on oil + storms → hint; cyber and quakes are unmapped for
        // XLE but STILL candidates (the judge decides, never a filter).
        assertTrue(oil.contains("[hint:"), oil);
        assertTrue(storm.contains("[hint:"), storm);
        assertFalse(cyber.contains("[hint:"), cyber);
        assertFalse(quake.contains("[hint:"), quake);
    }

    @Test
    void survivorsReachTheLageShelfWithoutTheHint() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Test AG";
        m.ticker = "TST";
        m.worldSignalKeep = List.of(
                "US petroleum stocks (EIA weekly report, week ending 2026-07-10):"
                        + " commercial crude 421.3 million barrels (-3.1 week-over-week)",
                "Space weather (NOAA): scales R0/S0/G2 today, 3-day maximum G3"
                        + " (geomagnetic storms stress power grids and satellites)");
        String lage = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
        assertNotNull(lage);
        assertTrue(lage.contains("WORLD SIGNALS"), lage);
        assertTrue(lage.contains("421.3 million barrels"), lage);
        assertTrue(lage.contains("R0/S0/G2"), lage);
        assertFalse(lage.contains("[hint:"), lage);
        // No survivors → no block, no empty header.
        DeepDiveService.Material empty = new DeepDiveService.Material();
        empty.canonicalName = "Leer AG";
        String none = DeepDiveService.sectionMaterials(empty)[DeepDiveService.SEC_SITUATION];
        assertTrue(none == null || !none.contains("WORLD SIGNALS"));
    }

    @Test
    void quietSpaceWeatherAndValuesStayExplained() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Test AG";
        m.worldSignals = new WorldSignals(List.of(), null, null, null,
                new SpaceWxStat(0, 0, 0, 0), List.of(), List.of(), List.of(),
                null, List.of(), List.of(), null);
        assertEquals(List.of(), DeepDiveService.worldSignalCandidateLines(m));
        // Values carry unit + comparison (ROOT locale) — never naked.
        m.worldSignals = fullSignals();
        List<String> c = DeepDiveService.worldSignalCandidateLines(m);
        String choke = c.stream().filter(l -> l.contains("Suez")).findFirst().orElseThrow();
        assertTrue(choke.contains("32 vessel transits/day"), choke);
        assertTrue(choke.contains("-17.9% vs one week earlier"), choke);
    }
}
