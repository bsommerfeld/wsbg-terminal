package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarningsSurpriseProfileTest {

    /** Mean ~2.67, std ~0.61. */
    private static final double[] SURPRISES = {2.0, 3.0, 2.5, 3.5, 2.0, 3.0};

    @Test
    void serialBeaterWithoutOutlierIsPricedIn() {
        Optional<SignalReading> r = EarningsSurpriseProfile.measure(9, 10, 2.5, SURPRISES);
        assertTrue(r.isPresent());
        // Posterior (9+1)/(10+2) = 0.833; z for 2.5 clearly below 1.
        assertEquals(10.0 / 12.0, r.get().value(), 1e-9);
        assertTrue(r.get().interpretation().contains("A BEAT IS THE NORM HERE"));
        assertTrue(r.get().interpretation().contains("n=10"));
        assertTrue(r.get().interpretation().contains("z="));
    }

    @Test
    void serialBeaterWithBigSurpriseIsRealSignal() {
        Optional<SignalReading> r = EarningsSurpriseProfile.measure(9, 10, 12.0, SURPRISES);
        assertTrue(r.isPresent());
        assertTrue(r.get().value() >= 0.7);
        assertTrue(r.get().interpretation().contains("GENUINE SURPRISE"));
        assertTrue(r.get().interpretation().contains("z="));
    }

    @Test
    void honestScattererCountsSimpleBeatMiss() {
        Optional<SignalReading> r = EarningsSurpriseProfile.measure(3, 10, null, null);
        assertTrue(r.isPresent());
        // Posterior (3+1)/(10+2) = 0.333.
        assertEquals(4.0 / 12.0, r.get().value(), 1e-9);
        assertTrue(r.get().value() < 0.5);
        assertTrue(r.get().interpretation().contains("Honest scatterer"));
        assertTrue(r.get().interpretation().contains("n=10"));
    }

    @Test
    void middleBandIsMixedProfile() {
        Optional<SignalReading> r = EarningsSurpriseProfile.measure(6, 10, null, null);
        assertTrue(r.isPresent());
        // Posterior (6+1)/(10+2) = 0.583.
        assertTrue(r.get().value() >= 0.5 && r.get().value() < 0.7);
        assertTrue(r.get().interpretation().contains("Mixed beat profile"));
    }

    @Test
    void thinReportHistoryCarriesCautionNote() {
        Optional<SignalReading> r = EarningsSurpriseProfile.measure(4, 5, null, null);
        assertTrue(r.isPresent());
        assertTrue(r.get().interpretation().contains("Caution"));
        assertTrue(r.get().interpretation().contains("thin data"));
    }

    @Test
    void emptyOnTooFewReports() {
        assertTrue(EarningsSurpriseProfile.measure(2, 3, null, null).isEmpty());
        assertTrue(EarningsSurpriseProfile.measure(5, 4, null, null).isEmpty());
    }
}
