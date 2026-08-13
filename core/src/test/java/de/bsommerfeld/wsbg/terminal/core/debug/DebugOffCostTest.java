package de.bsommerfeld.wsbg.terminal.core.debug;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE proof of the top rule: with debug off, the collection points cost
 * nothing. A fresh JVM is forked with {@code -Dwsbg.debug=false} (the same
 * override a shipped build effectively has via its JAR code source) and
 * {@link DebugOffProbe} measures thread-allocated bytes across five million
 * guarded collection-point calls — the probe exits non-zero if anything
 * allocated or the flag failed to force the off state.
 */
class DebugOffCostTest {

    @Test
    void guardedCollectionPointsAllocateNothingWhenOff() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process probe = new ProcessBuilder(
                java,
                "-Dwsbg.debug=false",
                "-cp", System.getProperty("java.class.path"),
                DebugOffProbe.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(probe.getInputStream().readAllBytes());
        assertTrue(probe.waitFor(120, TimeUnit.SECONDS), "probe JVM did not finish:\n" + output);
        assertEquals(0, probe.exitValue(),
                "off-state probe reported a violation (2 = flag ignored, 3 = allocation):\n" + output);
    }
}
