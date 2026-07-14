package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * A provider of the US listing view ({@link UsListingStats}) by bare US ticker —
 * the American counterpart to the German-register seams ({@link ShortInterestSource},
 * {@link InsiderDealingsSource}): short interest, insider trades, institutional
 * ownership, analyst consensus and earnings surprises for a US-listed symbol.
 * Consumers inject it optionally; a non-US-shaped symbol, an unknown ticker or a
 * provider outage is an {@link Optional#empty()}, never an exception.
 */
public interface UsListingStatsSource {

    /**
     * The listing stats for this bare US ticker (e.g. {@code "OTLK"},
     * {@code "BRK.A"}), or empty. A symbol that isn't US-shaped (venue suffix,
     * index {@code ^…}, future {@code =F}, crypto pair) resolves to empty
     * without any lookup.
     */
    Optional<UsListingStats> statsFor(String symbol);
}
