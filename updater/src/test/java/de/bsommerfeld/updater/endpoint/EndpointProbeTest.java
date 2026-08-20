package de.bsommerfeld.updater.endpoint;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The protocol detection. It carries the whole "does it work with my engine?"
 * question: the user knows their server's ADDRESS, not whether it speaks
 * Ollama's API or OpenAI's, so the probe decides - and everything downstream
 * (the launcher sheet, the settings, the runtime handles) takes its answer.
 */
class EndpointProbeTest {

    private HttpServer server;
    private final List<String> seenPaths = new CopyOnWriteArrayList<>();

    /** Serves exactly the given paths; everything else 404s, like a real server. */
    private String stub(Map<String, String> routes) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPaths.add(exchange.getRequestURI().getPath());
            String body = routes.get(exchange.getRequestURI().getPath());
            byte[] bytes = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void detectsOllama() throws Exception {
        String url = stub(Map.of("/api/tags", "{\"models\":[{\"name\":\"qwen3:32b\"}]}"));

        EndpointProbe.Result r = EndpointProbe.probe(url, "", "");

        assertTrue(r.ok());
        assertEquals(EndpointProbe.Api.OLLAMA, r.api());
        assertEquals(List.of("qwen3:32b"), r.models());
    }

    @Test
    void detectsOpenAiCompatible() throws Exception {
        // llama.cpp's server, vLLM, LM Studio, gateways: no /api/tags at all.
        String url = stub(Map.of("/v1/models",
                "{\"object\":\"list\",\"data\":[{\"id\":\"llama-3.3-70b\",\"object\":\"model\"}]}"));

        EndpointProbe.Result r = EndpointProbe.probe(url, "", "");

        assertTrue(r.ok());
        assertEquals(EndpointProbe.Api.OPENAI, r.api());
        assertEquals(List.of("llama-3.3-70b"), r.models());
        assertTrue(seenPaths.contains("/api/tags"), "Ollama is tried first");
    }

    @Test
    void prefersOllamaWhenAServerSpeaksBoth() throws Exception {
        // Ollama itself also serves an OpenAI-compatible /v1. Where both work,
        // the native API is the better one for this pipeline (thinking switch,
        // num_ctx, enforced schema) - and the user never has to know why.
        String url = stub(Map.of(
                "/api/tags", "{\"models\":[{\"name\":\"gemma4:e4b\"}]}",
                "/v1/models", "{\"data\":[{\"id\":\"gemma4:e4b\"}]}"));

        assertEquals(EndpointProbe.Api.OLLAMA, EndpointProbe.probe(url, "", "").api());
    }

    @Test
    void anUnreachableAddressReportsTheAddressProblem_notThePathProblem() {
        // Both attempts fail. Reporting the second one's 404 would send the user
        // hunting for a path when the host is what is wrong.
        EndpointProbe.Result r = EndpointProbe.probe("127.0.0.1:1", "", "");

        assertFalse(r.ok());
        assertFalse(r.reason().contains("404"), "got: " + r.reason());
        assertFalse(r.reason().isBlank());
    }

    @Test
    void aSilentServerIsAskedOnce_notTwice() throws Exception {
        // A machine that fell asleep accepts the connection and then says
        // nothing. Asking it the second protocol only doubles the wait -
        // measured 12 s against a frozen container, and that wait sits in front
        // of both the boot probe and the settings' test button. An HTTP answer
        // (even a 404) means the host is there and the second question is worth
        // asking; silence means it is not.
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0)) {
            Thread accepter = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) silent.accept();
                } catch (Exception ignored) {
                    // socket closed - done
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            long t0 = System.nanoTime();
            EndpointProbe.Result r = EndpointProbe.probe(
                    "127.0.0.1:" + silent.getLocalPort(), "", "");
            long seconds = (System.nanoTime() - t0) / 1_000_000_000L;

            assertFalse(r.ok());
            assertTrue(seconds < 10,
                    "one timeout, not two - took " + seconds + "s");
            accepter.interrupt();
        }
    }

    @Test
    void authTravelsVerbatimOnBothAttempts() throws Exception {
        List<String> seenAuth = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenAuth.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Api-Key")));
            byte[] bytes = "{\"data\":[{\"id\":\"m\"}]}".getBytes(StandardCharsets.UTF_8);
            boolean openAi = exchange.getRequestURI().getPath().startsWith("/v1");
            exchange.sendResponseHeaders(openAi ? 200 : 404, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "127.0.0.1:" + server.getAddress().getPort();

        EndpointProbe.Result r = EndpointProbe.probe(url, "X-Api-Key", "s3cret");

        assertTrue(r.ok());
        assertEquals(List.of("s3cret", "s3cret"), seenAuth,
                "a gateway that guards both paths must be reachable on both");
    }

    @Test
    void anEmptyServerIsReachableButSaysSo() throws Exception {
        String url = stub(Map.of("/api/tags", "{\"models\":[]}"));

        EndpointProbe.Result r = EndpointProbe.probe(url, "", "");

        assertTrue(r.ok(), "the address is fine - that is worth confirming");
        assertEquals("no models installed", r.reason());
    }

    @Test
    void normalizesWhatPeopleType() {
        assertEquals("http://box.local:11434", EndpointProbe.normalizeUrl("box.local:11434"));
        assertEquals("http://box.local:11434", EndpointProbe.normalizeUrl(" box.local:11434/ "));
        assertEquals("https://ai.example.org", EndpointProbe.normalizeUrl("https://ai.example.org/"));
        assertEquals("", EndpointProbe.normalizeUrl("  "));
        // The OpenAI prefix is not part of the address. Pasted from any docs
        // page it would otherwise produce /v1/api/tags and /v1/v1/models.
        assertEquals("http://box.local:8080", EndpointProbe.normalizeUrl("http://box.local:8080/v1"));
        assertEquals("http://box.local:8080", EndpointProbe.normalizeUrl("box.local:8080/v1/"));
        assertEquals("https://gw.example.com/openai",
                EndpointProbe.normalizeUrl("https://gw.example.com/openai/v1"));
    }
}
