package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the setup pipeline's output classification and utilities. Process
 * execution is exercised only where the exit-code/watchdog contract lives (on
 * {@link EnvironmentSetup}); the pure parsing logic now lives on
 * {@link ScriptOutputClassifier}, {@link SetupScriptLocator} and
 * {@link ByteSizes} and is tested there directly.
 */
class EnvironmentSetupTest {

    @TempDir
    Path appDir;

    private static List<String[]> collect(ScriptOutputClassifier classifier, String... lines) {
        List<String[]> emissions = new ArrayList<>();
        BiConsumer<String, String> collector = (phase, detail) -> emissions.add(new String[]{phase, detail});
        for (String line : lines) classifier.classify(line, collector);
        return emissions;
    }

    // =====================================================================
    // ScriptOutputClassifier — stripAnsi / isNoise
    // =====================================================================

    @Test
    void stripAnsi_shouldRemoveColorCodes() {
        assertEquals("Success", new ScriptOutputClassifier().stripAnsi("[32mSuccess[0m"));
    }

    @Test
    void stripAnsi_shouldHandleCleanInput() {
        assertEquals("clean text", new ScriptOutputClassifier().stripAnsi("clean text"));
    }

    @Test
    void isNoise_shouldMatchProgressBars() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        assertTrue(c.isNoise("################"));
        assertTrue(c.isNoise("================"));
        assertTrue(c.isNoise("▕███████████████▏"));
    }

    @Test
    void isNoise_shouldMatchBarePercentages() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        assertTrue(c.isNoise("42%"));
        assertTrue(c.isNoise("100%"));
    }

    @Test
    void isNoise_shouldNotMatchMeaningfulText() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        assertFalse(c.isNoise("Installing ollama..."));
        assertFalse(c.isNoise("Setup Complete"));
    }

    @Test
    void isNoise_shouldMatchSpinnerCharacters() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        assertTrue(c.isNoise("/"));
        assertTrue(c.isNoise("\\"));
        assertTrue(c.isNoise("|"));
        assertTrue(c.isNoise("-"));
    }

    // =====================================================================
    // ScriptOutputClassifier — classify
    // =====================================================================

    @Test
    void classify_shouldDetectOllamaPull() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(), "> Pulling dummy-model:1b...");
        assertFalse(emissions.isEmpty());
        assertEquals("Pulling dummy-model:1b", emissions.get(0)[0]);
        assertNull(emissions.get(0)[1]);
    }

    @Test
    void classify_shouldEmitModelCountBeforePull() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(),
                "> Pulling embeddinggemma:latest (2/2)...");

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
    void classify_shouldDetectOllamaProgress() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(),
                "42% ▕██████████░░░░░░░░░░▏ 1.2 GB / 3.3 GB");
        assertFalse(emissions.isEmpty());
        assertEquals("Pulling model", emissions.get(0)[0]);
        assertTrue(emissions.get(0)[1].contains("42%"));
        assertTrue(emissions.get(0)[1].contains("1.2 GB"));
    }

    @Test
    void classify_shouldDetectOllamaStatusLines() {
        for (String line : List.of("verifying sha256 digest", "writing manifest", "success")) {
            List<String[]> emissions = collect(new ScriptOutputClassifier(), line);
            assertFalse(emissions.isEmpty(), "Should emit for: " + line);
            assertEquals("Pulling model", emissions.get(0)[0]);
        }
    }

    @Test
    void classify_shouldDetectSetupComplete() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(), "Setup Complete!");
        assertFalse(emissions.isEmpty());
        assertEquals("Setup complete", emissions.get(0)[0]);
    }

    @Test
    void classify_shouldDetectOllamaInstall() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(), "[*] Installing Ollama");
        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
        assertNull(emissions.get(0)[1]);
    }

    @Test
    void classify_shouldDetectIsolatedOllamaInstall() {
        // The isolated-install line carries an extra word ("isolated") and a
        // version/path tail; the phase detector must still recognise it so the
        // UI shows "Installing AI platform", not the raw line.
        List<String[]> emissions = collect(new ScriptOutputClassifier(),
                "[*] Installing isolated Ollama 0.24.0 into C:\\Users\\x\\AppData\\Local\\wsbg-terminal\\ai ...");
        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
    }

    @Test
    void classify_shouldDetectOllamaInstallLegacyFormat() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(), "[*] Installing/updating Ollama");
        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
    }

    @Test
    void classify_shouldEmitCurlProgressDuringInstallPhase() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        collect(c, "[*] Installing Ollama");
        List<String[]> emissions = collect(c, "####   8.8%");
        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
        assertEquals("8%", emissions.get(0)[1]);
    }

    @Test
    void classify_shouldExitInstallPhaseOnConfigLine() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        collect(c, "[*] Installing Ollama", "Configuration loaded");
        List<String[]> emissions = collect(c, "some generic line");
        assertFalse(emissions.isEmpty());
        assertEquals("Setting up environment", emissions.get(0)[0]);
    }

    @Test
    void classify_shouldFallbackToSettingUpEnvironment() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(), "Checking system requirements");
        assertFalse(emissions.isEmpty());
        assertEquals("Setting up environment", emissions.get(0)[0]);
        assertEquals("Checking system requirements", emissions.get(0)[1]);
    }

    @Test
    void classify_shouldSuppressPullingPrefixLines() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(), "pulling a1b2c3d4e5f6");
        // Internal "pulling <hash>" lines are silently consumed
        assertTrue(emissions.isEmpty());
    }

    @Test
    void classify_shouldFilterSmallerLayerProgress() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(),
                // Large layer first — sets maxTotalBytes to ~3 GB
                "50% ▕██████████░░░░░░░░░░▏ 1.5 GB / 3.0 GB",
                // Smaller layer (50 MB < 3 GB) — consumed by the pattern but not emitted
                "80% ▕████████████████░░░░▏ 40 MB / 50 MB");
        assertEquals(1, emissions.size(), "Smaller layer progress should be suppressed");
        assertTrue(emissions.get(0)[1].contains("3.0 GB"));
    }

    @Test
    void classify_shouldResetMaxTotalBytesOnNewPull() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        collect(c,
                // Large layer sets maxTotalBytes high
                "90% ▕██████████░░░░░░░░░░▏ 2.7 GB / 3.0 GB",
                // New model pull resets maxTotalBytes
                "> Pulling small-model:latest...");
        List<String[]> emissions = collect(c, "50% ▕██████████░░░░░░░░░░▏ 25 MB / 50 MB");
        assertEquals(1, emissions.size());
        assertTrue(emissions.get(0)[1].contains("50 MB"));
    }

    @Test
    void classify_shouldDetectBrowserRuntimeInstall() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(),
                "[*] Installing browser runtime (macosx-arm64)...");
        assertFalse(emissions.isEmpty());
        assertEquals("Installing browser runtime", emissions.get(0)[0]);
        assertNull(emissions.get(0)[1]);
    }

    @Test
    void classify_shouldEmitCurlProgressDuringBrowserInstall() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        collect(c, "[*] Installing browser runtime (linux-amd64)...");
        // curl --progress-bar output (trailing percent, no byte figures)
        List<String[]> emissions = collect(c, "######            45.0%");
        assertFalse(emissions.isEmpty());
        assertEquals("Installing browser runtime", emissions.get(0)[0]);
        assertEquals("45%", emissions.get(0)[1]);
    }

    @Test
    void classify_shouldEndBrowserInstallOnReadyLine() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        collect(c, "[*] Installing browser runtime (macosx-arm64)...", "    Browser runtime ready.");
        List<String[]> emissions = collect(c, "some generic line");
        assertFalse(emissions.isEmpty());
        assertEquals("Setting up environment", emissions.get(0)[0]);
    }

    @Test
    void classify_shouldDetectFontInstall() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(), "[*] Installing terminal fonts...");
        assertFalse(emissions.isEmpty());
        assertEquals("Installing fonts", emissions.get(0)[0]);
        assertNull(emissions.get(0)[1]);
    }

    @Test
    void classify_shouldDetectModelCleanupHeader() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(), "[*] Cleaning up old models...");
        assertFalse(emissions.isEmpty());
        assertEquals("Cleaning up old models", emissions.get(0)[0]);
        assertNull(emissions.get(0)[1]);
    }

    @Test
    void classify_shouldDetectStaleModelRemoval() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(),
                "    > Removing stale model embeddinggemma:latest (1/1)...");
        assertFalse(emissions.isEmpty());
        assertEquals("Cleaning up old models", emissions.get(0)[0]);
        // The model name rides as detail (log only; the label stays translated).
        assertEquals("embeddinggemma:latest", emissions.get(0)[1]);
    }

    @Test
    void classify_shouldDetectPullingManifest() {
        List<String[]> emissions = collect(new ScriptOutputClassifier(), "pulling manifest");
        assertFalse(emissions.isEmpty());
        assertEquals("Pulling model", emissions.get(0)[0]);
        assertEquals("pulling manifest", emissions.get(0)[1]);
    }

    @Test
    void classify_emitsRichDetailDuringInstallPhase() {
        ScriptOutputClassifier c = new ScriptOutputClassifier();
        collect(c, "[*] Installing Ollama");
        List<String[]> emissions = collect(c,
                "2  1.92G    2 52.78M    0     0  50.27M      0  0:00:39  0:00:01  0:00:38 52.83M");
        assertFalse(emissions.isEmpty());
        assertEquals("Installing AI platform", emissions.get(0)[0]);
        assertEquals("2% — 52.78 MB / 1.92 GB", emissions.get(0)[1]);
    }

    // =====================================================================
    // ScriptOutputClassifier — curl size/percent parsers
    // =====================================================================

    @Test
    void parseDownloadPercent_readsCurlDefaultMeterLeadingColumn() {
        // Real curl no-TTY meter rows (leading "% Total" column = download %).
        assertEquals(2, ScriptOutputClassifier.parseDownloadPercent(
                "2  1.92G    2 52.78M    0     0  50.27M      0  0:00:39  0:00:01  0:00:38 52.83M"));
        assertEquals(100, ScriptOutputClassifier.parseDownloadPercent(
                "100  1.92G  100  1.92G    0     0  94.27M      0  0:00:20  0:00:20 --:--:-- 98.72M"));
    }

    @Test
    void parseDownloadPercent_ignoresHeaderAndNonProgressLines() {
        assertEquals(-1, ScriptOutputClassifier.parseDownloadPercent(
                "% Total    % Received % Xferd  Average Speed   Time    Time     Time  Current"));
        assertEquals(-1, ScriptOutputClassifier.parseDownloadPercent("Dload  Upload   Total   Spent    Left  Speed"));
        assertEquals(-1, ScriptOutputClassifier.parseDownloadPercent("Extracting..."));
    }

    @Test
    void parseDownloadPercent_alsoReadsProgressBarTrailingPercent() {
        assertEquals(45, ScriptOutputClassifier.parseDownloadPercent("######            45.0%"));
    }

    @Test
    void parseDownloadDetail_buildsRichDetailFromCurlMeter() {
        // Original curl no-TTY meter rows captured from the Ollama binary download.
        assertEquals("2% — 52.78 MB / 1.92 GB", ScriptOutputClassifier.parseDownloadDetail(
                "2  1.92G    2 52.78M    0     0  50.27M      0  0:00:39  0:00:01  0:00:38 52.83M"));
        assertEquals("100% — 1.92 GB / 1.92 GB", ScriptOutputClassifier.parseDownloadDetail(
                "100  1.92G  100  1.92G    0     0  94.27M      0  0:00:20  0:00:20 --:--:-- 98.72M"));
    }

    @Test
    void parseDownloadDetail_fallsBackToBarePercentForProgressBar() {
        assertEquals("45%", ScriptOutputClassifier.parseDownloadDetail("######            45.0%"));
    }

    @Test
    void parseDownloadDetail_returnsNullForHeaderAndNonProgress() {
        assertNull(ScriptOutputClassifier.parseDownloadDetail(
                "% Total    % Received % Xferd  Average Speed   Time    Time     Time  Current"));
        assertNull(ScriptOutputClassifier.parseDownloadDetail("Extracting..."));
    }

    @Test
    void normalizeCurlSize_handlesUnits() {
        assertEquals("1.92 GB", ScriptOutputClassifier.normalizeCurlSize("1.92G"));
        assertEquals("52.78 MB", ScriptOutputClassifier.normalizeCurlSize("52.78M"));
        assertEquals("739 KB", ScriptOutputClassifier.normalizeCurlSize("739K"));
        assertEquals("1.92 GB", ScriptOutputClassifier.normalizeCurlSize("1.92GB"));
    }

    // =====================================================================
    // ByteSizes
    // =====================================================================

    @Test
    void byteSizes_shouldConvertAllUnits() {
        assertEquals(3_000_000_000L, ByteSizes.parseOrZero("3 GB"));
        assertEquals(1_500_000_000L, ByteSizes.parseOrZero("1.5 GB"));
        assertEquals(739_000_000L, ByteSizes.parseOrZero("739 MB"));
        assertEquals(512_000L, ByteSizes.parseOrZero("512 KB"));
        assertEquals(1024L, ByteSizes.parseOrZero("1024 B"));
    }

    @Test
    void byteSizes_shouldReturnZeroForInvalidInput() {
        assertEquals(0L, ByteSizes.parseOrZero("not a number"));
    }

    // =====================================================================
    // SetupScriptLocator
    // =====================================================================

    @Test
    void resolveScript_shouldResolveSetupSh() {
        Path script = new SetupScriptLocator(appDir).resolveScript();
        assertNotNull(script);
        // On macOS/Linux, should resolve to bin/setup.sh
        assertTrue(script.toString().endsWith("setup.sh") || script.toString().endsWith("setup.ps1"));
    }

    @Test
    void resolveScript_shouldHonorScriptDirOverride() throws Exception {
        Path override = appDir.resolve("repo-scripts");
        java.nio.file.Files.createDirectories(override);
        java.nio.file.Files.writeString(override.resolve("setup.sh"), "#!/bin/bash\n");
        java.nio.file.Files.writeString(override.resolve("setup.ps1"), "");

        System.setProperty(SetupScriptLocator.SCRIPT_DIR_PROPERTY, override.toString());
        try {
            Path script = new SetupScriptLocator(appDir).resolveScript();
            assertTrue(script.startsWith(override),
                    "Override dir must win over <appDir>/bin: " + script);
        } finally {
            System.clearProperty(SetupScriptLocator.SCRIPT_DIR_PROPERTY);
        }
    }

    // =====================================================================
    // EnvironmentSetup — lifecycle + exit-code contract
    // =====================================================================

    @Test
    void run_shouldReturnTrueWhenNoScriptExists() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        List<String[]> emissions = new ArrayList<>();
        boolean result = setup.run((phase, detail) -> emissions.add(new String[]{phase, detail}));

        assertTrue(result, "Should succeed when no script exists");
        assertFalse(emissions.isEmpty());
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
        boolean result = setup.run((phase, detail) -> emissions.add(new String[]{phase, detail}));
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
        boolean result = setup.run((phase, detail) -> emissions.add(new String[]{phase, detail}));

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
        boolean result = setup.run((phase, detail) -> emissions.add(new String[]{phase, detail}));

        assertFalse(result);
        assertTrue(emissions.stream().anyMatch(e -> e[0].equals("Setup completed with warnings")),
                "Exit code 10 should surface as 'completed with warnings'");
    }
}
