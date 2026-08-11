package de.bsommerfeld.wsbg.terminal.web.impl.pool;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two clocks of the basin (user decision 2026-08-12): collector entries
 * STAY (the world wire is fetched consequently, not on demand), live entries
 * expire after five minutes so a later inquiry sees the world fresh.
 */
class LiveEntryTtlTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-08-12T10:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static final CollectorSource COLLECTOR = new CollectorSource() {
        @Override
        public String sourceName() {
            return "wire";
        }

        @Override
        public FetchUtil[] mode() {
            return new FetchUtil[] {FetchUtil.DIRECT};
        }

        @Override
        public FetchInterval interval() {
            return FetchInterval.DEFAULT;
        }

        @Override
        public List<Article> collect() {
            return List.of();
        }
    };

    private static final InstrumentSource LIVE = new InstrumentSource() {
        @Override
        public String sourceName() {
            return "live";
        }

        @Override
        public FetchUtil[] mode() {
            return new FetchUtil[] {FetchUtil.DIRECT};
        }

        @Override
        public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
            return List.of();
        }
    };

    private static Article article(String uuid) {
        return new Article(uuid, "Apple " + uuid, "P", "https://x.example/" + uuid,
                Instant.parse("2026-08-12T09:00:00Z"), List.of());
    }

    @Test
    void liveEntriesExpireCollectorEntriesStay() {
        TestClock clock = new TestClock();
        InMemoryArticlePool pool = new InMemoryArticlePool(clock);
        pool.add(COLLECTOR, List.of(article("wire-1")));
        pool.add(LIVE, List.of(article("live-1")));
        assertEquals(2, pool.recent(10).size());

        clock.now = clock.now.plus(Duration.ofMinutes(6));
        List<Article> after = pool.recent(10);
        assertEquals(List.of("wire-1"), after.stream().map(Article::uuid).toList(),
                "the live window passed; the collector entry stays");
    }

    @Test
    void withinTheWindowLiveEntriesAnswerFromRam() {
        TestClock clock = new TestClock();
        InMemoryArticlePool pool = new InMemoryArticlePool(clock);
        pool.add(LIVE, List.of(article("live-1")));
        clock.now = clock.now.plus(Duration.ofMinutes(4));
        assertEquals(1, pool.recent(10).size());
    }

    @Test
    void aCollectorConfirmationPromotesALiveEntry() {
        TestClock clock = new TestClock();
        InMemoryArticlePool pool = new InMemoryArticlePool(clock);
        pool.add(LIVE, List.of(article("story")));
        // The same story arrives on the wire — it earned durability.
        assertEquals(0, pool.add(COLLECTOR, List.of(article("story"))));
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertEquals(List.of("story"),
                pool.recent(10).stream().map(Article::uuid).toList());
    }
}
