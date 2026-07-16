package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingDelayDistressTest {

    /** Mittelwert 10.5, Std ~1.08. */
    private static final double[] HISTORY = {10, 12, 11, 9, 10, 11, 10, 12, 9, 11};

    @Test
    void muchLaterThanUsualRaisesFlag() {
        Optional<SignalReading> r = FilingDelayDistress.measure(HISTORY, 14);
        assertTrue(r.isPresent());
        assertTrue(r.get().value() >= 1.5);
        assertTrue(r.get().interpretation().contains("VERSPAETUNGS-FLAG"));
        // n=10 > 5: kein Duenne-Daten-Vorbehalt.
        assertFalse(r.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void withinOwnRangeIsNeutral() {
        Optional<SignalReading> r = FilingDelayDistress.measure(HISTORY, 11);
        assertTrue(r.isPresent());
        assertTrue(r.get().value() >= 0 && r.get().value() < 1.5);
        assertTrue(r.get().interpretation().contains("Im eigenen Rahmen"));
    }

    @Test
    void earlierThanUsualIsReassuring() {
        Optional<SignalReading> r = FilingDelayDistress.measure(HISTORY, 8);
        assertTrue(r.isPresent());
        assertTrue(r.get().value() < 0);
        assertTrue(r.get().interpretation().contains("Frueher als ueblich"));
        assertTrue(r.get().interpretation().contains("Entwarnung"));
    }

    @Test
    void thinHistoryCarriesCautionNote() {
        Optional<SignalReading> r = FilingDelayDistress.measure(new double[]{10, 11, 12, 9}, 20);
        assertTrue(r.isPresent());
        assertTrue(r.get().interpretation().contains("Vorsicht"));
        assertTrue(r.get().interpretation().contains("n=4"));
    }

    @Test
    void emptyOnTooLittleHistory() {
        assertTrue(FilingDelayDistress.measure(new double[]{10, 11}, 20).isEmpty());
        assertTrue(FilingDelayDistress.measure(null, 20).isEmpty());
    }
}
