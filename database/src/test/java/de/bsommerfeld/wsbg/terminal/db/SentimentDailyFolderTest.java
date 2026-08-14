package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily sentiment fold: headlines bucket per (complete day, subject key),
 * the running day is never folded, majority/arc are deterministic, and the
 * archive's identity makes re-folding idempotent.
 */
class SentimentDailyFolderTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @TempDir
    Path dir;

    private static HeadlineRecord headline(String day, int hour, HeadlineSentiment sentiment,
            String subjectName, String ticker) {
        long at = LocalDate.parse(day).atTime(hour, 0).atZone(ZONE).toEpochSecond();
        return new HeadlineRecord("TST", "Zeile um " + hour + " Uhr", "", at,
                List.of(), List.of(), HeadlineHighlight.NORMAL, ticker,
                List.of(new HeadlineSubject(subjectName, ticker)),
                null, List.of(), null, sentiment, null, false, List.of());
    }

    @Test
    void foldsCompleteDaysWithMajorityAndArcAndSkipsToday() {
        List<HeadlineRecord> headlines = List.of(
                headline("2026-08-13", 9, HeadlineSentiment.BULLISH, "Testwerk AG", "TST.DE"),
                headline("2026-08-13", 12, HeadlineSentiment.BULLISH, "Testwerk AG", "TST.DE"),
                headline("2026-08-13", 18, HeadlineSentiment.BEARISH, "Testwerk AG", "TST.DE"),
                headline("2026-08-14", 9, HeadlineSentiment.FOMO, "Testwerk AG", "TST.DE"));

        List<SubjectSentimentDayRecord> sheets = SentimentDailyFolder.fold(headlines, ZONE, TODAY);
        assertEquals(1, sheets.size(), "the running day must not fold");
        SubjectSentimentDayRecord d = sheets.get(0);
        assertEquals("2026-08-13", d.date());
        assertEquals("TST.DE", d.subjectKey());
        assertEquals(3, d.headlineCount());
        assertEquals("BULLISH", d.majority());
        assertEquals("BULLISH → BEARISH", d.arc());
        assertEquals(2, d.sentimentCounts().get("BULLISH"));
    }

    @Test
    void tickerlessSubjectsFoldUnderTheNameKeyConvention() {
        List<SubjectSentimentDayRecord> sheets = SentimentDailyFolder.fold(List.of(
                headline("2026-08-13", 9, HeadlineSentiment.BEARISH, "Zölle", null)), ZONE, TODAY);
        assertEquals(1, sheets.size());
        assertEquals("name:zölle", sheets.get(0).subjectKey());
    }

    @Test
    void foldMissingIsIdempotentAgainstTheArchive() {
        SubjectSentimentDailyArchive archive =
                new SubjectSentimentDailyArchive(dir.resolve("s.jsonl"));
        List<HeadlineRecord> headlines = List.of(
                headline("2026-08-13", 9, HeadlineSentiment.BULLISH, "Testwerk AG", "TST.DE"));
        assertEquals(1, SentimentDailyFolder.foldMissing(headlines, archive, ZONE, TODAY));
        assertEquals(0, SentimentDailyFolder.foldMissing(headlines, archive, ZONE, TODAY),
                "a re-fold appends nothing");
        assertTrue(archive.has("2026-08-13", "TST.DE"));
        // Survives a reload, and bySubject returns it oldest-first.
        SubjectSentimentDailyArchive reloaded =
                new SubjectSentimentDailyArchive(dir.resolve("s.jsonl"));
        assertEquals(1, reloaded.bySubject("TST.DE").size());
    }
}
