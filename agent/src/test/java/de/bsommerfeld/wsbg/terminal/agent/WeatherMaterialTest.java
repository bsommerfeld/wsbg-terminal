package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.AdhocStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoverStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PutCallStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RateStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RoomPulse;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentStat;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic material formatters: frozen stats → labelled blocks the
 * evening passes read. Empty legs must vanish entirely (no headers for the
 * model to hallucinate under), numbers must render German-formatted.
 */
class WeatherMaterialTest {

    @Test
    void emptyLegsProduceNoBlocks() {
        assertEquals("", WeatherMaterial.marketsBlock(List.of()));
        assertEquals("", WeatherMaterial.adhocBlock(List.of()));
        assertEquals("", WeatherMaterial.sentimentBlock(null, null));
        assertEquals("", WeatherMaterial.colourBlock(null));
    }

    @Test
    void ratesBlockCarriesPriorAndDate() {
        String block = WeatherMaterial.ratesBlock(List.of(
                new RateStat("10J Bund", 3.13, 3.17, "2026-07-10")));
        assertTrue(block.contains("10J Bund 3,13 %"), block);
        assertTrue(block.contains("prior 3,17 %"), block);
        assertTrue(block.contains("2026-07-10"), block);
    }

    @Test
    void sentimentBlockCombinesGaugesAndPutCall() {
        String block = WeatherMaterial.sentimentBlock(
                new SentimentStat(62, "GREED", 58, List.of(), 71, "GREED"),
                new PutCallStat(0.81, 0.55, 1.01, "2026-07-10"));
        assertTrue(block.contains("US Fear & Greed 62 (greed), prior day 58"), block);
        assertTrue(block.contains("Crypto Fear & Greed 71"), block);
        assertTrue(block.contains("equity 0,55"), block);
    }

    @Test
    void adhocBlockMarksCagePapers() {
        String block = WeatherMaterial.adhocBlock(List.of(
                new AdhocStat("Covestro AG: EBITDA-Prognose angehoben", "DE0006062144",
                        "10:57", "1COV")));
        assertTrue(block.contains("Covestro"), block);
        assertTrue(block.contains("discussed in the room today: 1COV"), block);
    }

    @Test
    void moversBlockGroupsKindsAndMarksOverlap() {
        String block = WeatherMaterial.moversBlock(List.of(
                new MoverStat("OPEN", "Opendoor", 42.6, 2.61, "GAINER", true),
                new MoverStat("XYZ", "Xyz Corp", -18.0, 5.0, "LOSER", false)));
        assertTrue(block.contains("gainers: Opendoor +42,6 % [also discussed in the room]"), block);
        assertTrue(block.contains("losers: Xyz Corp -18,0 %"), block);
    }

    @Test
    void sectorsBlockNamesStrongestAndWeakest() {
        String block = WeatherMaterial.sectorsBlock(List.of(
                new IndexStat("Tech", "XLK", 200.0, 1.8, null, "USD", null),
                new IndexStat("Energie", "XLE", 90.0, 0.4, null, "USD", null),
                new IndexStat("Immobilien", "XLRE", 40.0, -1.2, null, "USD", null)));
        assertTrue(block.contains("strongest Tech +1,8 %"), block);
        assertTrue(block.contains("Immobilien -1,2 %"), block);
    }

    @Test
    void compactRendersGermanMagnitudes() {
        assertEquals("39,4 Bio", WeatherMaterial.compact(39_414_179_016_130L));
        assertEquals("3,4 Mio", WeatherMaterial.compact(3_400_000L));
    }

    @Test
    void pulseAggregatesSentimentRedAndBusiestHour() {
        ZoneId utc = ZoneOffset.UTC;
        long tenUtc = 1783850400L;   // 2026-07-13 10:00 UTC
        RoomPulse pulse = WeatherStatsCollector.pulse(List.of(
                headline(tenUtc, HeadlineSentiment.BULLISH, HeadlineHighlight.IMPORTANT, "NVDA"),
                headline(tenUtc + 60, HeadlineSentiment.CAPITULATION, HeadlineHighlight.NORMAL, "RHM"),
                headline(tenUtc + 7200, HeadlineSentiment.NEUTRAL, HeadlineHighlight.NORMAL, null)),
                utc);
        assertEquals(1, pulse.bullish());
        assertEquals(1, pulse.bearish());
        assertEquals(1, pulse.neutral());
        assertEquals(1, pulse.redCount());
        assertEquals(10, pulse.busiestHour());
        assertEquals(3, pulse.distinctSubjects());
    }

    private static HeadlineRecord headline(long createdAt, HeadlineSentiment sentiment,
            HeadlineHighlight highlight, String ticker) {
        return new HeadlineRecord("c" + createdAt, "text", null, createdAt, List.of(), List.of(),
                highlight, ticker, List.of(), null, List.of(), null, sentiment, null,
                false, List.of());
    }
}
