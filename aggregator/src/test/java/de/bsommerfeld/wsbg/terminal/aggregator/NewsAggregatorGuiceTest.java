package de.bsommerfeld.wsbg.terminal.aggregator;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the exact injection shape {@code AppModule} relies on: a
 * {@code Multibinder<NewsSource>} feeding {@link NewsAggregator}'s
 * {@code Set<NewsSource>} constructor. This is the contract behind the live
 * wiring, exercised here without booting the full terminal module.
 */
class NewsAggregatorGuiceTest {

    private static final NewsSource STUB = new NewsSource() {
        @Override public String sourceName() { return "stub"; }
        @Override public List<RawNewsItem> newsFor(String symbol, int limit) {
            return List.of(new RawNewsItem("1", "hit for " + symbol, "pub",
                    "https://x/1", Instant.parse("2026-06-08T10:00:00Z"), List.of()));
        }
    };

    @Test
    void multibinderFeedsTheAggregator() {
        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                Multibinder.newSetBinder(binder(), NewsSource.class)
                        .addBinding().toInstance(STUB);
            }
        });

        NewsAggregator aggregator = injector.getInstance(NewsAggregator.class);
        assertNotNull(aggregator);

        List<RawNewsItem> out = aggregator.newsFor("NVDA", 5);
        assertEquals(1, out.size());
        assertEquals("hit for NVDA", out.getFirst().title());
    }

    @Test
    void emptyMultibinderInjectsAnEmptySet() {
        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                Multibinder.newSetBinder(binder(), NewsSource.class); // no bindings
            }
        });
        assertEquals(List.of(), injector.getInstance(NewsAggregator.class).newsFor("NVDA", 5));
    }
}
