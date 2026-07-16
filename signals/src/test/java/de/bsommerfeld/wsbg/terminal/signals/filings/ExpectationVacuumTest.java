package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpectationVacuumTest {

    @Test
    void silentImminentEventIsVacuum() {
        // Naehe 13/14 x Stille 1.0 x Streuung 0.9 = 0.836.
        Optional<SignalReading> r = ExpectationVacuum.measure(1, 0.0, 5.0, 0.9);
        assertTrue(r.isPresent());
        assertEquals((13.0 / 14.0) * 1.0 * 0.9, r.get().value(), 1e-9);
        assertTrue(r.get().value() >= 0.6);
        assertTrue(r.get().interpretation().contains("ERWARTUNGS-VAKUUM"));
        assertTrue(r.get().interpretation().contains("1 Tag(en)"));
    }

    @Test
    void partlyWatchedEventIsThinlyCovered() {
        // Naehe 0.5 x Stille 0.75 x Streuung 0.9 = 0.3375.
        Optional<SignalReading> r = ExpectationVacuum.measure(7, 1.0, 4.0, 0.9);
        assertTrue(r.isPresent());
        assertTrue(r.get().value() >= 0.3 && r.get().value() < 0.6);
        assertTrue(r.get().interpretation().contains("Duenn beachtet"));
        assertTrue(r.get().interpretation().contains("7 Tag(en)"));
    }

    @Test
    void wellWatchedEventIsPricedIn() {
        // Naehe 0.286 x Stille 0.2 x Streuung 0.5 = 0.029.
        Optional<SignalReading> r = ExpectationVacuum.measure(10, 4.0, 5.0, 0.5);
        assertTrue(r.isPresent());
        assertTrue(r.get().value() < 0.3);
        assertTrue(r.get().interpretation().contains("Erwartung ist gebildet"));
        assertTrue(r.get().interpretation().contains("10 Tag(en)"));
    }

    @Test
    void thinMentionBaselineCarriesCautionNote() {
        Optional<SignalReading> r = ExpectationVacuum.measure(2, 0.0, 0.5, 0.9);
        assertTrue(r.isPresent());
        assertTrue(r.get().interpretation().contains("Vorsicht"));
        assertTrue(r.get().interpretation().contains("duenne Datenbasis"));
    }

    @Test
    void emptyOutsideWindowOrWithoutBaseline() {
        assertTrue(ExpectationVacuum.measure(15, 0.0, 5.0, 0.9).isEmpty());
        assertTrue(ExpectationVacuum.measure(-1, 0.0, 5.0, 0.9).isEmpty());
        assertTrue(ExpectationVacuum.measure(3, 0.0, 0.0, 0.9).isEmpty());
    }
}
