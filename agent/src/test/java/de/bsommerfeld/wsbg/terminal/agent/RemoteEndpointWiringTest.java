package de.bsommerfeld.wsbg.terminal.agent;

import com.sun.net.httpserver.HttpServer;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the external-endpoint wiring against a stub server rather than against
 * a reading of the code: that the model handles really are built for the
 * CONFIGURED address, that the configured auth header really is sent, and that
 * the family fallback - which is right on a store we installed - stays off a
 * store we did not.
 *
 * <p>No Ollama involved: a two-line {@code /api/tags} stub is the whole
 * dependency, so this runs in the normal test loop.
 */
class RemoteEndpointWiringTest {

    private HttpServer server;
    private final List<String> seenAuth = new CopyOnWriteArrayList<>();

    /** Starts a stub answering {@code /api/tags} with {@code body}. */
    private String startStub(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            seenAuth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static AgentConfig remoteConfig(String url, String model) {
        AgentConfig cfg = new AgentConfig();
        cfg.setEndpointMode("remote");
        cfg.setEndpointUrl(url);
        cfg.setEndpointModel(model);
        return cfg;
    }

    @Test
    void talksToTheConfiguredServerAndSendsTheAuthHeader() throws Exception {
        String url = startStub("{\"models\":[{\"name\":\"qwen3:32b\"}]}");
        AgentConfig cfg = remoteConfig(url, "qwen3:32b");
        cfg.setEndpointAuth("Bearer s3cret");

        var models = new ChatModelFactory().build(cfg, true);

        assertEquals("qwen3:32b", models.activeAgentModel());
        assertEquals(List.of("Bearer s3cret"), seenAuth,
                "the configured header must reach the endpoint, verbatim and exactly once");
    }

    @Test
    void neverSubstitutesAnotherOfTheUsersModels() throws Exception {
        // The server has a gemma4 sitting there. On OUR store that is a valid
        // fallback for a missing tag; on the user's it would mean the headlines
        // came from a model they never named. Verify, then hand the tag on.
        String url = startStub("{\"models\":[{\"name\":\"gemma4:e4b\"}]}");

        var models = new ChatModelFactory().build(remoteConfig(url, "qwen3:32b"), true);

        assertEquals("qwen3:32b", models.activeAgentModel());
    }

    @Test
    void unreachableEndpointDoesNotBlockTheBoot() {
        // Port 1 answers nothing. The managed path throws here (it is a server we
        // are responsible for starting); a remote one must not, or a mistyped
        // address would leave the user with an app that cannot show them the typo.
        var models = assertDoesNotThrow(() ->
                new ChatModelFactory().build(remoteConfig("http://127.0.0.1:1", "qwen3:32b"), true));

        assertEquals("qwen3:32b", models.activeAgentModel());
    }

    @Test
    void noAuthConfigured_sendsNoAuthHeader() throws Exception {
        String url = startStub("{\"models\":[{\"name\":\"qwen3:32b\"}]}");

        new ChatModelFactory().build(remoteConfig(url, "qwen3:32b"), true);

        assertEquals(List.of("null"), seenAuth, "plain Ollama has no auth - send none");
    }
}
