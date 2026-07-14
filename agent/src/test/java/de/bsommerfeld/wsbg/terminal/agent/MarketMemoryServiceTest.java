package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.FnRssClient;
import de.bsommerfeld.wsbg.terminal.db.AdhocEventArchive;
import de.bsommerfeld.wsbg.terminal.db.FearGreedDayRecord;
import de.bsommerfeld.wsbg.terminal.db.FearGreedHistoryArchive;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The market-memory harvests, network-free: the ad-hoc sweep is idempotent
 * across polls, the Fear&amp;Greed top-up back-fills from the series floor,
 * resumes from the cursor, and never freezes today's still-moving value.
 */
class MarketMemoryServiceTest {

    @TempDir
    java.nio.file.Path dir;

    private MarketMemoryService service(AdhocEventArchive adhocs, FearGreedHistoryArchive fg) {
        return new MarketMemoryService(adhocs, fg,
                new de.bsommerfeld.wsbg.terminal.db.MarketEventArchive(dir.resolve("events.jsonl")), null);
    }

    // --- Fear&Greed fetch cursor ---

    @Test
    void emptyArchiveBackfillsFromTheSeriesFloor() {
        assertEquals(LocalDate.of(2020, 9, 21),
                MarketMemoryService.fearGreedFetchStart(Optional.empty(), LocalDate.of(2026, 7, 14)));
    }

    @Test
    void laggingArchiveResumesTheDayAfterTheCursor() {
        assertEquals(LocalDate.of(2026, 7, 10),
                MarketMemoryService.fearGreedFetchStart(Optional.of("2026-07-09"), LocalDate.of(2026, 7, 14)));
    }

    @Test
    void archiveCurrentThroughYesterdaySkipsTheFetch() {
        assertNull(MarketMemoryService.fearGreedFetchStart(Optional.of("2026-07-13"), LocalDate.of(2026, 7, 14)));
        assertNull(MarketMemoryService.fearGreedFetchStart(Optional.of("2026-07-14"), LocalDate.of(2026, 7, 14)));
    }

    @Test
    void unparseableCursorFallsBackToTheFloor() {
        assertEquals(LocalDate.of(2020, 9, 21),
                MarketMemoryService.fearGreedFetchStart(Optional.of("garbage"), LocalDate.of(2026, 7, 14)));
    }

    // --- harvests against the real archives (temp files, stubbed clients) ---

    @Test
    void adhocSweepArchivesOnceAcrossRepeatedPolls() {
        AdhocEventArchive adhocs = new AdhocEventArchive(dir.resolve("adhocs.jsonl"));
        MarketMemoryService svc = service(adhocs, new FearGreedHistoryArchive(dir.resolve("fg.jsonl")));
        svc.setFnRssClient(new FnRssClient() {
            @Override
            public List<AdhocItem> adhocs(int limit) {
                return List.of(
                        new AdhocItem("SAP senkt Prognose", "DE0007164600",
                                Instant.parse("2026-07-14T18:02:00Z"), "https://fn.de/1"),
                        new AdhocItem("Ohne Zeitstempel", "DE0007164600", null, "https://fn.de/2"));
            }
        });
        svc.harvestAdhocs();
        svc.harvestAdhocs();
        assertEquals(1, adhocs.size());
        assertEquals("SAP senkt Prognose", adhocs.recent(1).get(0).title());
    }

    @Test
    void fearGreedTopUpWritesOnlySettledDays() {
        FearGreedHistoryArchive fg = new FearGreedHistoryArchive(dir.resolve("fg.jsonl"));
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        MarketMemoryService svc = service(new AdhocEventArchive(dir.resolve("adhocs.jsonl")), fg);
        svc.setFearGreedClient(new FearGreedClient() {
            @Override
            public List<DailyScore> historySince(LocalDate since) {
                return List.of(
                        new DailyScore(today.minusDays(2), 24.1, "extreme fear"),
                        new DailyScore(today.minusDays(1), 31.7, "fear"),
                        new DailyScore(today, 55.0, "neutral"));
            }
        });
        svc.topUpFearGreedHistory();
        assertEquals(2, fg.size());
        assertEquals(today.minusDays(1).toString(), fg.latestDate().orElseThrow());
        assertEquals(Optional.empty(), fg.byDate(today.toString()).map(FearGreedDayRecord::date));

        // Current through yesterday now — the next tick must not fetch at all.
        svc.setFearGreedClient(new FearGreedClient() {
            @Override
            public List<DailyScore> historySince(LocalDate since) {
                throw new AssertionError("must not fetch when current");
            }
        });
        svc.topUpFearGreedHistory();
        assertEquals(2, fg.size());
    }

