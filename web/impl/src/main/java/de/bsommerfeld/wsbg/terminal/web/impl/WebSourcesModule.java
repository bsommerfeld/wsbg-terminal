package de.bsommerfeld.wsbg.terminal.web.impl;

import com.google.inject.AbstractModule;
import de.bsommerfeld.wsbg.terminal.web.impl.catalog.CatalogModule;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.BriefingSourcesModule;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.FeedSourcesModule;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.GermanSourcesModule;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.MarketNewsSourcesModule;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SearchSourcesModule;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SocialSourcesModule;

/**
 * The ONE install for the whole web world: core wiring (fetcher, pool,
 * scheduler, gateway), the curated catalog, and every hand-written source
 * group. The environment installs this and contributes what only it knows —
 * the BROWSER transport, the {@code FactsSources} roster, the
 * {@code InstrumentRegister}.
 */
public final class WebSourcesModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new WebModule());
        install(new CatalogModule());
        install(new FeedSourcesModule());
        install(new SocialSourcesModule());
        install(new SearchSourcesModule());
        install(new GermanSourcesModule());
        install(new MarketNewsSourcesModule());
        install(new BriefingSourcesModule());
    }
}
