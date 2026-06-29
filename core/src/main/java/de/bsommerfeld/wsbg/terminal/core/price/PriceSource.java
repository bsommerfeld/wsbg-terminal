package de.bsommerfeld.wsbg.terminal.core.price;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;

import java.util.Optional;

/**
 * A live price provider for one instrument. The editorial resolver consults a
 * single {@code PriceSource} for every snapshot; in production that's a fallback
 * chain (Lang &amp; Schwarz → Deutsche Börse → NASDAQ → Yahoo) assembled in the
 * composition root, so the resolver itself stays source-agnostic.
 *
 * <p>Implementations return {@link Optional#empty()} when they have nothing for
 * the ref (unknown instrument, venue down, no fresh quote) — never throw. The
 * returned snapshot is normalised to EUR; freshness is read from its
 * {@link MarketSnapshot#marketTimeEpochSeconds()}.
 */
public interface PriceSource {

    /** A live EUR snapshot for the instrument, or empty if this source has nothing. */
    Optional<MarketSnapshot> snapshot(PriceRef ref);

    /** Short identifier for logging. */
    String name();
}
