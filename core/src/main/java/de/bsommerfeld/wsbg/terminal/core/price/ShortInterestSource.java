package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * Short-interest lookup by ISIN (see {@link ShortInterest}). Same seam pattern
 * as {@link VenueStatsSource}: bound in production (Bundesanzeiger), optionally
 * injected, absent in tests. An issuer with no disclosed positions answers a
 * present value with an empty position list ONLY when the register was actually
 * read — an unreadable register answers empty.
 */
public interface ShortInterestSource {

    /**
     * The disclosed short positions for {@code isin}, or empty when the register
     * could not be read or the ISIN is not a German issuer the register covers.
     * A covered issuer nobody currently shorts returns a present record with
     * zero positions — "niemand meldepflichtig short" is a finding, not a miss.
     */
    Optional<ShortInterest> byIsin(String isin);
}
