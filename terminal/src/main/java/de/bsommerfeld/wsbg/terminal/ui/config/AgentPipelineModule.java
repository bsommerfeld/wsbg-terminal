package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator;
import de.bsommerfeld.wsbg.terminal.agent.EditorialPipeline;
import de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService;
import de.bsommerfeld.wsbg.terminal.core.price.PriceSource;
import de.bsommerfeld.wsbg.terminal.price.FallbackPriceSource;
import de.bsommerfeld.wsbg.terminal.ui.TimeTracker;

/**
 * The editorial pipeline quartet + the live price chain.
 *
 * <p><b>The eager-singleton ORDER here is load-bearing</b> and must stay in this
 * sequence (see CLAUDE.md): {@link EditorialPipeline} (its prep/compose/merge pools
 * must exist before any cluster change is submitted) → {@link AgentCoordinator}
 * (subscribes to ClusterRegistry changes before PassiveMonitorService emits them) →
 * {@link PassiveMonitorService} (starts the polling loop) → {@link TimeTracker}
 * (start/interval/stop checkpointing at boot). This whole quartet is kept in ONE
 * module so Guice instantiates them in this exact order; do not scatter it.
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
        bind(PassiveMonitorService.class).asEagerSingleton();
        // TimeTracker must be eager so it starts its start/interval/stop
        // checkpointing at boot; DonationStatsPublisher reads it for the
        // footer banner's reciprocity copy.
        bind(TimeTracker.class).asEagerSingleton();

        // The live price chain (EUR-first: L&S, then US fallback Yahoo).
        // Optionally injected into TickerResolver; Yahoo stays the search + news source.
        bind(PriceSource.class).to(FallbackPriceSource.class).in(Singleton.class);
    }
}
