package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * A provider of the hedge-fund popularity view ({@link HedgeFundPopularity})
 * by bare US ticker — the 13F "smart money" companion to the other US seam
 * ({@link UsListingStatsSource}). Consumers inject it optionally; a non-US-shaped
 * symbol, an unknown ticker or a provider outage is an {@link Optional#empty()},
 * never an exception.
 */
public interface HedgeFundPopularitySource {

    /**
     * The hedge-fund popularity curve for this bare US ticker (e.g.
     * {@code "OTLK"}, {@code "BRK.A"}), or empty. A symbol that isn't US-shaped
     * (venue suffix, index {@code ^…}, crypto pair) resolves to empty without
     * any lookup.
     */
    Optional<HedgeFundPopularity> popularityFor(String symbol);
}
