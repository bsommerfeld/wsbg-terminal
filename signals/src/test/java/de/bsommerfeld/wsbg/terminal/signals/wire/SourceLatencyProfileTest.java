package de.bsommerfeld.wsbg.terminal.signals.wire;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLatencyProfileTest {

    private static double[] repeat(double value, int n) {
        double[] xs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = value;
        }
        return xs;
    }

    @Test
    void chronicallySlowFirstPrinterRaisesExclusiveSuspicion() {
        Map<String, double[]> history = new LinkedHashMap<>();
        history.put("quelleA", repeat(30, 12));
        history.put("quelleB", repeat(10, 12));
        history.put("quelleC", repeat(10, 12));

        Optional<SignalReading> reading = SourceLatencyProfile.measure("quelleA", history);

        assertTrue(reading.isPresent());
        assertEquals("source-latency-profile", reading.get().id());
        assertTrue(reading.get().value() >= 1.5, "ratio must flag chronic slowness");
        assertTrue(reading.get().interpretation().contains("EXKLUSIV-VERDACHT"));
        assertTrue(reading.get().interpretation().contains("quelleA"));
        assertTrue(reading.get().interpretation().contains("30.0"));
        assertTrue(reading.get().interpretation().contains("10.0"));
    }

    @Test
    void averageLatencyIsUnremarkable() {
        Map<String, double[]> history = new LinkedHashMap<>();
        history.put("quelleA", repeat(10, 25));
        history.put("quelleB", repeat(10, 25));
        history.put("quelleC", repeat(10, 25));

        Optional<SignalReading> reading = SourceLatencyProfile.measure("quelleA", history);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() > 0.7 && reading.get().value() < 1.5);
        assertTrue(reading.get().interpretation().contains("nauffällig"));
        assertFalse(reading.get().interpretation().contains("kein Zusatzsignal"));
        assertFalse(reading.get().interpretation().contains("Vorsicht"),
                "25 observations over 3 sources is not thin data");
    }

    @Test
    void usualFastestFirstCarriesNoExtraSignal() {
        Map<String, double[]> history = new LinkedHashMap<>();
        history.put("quelleA", repeat(2, 12));
        history.put("quelleB", repeat(10, 12));
        history.put("quelleC", repeat(12, 12));

        Optional<SignalReading> reading = SourceLatencyProfile.measure("quelleA", history);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= 0.7);
        assertTrue(reading.get().interpretation().contains("kein Zusatzsignal"));
    }

    @Test
    void thinHistoryCarriesCautionNote() {
        Map<String, double[]> history = new LinkedHashMap<>();
        history.put("quelleA", repeat(30, 10));
        history.put("quelleB", repeat(10, 10));
        history.put("quelleC", repeat(10, 10));

        Optional<SignalReading> reading = SourceLatencyProfile.measure("quelleA", history);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void tooFewObservationsYieldsEmpty() {
        Map<String, double[]> history = new LinkedHashMap<>();
        history.put("quelleA", repeat(30, 9));
        history.put("quelleB", repeat(10, 12));

        assertTrue(SourceLatencyProfile.measure("quelleA", history).isEmpty());
    }

    @Test
    void singleSourceYieldsEmpty() {
        Map<String, double[]> history = new LinkedHashMap<>();
        history.put("quelleA", repeat(30, 12));

        assertTrue(SourceLatencyProfile.measure("quelleA", history).isEmpty());
    }
}
