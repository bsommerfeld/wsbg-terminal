package de.bsommerfeld.wsbg.terminal.nyse;

import java.time.LocalDate;
import java.util.List;

/**
 * One symbol's home-tape reading off the NYSE website's own quote service -
 * the exchange's numbers, not an aggregator's. {@code exchange} is the
 * listing's tier as the service names it ({@code NYSE}, {@code NASD}, ...):
 * the endpoint answers for Nasdaq listings too, so this is the US home tape,
 * not one venue's.
 *
 * <p>Missing numbers are {@link Double#NaN} (sizes/volumes {@code -1}), never
 * zero - a zero bid is a market statement, an absent one is not.
 *
 * @param history daily bars, OLDEST first, roughly five years deep - the only
 *        keyless venue-native OHLCV series a US listing has in this house
 * @param options the listed options chain, empty when the name has none
 */
public record NyseTape(String symbol, String name, String exchange,
        double last, double changePercent,
        double bid, double ask, long bidSize, long askSize,
        double open, double dayHigh, double dayLow, double previousClose,
        long volume, long averageVolume,
        double yearHigh, double yearLow,
        double dividend, double dividendYieldPercent, double beta, double eps,
        List<DailyBar> history, List<OptionContract> options) {

    /** Pre-options call sites keep compiling; they simply carry no chain. */
    public NyseTape(String symbol, String name, String exchange,
            double last, double changePercent,
            double bid, double ask, long bidSize, long askSize,
            double open, double dayHigh, double dayLow, double previousClose,
            long volume, long averageVolume,
            double yearHigh, double yearLow,
            double dividend, double dividendYieldPercent, double beta, double eps,
            List<DailyBar> history) {
        this(symbol, name, exchange, last, changePercent, bid, ask, bidSize, askSize,
                open, dayHigh, dayLow, previousClose, volume, averageVolume,
                yearHigh, yearLow, dividend, dividendYieldPercent, beta, eps,
                history, List.of());
    }

    /** One trading day as the exchange wrote it. */
    public record DailyBar(LocalDate date, double open, double high, double low,
            double close, long volume) {}

    /**
     * One listed option contract as the exchange reports it. Today's
     * {@code volume} against the standing {@code openInterest} is the one
     * reading that separates positions being OPENED from positions being
     * closed - volume alone cannot tell them apart.
     *
     * <p>Open interest is the PREVIOUS session's figure by market convention;
     * volume is today's. Anything comparing the two carries that one-day
     * offset.
     *
     * @param call         true for a call, false for a put
     * @param expiry       the contract's expiry, null when unreadable
     * @param strike       the strike, {@link Double#NaN} when unreadable
     * @param volume       contracts traded today, {@code -1} unknown
     * @param openInterest contracts outstanding (previous session), {@code -1} unknown
     */
    public record OptionContract(boolean call, LocalDate expiry, double strike,
            long volume, long openInterest) {}
}
