package de.bsommerfeld.wsbg.terminal.web.impl.pool;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import de.bsommerfeld.wsbg.terminal.web.source.WebSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryArticlePoolTest {

    private static WebSource source(String name, boolean social) {
        return new WebSource() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public FetchUtil[] mode() {
                return new FetchUtil[] {FetchUtil.DIRECT};
            }

            @Override
            public SourceOrigin origin() {
                return SourceOrigin.of("en", "US");
            }

            @Override
            public boolean socialSentiment() {
                return social;
            }
        };
    }

    private static Article article(String uuid, String title, Instant at) {
        return new Article(uuid, title, "Pub", "https://x.example/" + uuid, at, List.of());
    }

    private final InMemoryArticlePool pool = new InMemoryArticlePool();
    private final WebSource press = source("wire", false);
    private final WebSource board = source("board", true);

    @Test
    void deduplicatesAcrossPours() {
        assertEquals(1, pool.add(press, List.of(article("a", "Apple up", Instant.now()))));
        assertEquals(0, pool.add(press, List.of(article("a", "Apple up", Instant.now()))));
        assertEquals(1, pool.size());
    }

    @Test
    void originIsStampedAtThePour() {
        pool.add(press, List.of(article("a", "Apple up", Instant.now())));
        Article stored = pool.recent(1).get(0);
        assertEquals("US", stored.origin().sphere());
    }

    @Test
    void pressAndSentimentStayApart() {
        pool.add(press, List.of(article("p", "Apple erhöht Prognose", Instant.now())));
        pool.add(board, List.of(article("s", "Apple to the moon", Instant.now())));

        ResolvedInstrument apple = new ResolvedInstrument(
                Optional.empty(), Optional.empty(), "Apple");
        assertEquals(List.of("p"),
                pool.queryInstrument(apple, 10).stream().map(Article::uuid).toList());
        assertEquals(List.of("s"),
                pool.querySentiment(apple, 10).stream().map(Article::uuid).toList());
        assertEquals(1, pool.recent(10).size());
    }

    @Test
    void instrumentMatchIsAdditiveAcrossKeys() {
        Instant now = Instant.now();
        pool.add(press, List.of(
                new Article("by-isin", "Ad-hoc: Kapitalerhöhung", "EQS",
                        "https://x.example/1", now, List.of(), "DE0007164600", null, false),
                new Article("by-ticker", "Chips rally", "Wire",
                        "https://x.example/2", now, List.of("SAP"), null, null, false),
                new Article("by-name", "SAP kauft Startup", "Wire",
                        "https://x.example/3", now, List.of(), null, null, false),
                new Article("unrelated", "Oil slides", "Wire",
                        "https://x.example/4", now, List.of(), null, null, false)));

        ResolvedInstrument sap = new ResolvedInstrument(
                Isin.parse("DE0007164600"), Ticker.parse("SAP"), "SAP SE");
        List<String> hits = pool.queryInstrument(sap, 10).stream().map(Article::uuid).toList();
        assertTrue(hits.containsAll(List.of("by-isin", "by-ticker", "by-name")));
        assertEquals(3, hits.size());
    }

    @Test
    void freeTextQueryNeedsEveryWord() {
        pool.add(press, List.of(
                article("q1", "Lithium prices in Chile jump", Instant.now()),
                article("q2", "Lithium demand steady", Instant.now())));
        assertEquals(List.of("q1"),
                pool.query("lithium chile", 10).stream().map(Article::uuid).toList());
    }

    @Test
    void installedTaggerAnswersInstrumentQueries() {
        java.util.List<Article> ingested = new java.util.ArrayList<>();
        java.util.List<String> forgotten = new java.util.ArrayList<>();
        // Poured BEFORE the tagger arrives — must be backfilled on install.
        pool.add(press, List.of(article("early", "Poured before wiring", Instant.now())));
        pool.setTagger(new de.bsommerfeld.wsbg.terminal.web.pool.ArticleTagger() {
            @Override
            public void ingest(java.util.Collection<Article> articles) {
                ingested.addAll(articles);
            }

            @Override
            public void forget(java.util.Collection<String> identities) {
                forgotten.addAll(identities);
            }

            @Override
            public java.util.Set<String> articlesFor(ResolvedInstrument instrument) {
                return java.util.Set.of("judged");
            }
        });
        pool.add(press, List.of(
                article("judged", "Growth story about the actual company", Instant.now()),
                article("noise", "Russian economy returns to growth", Instant.now())));

        // The backfill and the pour both reached the tagger…
        assertEquals(List.of("early", "judged", "noise"),
                ingested.stream().map(Article::uuid).toList());
        // …and the query answers from the judgment set, not from word guessing.
        assertEquals(List.of("judged"),
                pool.queryInstrument(ResolvedInstrument.ofName("Canopy Growth"), 10)
                        .stream().map(Article::uuid).toList());
        assertTrue(forgotten.isEmpty());
    }

    @Test
    void withoutTaggerTheFallbackNeedsTheFullName() {
        pool.add(press, List.of(
                article("hit", "Canopy Growth kündigt Kapitalerhöhung an", Instant.now()),
                article("noise", "Russian economy returns to growth", Instant.now())));
        assertEquals(List.of("hit"),
                pool.queryInstrument(ResolvedInstrument.ofName("Canopy Growth"), 10)
                        .stream().map(Article::uuid).toList());
    }

    @Test
    void freshestFirstAndCapped() {
        Instant now = Instant.now();
        pool.add(press, List.of(
                article("old", "Apple a", now.minusSeconds(600)),
                article("new", "Apple b", now)));
        List<Article> hits = pool.queryInstrument(
                ResolvedInstrument.ofName("Apple"), 1);
        assertEquals(List.of("new"), hits.stream().map(Article::uuid).toList());
    }
}
