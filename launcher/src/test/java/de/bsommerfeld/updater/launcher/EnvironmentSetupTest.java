package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EnvironmentSetup's output classification and internal utilities.
 * Process execution is not tested — only the pure parsing logic.
 */
class EnvironmentSetupTest {

    @TempDir
    Path appDir;

    @Test
    void stripAnsi_shouldRemoveColorCodes() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("stripAnsi", String.class);
        m.setAccessible(true);

        String result = (String) m.invoke(setup, "\u001B[32mSuccess\u001B[0m");
        assertEquals("Success", result);
    }

    @Test
    void stripAnsi_shouldHandleCleanInput() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("stripAnsi", String.class);
        m.setAccessible(true);

        assertEquals("clean text", m.invoke(setup, "clean text"));
    }

    @Test
    void isNoise_shouldMatchProgressBars() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("isNoise", String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(setup, "################"));
        assertTrue((boolean) m.invoke(setup, "================"));
        assertTrue((boolean) m.invoke(setup, "▕███████████████▏"));
    }

    @Test
    void isNoise_shouldMatchBarePercentages() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("isNoise", String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(setup, "42%"));
        assertTrue((boolean) m.invoke(setup, "100%"));
    }

    @Test
    void isNoise_shouldNotMatchMeaningfulText() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("isNoise", String.class);
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(setup, "Installing ollama..."));
        assertFalse((boolean) m.invoke(setup, "Setup Complete"));
    }

    @Test
    void classifyAndEmit_shouldDetectOllamaPull() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "> Pulling dummy-model:1b...", (BiConsumer<String, String>) (phase, detail) -> {
            emissions.add(new String[] { phase, detail });
        });

        assertFalse(emissions.isEmpty());
        assertEquals("Pulling dummy-model:1b", emissions.get(0)[0]);
        assertNull(emissions.get(0)[1]);
    }

    @Test
    void classifyAndEmit_shouldDetectOllamaProgress() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "42% ▕██████████░░░░░░░░░░▏ 1.2 GB / 3.3 GB",
                (BiConsumer<String, String>) (phase, detail) -> {
                    emissions.add(new String[] { phase, detail });
                });

        assertFalse(emissions.isEmpty());
        assertEquals("Pulling model", emissions.get(0)[0]);
        assertTrue(emissions.get(0)[1].contains("42%"));
        assertTrue(emissions.get(0)[1].contains("1.2 GB"));
    }

    @Test
    void classifyAndEmit_shouldDetectOllamaStatusLines() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        for (String line : List.of("verifying sha256 digest", "writing manifest", "success")) {
            List<String[]> emissions = new ArrayList<>();
            m.invoke(setup, line, (BiConsumer<String, String>) (phase, detail) -> {
                emissions.add(new String[] { phase, detail });
            });
            assertFalse(emissions.isEmpty(), "Should emit for: " + line);
            assertEquals("Pulling model", emissions.get(0)[0]);
        }
    }

    @Test
    void classifyAndEmit_shouldDetectSetupComplete() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "Setup Complete!", (BiConsumer<String, String>) (phase, detail) -> {
            emissions.add(new String[] { phase, detail });
        });

        assertFalse(emissions.isEmpty());
        assertEquals("Setup complete", emissions.get(0)[0]);
    }

    @Test
    void resolveScript_shouldResolveSetupSh() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("resolveScript");
        m.setAccessible(true);

        Path script = (Path) m.invoke(setup);
        assertNotNull(script);
        // On macOS/Linux, should resolve to bin/setup.sh
        assertTrue(script.toString().endsWith("setup.sh") || script.toString().endsWith("setup.ps1"));
    }

    @Test
    void run_shouldReturnTrueWhenNoScriptExists() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        List<String[]> emissions = new ArrayList<>();
        boolean result = setup.run((phase, detail) -> emissions.add(new String[] { phase, detail }));

        assertTrue(result, "Should succeed when no script exists");
        assertFalse(emissions.isEmpty());
    }

    @Test
    void classifyAndEmit_shouldDetectOllamaInstall() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "[*] Installing Ollama", (BiConsumer<String, String>) (phase, detail) -> {
            emissions.add(new String[] { phase, detail });
        });

        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
        assertNull(emissions.get(0)[1]);
    }

    @Test
    void classifyAndEmit_shouldDetectOllamaInstallLegacyFormat() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "[*] Installing/updating Ollama", (BiConsumer<String, String>) (phase, detail) -> {
            emissions.add(new String[] { phase, detail });
        });

        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
    }

    @Test
    void classifyAndEmit_shouldEmitCurlProgressDuringInstallPhase() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method classify = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        classify.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        BiConsumer<String, String> collector = (phase, detail) -> emissions.add(new String[] { phase, detail });

        // Activate install phase
        classify.invoke(setup, "[*] Installing Ollama", collector);
        emissions.clear();

        // Curl progress during install
        classify.invoke(setup, "####   8.8%", collector);

        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
        assertEquals("8%", emissions.get(0)[1]);
    }

    @Test
    void classifyAndEmit_shouldExitInstallPhaseOnConfigLine() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method classify = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        classify.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        BiConsumer<String, String> collector = (phase, detail) -> emissions.add(new String[] { phase, detail });

        // Enter install phase, then exit via Configuration line
        classify.invoke(setup, "[*] Installing Ollama", collector);
        classify.invoke(setup, "Configuration loaded", collector);
        emissions.clear();

        // Next line should be generic, not "Installing AI platform"
        classify.invoke(setup, "some generic line", collector);

        assertFalse(emissions.isEmpty());
        assertEquals("Setting up environment", emissions.get(0)[0]);
    }

    @Test
    void classifyAndEmit_shouldFallbackToSettingUpEnvironment() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "Checking system requirements", (BiConsumer<String, String>) (phase, detail) -> {
            emissions.add(new String[] { phase, detail });
        });

        assertFalse(emissions.isEmpty());
        assertEquals("Setting up environment", emissions.get(0)[0]);
        assertEquals("Checking system requirements", emissions.get(0)[1]);
    }

    @Test
    void classifyAndEmit_shouldSuppressPullingPrefixLines() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "pulling a1b2c3d4e5f6", (BiConsumer<String, String>) (phase, detail) -> {
            emissions.add(new String[] { phase, detail });
        });

        // Internal "pulling <hash>" lines are silently consumed
        assertTrue(emissions.isEmpty());
    }

    @Test
    void classifyAndEmit_shouldFilterSmallerLayerProgress() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method classify = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        classify.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        BiConsumer<String, String> collector = (phase, detail) -> emissions.add(new String[] { phase, detail });

        // Large layer first — sets maxTotalBytes to ~3 GB
        classify.invoke(setup, "50% ▕██████████░░░░░░░░░░▏ 1.5 GB / 3.0 GB", collector);
        // Smaller layer (50 MB < 3 GB) — consumed by the pattern but not emitted
        classify.invoke(setup, "80% ▕████████████████░░░░▏ 40 MB / 50 MB", collector);

        assertEquals(1, emissions.size(), "Smaller layer progress should be suppressed");
        assertTrue(emissions.get(0)[1].contains("3.0 GB"));
    }

    @Test
    void classifyAndEmit_shouldResetMaxTotalBytesOnNewPull() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method classify = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        classify.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        BiConsumer<String, String> collector = (phase, detail) -> emissions.add(new String[] { phase, detail });

        // Large layer sets maxTotalBytes high
        classify.invoke(setup, "90% ▕██████████░░░░░░░░░░▏ 2.7 GB / 3.0 GB", collector);
        // New model pull resets maxTotalBytes
        classify.invoke(setup, "> Pulling small-model:latest...", collector);
        emissions.clear();

        // Small layer of new model should now be emitted
        classify.invoke(setup, "50% ▕██████████░░░░░░░░░░▏ 25 MB / 50 MB", collector);

        assertEquals(1, emissions.size());
        assertTrue(emissions.get(0)[1].contains("50 MB"));
    }

    @Test
    void parseBytes_shouldConvertAllUnits() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("parseBytes", String.class);
        m.setAccessible(true);

        assertEquals(3_000_000_000L, (long) m.invoke(setup, "3 GB"));
        assertEquals(1_500_000_000L, (long) m.invoke(setup, "1.5 GB"));
        assertEquals(739_000_000L, (long) m.invoke(setup, "739 MB"));
        assertEquals(512_000L, (long) m.invoke(setup, "512 KB"));
        assertEquals(1024L, (long) m.invoke(setup, "1024 B"));
    }

    @Test
    void parseBytes_shouldReturnZeroForInvalidInput() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("parseBytes", String.class);
        m.setAccessible(true);

        assertEquals(0L, (long) m.invoke(setup, "not a number"));
    }

    @Test
    void isNoise_shouldMatchSpinnerCharacters() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("isNoise", String.class);
        m.setAccessible(true);

        assertTrue((boolean) m.invoke(setup, "/"));
        assertTrue((boolean) m.invoke(setup, "\\"));
        assertTrue((boolean) m.invoke(setup, "|"));
        assertTrue((boolean) m.invoke(setup, "-"));
    }

    @Test
    void classifyAndEmit_shouldDetectPullingManifest() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "pulling manifest", (BiConsumer<String, String>) (phase, detail) -> {
            emissions.add(new String[] { phase, detail });
        });

        assertFalse(emissions.isEmpty());
        assertEquals("Pulling model", emissions.get(0)[0]);
        assertEquals("pulling manifest", emissions.get(0)[1]);
    }
}
