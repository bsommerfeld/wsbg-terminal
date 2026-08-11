package de.bsommerfeld.wsbg.terminal.wienerboerse;

import java.time.LocalDate;
import java.util.List;

/**
 * One AT listing's daily history off the Vienna Stock Exchange's own CSV
 * export - the home venue's numbers, not an aggregator's. Depth is whatever
 * the caller asked for; the export reaches back roughly 26 years.
 *
 * <p>Missing numbers are {@link Double#NaN}, never zero - an absent turnover
 * is not a quiet day.
 *
 * @param pagePath the canonical instrument path on wienerborse.at
 *        ({@code /aktien-prime-market/<slug>-<ISIN>/}) as the site's own
 *        redirect resolved it - the market segment lives in this path
 * @param bars daily bars, OLDEST first
 */
public record WienerBoerseHistory(String isin, String pagePath, List<WienerBar> bars) {

    /**
     * One trading day as the exchange wrote it. {@code volumeShares} is the
     * day's Stueckumsatz (shares traded), {@code turnoverEur} the Geldumsatz
     * in euros - the export carries both.
     */
    public record WienerBar(LocalDate date, double open, double high, double low,
            double close, double volumeShares, double turnoverEur) {}
}
