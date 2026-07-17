package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSubject;
import de.bsommerfeld.wsbg.terminal.db.SignalValue;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The desk is pure adapter: archive lines in, kernel readings out. Fixtures
 * are synthetic {@link HeadlineRecord}s - the desk never talks to a source.
 */
class SignalDeskTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static HeadlineRecord line(String clusterId, String headline, long createdAt,
            String ticker, HeadlineSentiment sentiment, List<HeadlineSubject> subjects) {
        return new HeadlineRecord(clusterId, headline, null, createdAt,
                List.of(), List.of(), null, ticker, subjects, null, List.of(), null,
                sentiment, null);
    }

    private static long at(LocalDate day, int hour) {
        return day.atStartOfDay(ZONE).plusHours(hour).toEpochSecond();
    }

    @Test
    void mentionsBucketPerDayAndSubject() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        List<HeadlineRecord> archive = List.of(
                line("c1", "a", at(today, 9), "AAA", null,
                        List.of(new HeadlineSubject("Alpha", "AAA"))),
                line("c2", "b", at(today, 10), "AAA", null,
                        List.of(new HeadlineSubject("Alpha", "AAA"))),
                line("c3", "c", at(today, 11), "BBB", null,
                        List.of(new HeadlineSubject("Beta", "BBB"))),
                line("c4", "d", at(today.minusDays(1), 9), "CCC", null,
                        List.of(new HeadlineSubject("Gamma", "CCC"))));
        Map<String, Integer> today0 = SignalDesk.mentionsOn(archive, today, ZONE);
        assertEquals(2, today0.get("AAA"));
        assertEquals(1, today0.get("BBB"));
        assertFalse(today0.containsKey("CCC"));
    }

    @Test
    void noveltyReadsNewestAgainstOlderLines() {
        LocalDate day = LocalDate.of(2026, 7, 16);
        List<HeadlineRecord> wire = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            wire.add(line("c" + i, "quarterly numbers in line with expectations " + i,
                    at(day.minusDays(12 - i), 9), "AAA", null, List.of()));
        }
        wire.add(line("cx", "insolvency filing after failed refinancing",
                at(day, 10), "AAA", null, List.of()));
        Optional<SignalReading> reading = SignalDesk.novelty(wire);
        assertTrue(reading.isPresent());
        assertEquals("novelty-score", reading.get().id());
        assertTrue(reading.get().value() > 0.5);
    }

    @Test
    void storyHalfLifeUsesClusterSpansExcludingTheCurrentStory() {
        LocalDate day = LocalDate.of(2026, 7, 16);
        List<HeadlineRecord> wire = new ArrayList<>();
        for (int c = 0; c < 9; c++) {
            // nine finished stories, each living exactly two days
            wire.add(line("old" + c, "story open " + c, at(day.minusDays(40 - c * 3), 9),
                    "AAA", null, List.of()));
            wire.add(line("old" + c, "story close " + c, at(day.minusDays(38 - c * 3), 9),
                    "AAA", null, List.of()));
        }
        // the current story started 10 days ago - far beyond the 2-day norm
        wire.add(line("cur", "current story", at(day.minusDays(10), 9), "AAA", null, List.of()));
        wire.add(line("cur", "current story update",
                at(day, 8), "AAA", null, List.of()));
        Optional<SignalReading> reading = SignalDesk.storyHalfLife(wire,
                day.atStartOfDay(ZONE).plusHours(12).toInstant());
        assertTrue(reading.isPresent());
        assertEquals("story-half-life", reading.get().id());
        assertTrue(reading.get().value() > 0.9, "10d age vs 2d norm must read structural");
    }

    @Test
    void attentionEntropyComparesTodayWithYesterday() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        List<HeadlineRecord> archive = new ArrayList<>();
        // yesterday: attention spread over four names
        for (String t : List.of("AAA", "BBB", "CCC", "DDD")) {
            for (int i = 0; i < 3; i++) {
                archive.add(line("y" + t + i, "x", at(today.minusDays(1), 8 + i), t, null,
                        List.of(new HeadlineSubject(t, t))));
            }
        }
        // today: everyone stares at one name
        for (int i = 0; i < 10; i++) {
            archive.add(line("t" + i, "x", at(today, 8 + i % 8), "AAA", null,
                    List.of(new HeadlineSubject("Alpha", "AAA"))));
        }
        archive.add(line("tb", "x", at(today, 9), "BBB", null,
                List.of(new HeadlineSubject("Beta", "BBB"))));
        Optional<SignalReading> reading = SignalDesk.attentionEntropy(archive, today, ZONE);
        assertTrue(reading.isPresent());
        assertEquals("attention-entropy", reading.get().id());
        assertTrue(reading.get().interpretation().contains("ENTROPY COLLAPSE"));
        // one measurable past day is far below the context floor
        assertFalse(reading.get().interpretation().contains("Own-history context"));
    }

    @Test
    void entropyGetsOwnHistoryPercentileWithEnoughMeasurableDays() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        List<HeadlineRecord> archive = new ArrayList<>();
        // 15 past days, each fragmented over four names (high entropy)
        for (int back = 15; back >= 1; back--) {
            for (String t : List.of("AAA", "BBB", "CCC", "DDD")) {
                for (int i = 0; i < 3; i++) {
                    archive.add(line("h" + back + t + i, "x", at(today.minusDays(back), 8 + i),
                            t, null, List.of(new HeadlineSubject(t, t))));
                }
            }
        }
        // today: collapsed onto one name
        for (int i = 0; i < 11; i++) {
            archive.add(line("t" + i, "x", at(today, 8 + i % 8), "AAA", null,
                    List.of(new HeadlineSubject("Alpha", "AAA"))));
        }
        archive.add(line("tb", "x", at(today, 9), "BBB", null,
                List.of(new HeadlineSubject("Beta", "BBB"))));
        Optional<SignalReading> reading = SignalDesk.attentionEntropy(archive, today, ZONE);
        assertTrue(reading.isPresent());
        String text = reading.get().interpretation();
        assertTrue(text.contains("Own-history context"));
        assertTrue(text.contains("15 measurable days"));
        // today is more focused than every past day -> bottom percentile
        assertTrue(text.contains("percentile 0") || text.contains("percentile 1")
                || text.contains("percentile 2") || text.contains("percentile 3"));
    }

    @Test
    void sentimentDivergenceAlignsCageAndFearGreedByDate() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        List<HeadlineRecord> archive = new ArrayList<>();
        Map<String, Double> fg = new HashMap<>();
        for (int back = 25; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            // cage broadly bullish every day...
            archive.add(line("b" + back, "x", at(day, 9), "AAA",
                    HeadlineSentiment.BULLISH, List.of()));
            archive.add(line("b2" + back, "x", at(day, 10), "BBB",
                    back == 1 ? HeadlineSentiment.BULLISH : HeadlineSentiment.BEARISH, List.of()));
            // ...Wall street neutral, last day fearful
            fg.put(day.toString(), back == 1 ? 20.0 : 50.0);
        }
        Optional<SignalReading> reading =
                SignalDesk.sentimentDivergence(archive, fg, today, ZONE);
        assertTrue(reading.isPresent());
        assertEquals("sentiment-divergence", reading.get().id());
        assertTrue(reading.get().value() > 1.5, "cage greedy vs fearful street must diverge");
        String text = reading.get().interpretation();
        assertTrue(text.contains("Own-history context"));
        assertTrue(text.contains("unusually greedy"), "top-of-record divergence reads greedy");
    }

    @Test
    void entropySeriesIsChronologicalAndSkipsThinDays() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        List<HeadlineRecord> archive = new ArrayList<>();
        // 5 fragmented days, then a thin day (2 mentions - below the floor), then today collapsed
        for (int back = 6; back >= 2; back--) {
            for (String t : List.of("AAA", "BBB", "CCC", "DDD")) {
                for (int i = 0; i < 3; i++) {
                    archive.add(line("h" + back + t + i, "x", at(today.minusDays(back), 8 + i),
                            t, null, List.of(new HeadlineSubject(t, t))));
                }
            }
        }
        archive.add(line("thin", "x", at(today.minusDays(1), 9), "AAA", null,
                List.of(new HeadlineSubject("Alpha", "AAA"))));
        for (int i = 0; i < 10; i++) {
            archive.add(line("t" + i, "x", at(today, 8 + i % 8), "AAA", null,
                    List.of(new HeadlineSubject("Alpha", "AAA"))));
        }
        archive.add(line("tb", "x", at(today, 9), "BBB", null,
                List.of(new HeadlineSubject("Beta", "BBB"))));
        List<Double> series = SignalDesk.entropySeries(archive, today, ZONE, 60);
        assertEquals(6, series.size(), "5 measurable past days + today, thin day skipped");
        assertTrue(series.get(0) > 0.9, "fragmented day reads high");
        assertTrue(series.get(series.size() - 1) < 0.5, "today's collapse reads low");
    }

    @Test
    void divergenceSeriesPairsCageWithStreetPerDay() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        List<HeadlineRecord> archive = new ArrayList<>();
        Map<String, Double> fg = new HashMap<>();
        for (int back = 12; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            archive.add(line("b" + back, "x", at(day, 9), "AAA",
                    HeadlineSentiment.BULLISH, List.of()));
            archive.add(line("b2" + back, "x", at(day, 10), "BBB",
                    back == 1 ? HeadlineSentiment.BULLISH : HeadlineSentiment.BEARISH, List.of()));
            fg.put(day.toString(), back == 1 ? 20.0 : 50.0);
        }
        List<Double> series = SignalDesk.divergenceSeries(archive, fg, today, ZONE);
        assertEquals(12, series.size());
        assertTrue(Math.abs(series.get(0)) < 10,
                "balanced day vs neutral street stays near zero, was " + series.get(0));
        assertTrue(series.get(series.size() - 1) > 30,
                "record-bull day vs fearful street diverges wide, was "
                        + series.get(series.size() - 1));
    }

    @Test
    void cageMoodIndexReadsARecordHotDayAsGreed() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        List<HeadlineRecord> archive = new ArrayList<>();
        for (int back = 12; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            archive.add(line("b" + back, "x", at(day, 9), "AAA",
                    HeadlineSentiment.BULLISH, List.of()));
            archive.add(line("s" + back, "x", at(day, 10), "BBB",
                    HeadlineSentiment.BEARISH, List.of()));
        }
        // today: all-bull with FOMO heat - the cage's hottest day on record
        for (int i = 0; i < 4; i++) {
            archive.add(line("t" + i, "x", at(today, 9 + i), "AAA",
                    HeadlineSentiment.BULLISH, List.of()));
        }
        archive.add(line("tf1", "x", at(today, 13), "AAA", HeadlineSentiment.FOMO, List.of()));
        archive.add(line("tf2", "x", at(today, 14), "AAA", HeadlineSentiment.SQUEEZE, List.of()));
        Optional<SignalReading> reading = SignalDesk.cageMoodIndex(archive, today, ZONE);
        assertTrue(reading.isPresent());
        assertEquals("cage-mood-index", reading.get().id());
        assertTrue(reading.get().value() > 75, "record day must score high, was "
                + reading.get().value());
        assertTrue(reading.get().interpretation().contains("EXTREME GREED"));
        assertTrue(reading.get().interpretation().contains("direction"),
                "component breakdown named");

        // The series twin agrees with the live reading on today's point.
        Map<LocalDate, Double> byDay = SignalDesk.cageIndexByDay(archive, today, ZONE);
        assertEquals(reading.get().value(), byDay.get(today), 1e-9,
                "shared kernel formula: series and reading can never disagree");
    }

    @Test
    void valuesFreezeIdTitleAndNumber() {
        SignalReading r = new SignalReading("test-id", "Test title", 0.42, "0.42 (scale 0-1)",
                "d", "i");
        List<SignalValue> values = SignalDesk.values(List.of(r));
        assertEquals(1, values.size());
        assertEquals("test-id", values.get(0).id());
        assertEquals(0.42, values.get(0).value());
        assertEquals("0.42 (scale 0-1)", values.get(0).formattedValue());
        assertTrue(SignalDesk.values(List.of()).isEmpty());
    }

    @Test
    void returnsFromClosesArePercent() {
        double[] r = SignalDesk.returnsFromCloses(List.of(100.0, 110.0, 99.0));
        assertEquals(2, r.length);
        assertEquals(10.0, r[0], 1e-9);
        assertEquals(-10.0, r[1], 1e-9);
        assertEquals(0, SignalDesk.returnsFromCloses(null).length);
    }

    @Test
    void signalBlockRidesTheSituationShelf() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.signalReadings = List.of(new SignalReading("novelty-score", "Novelty vs own archive",
                0.9, "0.90 (scale 0-1)", "d", "REGIME-CHANGE CANDIDATE: x"));
        DeepDiveService.Shelf[] shelves = DeepDiveService.sectionShelves(m);
        int hits = 0;
        for (DeepDiveService.Shelf shelf : shelves) {
            if (shelf != null && shelf.combined() != null
                    && shelf.combined().contains("QUANT SIGNALS")) {
                hits++;
                assertTrue(shelf.combined().contains("REGIME-CHANGE CANDIDATE"));
                assertTrue(shelf.combined().contains("discipline:"));
            }
        }
        assertEquals(1, hits, "exactly one shelf carries the situation signals");
    }

    @Test
    void roomOnlySignalRidesTheRoomShelfNotTheSituation() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.signalReadings = List.of(new SignalReading("hawkes-endogeneity",
                "Hawkes endogeneity", 0.8, "0.80", "d", "ENDOGENOUS - self-igniting cascade"));
        DeepDiveService.Shelf[] shelves = DeepDiveService.sectionShelves(m);
        int hits = 0;
        for (DeepDiveService.Shelf shelf : shelves) {
            if (shelf != null && shelf.combined() != null
                    && shelf.combined().contains("ENDOGENOUS")) {
                hits++;
            }
        }
        assertEquals(1, hits);
    }

    @Test
    void hawkesReadsRoomEvidenceTimestamps() {
        SubjectUnit unit = new SubjectUnit("AAA", "Alpha");
        long base = Instant.parse("2026-07-16T08:00:00Z").getEpochSecond();
        // dense cascade: bursts of near-simultaneous mentions
        List<SubjectUnit.EvidenceRef> refs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            refs.add(new SubjectUnit.EvidenceRef("t" + i, "c" + i, "s", "reddit",
                    base + (i / 5) * 3600 + (i % 5) * 20));
        }
        SubjectUnit restored = new SubjectUnit(new SubjectUnit.Snapshot(
                "AAA", "Alpha", "AAA", null, base, base, null, null, null,
                refs, List.of(), List.of()));
        Optional<SignalReading> reading = SignalDesk.hawkes(List.of(restored));
        assertTrue(reading.isPresent());
        assertEquals("hawkes-endogeneity", reading.get().id());
    }

    // ---- world level: the frozen evening editions ARE the gauge table ----

    private static WeatherReportRecord.WorldSignals worldSignals(double chokeDelta,
            double crudeDelta, double harpex, double powerAvg, int g, int civic, int cyber,
            int conflicts, int policy) {
        List<WeatherReportRecord.PolicyStat> policyList = new ArrayList<>();
        for (int i = 0; i < policy; i++) {
            policyList.add(new WeatherReportRecord.PolicyStat("FED", "p" + i, null));
        }
        List<WeatherReportRecord.CivicStat> civicList = new ArrayList<>();
        for (int i = 0; i < civic; i++) {
            civicList.add(new WeatherReportRecord.CivicStat("blaulicht", "office", "c" + i, null));
        }
        List<WeatherReportRecord.CyberStat> cyberList = new ArrayList<>();
        for (int i = 0; i < cyber; i++) {
            cyberList.add(new WeatherReportRecord.CyberStat("CVE-" + i, "x", "2026-07-01"));
        }
        List<WeatherReportRecord.ConflictStat> conflictList = new ArrayList<>();
        for (int i = 0; i < conflicts; i++) {
            conflictList.add(new WeatherReportRecord.ConflictStat("X", "t", "s", null, null));
        }
        return new WeatherReportRecord.WorldSignals(
                List.of(new WeatherReportRecord.ChokepointStat("Suez", "2026-07-01", 50, chokeDelta)),
                new WeatherReportRecord.OilStockStat("2026-07-01", 400.0, crudeDelta,
                        350.0, 0.0, 220.0, 0.0, 120.0, 0.0),
                new WeatherReportRecord.FreightStat(harpex, harpex, "2026-07-01"),
                new WeatherReportRecord.PowerStat(powerAvg, powerAvg, powerAvg, powerAvg,
                        50.0, "wind"),
                new WeatherReportRecord.SpaceWxStat(0, 0, g, g),
                policyList, List.of(), civicList, null, cyberList, List.of(), null,
                conflictList);
    }

    private static WeatherReportRecord edition(LocalDate day,
            WeatherReportRecord.WorldSignals ws) {
        WeatherReportRecord.WorldStats world = new WeatherReportRecord.WorldStats(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, ws);
        return new WeatherReportRecord(day.toString(), 0L, "t", "de", 0, 0,
                List.of(), List.of(), List.of(), null, world, List.of(), List.of());
    }

    @Test
    void worldKernelsReadTheFrozenEditions() {
        LocalDate today = LocalDate.of(2026, 7, 17);
        List<WeatherReportRecord> editions = new ArrayList<>();
        for (int back = 45; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            // mild daily wobble; the chokepoint delta leads the harpex level
            double choke = Math.sin(back / 5.0) * 2;
            double harpex = 1000 + Math.sin((back + 3) / 5.0) * 20;
            editions.add(edition(day, worldSignals(choke, -1.2 + (back % 3), harpex,
                    80 + (back % 7), back % 3, 3 + (back % 4), back % 2,
                    1 + (back % 2), 2 + (back % 3))));
        }
        // a loud world day across every gauge
        WeatherReportRecord.WorldSignals today0 =
                worldSignals(9.0, -8.0, 1200, 240, 4, 30, 5, 6, 8);
        List<SignalReading> out = SignalDesk.forWorld(editions, today0, today);
        List<String> ids = out.stream().map(SignalReading::id).toList();
        assertTrue(ids.contains("world-anomaly-index"), ids.toString());
        assertTrue(ids.contains("supply-chain-lag"), ids.toString());
        assertTrue(ids.contains("ground-unrest-residual"), ids.toString());
    }

    @Test
    void thinWorldArchiveCostsReadingsNeverTheCaller() {
        assertTrue(SignalDesk.forWorld(List.of(), null, LocalDate.of(2026, 7, 17)).isEmpty());
        // a handful of editions is below every kernel's day floor
        List<WeatherReportRecord> few = new ArrayList<>();
        for (int back = 5; back >= 1; back--) {
            few.add(edition(LocalDate.of(2026, 7, 17).minusDays(back),
                    worldSignals(1, 1, 1000, 80, 0, 2, 0, 1, 2)));
        }
        assertTrue(SignalDesk.forWorld(few, worldSignals(1, 1, 1000, 80, 0, 2, 0, 1, 2),
                LocalDate.of(2026, 7, 17)).isEmpty());
    }
}
