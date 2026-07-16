package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import de.bsommerfeld.wsbg.terminal.signals.filings.AdHocTimingPrior.TimedFiling;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdHocTimingPriorTest {

    /**
     * 36 Meldungen: 12 FREITAGNACHT (11 negativ), 12 HANDELSZEIT (6 negativ),
     * 12 NACHBOERSLICH (2 negativ).
     */
    private static List<TimedFiling> history() {
        List<TimedFiling> h = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            h.add(new TimedFiling(DayOfWeek.FRIDAY, 19, i < 11));
        }
        for (int i = 0; i < 12; i++) {
            h.add(new TimedFiling(DayOfWeek.TUESDAY, 11, i < 6));
        }
        for (int i = 0; i < 12; i++) {
            h.add(new TimedFiling(DayOfWeek.WEDNESDAY, 20, i < 2));
        }
        return h;
    }

    @Test
    void fridayNightBucketFlagsBadTimingPrior() {
        Optional<SignalReading> r = AdHocTimingPrior.measure(history(), DayOfWeek.SATURDAY, 10);
        assertTrue(r.isPresent());
        // Posterior (11+1)/(12+2) = 0.857
        assertEquals(12.0 / 14.0, r.get().value(), 1e-9);
        assertTrue(r.get().value() >= 0.65);
        assertTrue(r.get().interpretation().contains("TIMING-PRIOR SCHLECHT"));
        assertTrue(r.get().interpretation().contains("FREITAGNACHT"));
        assertTrue(r.get().interpretation().contains("n=12"));
    }

    @Test
    void tradingHoursBucketIsNeutral() {
        Optional<SignalReading> r = AdHocTimingPrior.measure(history(), DayOfWeek.TUESDAY, 12);
        assertTrue(r.isPresent());
        // Posterior (6+1)/(12+2) = 0.5
        assertEquals(0.5, r.get().value(), 1e-9);
        assertTrue(r.get().interpretation().contains("Neutraler Prior"));
        assertTrue(r.get().interpretation().contains("HANDELSZEIT"));
    }

    @Test
    void afterHoursBucketIsUnremarkable() {
        Optional<SignalReading> r = AdHocTimingPrior.measure(history(), DayOfWeek.MONDAY, 22);
        assertTrue(r.isPresent());
        // Posterior (2+1)/(12+2) = 0.214
        assertEquals(3.0 / 14.0, r.get().value(), 1e-9);
        assertTrue(r.get().value() < 0.45);
        assertTrue(r.get().interpretation().contains("Unauffaelliges Zeitfenster"));
        assertTrue(r.get().interpretation().contains("NACHBOERSLICH"));
    }

    @Test
    void thinBucketCarriesCautionNote() {
        // 30 Meldungen HANDELSZEIT, keine im FREITAGNACHT-Bucket -> n=0 dort.
        List<TimedFiling> h = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            h.add(new TimedFiling(DayOfWeek.MONDAY, 10, i % 2 == 0));
        }
        Optional<SignalReading> r = AdHocTimingPrior.measure(h, DayOfWeek.SUNDAY, 12);
        assertTrue(r.isPresent());
        assertTrue(r.get().interpretation().contains("Vorsicht"));
        assertTrue(r.get().interpretation().contains("duenne Datenbasis"));
    }

    @Test
    void emptyOnTooLittleHistory() {
        List<TimedFiling> h = new ArrayList<>();
        for (int i = 0; i < 29; i++) {
            h.add(new TimedFiling(DayOfWeek.MONDAY, 10, false));
        }
        assertTrue(AdHocTimingPrior.measure(h, DayOfWeek.FRIDAY, 19).isEmpty());
    }
}
