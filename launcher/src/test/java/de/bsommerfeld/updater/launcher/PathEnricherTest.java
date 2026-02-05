package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PathEnricher's process environment enrichment.
 */
class PathEnricherTest {

    @Test
    void enrich_shouldAddExtraPathsOnUnix() {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put("PATH", "/usr/bin:/bin");

        PathEnricher.enrich(pb);

        String path = pb.environment().get("PATH");
        // On macOS (current platform), extra paths should be appended
        assertTrue(path.contains("/usr/local/bin"));
        assertTrue(path.contains("/opt/homebrew/bin"));
        assertTrue(path.contains("/.local/bin"));
    }

    @Test
    void enrich_shouldPreserveOriginalPath() {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put("PATH", "/custom/path");

        PathEnricher.enrich(pb);

        String path = pb.environment().get("PATH");
        assertTrue(path.startsWith("/custom/path"));
    }

    @Test
    void enrich_shouldUseDefaultPathWhenMissing() {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().remove("PATH");

        PathEnricher.enrich(pb);

        String path = pb.environment().get("PATH");
        assertNotNull(path);
        assertTrue(path.contains("/usr/bin:/bin"));
    }

    @Test
    void enrich_shouldIncludeUserLocalBin() {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put("PATH", "/usr/bin");

        PathEnricher.enrich(pb);

        String path = pb.environment().get("PATH");
        String userHome = System.getProperty("user.home");
        assertTrue(path.contains(userHome + "/.local/bin"));
    }
}
