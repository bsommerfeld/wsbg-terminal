package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Ensures an Ollama server is running before models are used.
 * Starts {@code ollama serve} as a managed subprocess if no server
 * is reachable, and destroys it on shutdown.
 *
 * <p>
 * The subprocess is only started when the server is unreachable.
 * If the user already runs Ollama (GUI or CLI), the existing
 * instance is used without interference.
 */
@Singleton
public final class OllamaServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaServerManager.class);

    static final int MAX_RETRIES = 15;
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);
    static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);

    private Process serverProcess;

    /**
     * Ensures an Ollama server is reachable. Starts one if needed.
     *
     * @param baseUrl the Ollama HTTP endpoint (e.g. {@code http://localhost:11434})
     * @throws IllegalStateException if the server cannot be reached after retries
     */
    public void ensureRunning(String baseUrl) {
        LOG.info("Checking Ollama server at {}...", baseUrl);

        if (isReachable(baseUrl)) {
            LOG.info("Ollama server already running at {} — using existing instance", baseUrl);
            return;
        }

        LOG.warn("Ollama server not reachable at {} — starting managed instance", baseUrl);
        startServer();
        waitForServer(baseUrl);
        LOG.info("Managed Ollama server is ready at {}", baseUrl);
    }

    /** Destroys the managed subprocess if we started one. */
    public void shutdown() {
        if (serverProcess == null) {
            LOG.debug("No managed Ollama server to shut down");
            return;
        }

        long pid = serverProcess.pid();
        LOG.info("Shutting down managed Ollama server (PID: {})...", pid);
        serverProcess.destroy();
        try {
            // Grace period before force-kill — Ollama needs time to flush model
            // state. 5s matches the typical unload latency.
            if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
                LOG.warn("Ollama server did not exit within 5s — force killing PID {}", pid);
                serverProcess.destroyForcibly();
            } else {
                LOG.info("Ollama server (PID: {}) shut down cleanly", pid);
            }
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for Ollama shutdown — force killing PID {}", pid);
            serverProcess.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        serverProcess = null;
    }

    /** Whether we started a managed process (vs. reusing an external one). */
    public boolean isManaged() {
        return serverProcess != null && serverProcess.isAlive();
    }

    private void startServer() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ollama", "serve");
            pb.redirectErrorStream(true);

            // Discard output — Ollama logs to stderr internally, and we don't
            // need its output polluting our logs.
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            serverProcess = pb.start();
            LOG.info("Started 'ollama serve' (PID: {})", serverProcess.pid());
        } catch (Exception e) {
            LOG.error("Failed to start 'ollama serve' — is Ollama installed and on PATH?", e);
            throw new IllegalStateException(
                    "Failed to start 'ollama serve' — is Ollama installed?", e);
        }
    }

    private void waitForServer(String baseUrl) {
        for (int i = 1; i <= MAX_RETRIES; i++) {
            if (isReachable(baseUrl)) {
                LOG.info("Ollama server ready after {} attempt(s)", i);
                return;
            }

            // Check early exit — process crashed before becoming ready
            if (serverProcess != null && !serverProcess.isAlive()) {
                int exitCode = serverProcess.exitValue();
                serverProcess = null;
                LOG.error("ollama serve exited prematurely with code {}", exitCode);
                throw new IllegalStateException(
                        "ollama serve exited with code " + exitCode);
            }

            LOG.debug("Waiting for Ollama server... (attempt {}/{})", i, MAX_RETRIES);
            sleep(RETRY_INTERVAL);
        }

        LOG.error("Ollama server did not become reachable within {}s", MAX_RETRIES);
        // Clean up the hanging process
        if (serverProcess != null && serverProcess.isAlive()) {
            LOG.warn("Killing unresponsive Ollama server (PID: {})", serverProcess.pid());
            serverProcess.destroyForcibly();
            serverProcess = null;
        }
        throw new IllegalStateException(
                "Ollama server did not become reachable within " + MAX_RETRIES + "s");
    }

    /**
     * Probes the Ollama HTTP root endpoint. A 200 response confirms
     * the server is up and accepting connections.
     */
    boolean isReachable(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(HEALTH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            LOG.trace("Ollama health check failed: {}", e.getMessage());
            return false;
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
