package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * A venue that publishes order-book / turnover statistics ({@link VenueStats})
 * for an instrument identified by ISIN. Companion seam to {@link PriceSource}:
 * the price chain answers "what does it cost", this answers "what is trading" —
 * volume, executions, bid/ask depth. Consumers inject it optionally (like
 * {@code PriceSource}); a venue miss or an instrument the venue doesn't list is
 * an {@link Optional#empty()}, never an exception.
 */
public interface VenueStatsSource {

    /** Live venue statistics for the instrument with this ISIN, or empty. */
    Optional<VenueStats> statsByIsin(String isin);
}
