package de.bsommerfeld.wsbg.terminal.web.impl.sources;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.bluesky.BlueskySource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.financialjuice.FinancialJuiceSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.hackernews.HackerNewsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.stocktwits.StocktwitsSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;

/**
 * Registers the social / community sources migrated from the old module
 * world: Stocktwits and Bluesky (instrument-addressed social sentiment) and
 * Hacker News (instrument source AND search engine in one class).
 *
 * <p>{@link FinancialJuiceSource} is deliberately NOT on the collector clock:
 * the terminal's FJ publisher polls it every 60 s (push-grade latency the
 * 2-minute scheduler floor cannot give a breaking wire) and pours the yield
 * into the basin itself — one poller, both consumers. The class is bound
 * plain so that publisher can inject it.
 */
public final class SocialSourcesModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<InstrumentSource> instruments =
                Multibinder.newSetBinder(binder(), InstrumentSource.class);
        instruments.addBinding().to(StocktwitsSource.class);
        instruments.addBinding().to(BlueskySource.class);
        instruments.addBinding().to(HackerNewsSource.class);

        Multibinder<SearchEngine> engines =
                Multibinder.newSetBinder(binder(), SearchEngine.class);
        engines.addBinding().to(HackerNewsSource.class);

        bind(FinancialJuiceSource.class);
    }
}
