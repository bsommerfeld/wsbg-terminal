package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;

/**
 * Gate + bootstrap for the local AI integration tests. The model-facing ITs
 * (vision, embeddings, …) are tagged {@code @EnabledIf(... OllamaAvailability#available)}
 * so they run <b>automatically</b> in {@code mvn test} on a machine where the
 * isolated Ollama is installed/running, and <b>skip</b> in CI where it isn't —
 * no env flag, no Lab. {@link #ensureOllama()} starts (or reuses) the isolated
 * server once for the session.
 */
public final class OllamaAvailability {

    private static OllamaServerManager managed;

    private OllamaAvailability() {}

    /** Enabled when the isolated Ollama is already reachable, or installed (and thus startable). */
    public static boolean available() {
        if (reachable()) return true;
        try {
            return Files.isRegularFile(
                    StorageUtils.getAppDataDir().resolve("ollama").resolve("ollama"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Starts (or reuses) the isolated Ollama on {@link OllamaServerManager#BASE_URL} for the session. */
    static synchronized void ensureOllama() {
        if (managed == null) {
            managed = new OllamaServerManager();
            // Tear the test-spawned server down when the test JVM exits, so a
            // `mvn test` run doesn't leave an orphaned ollama (serve + model runners
            // holding GBs of RAM) behind. Within the run it's reused across IT classes.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (managed != null) managed.shutdown();
            }, "ollama-it-teardown"));
        }
        managed.ensureRunning(OllamaServerManager.BASE_URL);
    }

    private static boolean reachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(OllamaServerManager.HOST, OllamaServerManager.PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
