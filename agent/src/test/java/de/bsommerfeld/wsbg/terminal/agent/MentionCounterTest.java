package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.MentionArchive;
import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.CorpusSource;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionCounterTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.parse("2026-08-04");

    @TempDir
    Path tmp;

    private MentionArchive archive;
    private AliasStore aliases;
    private MentionCounter counter;

    @BeforeEach
    void setUp() {
        archive = new MentionArchive(tmp.resolve("mentions"), ZONE);
        aliases = new AliasStore(tmp.resolve("aliases.jsonl"));
        counter = new MentionCounter(archive, aliases, corpus());
    }

    private InstrumentCorpus corpus() {
        InstrumentCorpus c = new InstrumentCorpus(tmp.resolve("instruments.jsonl"), List.of(new CorpusSource() {
            @Override
            public String name() {
                return "fixture";
            }

            @Override
            public List<InstrumentEntry> fetch() {
                return List.of(
                        new InstrumentEntry("DTE", "Deutsche Telekom AG", null, "XETR", "stock", "xetra"),
                        new InstrumentEntry("TSLA", "Tesla Inc", null, "US", "EQUITY", "sec"),
                        new InstrumentEntry("DBK", "Deutsche Bank AG", null, "XETR", "stock", "xetra"),
                        new InstrumentEntry("DPW", "Deutsche Post AG", null, "XETR", "stock", "xetra"),
                        new InstrumentEntry("DB1", "Deutsche Boerse AG", null, "XETR", "stock", "xetra"));
            }
        }));
        c.refresh();
        return c;
    }

    private static long epoch(int hour) {
        return ZonedDateTime.of(DAY.atTime(hour, 0), ZONE).toEpochSecond();
    }

    private static RedditThread thread(String id, String title, String body, int hour) {
        return new RedditThread(id, "wallstreetbetsGER", title, "ape", body, epoch(hour),
                "/r/x/" + id, 10, 0.9, 0);
    }

    private static RedditComment comment(String id, String body, int hour) {
        return new RedditComment(id, "t3_x", "t3_x", "ape", body, 1, epoch(hour), epoch(hour), epoch(hour));
    }

    @Test
    void postsAndCommentsBothFeedTheCount() {
        counter.onThread(thread("t3_a", "$TSLA zum Mond", "nachgekauft bei $TSLA", 9));
        counter.onComment(comment("t1_a", "$TSLA ist mir zu teuer", 10));

        List<MentionCounter.MentionRow> rows = counter.rows(DAY, DAY);
        assertEquals(1, rows.size());
        assertEquals("TSLA", rows.get(0).symbol());
        assertEquals(3, rows.get(0).mentions());
        assertTrue(rows.get(0).resolved());
    }

    @Test
    void spamCountsAsSpam() {
        counter.onComment(comment("t1_a", "$GME $GME $GME $GME $GME", 10));
        assertEquals(5, counter.rows(DAY, DAY).get(0).mentions());
    }

    @Test
    void aRescrapeOfTheSameCommentAddsNothing() {
        RedditComment c = comment("t1_a", "$TSLA", 10);
        counter.onComment(c);
        counter.onComment(c);
        assertEquals(1, counter.rows(DAY, DAY).get(0).mentions());
    }

    @Test
    void anItemIsBookedOnTheDayItWasWrittenNotTheDayItWasScraped() {
        counter.onComment(comment("t1_a", "$TSLA", 10));
        assertTrue(counter.rows(DAY.plusDays(1), DAY.plusDays(1)).isEmpty());
        assertFalse(counter.rows(DAY, DAY).isEmpty());
    }

    @Test
    void anUnlearnedShortFormStandsBesideTheTickerAndFoldsIntoItOnceLearned() {
        counter.onComment(comment("t1_a", "Telekom laeuft, $DTE auch", 10));

        List<MentionCounter.MentionRow> before = counter.rows(DAY, DAY);
        assertEquals(2, before.size(), "day one: the ticker and the spelling stand side by side");
        assertEquals("DTE", before.get(0).symbol());
        assertNull(before.get(1).symbol());
        assertEquals("telekom", before.get(1).label());

        // the resolver settles the subject during normal operation
        aliases.learn("Telekom", "DTE");

        List<MentionCounter.MentionRow> after = counter.rows(DAY, DAY);
        assertEquals(1, after.size(), "learned: the spelling folds onto the ticker");
        assertEquals("DTE", after.get(0).symbol());
        assertEquals(2, after.get(0).mentions());
        assertEquals("Deutsche Telekom AG", after.get(0).label());
    }

    @Test
    void whatWasLearnedRepairsTheStoredPastToo() {
        counter.onComment(comment("t1_old", "Telekom", 9));
        aliases.learn("Telekom", "DTE");
        counter.onComment(comment("t1_new", "Telekom", 11));

        List<MentionCounter.MentionRow> rows = counter.rows(DAY, DAY);
        assertEquals(1, rows.size());
        assertEquals("DTE", rows.get(0).symbol());
        assertEquals(2, rows.get(0).mentions(), "the day already booked is resolved on read, not rewritten");
    }

    @Test
    void aRegisteredNameCountsWithoutAnyTicker() {
        counter.onComment(comment("t1_a", "Deutsche Telekom AG hat Zahlen", 10));
        assertEquals("DTE", counter.rows(DAY, DAY).get(0).symbol());
    }

    @Test
    void proseThatNamesNothingCountsNothing() {
        counter.onComment(comment("t1_a", "Deutsche Aktien sind langweilig, ich gehe schlafen", 10));
        assertTrue(counter.rows(DAY, DAY).isEmpty());
    }

    @Test
    void aWindowFoldsSeveralDaysIntoOneRanking() {
        counter.onComment(comment("t1_a", "$TSLA", 10));
        counter.onComment(new RedditComment("t1_b", "t3_x", "t3_x", "ape", "$TSLA $DTE", 1,
                ZonedDateTime.of(DAY.minusDays(2).atTime(10, 0), ZONE).toEpochSecond(), 0, 0));

        List<MentionCounter.MentionRow> rows = counter.rows(DAY.minusDays(6), DAY);
        assertEquals(2, rows.size());
        assertEquals("TSLA", rows.get(0).symbol());
        assertEquals(2, rows.get(0).mentions());
        assertEquals(2, counter.days(DAY.minusDays(6), DAY).size());
    }

    @Test
    void withoutGroundTruthNothingIsInvented() {
        MentionCounter blind = new MentionCounter(archive, aliases, null);
        blind.onComment(comment("t1_a", "Deutsche Telekom AG laeuft", 10));
        assertTrue(blind.rows(DAY, DAY).isEmpty());
    }

    @Test
    void emptyTextIsSimplyNotBooked() {
        counter.onThread(thread("t3_a", "", null, 9));
        counter.onComment(comment("t1_a", "   ", 10));
        assertTrue(counter.rows(DAY, DAY).isEmpty());
        assertTrue(counter.earliestDay().isEmpty());
    }
}
