package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.Bar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The market-adjusted CAR arithmetic: benchmark subtraction, weekend event
 * dates mapping to the next session, and windows that don't fit yielding
 * empty instead of a partial figure.
 */
class EventStudyTest {

    /** Weekday-only daily bars from {@code start}, one close per element. */
    private static List<Bar> series(LocalDate start, double... closes) {
        List<Bar> out = new ArrayList<>();
        LocalDate d = start;
        for (double close : closes) {
            while (d.getDayOfWeek().getValue() > 5) d = d.plusDays(1);
            out.add(new Bar(d.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                    close, close, close, close, 1000));
            d = d.plusDays(1);
        }
        return out;
    }

    @Test
    void computesMarketAdjustedCarsAgainstAFlatBenchmark() {
        // Mon 2026-06-01 start. Event on index 4 (Fri 2026-06-05): the stock
        // drops 100 -> 90 on the event day and stays there; benchmark is flat,
        // so the abnormal return IS the raw return.
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<Bar> stock = series(start, 100, 100, 100, 100, 90, 90, 90, 90, 90, 90, 90);
        List<Bar> bench = series(start, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50);

        EventStudy.Reaction r = EventStudy.compute(stock, bench, LocalDate.of(2026, 6, 5))
                .orElseThrow();
        // CAR(-1,+1): close(t-2)=100 -> close(t+1)=90 = -10 %.
        assertEquals(-10.0, r.carEventPct(), 1e-9);
        // CAR(0,+5): close(t-1)=100 -> close(t+5)=90 = -10 %.
        assertEquals(-10.0, r.carShortPct(), 1e-9);
    }

    @Test
    void benchmarkMoveIsSubtracted() {
        // Stock -10 % on the event day, but the whole market fell 10 % too:
        // the abnormal reaction is ~zero.
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<Bar> stock = series(start, 100, 100, 100, 100, 90, 90, 90, 90, 90, 90, 90);
        List<Bar> bench = series(start, 50, 50, 50, 50, 45, 45, 45, 45, 45, 45, 45);

        EventStudy.Reaction r = EventStudy.compute(stock, bench, LocalDate.of(2026, 6, 5))
                .orElseThrow();
        assertEquals(0.0, r.carEventPct(), 1e-9);
        assertEquals(0.0, r.carShortPct(), 1e-9);
    }

    @Test
    void weekendEventMapsToTheNextSession() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<Bar> stock = series(start, 100, 100, 100, 100, 100, 80, 80, 80, 80, 80, 80, 80);
        List<Bar> bench = series(start, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50);
        // Saturday 2026-06-06 → t0 = Monday 2026-06-08 (index 5, the 80 print).
        EventStudy.Reaction r = EventStudy.compute(stock, bench, LocalDate.of(2026, 6, 6))
                .orElseThrow();
        assertEquals(-20.0, r.carEventPct(), 1e-9);
    }

    @Test
    void unfittingWindowsYieldEmptyNeverPartial() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<Bar> shortSeries = series(start, 100, 100, 100, 100);
        List<Bar> bench = series(start, 50, 50, 50, 50);
        // Event too close to the series end (no t+5) and too close to the start (no t-2).
        assertTrue(EventStudy.compute(shortSeries, bench, LocalDate.of(2026, 6, 3)).isEmpty());
        assertTrue(EventStudy.compute(shortSeries, bench, LocalDate.of(2026, 6, 1)).isEmpty());
        // Event after the whole series.
        assertTrue(EventStudy.compute(shortSeries, bench, LocalDate.of(2027, 1, 1)).isEmpty());
        // Benchmark series starting after the window.
        List<Bar> stock = series(start, 100, 100, 100, 100, 90, 90, 90, 90, 90, 90, 90);
        List<Bar> lateBench = series(LocalDate.of(2026, 7, 1), 50, 50);
        assertTrue(EventStudy.compute(stock, lateBench, LocalDate.of(2026, 6, 5)).isEmpty());
    }

    @Test
    void nullBenchmarkMeansRawStudy() {
        // A macro event measured on the index itself: the raw move IS the
        // reaction, even while a benchmark subtraction would measure zero.
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<Bar> index = series(start, 100, 100, 100, 100, 98, 98, 98, 98, 98, 98, 98);
        EventStudy.Reaction r = EventStudy.compute(index, null, LocalDate.of(2026, 6, 5))
                .orElseThrow();
        assertEquals(-2.0, r.carEventPct(), 1e-9);
        assertEquals(-2.0, r.carShortPct(), 1e-9);
    }

    @Test
    void benchmarkHolidayFallsBackToTheLastCloseBefore() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<Bar> stock = series(start, 100, 100, 100, 100, 90, 90, 90, 90, 90, 90, 90);
        // Benchmark misses the stock's t+1 date entirely (holiday) — the last
        // close before it anchors the span, flat series → raw return survives.
        List<Bar> bench = new ArrayList<>(series(start, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50));
        bench.remove(5);
        Optional<EventStudy.Reaction> r = EventStudy.compute(stock, bench, LocalDate.of(2026, 6, 5));
        assertEquals(-10.0, r.orElseThrow().carEventPct(), 1e-9);
    }
}
