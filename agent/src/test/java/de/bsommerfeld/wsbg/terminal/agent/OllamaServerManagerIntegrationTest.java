package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link OllamaServerManager}.
 * Requires Ollama to be installed on the machine.
 *
 * <p>
 * Tagged {@code "integration"} — excluded from normal {@code mvn test} runs.
 * Execute explicitly with {@code mvn test -Dgroups=integration -pl agent}.
 */
@Tag("integration")
class OllamaServerManagerIntegrationTest {

    private static final String BASE_URL = "http://localhost:11434";

    private final OllamaServerManager manager = new OllamaServerManager();

    @AfterEach
    void cleanup() {
        manager.shutdown();
    }

    @Test
    void ensureRunning_shouldMakeServerReachable() {
        manager.ensureRunning(BASE_URL);
        assertTrue(manager.isReachable(BASE_URL),
                "Server must be reachable after ensureRunning()");
    }

    @Test
    void ensureRunning_isIdempotent() {
        manager.ensureRunning(BASE_URL);
        boolean managedFirst = manager.isManaged();

        // Second call must not crash or start a duplicate process
        manager.ensureRunning(BASE_URL);
        assertEquals(managedFirst, manager.isManaged(),
                "Managed state should not change on second call");
    }

    @Test
    void shutdown_shouldTerminateManagedProcess() {
        manager.ensureRunning(BASE_URL);

        if (!manager.isManaged()) {
            // External Ollama was already running — shutdown is a no-op.
            // Verify it doesn't throw.
            assertDoesNotThrow(manager::shutdown);
            return;
        }

        manager.shutdown();
        assertFalse(manager.isManaged(),
                "Managed process must be gone after shutdown");
    }

    @Test
    void serverRespondsWithOllamaRunning() {
        manager.ensureRunning(BASE_URL);

        // Ollama's root endpoint returns "Ollama is running" as plain text.
        // This guards against API changes in future Ollama versions.
        String body = fetchBody(BASE_URL);
        assertNotNull(body, "Root endpoint must return a response body");
        assertTrue(body.toLowerCase().contains("ollama"),
                "Expected 'Ollama' in response body, got: " + body);
    }

    @Test
    void apiTagsEndpointReturnsValidJson() {
        manager.ensureRunning(BASE_URL);

        String json = fetchBody(BASE_URL + "/api/tags");
        assertNotNull(json, "/api/tags must return a response body");
        assertTrue(json.trim().startsWith("{"),
                "Expected JSON object from /api/tags, got: "
                        + json.substring(0, Math.min(100, json.length())));
        assertTrue(json.contains("\"models\""),
                "Response must contain 'models' key");
    }

    @Test
    void apiVersionEndpointReturnsVersion() {
        manager.ensureRunning(BASE_URL);

        String json = fetchBody(BASE_URL + "/api/version");
        assertNotNull(json, "/api/version must return a response body");
        assertTrue(json.contains("\"version\""),
                "Response must contain 'version' key, got: " + json);
    }

    @Test
    void healthCheckConstants_areReasonable() {
        // Guard against accidental changes that would make startup fragile
        assertTrue(OllamaServerManager.MAX_RETRIES >= 10,
                "MAX_RETRIES too low — server may not have time to start");
        assertTrue(OllamaServerManager.RETRY_INTERVAL.toMillis() >= 500,
                "RETRY_INTERVAL too aggressive — would spam connections");
        assertTrue(OllamaServerManager.HEALTH_TIMEOUT.toMillis() >= 1000,
                "HEALTH_TIMEOUT too short — network latency could cause false negatives");
    }

    private String fetchBody(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            return HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString())
                    .body();
        } catch (Exception e) {
            fail("HTTP request to " + url + " failed: " + e.getMessage());
            return null;
        }
    }
}
