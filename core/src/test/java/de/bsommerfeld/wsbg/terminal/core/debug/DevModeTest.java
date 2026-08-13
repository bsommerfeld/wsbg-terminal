package de.bsommerfeld.wsbg.terminal.core.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevModeTest {

    /**
     * Surefire runs this class from {@code target/test-classes}, and the
     * production classes from {@code target/classes} — the exact classpath
     * shape of every developer entry (run.sh and IDE). The code-source probe
     * must therefore report dev mode here.
     */
    @Test
    void testRunIsDetectedAsDevRun() {
        assertTrue(DevMode.active(),
                "a classpath run from target/classes must count as a developer run");
    }

    @Test
    void debugConstantMirrorsDevMode() {
        assertEquals(DevMode.active(), Debug.ENABLED);
    }
}
