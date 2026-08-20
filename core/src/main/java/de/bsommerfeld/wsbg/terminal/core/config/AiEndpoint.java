package de.bsommerfeld.wsbg.terminal.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The resolved coordinates of the model server this run talks to - the ONE
 * answer to "where do the chat calls go, as what model, with which limits".
 *
 * <p>
 * There used to be no such answer to resolve: the address was a constant
 * ({@link OllamaEndpoint#BASE_URL}) welded from {@code core} through
 * {@code OllamaServerManager} into the model factory, and every limit around it
 * (context window, concurrency) was derived from the assumption that the server
 * runs on THIS machine and was installed by us. That assumption is what a
 * user-supplied endpoint breaks, so it is made explicit here instead of being
 * spread across three classes.
 *
 * <h3>The two modes</h3>
 * <ul>
 *   <li>{@link Mode#MANAGED} - our own isolated Ollama on
 *       {@link OllamaEndpoint#PORT}, installed and started by us. Every value is
 *       exactly what it was before this type existed: the hardware-derived model
 *       tag, the memory-scaled context window, our fixed slot count. Nothing
 *       about the managed path changed.</li>
 *   <li>{@link Mode#REMOTE} - an Ollama the user runs somewhere else (the box in
 *       the basement, a machine on the LAN). We only address it: we never start
 *       it, never adopt it, never shut it down, and we install nothing for it.
 *       The values we can no longer derive - which model is on it, how much
 *       context it can hold, how many slots it serves - become configuration,
 *       because there is no honest way to probe another machine's memory.</li>
 * </ul>
 *
 * <p>
 * Resolution never fails: a half-written remote configuration degrades to
 * {@link Mode#MANAGED} with a log line, the same way {@link
 * AgentConfig#resolveModelTag()} degrades an unknown tag. A config the user is
 * still typing must not be able to stop the app from booting.
 */
public record AiEndpoint(Mode mode, Api api, String baseUrl, String modelTag,
        Map<String, String> headers, int contextTokens, int parallelism) {

    private static final Logger LOG = LoggerFactory.getLogger(AiEndpoint.class);

    /** Who owns the server we talk to - see the class javadoc. */
    public enum Mode {
        MANAGED, REMOTE
    }

    /**
     * What the server SPEAKS - a second axis, deliberately not folded into
     * {@link Mode}.
     *
     * <p>
     * {@code OLLAMA} is the native API ({@code /api/chat}, {@code /api/tags})
     * and the only one that carries everything the pipeline was tuned on:
     * {@code think=false} (the measured throughput lever), {@code num_ctx} as a
     * request parameter, and a grammar-enforced {@code format}.
     *
     * <p>
     * {@code OPENAI} is the chat-completions API that everything else speaks -
     * llama.cpp's server, vLLM, LM Studio, gateways, and the hosted providers.
     * Three things do NOT survive the crossing, and it is worth being plain
     * about them rather than discovering them as bugs:
     * <ul>
     *   <li>no thinking switch - a hybrid-thinking model will spend tokens on
     *       hidden reasoning we cannot turn off (pick a model that does not);</li>
     *   <li>no {@code num_ctx} - the window is fixed when that server starts,
     *       so ours is only a budget we hold ourselves to;</li>
     *   <li>the compose schema travels as {@code response_format} and is
     *       enforced only if that server implements it. That is already the
     *       situation on our own Apple-Silicon default (the MLX runner ignores
     *       {@code format}), which is why the real guarantee lives in
     *       {@code ComposeReplyParser} and holds here too.</li>
     * </ul>
     *
     * <p>The managed instance is always {@code OLLAMA} - it is our own server.
     */
    public enum Api {
        OLLAMA, OPENAI
    }

    /**
     * The context window assumed for a remote server when the user names none.
     * The same floor {@link AgentConfig#contextTokensFor} returns for an
     * unprobeable machine: a window below it is not a smaller pipeline, it is a
     * broken one, and guessing HIGH on a foreign machine buys silent truncation.
     */
    public static final int REMOTE_DEFAULT_CONTEXT_TOKENS = 8192;

    public AiEndpoint {
        headers = Map.copyOf(headers);
    }

    /** Whether this is our own isolated instance (and thus ours to start and stop). */
    public boolean managed() {
        return mode == Mode.MANAGED;
    }

    /** Whether this endpoint speaks the OpenAI chat API rather than Ollama's. */
    public boolean openAi() {
        return api == Api.OPENAI;
    }

    /**
     * The base URL an OpenAI client wants - the user's address plus the
     * {@code /v1} prefix the API lives under. Appended here rather than asked
     * for: people know their server's address, not which path the chat
     * completions sit on, and typing it twice is the commonest way to get a
     * {@code /v1/v1/chat/completions} 404.
     */
    public String openAiBaseUrl() {
        String url = baseUrl;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.endsWith("/v1") ? url : url + "/v1";
    }

    /**
     * Resolves the endpoint from the persisted agent configuration.
     *
     * <p>Called on every model-handle build rather than cached, so a change in
     * the settings takes effect on the next {@code initialize()} without a
     * restart.
     */
    public static AiEndpoint resolve(AgentConfig cfg) {
        if (!"remote".equalsIgnoreCase(str(cfg.getEndpointMode()))) {
            return managed(cfg);
        }

        String url = normalizeUrl(str(cfg.getEndpointUrl()));
        if (url.isEmpty()) {
            LOG.warn("agent.endpoint-mode=remote but agent.endpoint-url is empty "
                    + "- falling back to the managed local instance.");
            return managed(cfg);
        }
        String tag = str(cfg.getEndpointModel());
        if (tag.isEmpty()) {
            LOG.warn("agent.endpoint-mode=remote but agent.endpoint-model is empty "
                    + "- falling back to the managed local instance.");
            return managed(cfg);
        }

        int ctx = cfg.getEndpointContextTokens() > 0
                ? cfg.getEndpointContextTokens() : REMOTE_DEFAULT_CONTEXT_TOKENS;
        int slots = cfg.getEndpointParallelism() > 0
                ? cfg.getEndpointParallelism() : OllamaEndpoint.PARALLELISM;
        // Unknown values mean Ollama: it is the protocol the pipeline was built
        // on and loses nothing, so it is the safe end of a typo.
        Api api = "openai".equalsIgnoreCase(str(cfg.getEndpointApi()))
                ? Api.OPENAI : Api.OLLAMA;

        return new AiEndpoint(Mode.REMOTE, api, url, tag, authHeader(cfg), ctx, slots);
    }

    /** Our own isolated instance - the values are the pre-endpoint behaviour, unchanged. */
    private static AiEndpoint managed(AgentConfig cfg) {
        return new AiEndpoint(Mode.MANAGED, Api.OLLAMA, OllamaEndpoint.BASE_URL,
                cfg.resolveModelTag(), Map.of(), cfg.resolveContextTokens(),
                OllamaEndpoint.PARALLELISM);
    }

    /**
     * The auth header, or nothing. Deliberately literal: the configured value is
     * sent verbatim, so {@code Bearer abc}, an opaque proxy token and a Basic
     * credential all work without the config layer guessing a scheme. The UIs
     * that collect the value are where a "Bearer " convenience belongs.
     */
    private static Map<String, String> authHeader(AgentConfig cfg) {
        String value = str(cfg.getEndpointAuth());
        if (value.isEmpty()) return Map.of();
        String name = str(cfg.getEndpointAuthHeader());
        return Map.of(name.isEmpty() ? "Authorization" : name, value);
    }

    /**
     * Accepts what a human types - delegated to the ONE definition in
     * {@code updater}, which the launcher's advanced sheet and the settings'
     * connection test read too. A test that passes on a string the saved
     * endpoint would treat differently is worse than no test.
     */
    public static String normalizeUrl(String raw) {
        return de.bsommerfeld.updater.endpoint.EndpointProbe.normalizeUrl(raw);
    }

    private static String str(String s) {
        return s == null ? "" : s.strip();
    }
}
