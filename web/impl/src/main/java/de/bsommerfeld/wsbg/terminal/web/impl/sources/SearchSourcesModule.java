package de.bsommerfeld.wsbg.terminal.web.impl.sources;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.edgar.EdgarFullTextSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.googlenews.GoogleNewsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.googlenews.GoogleNewsWorldSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.websearch.BingWebSearchSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.websearch.GdeltDocSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.websearch.GdeltWorldSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance.YahooFinanceSource;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;

/**
 * Registers the search-shaped sources: the engines that answer a free
 * research query (Google News home, GDELT's German and world legs, Bing's
 * search RSS, Yahoo's search endpoint) and the instrument doors the same
 * backends carry (plus the Google News world editions and EDGAR's full-text
 * index, which are instrument-only). The EDGAR service clients beside them
 * ({@code EdgarClient}, {@code EdgarFactsClient}, {@code EdgarInsiderClient})
 * need no line here — they are plain {@code @Singleton}s Guice resolves on
 * demand, not multibound sources.
 */
public final class SearchSourcesModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<SearchEngine> engines =
                Multibinder.newSetBinder(binder(), SearchEngine.class);
        engines.addBinding().to(GoogleNewsSource.class);
        engines.addBinding().to(GdeltDocSource.class);
        engines.addBinding().to(GdeltWorldSource.class);
        engines.addBinding().to(BingWebSearchSource.class);
        engines.addBinding().to(YahooFinanceSource.class);

        Multibinder<InstrumentSource> instruments =
                Multibinder.newSetBinder(binder(), InstrumentSource.class);
        instruments.addBinding().to(GoogleNewsSource.class);
        instruments.addBinding().to(GoogleNewsWorldSource.class);
        instruments.addBinding().to(GdeltDocSource.class);
        instruments.addBinding().to(GdeltWorldSource.class);
        instruments.addBinding().to(EdgarFullTextSource.class);
        instruments.addBinding().to(YahooFinanceSource.class);

        Multibinder<ArchiveSource> archives =
                Multibinder.newSetBinder(binder(), ArchiveSource.class);
        archives.addBinding().to(GoogleNewsSource.class);
        archives.addBinding().to(GoogleNewsWorldSource.class);
        archives.addBinding().to(GdeltDocSource.class);
        archives.addBinding().to(GdeltWorldSource.class);
    }
}
