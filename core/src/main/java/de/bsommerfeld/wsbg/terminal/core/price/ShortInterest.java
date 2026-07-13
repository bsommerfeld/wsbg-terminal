package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * Disclosed net short positions for one issuer — the German Leerverkaufsregister
 * (Bundesanzeiger publishes every position ≥ 0.5 % of shares outstanding, with
 * the position holder's NAME). German issuers only; other markets simply resolve
 * to empty. Venue-neutral seam like the other {@code core.price} records.
 *
 * @param isin                  the issuer's ISIN (UPPER)
 * @param totalDisclosedPercent sum of all disclosed positions, in percent of
 *                              shares outstanding — the visible short interest
 *                              floor (positions below 0.5 % are invisible)
 * @param positions             the individual disclosed positions, largest first
 * @param fetchedAtEpochSeconds wall-clock second the register was read
 */
public record ShortInterest(
        String isin,
        double totalDisclosedPercent,
        List<ShortPosition> positions,
        long fetchedAtEpochSeconds) {

    /**
     * One disclosed net short position.
     *
     * @param holder  the position holder's name (e.g. "Citadel Advisors LLC")
     * @param percent net short position in percent of shares outstanding
     * @param dateIso the position date as reported (ISO {@code yyyy-MM-dd})
     */
    public record ShortPosition(String holder, double percent, String dateIso) {
    }
}
