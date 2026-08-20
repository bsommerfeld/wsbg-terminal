package de.bsommerfeld.wsbg.terminal.web.impl.sources.finra;

import java.time.LocalDate;

/**
 * One session's short-sale volume for one symbol, as FINRA disseminates it.
 *
 * <p><b>This is NOT short interest.</b> Short interest is a POSITION reported
 * twice a month; this is the share of a single day's TRADES that were marked
 * short, and the larger part of it is market makers hedging the other side of
 * ordinary customer orders rather than anyone betting against the name. Only
 * the CHANGE in the share against a name's own normal carries information -
 * the level does not, and nothing in this house may quote it as bearishness.
 *
 * @param date         the trade date
 * @param shortVolume  shares sold short and reported to a FINRA facility
 * @param totalVolume  all shares reported to those facilities that day
 */
public record ShortVolumeDay(LocalDate date, double shortVolume, double totalVolume) {

    /** Short share of the day's reported volume, {@code NaN} when unmeasurable. */
    public double shortShare() {
        return totalVolume > 0 ? shortVolume / totalVolume : Double.NaN;
    }
}
