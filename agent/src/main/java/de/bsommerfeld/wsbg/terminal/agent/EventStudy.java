package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.Bar;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The market memory's reaction arithmetic — PURE and deterministic (user
 * mandate: statistics are never the model's job). Computes market-adjusted
 * cumulative abnormal returns for one event date from two daily bar series:
 *
 * <ul>
 *   <li>{@code CAR(−1,+1)} — the immediate reaction window (t−1 catches
 *       leakage), measured close(t−2) → close(t+1);</li>
 *   <li>{@code CAR(0,+5)} — the short drift window, close(t−1) → close(t+5).</li>
 * </ul>
 *
 * <p>Both are buy-and-hold returns MINUS the benchmark's return over the same
 * calendar span (market-adjusted model, β=1 — for windows this short the
 * difference to a fitted market model drowns in the noise, and a raw return
 * would misread a −2 % market day as an event reaction; MacKinlay 1997).
 *
 * <p>The event's calendar date maps to the FIRST trading day ON OR AFTER it
 * in the instrument's own series — an after-hours disclosure or a weekend
 * date lands on the next session, the most common hand-rolled event-study
 * mistake. Windows that don't fit inside the series (event too young, series
 * too short) yield empty, never a partial figure.
 */
final class EventStudy {

    private EventStudy() {
    }

    /** Both windows' market-adjusted CARs, in percent. */
    record Reaction(double carEventPct, double carShortPct) {
    }

    /**
     * @param instrument daily bars of the event's instrument, oldest first
     * @param benchmark  daily bars of the benchmark index, oldest first
     * @param eventDate  the event's calendar date
     */
    static Optional<Reaction> compute(List<Bar> instrument, List<Bar> benchmark,
            LocalDate eventDate) {
        if (instrument == null || benchmark == null || eventDate == null) return Optional.empty();
        int t0 = firstTradingIndexOnOrAfter(instrument, eventDate);
        if (t0 < 2 || t0 + 5 >= instrument.size()) return Optional.empty();

        Double carEvent = adjustedSpanReturn(instrument, benchmark, t0 - 2, t0 + 1);
        Double carShort = adjustedSpanReturn(instrument, benchmark, t0 - 1, t0 + 5);
        if (carEvent == null || carShort == null) return Optional.empty();
        return Optional.of(new Reaction(carEvent, carShort));
    }

    /**
     * Market-adjusted return over close(from) → close(to), in percent — the
     * instrument's span return minus the benchmark's over the same DATES
     * (nearest benchmark close on or before each anchor date, so venue
     * holidays don't tear the match). Null when either side is unusable.
     */
    private static Double adjustedSpanReturn(List<Bar> instrument, List<Bar> benchmark,
            int from, int to) {
        double pFrom = instrument.get(from).close();
        double pTo = instrument.get(to).close();
        if (!(pFrom > 0) || !(pTo > 0)) return null;
        double own = pTo / pFrom - 1.0;

        Double bFrom = closeOnOrBefore(benchmark, instrument.get(from).date());
        Double bTo = closeOnOrBefore(benchmark, instrument.get(to).date());
        if (bFrom == null || bTo == null || !(bFrom > 0)) return null;
        double bench = bTo / bFrom - 1.0;
        return (own - bench) * 100.0;
    }

    /** Index of the first bar whose date is on or after the event date, or -1. */
    static int firstTradingIndexOnOrAfter(List<Bar> bars, LocalDate date) {
        for (int i = 0; i < bars.size(); i++) {
            if (!bars.get(i).date().isBefore(date)) return i;
        }
        return -1;
    }

    /** The last close at or before {@code date}, or null when the series starts later. */
    static Double closeOnOrBefore(List<Bar> bars, LocalDate date) {
        Double last = null;
        for (Bar bar : bars) {
            if (bar.date().isAfter(date)) break;
            last = bar.close();
        }
        return last;
    }
}
