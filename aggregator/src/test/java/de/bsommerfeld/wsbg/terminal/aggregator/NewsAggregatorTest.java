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

    /**
     * The IDENTICAL article often arrives twice under different links — the
     * symbol query and the name query both hit it, and Google News mints a
     * fresh redirect per query. Same normalized title + publisher = same
     * story, first-seen wins (user mandate 2026-07-13 "identische News dedupen").
     */
    @Test
    void identicalStoryUnderDifferentLinksDedupsByTitleAndPublisher() {
        Instant t = Instant.parse("2026-07-13T10:00:00Z");
        RawNewsItem viaSymbol = item("g-1", "Outlook Therapeutics Aktie: FDA-Entscheid am 29. Juli", t);
        RawNewsItem viaName = item("g-2", "Outlook  Therapeutics Aktie: FDA-Entscheid am 29. Juli ", t);
        NewsSource google = new NewsSource() {
            @Override public String sourceName() { return "google"; }
            @Override public List<RawNewsItem> newsFor(String symbol, int limit) {
                return List.of(viaSymbol);
            }
            @Override public List<RawNewsItem> newsForName(String name, int limit) {
                return List.of(viaName);
            }
        };

        List<RawNewsItem> out = aggregator(google).newsFor("OTLK", "Outlook Therapeutics", 10);

        assertEquals(1, out.size(), "identical story must ride once");
        assertEquals("g-1", out.get(0).uuid(), "first-seen occurrence wins");
    }

    /** A fixed-list SOCIAL source (forum/social chatter, not press). */
    private static NewsSource socialSource(String name, RawNewsItem... items) {
        return new NewsSource() {
            @Override public String sourceName() { return name; }
            @Override public boolean socialSentiment() { return true; }
            @Override public List<RawNewsItem> newsFor(String symbol, int limit) {
                return List.of(items);
            }
        };
    }

    /**
     * Press and sentiment stay strictly apart (2026-07-16): a forum post must
     * never masquerade as an article in the press loom, and the sentiment fan
     * must never surface articles.
     */
    @Test
    void socialSourcesRideTheSentimentFanNeverTheNewsFan() {
        Instant older = Instant.parse("2026-07-16T09:00:00Z");
        Instant newer = Instant.parse("2026-07-16T12:00:00Z");
        NewsSource press = source("press", false, item("p1", "Rheinmetall hebt Prognose", newer));
        NewsSource forum = socialSource("forum",
                item("f1", "RHM läuft heute", older), item("f2", "jetzt long", newer));

        NewsAggregator agg = aggregator(press, forum);

        List<RawNewsItem> news = agg.newsFor("RHM", null, null, 10);
        assertEquals(1, news.size(), "the news fan sees ONLY press sources");
        assertEquals("p1", news.get(0).uuid());

        List<RawNewsItem> sentiment = agg.sentimentFor("RHM", null, null, 10);
        assertEquals(2, sentiment.size(), "the sentiment fan sees ONLY social sources");
        assertEquals("f2", sentiment.get(0).uuid(), "sentiment orders by pure recency");
        assertTrue(sentiment.stream().noneMatch(i -> i.uuid().equals("p1")));

        assertTrue(agg.sentimentFor(null, null, null, 10).isEmpty());
        assertTrue(agg.sentimentFor("RHM", null, null, 0).isEmpty());
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
