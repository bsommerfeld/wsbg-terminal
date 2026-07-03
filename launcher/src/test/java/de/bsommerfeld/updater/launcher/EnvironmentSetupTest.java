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
    void classifyAndEmit_shouldEmitModelCountBeforePull() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "> Pulling embeddinggemma:latest (2/2)...",
                (BiConsumer<String, String>) (phase, detail) -> {
                    emissions.add(new String[] { phase, detail });
                });

        // First a ModelCount control message "total/started" (idx 2 → both
        // pips lit: the second model's pull just began), then the visible pull
        // phase with a clean model name (no count/dots).
        assertEquals(2, emissions.size());
        assertEquals("ModelCount", emissions.get(0)[0]);
        assertEquals("2/2", emissions.get(0)[1]);
        assertEquals("Pulling embeddinggemma:latest", emissions.get(1)[0]);
        assertNull(emissions.get(1)[1]);
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
    void parseDownloadPercent_readsCurlDefaultMeterLeadingColumn() {
        // Real curl no-TTY meter rows (leading "% Total" column = download %).
        assertEquals(2, EnvironmentSetup.parseDownloadPercent(
                "2  1.92G    2 52.78M    0     0  50.27M      0  0:00:39  0:00:01  0:00:38 52.83M"));
        assertEquals(100, EnvironmentSetup.parseDownloadPercent(
                "100  1.92G  100  1.92G    0     0  94.27M      0  0:00:20  0:00:20 --:--:-- 98.72M"));
    }

    @Test
    void parseDownloadPercent_ignoresHeaderAndNonProgressLines() {
        assertEquals(-1, EnvironmentSetup.parseDownloadPercent(
                "% Total    % Received % Xferd  Average Speed   Time    Time     Time  Current"));
        assertEquals(-1, EnvironmentSetup.parseDownloadPercent("Dload  Upload   Total   Spent    Left  Speed"));
        assertEquals(-1, EnvironmentSetup.parseDownloadPercent("Extracting..."));
    }

    @Test
    void parseDownloadPercent_alsoReadsProgressBarTrailingPercent() {
        assertEquals(45, EnvironmentSetup.parseDownloadPercent("######            45.0%"));
    }

    @Test
    void classifyAndEmit_shouldDetectIsolatedOllamaInstall() throws Exception {
        // The isolated-install line carries an extra word ("isolated") and a
        // version/path tail; the phase detector must still recognise it so the
        // UI shows "Installing AI platform", not the raw line.
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "[*] Installing isolated Ollama 0.24.0 into C:\\Users\\x\\AppData\\Local\\wsbg-terminal\\ai ...",
                (BiConsumer<String, String>) (phase, detail) -> emissions.add(new String[] { phase, detail }));

        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
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
    void classifyAndEmit_shouldDetectBrowserRuntimeInstall() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "[*] Installing browser runtime (macosx-arm64)...",
                (BiConsumer<String, String>) (phase, detail) -> emissions.add(new String[] { phase, detail }));

        assertFalse(emissions.isEmpty());
        assertEquals("Installing browser runtime", emissions.get(0)[0]);
        assertNull(emissions.get(0)[1]);
    }

    @Test
    void classifyAndEmit_shouldEmitCurlProgressDuringBrowserInstall() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method classify = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        classify.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        BiConsumer<String, String> collector = (phase, detail) -> emissions.add(new String[] { phase, detail });

        classify.invoke(setup, "[*] Installing browser runtime (linux-amd64)...", collector);
        emissions.clear();

        // curl --progress-bar output (trailing percent, no byte figures)
        classify.invoke(setup, "######            45.0%", collector);

        assertFalse(emissions.isEmpty());
        assertEquals("Installing browser runtime", emissions.get(0)[0]);
        assertEquals("45%", emissions.get(0)[1]);
    }

    @Test
    void classifyAndEmit_shouldEndBrowserInstallOnReadyLine() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method classify = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        classify.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        BiConsumer<String, String> collector = (phase, detail) -> emissions.add(new String[] { phase, detail });

        classify.invoke(setup, "[*] Installing browser runtime (macosx-arm64)...", collector);
        classify.invoke(setup, "    Browser runtime ready.", collector);
        emissions.clear();

        // After the ready line the phase must be back to generic, not browser.
        classify.invoke(setup, "some generic line", collector);

        assertFalse(emissions.isEmpty());
        assertEquals("Setting up environment", emissions.get(0)[0]);
    }

    @Test
    void classifyAndEmit_shouldDetectFontInstall() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "[*] Installing terminal fonts...",
                (BiConsumer<String, String>) (phase, detail) -> emissions.add(new String[] { phase, detail }));

        assertFalse(emissions.isEmpty());
        assertEquals("Installing fonts", emissions.get(0)[0]);
        assertNull(emissions.get(0)[1]);
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

    @Test
    void parseDownloadDetail_buildsRichDetailFromCurlMeter() {
        // Original curl no-TTY meter rows captured from the Ollama binary download.
        assertEquals("2% — 52.78 MB / 1.92 GB", EnvironmentSetup.parseDownloadDetail(
                "2  1.92G    2 52.78M    0     0  50.27M      0  0:00:39  0:00:01  0:00:38 52.83M"));
        assertEquals("100% — 1.92 GB / 1.92 GB", EnvironmentSetup.parseDownloadDetail(
                "100  1.92G  100  1.92G    0     0  94.27M      0  0:00:20  0:00:20 --:--:-- 98.72M"));
    }

    @Test
    void parseDownloadDetail_fallsBackToBarePercentForProgressBar() {
        assertEquals("45%", EnvironmentSetup.parseDownloadDetail("######            45.0%"));
    }

    @Test
    void parseDownloadDetail_returnsNullForHeaderAndNonProgress() {
        assertNull(EnvironmentSetup.parseDownloadDetail(
                "% Total    % Received % Xferd  Average Speed   Time    Time     Time  Current"));
        assertNull(EnvironmentSetup.parseDownloadDetail("Extracting..."));
    }

    @Test
    void classifyAndEmit_emitsRichDetailDuringInstallPhase() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method classify = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                BiConsumer.class);
        classify.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        BiConsumer<String, String> collector = (phase, detail) -> emissions.add(new String[] { phase, detail });

        classify.invoke(setup, "[*] Installing Ollama", collector);
        emissions.clear();

        classify.invoke(setup,
                "2  1.92G    2 52.78M    0     0  50.27M      0  0:00:39  0:00:01  0:00:38 52.83M", collector);

        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
        assertEquals("2% — 52.78 MB / 1.92 GB", emissions.get(0)[1]);
    }

    @Test
    void normalizeCurlSize_handlesUnits() {
        assertEquals("1.92 GB", EnvironmentSetup.normalizeCurlSize("1.92G"));
        assertEquals("52.78 MB", EnvironmentSetup.normalizeCurlSize("52.78M"));
        assertEquals("739 KB", EnvironmentSetup.normalizeCurlSize("739K"));
        assertEquals("1.92 GB", EnvironmentSetup.normalizeCurlSize("1.92GB"));
    }

    @Test
    void run_shouldKillScriptThatGoesSilent() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path bin = appDir.resolve("bin");
        java.nio.file.Files.createDirectories(bin);
        // Emits one line, then stays silent far beyond the idle timeout.
        java.nio.file.Files.writeString(bin.resolve("setup.sh"),
                "#!/bin/bash\necho started\nsleep 60\n");

        EnvironmentSetup setup = new EnvironmentSetup(appDir, java.time.Duration.ofMillis(500));
        List<String[]> emissions = new ArrayList<>();

        long start = System.currentTimeMillis();
        boolean result = setup.run((phase, detail) -> emissions.add(new String[] { phase, detail }));
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result, "Silent script must be reported as failed");
        assertTrue(elapsed < 30_000, "Watchdog must kill the script long before the sleep ends");
        assertTrue(emissions.stream().anyMatch(e -> e[0].equals("Setup timed out")),
                "Should emit a timeout phase");
    }

    @Test
    void run_shouldNotKillScriptThatKeepsEmittingOutput() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path bin = appDir.resolve("bin");
        java.nio.file.Files.createDirectories(bin);
        // Total runtime (~1.2s) exceeds the idle timeout (500ms), but output
        // keeps flowing — the watchdog must treat it as alive.
        java.nio.file.Files.writeString(bin.resolve("setup.sh"),
                "#!/bin/bash\nfor i in 1 2 3 4 5 6; do echo tick $i; sleep 0.2; done\n");

        EnvironmentSetup setup = new EnvironmentSetup(appDir, java.time.Duration.ofMillis(500));
        List<String[]> emissions = new ArrayList<>();
        boolean result = setup.run((phase, detail) -> emissions.add(new String[] { phase, detail }));

        assertTrue(result, "Steadily emitting script must complete normally");
    }

    @Test
    void run_shouldReportWarningsExitCodeAsDegraded() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path bin = appDir.resolve("bin");
        java.nio.file.Files.createDirectories(bin);
        java.nio.file.Files.writeString(bin.resolve("setup.sh"),
                "#!/bin/bash\necho done\nexit " + EnvironmentSetup.EXIT_WITH_WARNINGS + "\n");

        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        List<String[]> emissions = new ArrayList<>();
        boolean result = setup.run((phase, detail) -> emissions.add(new String[] { phase, detail }));

        assertFalse(result);
        assertTrue(emissions.stream().anyMatch(e -> e[0].equals("Setup completed with warnings")),
                "Exit code 10 should surface as 'completed with warnings'");
    }

    @Test
    void resolveScript_shouldHonorScriptDirOverride() throws Exception {
        Path override = appDir.resolve("repo-scripts");
        java.nio.file.Files.createDirectories(override);
        java.nio.file.Files.writeString(override.resolve("setup.sh"), "#!/bin/bash\n");
        java.nio.file.Files.writeString(override.resolve("setup.ps1"), "");

        System.setProperty(EnvironmentSetup.SCRIPT_DIR_PROPERTY, override.toString());
        try {
            EnvironmentSetup setup = new EnvironmentSetup(appDir);
            Method m = EnvironmentSetup.class.getDeclaredMethod("resolveScript");
            m.setAccessible(true);

            Path script = (Path) m.invoke(setup);
            assertTrue(script.startsWith(override),
                    "Override dir must win over <appDir>/bin: " + script);
        } finally {
            System.clearProperty(EnvironmentSetup.SCRIPT_DIR_PROPERTY);
        }
    }
}
