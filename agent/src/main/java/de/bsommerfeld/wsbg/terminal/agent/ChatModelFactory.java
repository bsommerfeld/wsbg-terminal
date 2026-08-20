package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.AiEndpoint;
import de.bsommerfeld.wsbg.terminal.core.config.Model;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the three {@link ChatModel} instances the {@link AgentBrain} runs on
 * (extracted from {@code AgentBrain.initialize}). All are the same resident
 * one resident model (same name + num_ctx, so ONE loaded runner): the agent model
 * (subject extraction + judge calls, JSON mode), the compose model (schema
 * attached — see the honesty note at the compose builder for what that does and
 * does not guarantee) and the prose model (no {@code format} at all, for the one
 * lane that asks for sentences). Also resolves the model name against the live Ollama tag list -
 * on our own instance falling back to any installed model of the same family, on a
 * user's remote server never substituting anything (see {@link #resolveModel}).
 *
 * <p>WHERE the handles point is not this class's decision: {@link AiEndpoint}
 * resolves address, model tag, auth headers and context window from the config -
 * our own isolated instance by default, a server the user runs elsewhere when
 * they configured one.
 *
 * <p>WHAT they speak is decided here, and it is the one place in the app that
 * knows there are two protocols: Ollama's native API, and the OpenAI chat API
 * that everything else offers. The lanes, their prompts, their gates and their
 * parsers are identical either way - only the handle construction forks, and
 * {@link AiEndpoint.Api} names what that fork costs.
 */
