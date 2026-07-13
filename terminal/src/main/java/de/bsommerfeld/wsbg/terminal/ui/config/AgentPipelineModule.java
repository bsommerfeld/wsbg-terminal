package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator;
import de.bsommerfeld.wsbg.terminal.agent.EditorialPipeline;
import de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService;
import de.bsommerfeld.wsbg.terminal.core.price.InstrumentLookup;
import de.bsommerfeld.wsbg.terminal.core.price.InstrumentFactsSource;
import de.bsommerfeld.wsbg.terminal.core.price.PriceSource;
import de.bsommerfeld.wsbg.terminal.core.price.VenueStatsSource;
import de.bsommerfeld.wsbg.terminal.langschwarz.LangSchwarzClient;
import de.bsommerfeld.wsbg.terminal.onvista.OnvistaClient;
import de.bsommerfeld.wsbg.terminal.price.FallbackPriceSource;
import de.bsommerfeld.wsbg.terminal.tradegate.TradegateQuoteClient;
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

        // The identity desk's venue candidate search: L&S typed search results
        // (STK/ETF/CUR/RES) feed the gemma4 identity judgment. Optionally injected
        // into EditorialAgent, which also arms the persistent verdict ledger.
        bind(InstrumentLookup.class).to(LangSchwarzClient.class).in(Singleton.class);

        // Venue depth stats (bid/ask with sizes, day volume, executions) by ISIN —
        // Tradegate, the one German venue publishing these keylessly. Optionally
        // injected into WatchlistService for the Marktdaten panel; L&S stays the
        // price/spark source (its chart endpoint carries no volume, probed 2026-07-10).
        bind(VenueStatsSource.class).to(TradegateQuoteClient.class).in(Singleton.class);

        // Company profile facts (sector, market cap, P/E, workforce, 30d average
        // volume) by ISIN — onvista's keyless stocks snapshot. Optionally injected
        // into WatchlistService for the Profil panel + the dossier brief.
        bind(InstrumentFactsSource.class).to(OnvistaClient.class).in(Singleton.class);

        // Analyst opinions (five-tier rating distribution + 3-month trend,
        // consensus EUR price target, upcoming earnings/dividend dates) by ISIN —
        // Consorsbank's keyless financial-info API. Optionally injected into
        // DeepDiveService for the KI-DD's Analysten layer.
        bind(de.bsommerfeld.wsbg.terminal.core.price.AnalystViewSource.class)
                .to(de.bsommerfeld.wsbg.terminal.consorsbank.ConsorsbankClient.class)
                .in(Singleton.class);

        // The deep company record (official website + portrait, key figures with
        // estimates to 2029, balance sheets, boards, TradingCentral chart read,
        // peers, volatility, index membership) — the SAME Consorsbank client,
        // one heavyweight on-demand call for the KI-DD report.
        bind(de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDiveSource.class)
                .to(de.bsommerfeld.wsbg.terminal.consorsbank.ConsorsbankClient.class)
                .in(Singleton.class);

        // Disclosed short positions ≥0.5% with the HOLDER'S NAME — the German
        // Leerverkaufsregister (Bundesanzeiger CSV, 2-request cookie flow).
        bind(de.bsommerfeld.wsbg.terminal.core.price.ShortInterestSource.class)
                .to(de.bsommerfeld.wsbg.terminal.bundesanzeiger.ShortInterestClient.class)
                .in(Singleton.class);

        // Directors' Dealings (§19 MAR): manager buys/sells with price and
        // aggregated EUR volume — BaFin's keyless database export.
        bind(de.bsommerfeld.wsbg.terminal.core.price.InsiderDealingsSource.class)
                .to(de.bsommerfeld.wsbg.terminal.bafin.InsiderDealingsClient.class)
                .in(Singleton.class);
    }
}
