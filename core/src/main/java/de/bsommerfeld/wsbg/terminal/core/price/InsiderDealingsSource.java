package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.Optional;

/**
 * Directors'-Dealings lookup by ISIN (see {@link InsiderDealings}). Same seam
 * pattern as the other {@code core.price} sources: bound in production (BaFin),
 * optionally injected, absent in tests.
 */
public interface InsiderDealingsSource {

    /**
     * The reported manager transactions for {@code isin}, or empty when the
     * database could not be read or doesn't cover the issuer. An issuer with no
     * reported deals in the queried window returns a present record with an
     * empty deal list — "keine Insider-Meldungen" is a finding, not a miss.
     */
    Optional<InsiderDealings> byIsin(String isin);
}
