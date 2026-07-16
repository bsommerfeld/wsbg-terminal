package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttentionEntropyTest {

    private static Map<String, Integer> counts(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    @Test
    void suddenFocusIsEntropyCollapse() {
        Map<String, Integer> previous = counts("AAA1", 10, "BBB2", 10, "CCC3", 10, "DDD4", 10, "EEE5", 10);
        Map<String, Integer> current = counts("AAA1", 40, "BBB2", 2);
        SignalReading reading = AttentionEntropy.measure(current, previous).orElseThrow();
        assertTrue(reading.value() < 0.4, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("ENTROPIE-KOLLAPS"), reading.interpretation());
        assertTrue(reading.interpretation().contains("-0.72"), reading.interpretation());
    }

    @Test
    void concentratedAttentionWithoutHistoryIsConsensus() {
        SignalReading reading = AttentionEntropy.measure(counts("AAA1", 40, "BBB2", 2), null).orElseThrow();
        assertTrue(reading.value() < 0.4, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("Konsens"), reading.interpretation());
    }

    @Test
    void skewedButNotFixatedIsNormalAndThinDataCarriesCaution() {
        SignalReading reading = AttentionEntropy.measure(counts("AAA1", 20, "BBB2", 4, "CCC3", 1), null).orElseThrow();
        assertTrue(reading.value() > 0.4 && reading.value() < 0.8, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("normal verteilt"), reading.interpretation());
        assertTrue(reading.interpretation().contains("Vorsicht"), reading.interpretation());
    }

    @Test
    void uniformAttentionIsFragmented() {
        Map<String, Integer> current = counts("AAA1", 10, "BBB2", 10, "CCC3", 10, "DDD4", 10, "EEE5", 10);
        SignalReading reading = AttentionEntropy.measure(current, null).orElseThrow();
        assertTrue(reading.value() > 0.8, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("ersplittert"), reading.interpretation());
        assertFalse(reading.interpretation().contains("Vorsicht"), reading.interpretation());
    }

    @Test
    void nonCollapseDeltaIsQuantified() {
        Map<String, Integer> stable = counts("AAA1", 10, "BBB2", 10, "CCC3", 10, "DDD4", 10, "EEE5", 10);
        SignalReading reading = AttentionEntropy.measure(stable, stable).orElseThrow();
        assertTrue(reading.interpretation().contains("+0.00"), reading.interpretation());
    }

    @Test
    void tooThinDataYieldsEmpty() {
        assertTrue(AttentionEntropy.measure(counts("AAA1", 50), null).isEmpty());
        assertTrue(AttentionEntropy.measure(counts("AAA1", 5, "BBB2", 4), null).isEmpty());
        assertTrue(AttentionEntropy.measure(null, null).isEmpty());
    }
}