final class ChatModelFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ChatModelFactory.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    /** The built models plus the resolved model names (for the init log line). */
    record Models(ChatModel agentModel, ChatModel composeModel, ChatModel proseModel,
            String activeAgentModel) {
    }

    /**
     * The closed compose vocabulary — single source for the schema sent as
     * {@code format} AND for {@link ComposeReplyParser}'s validation. The two must
     * never drift: the schema is only enforced by the GGUF runner, so the parser's
     * check against these same lists is what actually holds on the MLX default path.
     */
    static final java.util.List<String> TRIGGER_VALUES = java.util.List.of(
            "NONE", "RUNNER", "SQUEEZE", "BREAKOUT",
            "HARD_CATALYST", "POOLED_CALL", "EXTREME_DIRECTION");
    static final java.util.List<String> HIGHLIGHT_VALUES = java.util.List.of("NORMAL", "IMPORTANT");
    static final java.util.List<String> SENTIMENT_VALUES = java.util.List.of(
            "BULLISH", "BEARISH", "MIXED", "FOMO", "CAPITULATION",
            "SQUEEZE", "REVERSAL", "BREAKOUT", "NEUTRAL");

    /**
     * The ONE output ceiling for every lane. It used to be six hand-tuned
     * numbers (768/1024/1536/3584), each argued from the shape its lane
     * happened to emit — and every one of them was a latent truncation waiting
     * for its lane's output to grow. The 768 did exactly that: it was sized for
     * a ~300-token subject array, a triage lane later grew a per-item reason,
     * and ten of ~24 triage calls came back cut off (2026-08-11).
     *
     * <p>
     * A ceiling is not a reservation. Ollama allocates nothing for it, so a
     * lane that emits 200 tokens costs exactly 200 tokens whether the ceiling
     * sits at 1024 or 3584 — the tight numbers bought nothing and cost
     * verdicts. They were also hand-copied into every caller that had to budget
     * prompt against output; one number means there is nothing left to mirror.
     *
     * <p>
     * 3584 is the roomiest lane's measured need: a long free-form pass that
     * returns an entire revised text, where 2560 was measured cutting the reply
     * mid-sentence.
     */
    static final int NUM_PREDICT = 3584;

    /**
     * Stands up all model instances on the ONE resident model. On the managed
     * endpoint the concrete tag comes from {@link AgentConfig#resolveModelTag()} —
     * the user's hardware-based choice (gemma4:e2b..26b, -mlx twins on Apple
     * Silicon) or the managed default gemma4:e4b; on a remote endpoint it is
     * whatever tag the user named. All variants share the same model name AND the
     * same num_ctx, so Ollama keeps a single runner resident.
     */
    Models build(AgentConfig config) {
        return build(config, true);
    }

    /**
     * Builds the model handles.
     *
     * <p>{@code askOllama} decides whether the configured tag is VERIFIED
     * against the running server (and replaced by an installed sibling of the
     * same family when it is missing). That check is an HTTP call, and it used
     * to run inside Guice's injector - so the app could not boot at all unless
     * an Ollama already happened to be listening, with a message that named
     * neither the cause nor the fix ("Ollama connection failed: null"). It
     * survived only because an orphaned server from an earlier run, a smoke or
     * a probe was usually still up; the first launch of the day would have
     * failed.
     *
     * <p>The app is meant to come up independently of Ollama - intro first,
     * then everything else behind the boot gate. So injection builds handles
     * from the CONFIGURED tag without asking anyone, and {@link
     * AgentBrain#start()} rebuilds them once the server is up, where the
     * fallback can actually be resolved.
     */
    Models build(AgentConfig config, boolean askOllama) {
        // Resolved per build, not held from construction: the settings can move
        // the endpoint at runtime, and a rebuild is all that should take.
        AiEndpoint endpoint = AiEndpoint.resolve(config);
        Model agentModelEnum = config.resolveEditorialModel();
        String agentName = askOllama
                ? resolveModel(endpoint, agentModelEnum.getFamilyPrefix())
                : endpoint.modelTag();

        // All non-streaming — the full response is returned as String.
        // Generous timeouts: the agent + tool-use can take a minute per round
        // on a busy machine, routinely pushing past the LangChain4j default
        // of 60s. Set to 5 min across the board.
        java.time.Duration timeout = java.time.Duration.ofMinutes(5);

        // Context window (num_ctx): Ollama silently caps at 4096 unless told
        // otherwise. Every variant uses the SAME num_ctx on purpose: num_ctx is a
        // load-time parameter, so matching it (with the same model name) keeps
        // ONE Ollama runner instead of spawning a second weight copy. The
        // per-request sampling params (temperature, numPredict) can still differ
        // freely — those don't fork the runner.
        // MANAGED: auto-scaled to this machine's memory. REMOTE: whatever the user
        // configured (we cannot probe a foreign box) — see AiEndpoint.
        int ctxTokens = endpoint.contextTokens();
        if (endpoint.managed()) {
            LOG.info("[models] context window auto-scaled to {} tokens for this machine's memory.",
                    ctxTokens);
        } else {
            LOG.info("[models] remote endpoint {} — model '{}', context window {} tokens (configured).",
                    endpoint.baseUrl(), agentName, ctxTokens);
        }

        if (endpoint.openAi()) {
            return buildOpenAi(endpoint, agentModelEnum, agentName, ctxTokens, timeout);
        }

        // The deployed families are HYBRID THINKING models and Ollama defaults thinking ON — without an
        // explicit think=false every call silently generated ~2k chars of hidden English
        // "Thinking Process" (~500 tokens, 6–10 s GPU time) that nothing ever read. That,
        // not prefill and not parallelism, was the throughput ceiling (~5 calls/min): with
        // thinking off the same compose measures ~1 s instead of ~10 s at IDENTICAL headline
        // quality (A/B'd 2026-07-01 on real briefs). It also explains the historical
        // "JSON-mode whitespace-loop" / empty-reply lore: with a tight numPredict the
        // thinking consumed the whole token budget and the visible content came back
        // truncated or empty — the loop was the model reasoning, invisibly.
        final boolean think = false;

        // Editorial agent — every call in the deterministic pipeline expects a
        // JSON reply, so Ollama's JSON mode is requested. Honesty (verified live
        // 2026-08-13): only the GGUF runner enforces it via constrained decoding;
        // the MLX runner — the Apple-Silicon DEFAULT — ignores `format` entirely
        // (no grammar in x/mlxrunner/sample, Ollama issue #16563), so there the
        // JSON shape rests on the prompt and on JsonReplies' lenient parsing.
        // Plain JSON (no schema) because this one model instance serves TWO output
        // shapes (extraction + judge). Low temperature + tightened nucleus keep the
        // headlines faithful (no creative drift away from the cluster evidence).
        ChatModel agentModel = OllamaChatModel.builder()
                .baseUrl(endpoint.baseUrl()).customHeaders(endpoint.headers())
                .modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                // (No `\n\n` stop — it truncated extraction to empty when the
                // model led with a blank line; see composeModel.)
                .numCtx(ctxTokens).numPredict(NUM_PREDICT)
                .responseFormat(ResponseFormat.JSON)
                .think(think)
                .timeout(timeout)
                .build();

        // Compose model — the SAME model (no extra load), with the compose SCHEMA
        // attached as `format`: {headline, trigger, highlight, sentiment, derivedFrom,
        // newsUsed} with closed enums. Honesty about what that buys (verified live
        // 2026-08-13): on the GGUF runner the grammar enforces the shape; on the MLX
        // runner — the Apple-Silicon DEFAULT — `format` is ignored, the schema is a
        // prompt-side request and nothing more. The actual guarantee therefore lives
        // in ComposeReplyParser (validate → one corrective retry → salvage), which
        // holds on every runner. trigger is the IMPORTANT anchor: red must name its
        // concrete trigger, and HighlightReconciler demotes an IMPORTANT whose
        // trigger doesn't hold up — deterministically, after the model.
        ChatModel composeModel = OllamaChatModel.builder()
                .baseUrl(endpoint.baseUrl()).customHeaders(endpoint.headers())
                .modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(NUM_PREDICT)
                .responseFormat(buildComposeSchema())
                .think(think)
                .timeout(timeout)
                .build();

        // Prose model — the SAME model again (same tag, same num_ctx, so still ONE
        // runner), but WITHOUT `format`. The one lane whose prompt asks for plain
        // German sentences instead of JSON is the article digest: it wants 2–4
        // prose sentences, and it used to run on agentModel's JSON handle. On the
        // GGUF runner that grammar is enforced, so the digest came back as JSON —
        // either pasted into the brief as a JSON string or shorter than
        // MIN_DIGEST_CHARS and dropped to the title fallback. On MLX `format` is
        // ignored, which is why it never showed up on the default path.
        ChatModel proseModel = OllamaChatModel.builder()
                .baseUrl(endpoint.baseUrl()).customHeaders(endpoint.headers())
                .modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(NUM_PREDICT)
                .think(think)
                .timeout(timeout)
                .build();

        // The former deliberate + verdict lanes (free-form judge / greedy seeded
        // "same sheet -> same number" decode) were REMOVED 2026-08-13: their only
        // caller, the closing assessment, left with the KI-DD, so both were dead
        // handles — and the verdict lane's determinism promise was measured broken
        // on the MLX runner anyway (a warm long-lived runner returned 3 different
        // answers for an identical prompt at T=0, topK=1, fixed seed; Ollama issue
        // #16860). If a reproducible-verdict lane ever returns, it must NOT be
        // built as "seed + T=0 on the resident MLX runner": either pin it to the
        // GGUF tag (second runner) or unload the runner before each verdict call.
        return new Models(agentModel, composeModel, proseModel, agentName);
    }

    /**
     * The same three lanes against an OpenAI-compatible server.
     *
     * <p>What is missing here is not an oversight, it is the protocol:
     * <ul>
     *   <li><b>no {@code think=false}</b> - the measured throughput lever
     *       (compose ~10 s → ~1 s) has no equivalent. On a hybrid-thinking model
     *       every call will spend tokens on reasoning nothing reads; the answer
     *       is to point this at a model that does not think, not to pretend the
     *       switch exists.</li>
     *   <li><b>no {@code num_ctx}</b> - the window is fixed when that server
     *       starts. {@code ctxTokens} is therefore only OUR budget, which the
     *       prompt guard in {@link ChatGateway} still holds us to; nothing on
     *       the far side enforces it.</li>
     *   <li><b>no {@code topK}</b> - not part of the API.</li>
     *   <li><b>the schema is a request, not a grammar</b> - it travels as
     *       {@code response_format} and is enforced only if that server
     *       implements it. Exactly the situation on our own Apple-Silicon
     *       default already, which is why {@link ComposeReplyParser} is the
     *       real guarantee and holds here unchanged.</li>
     * </ul>
     *
     * <p>{@code strictJsonSchema} stays OFF: it is an OpenAI-specific strict
     * mode that local servers commonly reject outright, and a 400 on every
     * compose call is a worse trade than a schema that is merely advisory.
     *
     * <p>The auth value rides as a plain header rather than {@code apiKey}, so
     * ONE rule covers both protocols: what the user types is the header value,
     * verbatim - {@code Bearer sk-...}, an opaque proxy token, anything.
     */
    private Models buildOpenAi(AiEndpoint endpoint, Model agentModelEnum, String agentName,
            int ctxTokens, java.time.Duration timeout) {
        LOG.info("[models] OpenAI-compatible endpoint {} — model '{}', prompt budget {} tokens "
                + "(not enforced by that server), thinking not switchable.",
                endpoint.openAiBaseUrl(), agentName, ctxTokens);

        ChatModel agentModel = openAi(endpoint, agentModelEnum, agentName, timeout)
                .responseFormat(ResponseFormat.JSON)
                .build();
        ChatModel composeModel = openAi(endpoint, agentModelEnum, agentName, timeout)
                .responseFormat(buildComposeSchema())
                .build();
        ChatModel proseModel = openAi(endpoint, agentModelEnum, agentName, timeout)
                .build();
        return new Models(agentModel, composeModel, proseModel, agentName);
    }

    /** The settings every OpenAI lane shares; the caller adds the response format. */
    private static OpenAiChatModel.OpenAiChatModelBuilder openAi(AiEndpoint endpoint,
            Model agentModelEnum, String agentName, java.time.Duration timeout) {
        return OpenAiChatModel.builder()
                .baseUrl(endpoint.openAiBaseUrl())
                .customHeaders(endpoint.headers())
                .modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9)
                .maxTokens(NUM_PREDICT)
                .timeout(timeout)
                // ChatGateway owns the retry policy (its ladder feeds the
                // circuit breaker); a second, silent one inside the client
                // would multiply the wait and report nothing.
                .maxRetries(1);
    }

    /**
     * The compose response format sent as {@code format}: exactly
     * {@code {headline, trigger, highlight, sentiment, derivedFrom, newsUsed}} with
     * the enums closed and the provenance ordinal arrays. Enforced as a grammar by
     * the GGUF runner only; the MLX runner ignores it (see the compose builder),
     * so the shape's real enforcement is {@link ComposeReplyParser}.
     */
    private static ResponseFormat buildComposeSchema() {
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name("headline")
                        .rootElement(JsonObjectSchema.builder()
                                .addStringProperty("headline")
                                .addEnumProperty("trigger", TRIGGER_VALUES)
                                .addEnumProperty("highlight", HIGHLIGHT_VALUES)
                                .addEnumProperty("sentiment", SENTIMENT_VALUES)
                                // Provenance chaining: the ordinals of the numbered prior
                                // headlines this line builds on (empty when none) — small
                                // integers on a short numbered list, which a 4B cites far
                                // more reliably than the long news uuids that killed the
                                // old sourceNewsIds field.
                                .addProperty("derivedFrom",
                                        dev.langchain4j.model.chat.request.json.JsonArraySchema.builder()
                                                .items(dev.langchain4j.model.chat.request.json.JsonIntegerSchema.builder().build())
                                                .build())
                                // News provenance, same mechanism: the [N#] ordinals of the
                                // brief's numbered news list the line actually leaned on
                                // (empty when none). Small integers are citable where the
                                // old long uuids were not; the deterministic token-overlap
                                // test remains the backstop for under-citing.
                                .addProperty("newsUsed",
                                        dev.langchain4j.model.chat.request.json.JsonArraySchema.builder()
                                                .items(dev.langchain4j.model.chat.request.json.JsonIntegerSchema.builder().build())
                                                .build())
                                .required("headline", "trigger", "highlight", "sentiment", "derivedFrom", "newsUsed")
                                .build())
                        .build())
                .build();
    }

    /**
     * Verifies the target model exists on the server behind {@code endpoint}.
     *
     * <p><b>Managed</b>: falls back to any installed model from the same family
     * to prevent crashes - the target's OWN family first, the anchor family
     * after. The catalog is single-family again since 2026-08-11, so the two
     * coincide today; the ordering stays because it is what makes a second
     * family safe to add back. We installed that store, so substituting inside
     * it is our call to make.
     *
     * <p><b>Remote</b> (either protocol): verify, never substitute. The store
     * belongs to the user and they named the tag; quietly running a different
     * model off their machine because ours was missing would be a lie about
     * what produced the headlines. A missing tag is logged and handed on
     * unchanged, so the failure arrives as the server's own "model not found" -
     * the one message that says what to fix. Unreachable is not fatal either:
     * the app must boot without the endpoint (the address may be a typo, the
     * box asleep), and the first real call is where that becomes visible.
     */
    private String resolveModel(AiEndpoint endpoint, String familyPrefix) {
        String target = endpoint.modelTag();
        if (!endpoint.managed()) {
            // One listing routine for both protocols, shared with the launcher
            // and the settings' connection test - so what "the model is there"
            // means cannot differ between the button that checks it and the
            // code that relies on it.
            var auth = endpoint.headers().entrySet().stream().findFirst().orElse(null);
            var probe = de.bsommerfeld.updater.endpoint.EndpointProbe.probe(
                    endpoint.baseUrl(),
                    auth == null ? "" : auth.getKey(),
                    auth == null ? "" : auth.getValue());
            if (!probe.ok()) {
                LOG.error("Remote AI endpoint {} is not reachable ({}). Keeping model '{}' "
                        + "configured; calls will fail until it answers.",
                        endpoint.baseUrl(), probe.reason(), target);
            } else if (!probe.models().contains(target)) {
                LOG.warn("Model '{}' is not on the remote endpoint {} (it offers: {}). Using it "
                        + "anyway - the server will report it; we do not substitute another of "
                        + "the user's models.", target, endpoint.baseUrl(), probe.models());
            }
            return target;
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.baseUrl() + "/api/tags")).GET();
            endpoint.headers().forEach(request::header);
            String json = new String(
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()).body());

            if (json.contains("\"" + target + "\"")) {
                return target;
            }

            int colon = target.indexOf(':');
            String ownFamily = colon > 0 ? target.substring(0, colon) : familyPrefix;

            LOG.warn("Model '{}' not found. Resolving fallback ('{}', then '{}')...",
                    target, ownFamily, familyPrefix);

            for (String family : new String[] {ownFamily, familyPrefix}) {
                Pattern p = Pattern.compile("\"name\":\"(" + Pattern.quote(family) + "[^\"]*)\"");
                Matcher m = p.matcher(json);
                if (m.find()) {
                    String fallback = m.group(1);
                    LOG.warn("Auto-resolved '{}' → '{}'", target, fallback);
                    return fallback;
                }
            }

            throw new IllegalStateException("No installed model found for family: " + familyPrefix);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ollama connection failed: " + e.getMessage(), e);
        }
    }
}
