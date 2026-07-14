package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * A venue that publishes a multi-level order book ({@link OrderBookSnapshot})
 * for an instrument identified by ISIN. Companion seam to
 * {@link VenueStatsSource}: that one carries top-of-book plus turnover, this
 * one the level-by-level resting interest. Consumers inject it optionally; an
 * unlisted instrument, a closed session (the book only exists during trading
 * hours) or any transport failure is an {@link Optional#empty()}, never an
 * exception.
 */
public interface OrderBookSource {

    /** The current visible book for the instrument with this ISIN, or empty. */
    Optional<OrderBookSnapshot> orderBookByIsin(String isin);
}
