package de.bsommerfeld.wsbg.terminal.agent;

import com.sun.net.httpserver.HttpServer;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The OpenAI-compatible path, end to end against a stub: address, path, auth
 * header and a real reply parsed back out.
 *
 * <p>This is the test that answers "does it work with my engine?". llama.cpp's
 * server, vLLM, LM Studio and the hosted providers all speak this API and none
 * of them speak Ollama's, so everything about them that can be checked without
 * their binaries is checked here: that we hit {@code /v1/chat/completions} and
 * not {@code /api/chat}, that {@code /v1} lands exactly once, and that a normal
 * completion comes back as text.
 */
class OpenAiEndpointWiringTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    private String startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (path.equals("/v1/models")) {
                body = "{\"object\":\"list\",\"data\":[{\"id\":\"llama-3.3-70b\"}]}";
            } else if (path.equals("/v1/chat/completions")) {
                body = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,"
                        + "\"model\":\"llama-3.3-70b\",\"choices\":[{\"index\":0,\"message\":"
                        + "{\"role\":\"assistant\",\"content\":\"moin\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                        + "\"completion_tokens\":1,\"total_tokens\":4}}";
            } else {
                // Everything Ollama-shaped is absent, exactly like a real
                // llama.cpp server.
                body = "not found";
                status = 404;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
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

    private static AgentConfig config(String url) {
        AgentConfig cfg = new AgentConfig();
        cfg.setEndpointMode("remote");
        cfg.setEndpointApi("openai");
        cfg.setEndpointUrl(url);
        cfg.setEndpointModel("llama-3.3-70b");
        return cfg;
    }

    @Test
    void aRealCallGoesToChatCompletionsAndComesBackAsText() throws Exception {
        String url = startStub();
        AgentConfig cfg = config(url);
        cfg.setEndpointAuth("Bearer s3cret");

        var models = new ChatModelFactory().build(cfg, true);
        String reply = models.agentModel()
                .chat(ChatRequest.builder().messages(UserMessage.from("hallo")).build())
                .aiMessage().text();

        assertEquals("moin", reply);
        assertTrue(paths.contains("/v1/chat/completions"),
                "must speak the OpenAI API, not Ollama's: " + paths);
        assertFalse(paths.contains("/api/chat"), "Ollama's path must never be tried here");
        assertTrue(auth.contains("Bearer s3cret"), "the header travels verbatim: " + auth);
    }

    @Test
    void theV1PrefixLandsExactlyOnce() throws Exception {
        // Typing the /v1 yourself is the commonest way to end up with
        // /v1/v1/chat/completions and a 404 nobody can read.
        String url = startStub();
        AgentConfig cfg = config(url + "/v1/");

        new ChatModelFactory().build(cfg, true).agentModel()
                .chat(ChatRequest.builder().messages(UserMessage.from("hallo")).build());

        assertTrue(paths.contains("/v1/chat/completions"), paths.toString());
        assertTrue(paths.stream().noneMatch(p -> p.contains("/v1/v1")), paths.toString());
    }

    @Test
    void theModelNameIsTheOneTheUserNamed() throws Exception {
        String url = startStub();

        var models = new ChatModelFactory().build(config(url), true);

        assertEquals("llama-3.3-70b", models.activeAgentModel());
        // Verification happens over /v1/models here - there is no tag list.
        assertTrue(paths.contains("/v1/models"), paths.toString());
    }

    @Test
    void anUnreachableOpenAiEndpointStillBoots() {
        AgentConfig cfg = config("http://127.0.0.1:1");

        var models = assertDoesNotThrow(() -> new ChatModelFactory().build(cfg, true));

        assertEquals("llama-3.3-70b", models.activeAgentModel());
    }

    @Test
    void aHealthyOpenAiServerIsNotDeclaredDeadAtBoot() throws Exception {
        // The boot check used to ask the bare address for a 200 - which is
        // Ollama's root page and nothing else's. A llama.cpp server answers 404
        // there, so a perfectly healthy endpoint would have gone red on every
        // start. It asks the model list now, per protocol.
        String url = startStub();
        AgentConfig cfg = config(url);
        var auth = java.util.Map.<String, String>of();

        var probe = de.bsommerfeld.updater.endpoint.EndpointProbe.probe(
                cfg.getEndpointUrl(), "", "");

        assertTrue(probe.ok(), "reason: " + probe.reason());
        assertEquals(de.bsommerfeld.updater.endpoint.EndpointProbe.Api.OPENAI, probe.api());
        assertFalse(paths.contains("/"), "the bare root is never the health question: " + paths);
        assertTrue(auth.isEmpty());
    }

    @Test
    void theComposeLaneAsksForItsSchema() throws Exception {
        String url = startStub();

        new ChatModelFactory().build(config(url), true).composeModel()
                .chat(ChatRequest.builder().messages(UserMessage.from("hallo")).build());

        String body = bodies.stream().filter(b -> b.contains("messages")).findFirst().orElse("");
        // A request, not a grammar - see AiEndpoint.Api. Whether the far side
        // honours it is its business; ComposeReplyParser is what actually holds.
        assertTrue(body.contains("response_format"), body);
        assertTrue(body.contains("headline"), "the compose schema must travel: " + body);
    }
}
