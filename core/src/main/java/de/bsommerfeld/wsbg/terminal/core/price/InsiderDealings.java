package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * Directors' Dealings for one issuer — manager transactions the issuer must
 * report (§ 19 MAR), published by BaFin with price and aggregated volume.
 * German-supervised issuers only; other markets resolve to empty.
 *
 * @param isin                  the issuer's ISIN (UPPER)
 * @param deals                 reported transactions, newest first
 * @param fetchedAtEpochSeconds wall-clock second the database was read
 */
public record InsiderDealings(
        String isin,
        List<InsiderDeal> deals,
        long fetchedAtEpochSeconds) {

    /**
     * One reported manager transaction.
     *
     * @param person         the reporting person or entity
     * @param positionStatus the person's relation ("Vorstand", "Aufsichtsrat",
     *                       "in enger Beziehung", …) as reported
     * @param instrumentType instrument kind ("Aktie", "Schuldtitel", …)
     * @param dealType       "Kauf" / "Verkauf" (verbatim from the register)
     * @param avgPrice       average price of the transaction, NaN unknown
     * @param currency       price currency (e.g. "EUR"), null unknown
     * @param volumeEur      aggregated transaction volume, NaN unknown
     * @param dealDateIso    trade date (ISO {@code yyyy-MM-dd}), null unknown
     * @param notifiedDateIso notification date (ISO), null unknown
     * @param venue          execution venue as reported, null unknown
     */
    public record InsiderDeal(String person, String positionStatus,
            String instrumentType, String dealType,
            double avgPrice, String currency, double volumeEur,
            String dealDateIso, String notifiedDateIso, String venue) {
    }
}
