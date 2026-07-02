package de.bsommerfeld.wsbg.terminal.aggregator;

import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsAggregatorTest {

    private static RawNewsItem item(String uuid, String title, Instant at) {
        return new RawNewsItem(uuid, title, "pub", "https://x/" + uuid, at, List.of());
    }

    /** A fixed-list news source; optionally throwing to test resilience. */
    private static NewsSource source(String name, boolean explode, RawNewsItem... items) {
        return new NewsSource() {
            @Override public String sourceName() { return name; }
            @Override public List<RawNewsItem> newsFor(String symbol, int limit) {
                if (explode) throw new RuntimeException("boom");
                return List.of(items);
            }
        };
    }

    private static NewsAggregator aggregator(NewsSource... sources) {
        Set<NewsSource> set = new LinkedHashSet<>(List.of(sources));
        return new NewsAggregator(set);
    }

    @Test
    void mergesAcrossSourcesAndDedupsById() {
        Instant t = Instant.parse("2026-06-08T10:00:00Z");
        NewsSource a = source("a", false, item("1", "shared", t), item("2", "only-a", t));
        NewsSource b = source("b", false, item("1", "shared-dup", t), item("3", "only-b", t));

        List<RawNewsItem> out = aggregator(a, b).newsFor("NVDA", 10);

        assertEquals(3, out.size(), "uuid 1 must appear once");
        // first occurrence (source a) wins the dedup
        assertTrue(out.stream().anyMatch(i -> i.uuid().equals("1") && i.title().equals("shared")));
    }

    @Test
    void ordersNewestFirstNullsLast() {
        RawNewsItem older = item("o", "older", Instant.parse("2026-06-01T00:00:00Z"));
        RawNewsItem newer = item("n", "newer", Instant.parse("2026-06-08T00:00:00Z"));
        RawNewsItem undated = item("u", "undated", null);

        List<RawNewsItem> out = aggregator(source("a", false, older, undated, newer))
                .newsFor("NVDA", 10);

        assertEquals(List.of("n", "o", "u"), out.stream().map(RawNewsItem::uuid).toList());
    }

    @Test
    void respectsLimit() {
        Instant t = Instant.parse("2026-06-08T10:00:00Z");
        NewsSource a = source("a", false,
                item("1", "a", t), item("2", "b", t), item("3", "c", t));
        assertEquals(2, aggregator(a).newsFor("NVDA", 2).size());
    }

    @Test
    void toleratesAThrowingSource() {
        Instant t = Instant.parse("2026-06-08T10:00:00Z");
        NewsSource bad = source("bad", true);
        NewsSource good = source("good", false, item("1", "ok", t));

        List<RawNewsItem> out = aggregator(bad, good).newsFor("NVDA", 10);
        assertEquals(1, out.size());
        assertEquals("ok", out.getFirst().title());
    }

    @Test
    void emptyOnBlankSymbolOrNonPositiveLimit() {
        NewsSource a = source("a", false, item("1", "x", Instant.now()));
        assertTrue(aggregator(a).newsFor("  ", 10).isEmpty());
        assertTrue(aggregator(a).newsFor("NVDA", 0).isEmpty());
    }

    @Test
    void noSourcesYieldsEmpty() {
        assertTrue(aggregator().newsFor("NVDA", 10).isEmpty());
    }

    // ---- name-addressed fan-out + relevance tiering ----

    /** A source that only answers the NAME query (like wallstreet-online). */
    private static NewsSource nameSource(String name, RawNewsItem... items) {
        return new NewsSource() {
            @Override public String sourceName() { return name; }
            @Override public List<RawNewsItem> newsFor(String symbol, int limit) { return List.of(); }
            @Override public List<RawNewsItem> newsForName(String companyName, int limit) {
                return List.of(items);
            }
        };
    }

    @Test
    void nameQueryReachesNameAddressedSources() {
        Instant t = Instant.now();
        NewsSource yahooish = source("yahoo", false, item("y1", "Symbol news", t));
        NewsSource wsoish = nameSource("wso", item("w1", "Meta Wolf AG: CERAM TECH", t));

        List<RawNewsItem> out = aggregator(yahooish, wsoish).newsFor("WOLF.DE", "Meta Wolf AG", 10);
        assertEquals(2, out.size(), "both the symbol and the name fan contribute");
        assertTrue(out.stream().anyMatch(i -> i.uuid().equals("w1")));
    }

    @Test
    void rankingPrefersFreshTitleNamedOverFreshOverStale() {
        Instant fresh = Instant.now().minusSeconds(3600);
        Instant stale = Instant.now().minusSeconds(7L * 24 * 3600);
        RawNewsItem freshNamed = item("fn", "Rheinmetall gewinnt Großauftrag", fresh);
        RawNewsItem freshOther = item("fo", "Rüstungssektor im Aufwind", fresh.plusSeconds(600));
        RawNewsItem staleNamed = item("sn", "Rheinmetall Rückblick", stale);

        List<RawNewsItem> out = aggregator(source("a", false, freshOther, staleNamed, freshNamed))
                .newsFor("RHM.DE", "Rheinmetall", 10);

        assertEquals(List.of("fn", "fo", "sn"), out.stream().map(RawNewsItem::uuid).toList(),
                "fresh+named beats fresh beats stale — despite freshOther being newer");
    }

    @Test
    void nameOnlyQueryWorksWithoutASymbol() {
        NewsSource wsoish = nameSource("wso", item("w1", "Meta Wolf AG: CERAM TECH", Instant.now()));
        assertEquals(1, aggregator(wsoish).newsFor(null, "Meta Wolf AG", 5).size());
    }
}
