package de.bsommerfeld.wsbg.terminal.web.impl.sources;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.cnbc.CnbcSearchSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.fool.FoolNewsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.fool.FoolQuoteNewsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.nasdaq.NasdaqNewsRssSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista.OnvistaNewsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.stocknear.StocknearClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.tradingview.TradingViewMindsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.tradingview.TradingViewNewsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.tradingview.TradingViewSymbolSearch;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;

/**
 * The market-news migration group: the NEWS/article legs of the nasdaq,
 * tradingview, cnbc, fool, stocknear and onvista modules as web sources.
 * (The FACTS legs of the same old modules ride their own module; the plain
 * RSS legs — CNBC section feeds, foolwatch — ride the curated feed catalog.)
 */
public final class MarketNewsSourcesModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<InstrumentSource> instrumentSources =
                Multibinder.newSetBinder(binder(), InstrumentSource.class);
        instrumentSources.addBinding().to(NasdaqNewsRssSource.class);
        instrumentSources.addBinding().to(TradingViewNewsSource.class);
        instrumentSources.addBinding().to(TradingViewMindsSource.class);
        instrumentSources.addBinding().to(CnbcSearchSource.class);
        instrumentSources.addBinding().to(FoolNewsSource.class);
        instrumentSources.addBinding().to(FoolQuoteNewsSource.class);
        instrumentSources.addBinding().to(OnvistaNewsSource.class);

        Multibinder<SearchEngine> searchEngines =
                Multibinder.newSetBinder(binder(), SearchEngine.class);
        searchEngines.addBinding().to(CnbcSearchSource.class);

        Multibinder<ArchiveSource> archives =
                Multibinder.newSetBinder(binder(), ArchiveSource.class);
        archives.addBinding().to(CnbcSearchSource.class);

        // Resolver building block and the narrow Stocknear fallback client —
        // no article role, bound so they are reachable for direct injection.
        bind(TradingViewSymbolSearch.class);
        bind(StocknearClient.class);
    }
}
