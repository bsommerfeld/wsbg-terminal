package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hard smoke for the fact machinery's storage layer: torn and hostile JSONL
 * lines, volume (10k facts append + cold reload), concurrent appends from many
 * threads, a leftover tmp file from a crashed rewrite, unicode/CRLF payloads,
 * and a year × many-subjects sentiment fold — everything that would hurt in a
 * long-lived production archive.
 */
class DossierStressTest {

    @TempDir
    Path dir;

    private static DossierFact fact(String key, int i) {
        return new DossierFact(key, null, "Testwerk", "Fakt Nummer " + i + " über Testwerk.",
                1000 + i, "Titel " + i, "Blatt", "https://x/" + key + "/" + i, null, false);
    }

    @Test
    void tornAndHostileLinesAreSkippedWithoutPoisoningTheLoad() throws Exception {
        Path file = dir.resolve("d.jsonl");
        SubjectDossierArchive a = new SubjectDossierArchive(file);
        a.append(fact("TST", 1));
        a.append(fact("TST", 2));
        // Simulate a crash mid-append plus hostile garbage between valid lines.
        Files.writeString(file, "{\"subjectKey\":\"TST\",\"tex" + System.lineSeparator()
                        + "not json at all" + System.lineSeparator()
                        + "{\"unrelated\":true}" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        SubjectDossierArchive reloaded = new SubjectDossierArchive(file);
        assertEquals(2, reloaded.size(), "torn/hostile lines skip, valid facts survive");
        // The archive stays writable after the poison.
        assertTrue(reloaded.append(fact("TST", 3)));
        assertEquals(3, new SubjectDossierArchive(file).size());
    }

    @Test
    void tenThousandFactsAppendAndReload() {
        Path file = dir.resolve("big.jsonl");
        SubjectDossierArchive a = new SubjectDossierArchive(file);
        long t0 = System.nanoTime();
        for (int s = 0; s < 100; s++) {
            for (int i = 0; i < 100; i++) {
                a.append(fact("SUB" + s, i));
            }
        }
        long appendMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(10_000, a.size());
        t0 = System.nanoTime();
        SubjectDossierArchive reloaded = new SubjectDossierArchive(file);
        long loadMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(10_000, reloaded.size());
        assertEquals(100, reloaded.factCount("SUB42"));
        // Measurement, not assertion-by-feel: the full-load pattern must stay
        // interactive at 10k lines (an order of magnitude over a busy year).
        System.out.printf("[STRESS] 10k facts: append %d ms, cold load %d ms%n", appendMs, loadMs);
        assertTrue(loadMs < 5_000, "cold load of 10k facts must stay well under 5s");
    }

    @Test
    void concurrentAppendsFromEightThreadsLoseNothing() throws Exception {
        Path file = dir.resolve("conc.jsonl");
        SubjectDossierArchive a = new SubjectDossierArchive(file);
        int threads = 8, perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    a.append(fact("T" + tid, i));
                    if (i % 50 == 0) a.bySubject("T" + tid, null); // readers race writers
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(threads * perThread, a.size());
        assertEquals(threads * perThread, new SubjectDossierArchive(file).size(),
                "every concurrent append must be on disk");
    }

    @Test
    void rewriteSurvivesALeftoverTmpFileAndKeepsOtherSubjectsIntact() throws Exception {
        Path file = dir.resolve("rw.jsonl");
        // A crashed earlier rewrite left its tmp file behind — must not confuse anything.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SubjectDossierArchive.FILE_NAME + ".tmp"), "half a line",
                StandardCharsets.UTF_8);
        SubjectDossierArchive a = new SubjectDossierArchive(file);
        for (int i = 0; i < 50; i++) a.append(fact("TST", i));
        for (int i = 0; i < 50; i++) a.append(fact("OTHER", i));
        DossierFact summary = new DossierFact("TST", null, "Testwerk",
                "Sammelstand über 50 Fakten.", 2000, null, null, null, null, true);
        List<DossierFact> replacement = new ArrayList<>();
        replacement.add(summary);
        for (int i = 40; i < 50; i++) replacement.add(fact("TST", i));
        assertTrue(a.rewriteSubject("TST", replacement));
        assertEquals(11, a.factCount("TST"));
        assertEquals(50, a.factCount("OTHER"));
        SubjectDossierArchive reloaded = new SubjectDossierArchive(file);
        assertEquals(61, reloaded.size());
        assertTrue(reloaded.bySubject("TST", null).get(0).consolidated());
    }

    @Test
    void unicodeCrlfAndLongTextsRoundTrip() {
        Path file = dir.resolve("uni.jsonl");
        SubjectDossierArchive a = new SubjectDossierArchive(file);
        String hostile = "Zäöüß 💎🙌 «quotes» \"escaped\" \t tab, CR\r\nLF und 25 %-Marge — fertig.";
        String longText = "x".repeat(8_000);
        a.append(new DossierFact("TST", "DE000BASF111", "BASF SE ✓", hostile, 1,
                "Titel \"mit\" Quotes", "Blatt & Söhne", "https://x/u1", 5L, false));
        a.append(new DossierFact("TST", null, "Testwerk", longText, 2,
                null, null, "https://x/u2", null, false));
        List<DossierFact> back = new SubjectDossierArchive(file).bySubject("TST", null);
        assertEquals(2, back.size());
        assertEquals(hostile, back.get(0).text(), "hostile unicode/CRLF text must round-trip exactly");
        assertEquals(longText, back.get(1).text());
        assertEquals("DE000BASF111", back.get(0).isin());
    }

    @Test
    void aYearOfSentimentAcrossFiftySubjectsFoldsFastAndIdempotently() {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        LocalDate today = LocalDate.of(2026, 8, 14);
        HeadlineSentiment[] moods = HeadlineSentiment.values();
        List<HeadlineRecord> headlines = new ArrayList<>();
        LocalDate day = today.minusDays(365);
        int n = 0;
        while (day.isBefore(today)) {
            for (int s = 0; s < 50; s++) {
                if ((s + day.getDayOfYear()) % 7 != 0) continue; // sparse, like reality
                headlines.add(new HeadlineRecord("SUB" + s, "Zeile " + n, "",
                        day.atTime(9 + (n % 8), 0).atZone(zone).toEpochSecond(),
                        List.of(), List.of(), HeadlineHighlight.NORMAL, "SUB" + s,
                        List.of(new HeadlineSubject("Subjekt " + s, "SUB" + s)),
                        null, List.of(), null, moods[n++ % moods.length], null, false, List.of()));
            }
            day = day.plusDays(1);
        }
        SubjectSentimentDailyArchive archive =
                new SubjectSentimentDailyArchive(dir.resolve("sent.jsonl"));
        long t0 = System.nanoTime();
        int appended = SentimentDailyFolder.foldMissing(headlines, archive, zone, today);
        long foldMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(appended > 2_000, "a year × 50 subjects must produce thousands of day-sheets");
        assertEquals(0, SentimentDailyFolder.foldMissing(headlines, archive, zone, today),
                "the re-fold appends nothing");
        System.out.printf("[STRESS] %d headlines → %d day-sheets in %d ms%n",
                headlines.size(), appended, foldMs);
        assertTrue(foldMs < 5_000, "the yearly fold must stay well under 5s");
        assertEquals(appended, new SubjectSentimentDailyArchive(dir.resolve("sent.jsonl")).size());
    }
}
