package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * A provider of the analyst action trail ({@link AnalystActions}) by ticker —
 * the WHO/WHEN/FROM/TO rating history that the consensus-snapshot seam
 * ({@link AnalystViewSource}) compresses away, plus the US short-interest
 * facts with percent of float. Consumers inject it optionally; an unknown or
 * un-routable symbol and a provider outage are an {@link Optional#empty()},
 * never an exception.
 */
public interface AnalystActionsSource {

    /**
     * The action trail for this ticker. Accepts the bare US shape
     * ({@code "AAPL"}, {@code "BRK.B"}) and the venue-suffixed German/London
     * forms ({@code "RHM.DE"}, {@code "RR.L"}); any other shape (index
     * {@code ^…}, crypto pair, future, unknown suffix) resolves to empty
     * without a lookup.
     */
    Optional<AnalystActions> actionsFor(String symbol);

    /**
     * The day's full ratings table for one country filter (provider-specific;
     * MarketBeat's is ~490 rows for {@code "US"}) — the Abendausgabe's
     * "who moved a target today" leg. Sources without a daily table keep this
     * default no-op.
     */
    default java.util.List<AnalystActions.Action> todaysActions(String country) {
        return java.util.List.of();
    }
}
