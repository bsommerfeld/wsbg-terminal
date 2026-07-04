package de.bsommerfeld.wsbg.terminal.price;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The CET/CEST trading-window clock that decides which price source leads at a
 * given moment. Pure time logic, extracted from {@link FallbackPriceSource} so
 * the window table is unit-testable in isolation.
 */
final class TradingWindowClock {

    private TradingWindowClock() {
    }

    /** Which trading window the CET clock is in — drives which price source leads. */
    enum PriceWindow { LS, US_AFTERHOURS, GAP }

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** The active window right now, against the live CET clock. */
    static PriceWindow now() {
        return windowAt(ZonedDateTime.now(BERLIN));
    }

    /**
     * The active window at {@code berlin} (CET/CEST):
     * <ul>
     *   <li><b>LS</b> — L&amp;S Tradecenter open: Mon–Fri 07:30–23:00, plus the weekend
     *       slots Sat 10:00–13:00 and Sun 17:00–19:00.</li>
     *   <li><b>US_AFTERHOURS</b> — L&amp;S closed but US post-market live: weekday
     *       23:00–24:00 and 00:00–02:00 of the morning after a weekday (Tue–Sat).</li>
     *   <li><b>GAP</b> — 02:00–07:30 and idle weekend hours: nothing trades.</li>
     * </ul>
     * Package-private + parameterised for testing.
     */
    static PriceWindow windowAt(ZonedDateTime berlin) {
        DayOfWeek day = berlin.getDayOfWeek();
        LocalTime t = berlin.toLocalTime();
        boolean weekday = day.getValue() <= 5; // Mon(1)–Fri(5)
        if (day == DayOfWeek.SATURDAY && inRange(t, LocalTime.of(10, 0), LocalTime.of(13, 0))) return PriceWindow.LS;
        if (day == DayOfWeek.SUNDAY && inRange(t, LocalTime.of(17, 0), LocalTime.of(19, 0))) return PriceWindow.LS;
        if (weekday && inRange(t, LocalTime.of(7, 30), LocalTime.of(23, 0))) return PriceWindow.LS;
        if (weekday && !t.isBefore(LocalTime.of(23, 0))) return PriceWindow.US_AFTERHOURS; // 23:00–24:00
        if (t.isBefore(LocalTime.of(2, 0)) && berlin.minusDays(1).getDayOfWeek().getValue() <= 5) {
            return PriceWindow.US_AFTERHOURS; // 00:00–02:00 after a weekday (incl. Fri→Sat)
        }
        return PriceWindow.GAP;
    }

    private static boolean inRange(LocalTime t, LocalTime from, LocalTime toExclusive) {
        return !t.isBefore(from) && t.isBefore(toExclusive);
    }
}
