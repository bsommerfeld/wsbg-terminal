package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarCollisionDensityTest {

    /** 60 historische Tage mit Gewichten 0..59. */
    private static double[] history() {
        double[] h = new double[60];
        for (int i = 0; i < 60; i++) h[i] = i;
        return h;
    }

    @Test
    void overloadedDayIsCollisionDay() {
        Optional<SignalReading> r = CalendarCollisionDensity.measure(history(), 100);
        assertTrue(r.isPresent());
        assertEquals(1.0, r.get().value(), 1e-9);
        assertTrue(r.get().interpretation().contains("KOLLISIONSTAG"));
        // n=60 >= 45: kein Duenne-Daten-Vorbehalt.
        assertFalse(r.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void averageDayIsFullDay() {
        Optional<SignalReading> r = CalendarCollisionDensity.measure(history(), 35);
        assertTrue(r.isPresent());
        // Perzentil (35+0.5)/60 = 0.59.
        assertTrue(r.get().value() >= 0.5 && r.get().value() < 0.9);
        assertTrue(r.get().interpretation().contains("Voller Tag"));
    }

    @Test
    void lightDayIsQuietCalendar() {
        Optional<SignalReading> r = CalendarCollisionDensity.measure(history(), 5);
        assertTrue(r.isPresent());
        // Perzentil (5+0.5)/60 = 0.09.
        assertTrue(r.get().value() < 0.5);
        assertTrue(r.get().interpretation().contains("Ruhiger Kalender"));
    }

    @Test
    void thinHistoryCarriesCautionNote() {
        double[] h = new double[30];
        for (int i = 0; i < 30; i++) h[i] = i;
        Optional<SignalReading> r = CalendarCollisionDensity.measure(h, 40);
        assertTrue(r.isPresent());
        assertTrue(r.get().interpretation().contains("Vorsicht"));
        assertTrue(r.get().interpretation().contains("duenne Datenbasis"));
    }

    @Test
    void emptyOnTooLittleHistory() {
        double[] h = new double[29];
        for (int i = 0; i < 29; i++) h[i] = i;
        assertTrue(CalendarCollisionDensity.measure(h, 10).isEmpty());
        assertTrue(CalendarCollisionDensity.measure(null, 10).isEmpty());
    }
}