    // --- deterministic mapping helpers ---

    @Test
    void actionTypesMapToCleanClassesOnly() {
        assertEquals("DOWNGRADE", MarketMemoryService.actionClass("Downgrade"));
        assertEquals("UPGRADE", MarketMemoryService.actionClass("Upgrade"));
        assertEquals("INITIATION", MarketMemoryService.actionClass("Initiated Coverage"));
        assertNull(MarketMemoryService.actionClass("Target Raised"));
        assertNull(MarketMemoryService.actionClass(null));
    }

    @Test
    void surpriseSignDecidesTheClass() {
        assertEquals("EARNINGS_BEAT", MarketMemoryService.surpriseClass(4.2));
        assertEquals("EARNINGS_MISS", MarketMemoryService.surpriseClass(-1.0));
        assertNull(MarketMemoryService.surpriseClass(0.0));
        assertNull(MarketMemoryService.surpriseClass(Double.NaN));
    }

    @Test
    void afterCloseDisclosureLandsOnTheNextSession() {
        // 18:02 Berlin (16:02Z in July) is after the XETRA close → next day.
        assertEquals("2026-07-15", MarketMemoryService.effectiveEventDate("2026-07-14T16:02:00Z"));
        // 14:00 Berlin is intraday → same day.
        assertEquals("2026-07-14", MarketMemoryService.effectiveEventDate("2026-07-14T12:00:00Z"));
        assertNull(MarketMemoryService.effectiveEventDate("garbage"));
    }

    @Test
    void benchmarkFollowsTheListingShape() {
        assertEquals("^GDAXI", MarketMemoryService.benchmarkFor("RHM.DE"));
        assertEquals("^GSPC", MarketMemoryService.benchmarkFor("AAPL"));
        assertEquals("^GSPC", MarketMemoryService.benchmarkFor(null));
        // An index measures itself RAW — subtracting it from itself would be zero.
        assertNull(MarketMemoryService.benchmarkFor("^GSPC"));
        assertNull(MarketMemoryService.benchmarkFor("^GDAXI"));
    }

    @Test
    void macroSurpriseClassNeedsGroupAndSign() {
        assertEquals("INFLATION_UEBER_PROGNOSE",
                MarketMemoryService.macroSurpriseClass("INFLATION", 0.4, 0.3));
        assertEquals("ARBEITSMARKT_UNTER_PROGNOSE",
                MarketMemoryService.macroSurpriseClass("ARBEITSMARKT", 150.0, 180.0));
        assertNull(MarketMemoryService.macroSurpriseClass("INFLATION", 0.3, 0.3)); // in line
        assertNull(MarketMemoryService.macroSurpriseClass("SONSTIGES", 1.0, 0.5));
        assertNull(MarketMemoryService.macroSurpriseClass(null, 1.0, 0.5));
        assertNull(MarketMemoryService.macroSurpriseClass("INFLATION", null, 0.5));
    }

    @Test
    void macroIndexCoversUsAndEuroZoneOnly() {
        assertEquals("^GSPC", MarketMemoryService.macroIndexFor("US"));
        assertEquals("^GDAXI", MarketMemoryService.macroIndexFor("DE"));
        assertEquals("^GDAXI", MarketMemoryService.macroIndexFor("EU"));
        assertNull(MarketMemoryService.macroIndexFor("JP"));
        assertNull(MarketMemoryService.macroIndexFor(null));
    }

    @Test
    void bandThresholdsMatchCnns() {
        assertEquals("EXTREME_FEAR", MarketMemoryService.bandOf(10));
        assertEquals("FEAR", MarketMemoryService.bandOf(30));
        assertEquals("NEUTRAL", MarketMemoryService.bandOf(50));
        assertEquals("GREED", MarketMemoryService.bandOf(70));
        assertEquals("EXTREME_GREED", MarketMemoryService.bandOf(90));
    }
}
