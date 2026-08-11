package de.bsommerfeld.wsbg.terminal.web.impl;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import de.bsommerfeld.wsbg.terminal.web.fetch.Transport;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.gateway.WebGateway;
import de.bsommerfeld.wsbg.terminal.web.impl.gateway.HouseWebGateway;
import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import de.bsommerfeld.wsbg.terminal.web.impl.pool.InMemoryArticlePool;
import de.bsommerfeld.wsbg.terminal.web.impl.schedule.RandomIntervalScheduler;
import de.bsommerfeld.wsbg.terminal.web.pool.ArticlePool;
import de.bsommerfeld.wsbg.terminal.web.schedule.CollectorScheduler;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;

/**
 * web-impl's own wiring: fetcher, pool, scheduler, gateway — everything that
 * is the same in every environment. The environment (terminal bootstrap)
 * layers on top what only it knows: the BROWSER transport (it owns the
 * embedded Chromium), the {@code FactsSources} roster, the
 * {@code InstrumentRegister}, and the source registrations into the
 * multibinders opened here.
 */
public final class WebModule extends AbstractModule {

    @Override
    protected void configure() {
        // Transports: DIRECT ships here; BROWSER joins from the terminal.
        Multibinder<Transport> transports = Multibinder.newSetBinder(binder(), Transport.class);
        transports.addBinding().to(DirectTransport.class);
        bind(DirectTransport.class).asEagerSingleton();

        bind(WebFetcher.class).to(HouseFetcher.class);
        bind(ArticlePool.class).to(InMemoryArticlePool.class);
        bind(CollectorScheduler.class).to(RandomIntervalScheduler.class);
        bind(WebGateway.class).to(HouseWebGateway.class);

        // Open the source multibinders so registration modules can contribute
        // even when a set stays empty.
        Multibinder.newSetBinder(binder(), CollectorSource.class);
        Multibinder.newSetBinder(binder(), SearchEngine.class);
        Multibinder.newSetBinder(binder(), InstrumentSource.class);
        Multibinder.newSetBinder(binder(),
                de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource.class);
    }
}
