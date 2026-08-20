package de.bsommerfeld.wsbg.terminal.web.impl.sources.minkabu;

import java.time.LocalDate;
import java.util.List;

/**
 * One Tokyo listing's daily tape off Minkabu's open chart backend - the only
 * keyless venue-deep OHLCV series a JPX listing has in this house (the
 * exchange's own site is closed). {@code name} is the issuer as the assets
 * catalogue writes it (native Japanese, e.g. a group name in kanji), or
 * {@code null} when the catalogue did not answer - the bars stand alone.
 *
 * <p>The youngest bar is the RUNNING Tokyo session (~20 minutes delayed), so
 * {@link #latestClose()} doubles as the current price and
 * {@link #latestDate()} names the session it belongs to.
 *
 * @param bars daily bars, OLDEST first, up to roughly two decades deep
 */
public record MinkabuHistory(String ric, String name, List<MinkabuBar> bars) {

    /** One Tokyo trading day; the date is the session's date in Asia/Tokyo. */
    public record MinkabuBar(LocalDate date, double open, double high,
            double low, double close, double volume) {}

    /** The running session's close so far - the ~20-minute-delayed price. */
    public double latestClose() {
        return bars.isEmpty() ? Double.NaN : bars.get(bars.size() - 1).close();
    }

    /** The running session's Tokyo date, or {@code null} without bars. */
    public LocalDate latestDate() {
        return bars.isEmpty() ? null : bars.get(bars.size() - 1).date();
    }
}
