package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the isolation-relevant pure logic of {@link OllamaServerManager}
 * (binary/model-path resolution, private endpoint). These need no running Ollama;
 * live behaviour is covered by {@code OllamaServerManagerIntegrationTest}.
 */
class OllamaServerManagerTest {

    private final Path appData = Path.of("/app");

    @Test
    void endpoint_usesPrivatePort_neverTheUsersDefault() {
        assertEquals(11500, OllamaServerManager.PORT);
        assertNotEquals(11434, OllamaServerManager.PORT, "must not be Ollama's default port");
        assertEquals("http://127.0.0.1:11500", OllamaServerManager.BASE_URL);
    }

    @Test
    void modelsDir_isUnderIsolatedAiFolder() {
        assertEquals(appData.resolve("ollama").resolve("models"),
                OllamaServerManager.modelsDir(appData));
    }

    @Test
    void candidateBinaries_windows_checksExeLocations() {
        List<Path> candidates = OllamaServerManager.candidateBinaries(appData, "Windows 11");

        assertEquals(List.of(
                appData.resolve("ollama").resolve("ollama.exe"),
                appData.resolve("ollama").resolve("bin").resolve("ollama.exe")), candidates);
    }

    @Test
    void candidateBinaries_unix_prefersBinThenRoot() {
        List<Path> mac = OllamaServerManager.candidateBinaries(appData, "Mac OS X");
        List<Path> linux = OllamaServerManager.candidateBinaries(appData, "Linux");

        List<Path> expected = List.of(
                appData.resolve("ollama").resolve("bin").resolve("ollama"),
                appData.resolve("ollama").resolve("ollama"));
        assertEquals(expected, mac);
        assertEquals(expected, linux);
    }

    @Test
    void candidateBinaries_alwaysLiveUnderTheAiSubdir() {
        OllamaServerManager.candidateBinaries(appData, "Linux")
                .forEach(p -> assertTrue(p.startsWith(appData.resolve("ollama")),
                        () -> p + " must be under the isolated ai/ folder"));
    }
}
