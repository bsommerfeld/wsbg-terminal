package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingDelayDistressTest {

    /** Mean 10.5, std ~1.08. */
    private static final double[] HISTORY = {10, 12, 11, 9, 10, 11, 10, 12, 9, 11};

    @Test
    void muchLaterThanUsualRaisesFlag() {
        Optional<SignalReading> r = FilingDelayDistress.measure(HISTORY, 14);
        assertTrue(r.isPresent());
        assertTrue(r.get().value() >= 1.5);
        assertTrue(r.get().interpretation().contains("DELAY FLAG"));
        // n=10 > 5: no thin-data caveat.
        assertFalse(r.get().interpretation().contains("Caution"));
    }

    @Test
    void withinOwnRangeIsNeutral() {
        Optional<SignalReading> r = FilingDelayDistress.measure(HISTORY, 11);
        assertTrue(r.isPresent());
        assertTrue(r.get().value() >= 0 && r.get().value() < 1.5);
        assertTrue(r.get().interpretation().contains("Within own range"));
    }

    @Test
    void earlierThanUsualIsReassuring() {
        Optional<SignalReading> r = FilingDelayDistress.measure(HISTORY, 8);
        assertTrue(r.isPresent());
        assertTrue(r.get().value() < 0);
        assertTrue(r.get().interpretation().contains("Earlier than usual"));
        assertTrue(r.get().interpretation().contains("reassuring"));
    }

    @Test
    void thinHistoryCarriesCautionNote() {
        Optional<SignalReading> r = FilingDelayDistress.measure(new double[]{10, 11, 12, 9}, 20);
        assertTrue(r.isPresent());
        assertTrue(r.get().interpretation().contains("Caution"));
        assertTrue(r.get().interpretation().contains("n=4"));
    }

    @Test
    void emptyOnTooLittleHistory() {
        assertTrue(FilingDelayDistress.measure(new double[]{10, 11}, 20).isEmpty());
        assertTrue(FilingDelayDistress.measure(null, 20).isEmpty());
    }
}
