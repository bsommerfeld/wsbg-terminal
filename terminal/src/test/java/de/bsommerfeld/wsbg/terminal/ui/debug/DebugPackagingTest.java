package de.bsommerfeld.wsbg.terminal.ui.debug;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nails the delivery locks: the reserved debug-asset paths stay excluded from
 * the packaged JAR (the {@code _preview} precedent), and the log ring stays
 * out of {@code logback.xml} (it must only ever be installed programmatically
 * behind the dev-mode guard).
 */
class DebugPackagingTest {

    @Test
    void pomExcludesEveryReservedDebugAssetPath() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        for (String exclude : new String[] {
                "<exclude>web/debug*.html</exclude>",
                "<exclude>web/debug/**</exclude>",
                "<exclude>web/js/debug/**</exclude>",
                "<exclude>web/css/debug*.css</exclude>"}) {
            assertTrue(pom.contains(exclude),
                    "terminal/pom.xml lost the packaging lock: " + exclude);
        }
    }

    @Test
    void logbackXmlNeverReferencesTheDebugAppender() throws Exception {
        String logback = Files.readString(Path.of("src/main/resources/logback.xml"));
        assertFalse(logback.contains("DebugLogAppender"),
                "the ring appender is dev-only and must never be wired in logback.xml — "
                        + "a shipped build would then hold log content in RAM");
    }

    @Test
    void debugBridgeBindingIsDevModeGuarded() throws Exception {
        String module = Files.readString(
                Path.of("src/main/java/de/bsommerfeld/wsbg/terminal/ui/config/BridgeModule.java"));
        int guard = module.indexOf("DevMode.active()");
        int binding = module.indexOf("DebugBridge.class");
        assertTrue(guard >= 0 && binding > guard,
                "DebugBridge must only be bound behind DevMode.active() — an unconditional "
                        + "binding would give a shipped build a live debug handler");
    }
}
