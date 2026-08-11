package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator;
import de.bsommerfeld.wsbg.terminal.agent.EditorialPipeline;
import de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService;
import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.price.CorpusInstrumentRegister;
import de.bsommerfeld.wsbg.terminal.price.FallbackPriceSource;
import de.bsommerfeld.wsbg.terminal.ui.TimeTracker;
import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentLookup;
import de.bsommerfeld.wsbg.terminal.web.facts.PriceSource;
import de.bsommerfeld.wsbg.terminal.web.impl.gateway.FactsSources;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.bafin.BafinInsiderDealingsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.boersefrankfurt.BoerseFrankfurtOrderBookSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.bundesanzeiger.BundesanzeigerShortInterestSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.consorsbank.ConsorsbankSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.insidermonkey.InsiderMonkeySource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.langschwarz.LangSchwarzSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.marketbeat.MarketBeatSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.nasdaq.NasdaqCompanySource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista.OnvistaFactsSource;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.tradegate.TradegateQuoteSource;
import de.bsommerfeld.wsbg.terminal.web.instrument.InstrumentRegister;

/**
 * The editorial pipeline quartet + the facts world.
 *
 * <p><b>The eager-singleton ORDER here is load-bearing</b> and must stay in this
 * sequence (see CLAUDE.md): {@link EditorialPipeline} (its prep/compose/merge pools
 * must exist before any cluster change is submitted) → {@link AgentCoordinator}
 * (subscribes to ClusterRegistry changes before PassiveMonitorService emits them) →
 * {@link PassiveMonitorService} (starts the polling loop) → {@link TimeTracker}
 * (start/interval/stop checkpointing at boot). This whole quartet is kept in ONE
 * module so Guice instantiates them in this exact order; do not scatter it.
 *
 * <p>The facts sources of the old per-interface bindings are bundled into the
 * {@link FactsSources} roster: the gateway's third pipeline grazes them all in
 * parallel per inquiry, so "wired but no consumer" is finally over.
 */
final class AgentPipelineModule extends AbstractModule {

    @Override
    protected void configure() {
        // EditorialPipeline (#3) owns the prep/compose/merge pools that turn
        // changed clusters into headlines; eager so its pools are up before any
        // cluster change is submitted. AgentCoordinator must be eager so it
        // subscribes to ClusterRegistry changes before PassiveMonitorService
        // starts emitting them, and routes them into the pipeline.
        bind(EditorialPipeline.class).asEagerSingleton();
        bind(AgentCoordinator.class).asEagerSingleton();
        // The learned name memory is bound explicitly so the resolver's optional
        // injection finds it.
        bind(AliasStore.class).in(Singleton.class);
        bind(PassiveMonitorService.class).asEagerSingleton();
        // TimeTracker must be eager so it starts its start/interval/stop
        // checkpointing at boot; DonationStatsPublisher reads it for the
        // footer banner's reciprocity copy.
        bind(TimeTracker.class).asEagerSingleton();

        // The live price chain (EUR-first: L&S, then US fallback Yahoo).
        // Optionally injected into TickerResolver; Yahoo stays a search + news source.
        bind(PriceSource.class).to(FallbackPriceSource.class).in(Singleton.class);

        // The identity desk's venue candidate search: L&S typed search results
        // (STK/ETF/CUR/RES) feed the gemma4 identity judgment.
        bind(InstrumentLookup.class).to(LangSchwarzSource.class).in(Singleton.class);

        // The gateway's register door: whatever a caller typed resolves against
        // the local corpus into every key it can vouch for.
        bind(InstrumentRegister.class).to(CorpusInstrumentRegister.class).in(Singleton.class);
    }

    /**
     * The facts roster — which concrete source answers each block of the
     * gateway's facts graze. One place instead of ten bindings; a slot this
     * environment cannot serve stays null and shows as SKIPPED in the
     * inquiry's outcomes.
     */
    @Provides
    @Singleton
    FactsSources provideFactsSources(
            FallbackPriceSource price,
            TradegateQuoteSource venueStats,
            BoerseFrankfurtOrderBookSource orderBook,
            OnvistaFactsSource profile,
            ConsorsbankSource analystView,
            MarketBeatSource analystActions,
            BundesanzeigerShortInterestSource shortInterest,
            BafinInsiderDealingsSource insiderDealings,
            InsiderMonkeySource hedgeFunds,
            NasdaqCompanySource usListing) {
        return new FactsSources(price, venueStats, orderBook, profile, analystView,
                analystActions, shortInterest, insiderDealings, hedgeFunds, usListing);
    }
}
