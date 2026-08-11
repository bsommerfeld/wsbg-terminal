package de.bsommerfeld.wsbg.terminal.web.impl.gateway;

import de.bsommerfeld.wsbg.terminal.web.facts.AnalystActionsSource;
import de.bsommerfeld.wsbg.terminal.web.facts.AnalystViewSource;
import de.bsommerfeld.wsbg.terminal.web.facts.HedgeFundPopularitySource;
import de.bsommerfeld.wsbg.terminal.web.facts.InsiderDealingsSource;
import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentFactsSource;
import de.bsommerfeld.wsbg.terminal.web.facts.OrderBookSource;
import de.bsommerfeld.wsbg.terminal.web.facts.PriceSource;
import de.bsommerfeld.wsbg.terminal.web.facts.ShortInterestSource;
import de.bsommerfeld.wsbg.terminal.web.facts.UsListingStatsSource;
import de.bsommerfeld.wsbg.terminal.web.facts.VenueStatsSource;

/**
 * The facts pipeline's roster: which concrete source answers each block.
 * Any slot may be {@code null} — an environment without that capability
 * simply skips the block, and the skip shows in the inquiry's outcomes.
 * Wired once at bootstrap, never consulted per-source anywhere else.
 */
public record FactsSources(
        PriceSource price,
        VenueStatsSource venueStats,
        OrderBookSource orderBook,
        InstrumentFactsSource profile,
        AnalystViewSource analystView,
        AnalystActionsSource analystActions,
        ShortInterestSource shortInterest,
        InsiderDealingsSource insiderDealings,
        HedgeFundPopularitySource hedgeFunds,
        UsListingStatsSource usListing) {

    /** No facts capability at all — the gateway still answers, articles only. */
    public static final FactsSources NONE = new FactsSources(
            null, null, null, null, null, null, null, null, null, null);
}
