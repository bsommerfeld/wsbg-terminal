package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live-probed maritime/energy shapes (fixtures fetched 2026-07-14):
 * IMF PortWatch's ArcGIS rows with the plain "YYYY-MM-DD" DateOnly field, the
 * EIA WPSR table1 CSV with quoted thousands-comma numbers and the second
 * STUB_1 header that ends the stock section, Harpex's HTML-escaped data-json
 * attribute, Energy-Charts' aligned series with null tails, SWPC's
 * relative-day keys, and PEGELONLINE's station net + current measurements.
 */
class MaritimeEnergyClientsTest {

    private static String fixture(String name) {
        try (InputStream in = MaritimeEnergyClientsTest.class.getResourceAsStream("/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- PortWatch ---------------------------------------------------------

    @Test
    void portWatchParsesArcGisRows() {
        List<PortWatchClient.ChokepointDay> rows =
                PortWatchClient.parse(fixture("portwatch-day.json"));
        assertEquals(5, rows.size());
        PortWatchClient.ChokepointDay bab = rows.get(0);
        assertEquals("Bab el-Mandeb Strait", bab.name());
        assertEquals(LocalDate.of(2026, 7, 12), bab.date());
        assertEquals(41, bab.nTotal());
        assertEquals(14, bab.nTanker());
        assertEquals(7, bab.nContainer());
        assertEquals(27, bab.nCargo());
        assertEquals(1366176L, bab.capacityTons());
    }

    @Test
    void portWatchLatestDayKeepsOnlyTheNewestDate() {
        List<PortWatchClient.ChokepointDay> rows =
                PortWatchClient.latestDay(PortWatchClient.parse(fixture("portwatch-day.json")));
        assertEquals(3, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.date().equals(LocalDate.of(2026, 7, 12))));
    }

    @Test
    void portWatchHistoryRowsParseNewestFirst() {
        List<PortWatchClient.ChokepointDay> rows =
                PortWatchClient.parse(fixture("portwatch-history.json"));
        assertEquals(5, rows.size());
        assertTrue(rows.stream().allMatch(r -> "Suez Canal".equals(r.name())));
        assertEquals(LocalDate.of(2026, 7, 12), rows.get(0).date());
        assertEquals(LocalDate.of(2026, 7, 8), rows.get(4).date());
        assertEquals(39, rows.get(0).nTotal());
        assertEquals(1325078L, rows.get(0).capacityTons());
    }

    @Test
    void portWatchGarbageAndArcGisErrorYieldEmpty() {
        assertTrue(PortWatchClient.parse(null).isEmpty());
        assertTrue(PortWatchClient.parse("<html>wall</html>").isEmpty());
        // ArcGIS answers HTTP 200 with an error object — no features, no rows.
        assertTrue(PortWatchClient.parse(
                "{\"error\":{\"code\":400,\"message\":\"Invalid query\"}}").isEmpty());
    }

    // ---- EIA WPSR ----------------------------------------------------------

    @Test
    void wpsrParsesSummaryLevelsAndDeltas() {
        Optional<EiaWpsrClient.WpsrSummary> parsed =
                EiaWpsrClient.parse(fixture("eia-wpsr-table1.csv"));
        assertTrue(parsed.isPresent());
        EiaWpsrClient.WpsrSummary s = parsed.get();
        assertEquals(LocalDate.of(2026, 7, 3), s.weekEnding());
        assertEquals(411.357, s.commercialCrudeMb());
        assertEquals(2.998, s.commercialCrudeDeltaMb());
        assertEquals(319.489, s.sprMb());
        assertEquals(-6.166, s.sprDeltaMb());
        assertEquals(212.062, s.gasolineMb());
        assertEquals(-1.904, s.gasolineDeltaMb());
        assertEquals(103.619, s.distillateMb());
        assertEquals(-4.980, s.distillateDeltaMb());
    }

    @Test
    void wpsrIgnoresTheSupplyDispositionSection() {
        // The fixture carries the second STUB_1 header + supply rows whose
        // thousands-scale figures must never bleed into the summary.
        Optional<EiaWpsrClient.WpsrSummary> parsed =
                EiaWpsrClient.parse(fixture("eia-wpsr-table1.csv"));
        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().commercialCrudeMb() < 1000);
    }

    @Test
    void wpsrGarbageYieldsEmpty() {
        assertTrue(EiaWpsrClient.parse(null).isEmpty());
        assertTrue(EiaWpsrClient.parse("<html>signin</html>").isEmpty());
        assertTrue(EiaWpsrClient.parse("no,quoted,cells").isEmpty());
    }

    // ---- Harpex ------------------------------------------------------------

    @Test
    void harpexExtractsAndUnescapesTheEmbeddedSeries() {
        List<HarpexClient.HarpexPoint> series =
                HarpexClient.parseSeries(fixture("harpex.html"));
        assertTrue(series.size() > 40, "a 12-month weekly series");
        assertEquals(LocalDate.of(2025, 7, 11), series.get(0).date());
        assertEquals(2164.15, series.get(0).value());
        HarpexClient.HarpexPoint last = series.get(series.size() - 1);
        assertEquals(LocalDate.of(2026, 7, 10), last.date());
        assertEquals(2337.11, last.value());
        // Chronological throughout.
        for (int i = 1; i < series.size(); i++) {
            assertTrue(series.get(i).date().isAfter(series.get(i - 1).date()));
        }
    }

    @Test
    void harpexUnescapeHandlesTheAttributeEntities() {
        assertEquals("{\"a\":1}", HarpexClient.unescapeHtml("{&quot;a&quot;:1}"));
        assertEquals("a&b", HarpexClient.unescapeHtml("a&amp;b"));
    }

    @Test
    void harpexGarbageYieldsEmpty() {
        assertTrue(HarpexClient.parseSeries(null).isEmpty());
        assertTrue(HarpexClient.parseSeries("<html>no attribute</html>").isEmpty());
        assertTrue(HarpexClient.parseSeries("data-json=\"not json\"").isEmpty());
    }

    // ---- Energy-Charts -----------------------------------------------------

    @Test
    void energyPriceStatsPickTheCurrentSlot() {
        // Fixture: 8 slots from 1783980000 step 900; prices 148.37 … 129.27.
        String body = fixture("energycharts-price.json");
        Optional<EnergyChartsClient.PriceStats> stats =
                EnergyChartsClient.parsePrice(body, 1783981800L + 100);
        assertTrue(stats.isPresent());
        assertEquals(137.07, stats.get().currentEurMwh()); // slot index 2
        assertEquals(129.27, stats.get().minEurMwh());
        assertEquals(148.37, stats.get().maxEurMwh());
        assertEquals(136.76375, stats.get().avgEurMwh(), 1e-9);
    }

    @Test
    void energyPriceCurrentClampsToTheDayEdges() {
        String body = fixture("energycharts-price.json");
        assertEquals(148.37, EnergyChartsClient.parsePrice(body, 1783980000L - 50)
                .orElseThrow().currentEurMwh()); // before the first slot
        assertEquals(129.27, EnergyChartsClient.parsePrice(body, 1783980000L + 86400)
                .orElseThrow().currentEurMwh()); // after the day ran out
    }

    @Test
    void energyMixReadsShareAndTopSourceAtTheFreshestSlot() {
        // Fixture tail: last slot's Load/share are null — index 2 is the
        // freshest published one; Wind onshore (9500.2 MW) tops it.
        Optional<EnergyChartsClient.PowerMix> mix =
                EnergyChartsClient.parseMix(fixture("energycharts-power.json"));
        assertTrue(mix.isPresent());
        assertEquals(49.0, mix.get().renewableSharePercent());
        assertEquals("Wind onshore", mix.get().topSource());
        assertEquals(9500.2, mix.get().topSourceMw());
        assertEquals(46925.8, mix.get().loadMw());
    }

    @Test
    void energyChartsGarbageYieldsEmpty() {
        assertTrue(EnergyChartsClient.parsePrice(null, 0).isEmpty());
        assertTrue(EnergyChartsClient.parsePrice("<html>wall</html>", 0).isEmpty());
        assertTrue(EnergyChartsClient.parseMix("{\"production_types\":[]}").isEmpty());
    }

    // ---- NOAA SWPC ---------------------------------------------------------

    @Test
    void swpcParsesTodayAndForecastMaxima() {
        Optional<SpaceWeatherClient.SpaceScales> parsed =
                SpaceWeatherClient.parse(fixture("swpc-noaa-scales.json"));
        assertTrue(parsed.isPresent());
        SpaceWeatherClient.SpaceScales s = parsed.get();
        assertEquals("2026-07-14", s.dateIso());
        assertEquals(0, s.r());
        assertEquals(0, s.s());
        assertEquals(0, s.g());
        assertEquals(0, s.forecastMaxG());
        assertEquals(10, s.forecastMaxRMinorProb());
        assertEquals(1, s.forecastMaxRMajorProb());
        assertEquals(1, s.forecastMaxSProb());
    }

    @Test
    void swpcQuietForecastWithNullScalesStaysUnknownNotZero() {
        // Forecast R/S carry probabilities, never a Scale — G alone forecasts a level.
        String body = """
                {"0": {"DateStamp": "2026-07-14", "R": {"Scale": "1"}, "S": {"Scale": null},
                       "G": {"Scale": "2"}},
                 "1": {"R": {"MinorProb": null}, "S": {"Prob": null}, "G": {"Scale": null}}}
                """;
        SpaceWeatherClient.SpaceScales s = SpaceWeatherClient.parse(body).orElseThrow();
        assertEquals(1, s.r());
        assertEquals(-1, s.s());
        assertEquals(2, s.g());
        assertEquals(-1, s.forecastMaxG());
        assertEquals(-1, s.forecastMaxRMinorProb());
    }

    @Test
    void swpcGarbageYieldsEmpty() {
        assertTrue(SpaceWeatherClient.parse(null).isEmpty());
        assertTrue(SpaceWeatherClient.parse("<html>wall</html>").isEmpty());
    }

    // ---- PEGELONLINE extension ---------------------------------------------

    @Test
    void pegelMeasurementParsesOffsetTimestampAndValue() {
        Optional<RhinePegelClient.Measurement> m =
                RhinePegelClient.parseMeasurement(fixture("pegel-current.json"));
        assertTrue(m.isPresent());
        assertEquals(620.0, m.get().value());
        assertEquals(Instant.parse("2026-07-14T21:15:00Z"), m.get().timestamp());
    }

    @Test
    void pegelStationsCarryTheirSeriesAndUnits() {
        List<RhinePegelClient.Station> stations =
                RhinePegelClient.parseStations(fixture("pegel-stations.json"));
        assertEquals(3, stations.size());
        RhinePegelClient.Station kaub = stations.stream()
                .filter(s -> "KAUB".equals(s.shortname())).findFirst().orElseThrow();
        assertEquals("RHEIN", kaub.water());
        assertTrue(kaub.series().stream().anyMatch(t ->
                "Q".equals(t.shortname()) && "m³/s".equals(t.unit())));
        assertTrue(kaub.series().stream().anyMatch(t ->
                "W".equals(t.shortname()) && "cm".equals(t.unit())));
        assertFalse(kaub.series().stream().anyMatch(t -> "WT".equals(t.shortname())));
        // One net station carries WT — the temperature surface's address space.
        assertTrue(stations.stream().anyMatch(s ->
                s.series().stream().anyMatch(t -> "WT".equals(t.shortname()))));
    }

    @Test
    void pegelGarbageYieldsEmpty() {
        assertTrue(RhinePegelClient.parseMeasurement(null).isEmpty());
        assertTrue(RhinePegelClient.parseMeasurement(
                "{\"status\":404,\"message\":\"Current measurement does not exist.\"}").isEmpty());
        assertTrue(RhinePegelClient.parseStations("{\"not\":\"an array\"}").isEmpty());
        assertTrue(RhinePegelClient.parseStations("<html>wall</html>").isEmpty());
    }
}
