package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
                java.util.function.BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "Pulling gemma3:4b...", (java.util.function.BiConsumer<String, String>) (phase, detail) -> {
            emissions.add(new String[] { phase, detail });
        });

        assertFalse(emissions.isEmpty());
        assertEquals("Pulling model", emissions.get(0)[0]);
        assertTrue(emissions.get(0)[1].contains("gemma3:4b"));
    }

    @Test
    void classifyAndEmit_shouldDetectOllamaProgress() throws Exception {
        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        Method m = EnvironmentSetup.class.getDeclaredMethod("classifyAndEmit", String.class,
                java.util.function.BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "42% ▕██████████░░░░░░░░░░▏ 1.2 GB",
                (java.util.function.BiConsumer<String, String>) (phase, detail) -> {
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
                java.util.function.BiConsumer.class);
        m.setAccessible(true);

        for (String line : List.of("verifying sha256 digest", "writing manifest", "success")) {
            List<String[]> emissions = new ArrayList<>();
            m.invoke(setup, line, (java.util.function.BiConsumer<String, String>) (phase, detail) -> {
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
                java.util.function.BiConsumer.class);
        m.setAccessible(true);

        List<String[]> emissions = new ArrayList<>();
        m.invoke(setup, "Setup Complete!", (java.util.function.BiConsumer<String, String>) (phase, detail) -> {
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
}
