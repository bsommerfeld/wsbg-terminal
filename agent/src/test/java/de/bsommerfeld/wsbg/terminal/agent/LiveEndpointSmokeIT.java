package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.updater.endpoint.EndpointProbe;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.AiEndpoint;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.AiHealthEvent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The external endpoint against a REAL server - the half the stub tests cannot
 * reach: a live model answering over both protocols, and the lifecycle guard
 * proved against a process we did not start.
 *
 * <p>Gated twice, like the pipeline smoke: {@code @Tag("integration")} plus an
 * env switch, because it needs someone else's server to be up.
 *
 * <pre>
 * LIVE_ENDPOINT_SMOKE=true mvn test -pl agent -Dtest=LiveEndpointSmokeIT -Dtest.excludedGroups=
 * </pre>
 *
 * <p>{@code WSBG_SMOKE_ENDPOINT} overrides the address (default
 * {@code 127.0.0.1:11434} - a system Ollama, which is deliberately the address
 * the app must never adopt).
 */
@Tag("integration")
class LiveEndpointSmokeIT {

    private static final String ENDPOINT = System.getenv().getOrDefault(
            "WSBG_SMOKE_ENDPOINT", "127.0.0.1:11434");

    private static EndpointProbe.Result probe;
    private static String model;

    @BeforeAll
    static void requireALiveServer() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("LIVE_ENDPOINT_SMOKE")),
                "set LIVE_ENDPOINT_SMOKE=true to run against a live server");
        probe = EndpointProbe.probe(ENDPOINT, "", "");
        Assumptions.assumeTrue(probe.ok(), "no server at " + ENDPOINT + ": " + probe.reason());
        // Prefer something small when the server offers a choice - a real call,
        // not a slow one. A single-model server (llama.cpp, LM Studio) just
        // hands us the one it has.
        model = probe.models().stream()
                .filter(m -> !m.contains("embed"))
                .filter(m -> m.startsWith("granite"))
                .findFirst()
                .orElse(probe.models().stream()
                        .filter(m -> !m.contains("embed"))
                        .findFirst()
                        .orElse(probe.models().get(0)));
        System.out.println("[smoke] " + ENDPOINT + " speaks " + probe.api()
                + ", using model " + model);
    }

    private static GlobalConfig remote(String api) {
        GlobalConfig config = new GlobalConfig();
        AgentConfig agent = config.getAgent();
        agent.setEndpointMode("remote");
        agent.setEndpointApi(api);
        agent.setEndpointUrl(ENDPOINT);
        agent.setEndpointModel(model);
        return config;
    }

    @Test
    void theProtocolIsDetected() {
        assertTrue(probe.ok());
        assertFalse(probe.models().isEmpty());
        // Consistency, not a fixed expectation - the address may be any engine.
        // What must hold: the native API is chosen exactly when it is there,
        // because an Ollama also serves /v1 and the native one is the better of
        // the two for this pipeline.
        boolean nativeApiAnswers = EndpointProbe.probe(ENDPOINT, "", "").ok()
                && rawOllamaTagsAnswer();
        assertEquals(nativeApiAnswers ? EndpointProbe.Api.OLLAMA : EndpointProbe.Api.OPENAI,
                probe.api());
    }

    /** Whether {@code /api/tags} answers at all - the Ollama-only endpoint. */
    private static boolean rawOllamaTagsAnswer() {
        try {
            var response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(
                                    EndpointProbe.normalizeUrl(ENDPOINT) + "/api/tags"))
                            .timeout(java.time.Duration.ofSeconds(5)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void aRealCallOverTheOllamaApi() {
        Assumptions.assumeTrue(probe.api() == EndpointProbe.Api.OLLAMA,
                "this endpoint does not speak the native Ollama API");
        var models = new ChatModelFactory().build(remote("ollama").getAgent(), true);
        assertEquals(model, models.activeAgentModel(), "the named model, never a substitute");

        String reply = models.proseModel()
                .chat(ChatRequest.builder()
                        .messages(UserMessage.from("Antworte mit genau einem Wort: Testlauf"))
                        .build())
                .aiMessage().text();

        System.out.println("[smoke] ollama reply: " + reply);
        assertNotNull(reply);
        assertFalse(reply.isBlank(), "a live model must answer something");
    }

    @Test
    void aRealCallOverTheOpenAiApi() {
        // The same server, the same model, the other protocol - so any
        // difference in the reply is the protocol and nothing else.
        var models = new ChatModelFactory().build(remote("openai").getAgent(), true);

        String reply = models.proseModel()
                .chat(ChatRequest.builder()
                        .messages(UserMessage.from("Antworte mit genau einem Wort: Testlauf"))
                        .build())
                .aiMessage().text();

        System.out.println("[smoke] openai reply: " + reply);
        assertNotNull(reply);
        assertFalse(reply.isBlank(), "the OpenAI branch must reach a real model too");
    }

    @Test
    void jsonModeStillProducesJsonOverBothProtocols() {
        // The editorial pipeline asks for JSON on every lane but one. On the
        // OpenAI side that is a request, not a grammar (see AiEndpoint.Api) -
        // so it is worth seeing whether a real server honours it.
        List<String> protocols = probe.api() == EndpointProbe.Api.OLLAMA
                ? List.of("ollama", "openai")   // an Ollama serves both
                : List.of("openai");
        for (String api : protocols) {
            var models = new ChatModelFactory().build(remote(api).getAgent(), true);
            String reply = models.agentModel()
                    .chat(ChatRequest.builder()
                            .messages(UserMessage.from(
                                    "Gib ein JSON-Objekt zurueck: {\"ok\": true}"))
                            .build())
                    .aiMessage().text();
            System.out.println("[smoke] " + api + " json: " + reply.strip());
            assertTrue(reply.contains("{"), api + " returned no JSON at all: " + reply);
        }
    }

    @Test
    void weNeverAdoptOrKillAServerWeDidNotStart() {
        // THE dangerous one. ensureRunning() used to take any address; pointed
        // at 11434 it would have claimed the user's own Ollama on start and
        // killed it on exit. Here the whole brain lifecycle runs against
        // exactly that address.
        Set<Long> before = ollamaPids();
        assumeSomethingToProtect(before);

        ApplicationEventBus bus = new ApplicationEventBus();
        AiHealth health = new AiHealth(bus);
        OllamaServerManager manager = new OllamaServerManager();
        AgentBrain brain = new AgentBrain(remote("ollama"), bus, manager, new LlmGate(), health);

        brain.start();
        assertFalse(manager.isManaged(), "a remote endpoint is never ours to manage");

        manager.shutdown();   // what AppLifecycle does on exit

        Set<Long> after = ollamaPids();
        assertTrue(after.containsAll(before),
                "we killed a server we did not start: " + before + " -> " + after);
    }

    @Test
    void aDeadAddressGoesRedInsteadOfBlockingTheBoot() {
        ApplicationEventBus bus = new ApplicationEventBus();
        AiHealth health = new AiHealth(bus);
        GlobalConfig config = remote("ollama");
        config.getAgent().setEndpointUrl("127.0.0.1:1");

        AgentBrain brain = new AgentBrain(config, bus, new OllamaServerManager(),
                new LlmGate(), health);
        assertDoesNotThrow(brain::start, "the terminal must come up regardless");

        assertEquals(AiHealthEvent.State.UNREACHABLE, health.snapshot().state());
        assertFalse(health.snapshot().reason().isBlank(), "the UI needs a reason to show");
        System.out.println("[smoke] dead endpoint reason: " + health.snapshot().reason());
    }

    @Test
    void theResolvedEndpointIsWhatWeThinkItIs() {
        AiEndpoint ollama = AiEndpoint.resolve(remote("ollama").getAgent());
        AiEndpoint openAi = AiEndpoint.resolve(remote("openai").getAgent());

        assertFalse(ollama.managed());
        assertEquals("http://" + ENDPOINT, ollama.baseUrl());
        assertEquals("http://" + ENDPOINT + "/v1", openAi.openAiBaseUrl());
    }

    // ------------------------------------------------------------------
    // The guarded endpoint - a proxy in front that demands a header.
    // ------------------------------------------------------------------
    // Set WSBG_SMOKE_GATE / WSBG_SMOKE_GATE_AUTH to a guarded address and its
    // header value. Until then every server in this suite happily ignored the
    // auth field, so nothing proved it was ever SENT.

    private static final String GATE = System.getenv("WSBG_SMOKE_GATE");
    private static final String GATE_AUTH = System.getenv().getOrDefault(
            "WSBG_SMOKE_GATE_AUTH", "");

    private static GlobalConfig guarded(String auth) {
        GlobalConfig config = new GlobalConfig();
        AgentConfig agent = config.getAgent();
        agent.setEndpointMode("remote");
        agent.setEndpointUrl(GATE);
        agent.setEndpointModel("probe-only");
        agent.setEndpointAuth(auth);
        return config;
    }

    @Test
    void theAuthHeaderReachesTheModelListToo() {
        Assumptions.assumeTrue(GATE != null, "no guarded endpoint configured");

        // Without it: the probe must fail, and say WHY in a way that points at
        // a header rather than at the network.
        EndpointProbe.Result blocked = EndpointProbe.probe(GATE, "Authorization", "");
        assertFalse(blocked.ok());
        assertTrue(blocked.reason().contains("401"), blocked.reason());
        assertTrue(blocked.reason().contains("authentication"),
                "the hint is the whole value of the message here: " + blocked.reason());
        System.out.println("[smoke] gate without token: " + blocked.reason());

        // A wrong one is not better than none - and must not read differently.
        assertFalse(EndpointProbe.probe(GATE, "Authorization", "Bearer nonsense").ok());

        // With it: through, model list and all.
        EndpointProbe.Result open = EndpointProbe.probe(GATE, "Authorization", GATE_AUTH);
        assertTrue(open.ok(), open.reason());
        assertFalse(open.models().isEmpty());
        System.out.println("[smoke] gate with token: " + open.api()
                + ", " + open.models().size() + " models");
    }

    @Test
    void aRealCallSurvivesTheGate() {
        Assumptions.assumeTrue(GATE != null, "no guarded endpoint configured");
        String gateModel = EndpointProbe.probe(GATE, "Authorization", GATE_AUTH).models().stream()
                .filter(m -> !m.contains("embed"))
                .filter(m -> m.startsWith("granite"))
                .findFirst().orElse(model);

        GlobalConfig config = guarded(GATE_AUTH);
        config.getAgent().setEndpointModel(gateModel);
        var models = new ChatModelFactory().build(config.getAgent(), true);

        String reply = models.proseModel()
                .chat(ChatRequest.builder()
                        .messages(UserMessage.from("Antworte mit genau einem Wort: Torwaerter"))
                        .build())
                .aiMessage().text();

        System.out.println("[smoke] through the gate: " + reply);
        assertNotNull(reply);
        assertFalse(reply.isBlank(), "the chat call must carry the header as well");
    }

    @Test
    void aGuardedEndpointWithoutTheTokenGoesRedWithAReadableReason() {
        Assumptions.assumeTrue(GATE != null, "no guarded endpoint configured");

        ApplicationEventBus bus = new ApplicationEventBus();
        AiHealth health = new AiHealth(bus);
        AgentBrain brain = new AgentBrain(guarded(""), bus, new OllamaServerManager(),
                new LlmGate(), health);

        assertDoesNotThrow(brain::start);
        assertEquals(AiHealthEvent.State.UNREACHABLE, health.snapshot().state());
        assertTrue(health.snapshot().reason().contains("401"),
                "the overlay must name the real problem: " + health.snapshot().reason());
        System.out.println("[smoke] gate health reason: " + health.snapshot().reason());
    }

    /** PIDs of every running {@code ollama serve} - ours and the user's alike. */
    private static Set<Long> ollamaPids() {
        return ProcessHandle.allProcesses()
                .filter(h -> h.info().command().orElse("").endsWith("ollama"))
                .filter(h -> {
                    String[] args = h.info().arguments().orElse(new String[0]);
                    return args.length > 0 && "serve".equals(args[0]);
                })
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }

    private static void assumeSomethingToProtect(Set<Long> pids) {
        Assumptions.assumeFalse(pids.isEmpty(),
                "no 'ollama serve' process visible - nothing to prove here");
    }
}
