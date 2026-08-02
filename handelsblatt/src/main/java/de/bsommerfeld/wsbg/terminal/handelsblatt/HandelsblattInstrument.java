package de.bsommerfeld.wsbg.terminal.handelsblatt;

/**
 * The instrument master data Handelsblatt's market API returns for an ISIN
 * ({@code market.www.handelsblatt.com/api/isin/<ISIN>}, keyless, probed
 * 2026-08-02) - the ONE place in this publisher's stack where an ISIN maps onto
 * a ticker, a sector and a performance ladder in a single keyless call.
 *
 * <p>Not news, so deliberately not a {@code NewsSource}: this is reference data
 * for the deep-dive pipeline, fetched per instrument, never polled.
 *
 * <p>Fields the API leaves {@code null} stay {@code null} - the venue snapshot
 * often carries {@code last} without volume, bid or ask.
 *
 * @param name             the listing's name as the venue prints it
 * @param companyName      the issuer's name
 * @param isin             the queried ISIN
 * @param wkn              the German securities id
 * @param ticker           the venue ticker symbol
 * @param currency         quote currency
 * @param classification   instrument class ({@code Aktien}, …)
 * @param sector           the issuer's sector
 * @param country          the issuer's country name
 * @param marketplace      the venue the snapshot came from
 * @param description      the issuer profile text
 * @param last             last traded price
 * @param oneWeekPercent   performance, one week, in percent
 * @param oneMonthPercent  performance, one month
 * @param threeMonthPercent performance, three months
 * @param sixMonthPercent  performance, six months
 * @param oneYearPercent   performance, one year
 * @param fiveYearPercent  performance, five years
 * @param yearToDatePercent performance, year to date
 * @param eps              earnings per share, current
 * @param dividend         dividend per share, current
 * @param dividendYield    dividend yield in percent
 */
public record HandelsblattInstrument(
        String name,
        String companyName,
        String isin,
        String wkn,
        String ticker,
        String currency,
        String classification,
        String sector,
        String country,
        String marketplace,
        String description,
        Double last,
        Double oneWeekPercent,
        Double oneMonthPercent,
        Double threeMonthPercent,
        Double sixMonthPercent,
        Double oneYearPercent,
        Double fiveYearPercent,
        Double yearToDatePercent,
        Double eps,
        Double dividend,
        Double dividendYield) {
}
