package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.Model;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the Ollama {@link ChatModel} instances the {@link AgentBrain} runs on
 * (extracted from {@code AgentBrain.initialize}). All of them are the same resident
 * gemma4 (same model name + num_ctx, so ONE loaded runner): the agent model
 * (subject extraction + judge calls), the schema-constrained compose model, and the
 * prose/dossier/deep-dive variants. Also resolves the model name against the live
 * Ollama tag list, falling back to any installed model of the same family.
 */
final class OllamaModelFactory {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaModelFactory.class);

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    OllamaModelFactory(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** The built models plus the resolved model names (for the init log line). */
    record Models(ChatModel agentModel, ChatModel composeModel, ChatModel proseModel,
            ChatModel dossierModel, ChatModel deepDiveModel, String activeAgentModel) {
    }

    /**
     * Stands up all model instances on the ONE resident gemma4. The concrete tag
     * comes from {@link AgentConfig#resolveModelTag()} — the user's hardware-based
     * choice (gemma4:e2b..31b, -mlx twins on Apple Silicon) or the managed default
     * gemma4:e4b. All variants share the same model name AND the same num_ctx, so
     * Ollama keeps a single runner resident.
     */
    Models build(AgentConfig config) {
        Model agentModelEnum = config.resolveEditorialModel();
        String agentName = resolveModel(config.resolveModelTag(), agentModelEnum.getFamilyPrefix());

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
        int ctxTokens = config.resolveContextTokens();
        LOG.info("[models] context window auto-scaled to {} tokens for this machine's memory.",
                ctxTokens);

        // gemma4 is a HYBRID THINKING model and Ollama defaults thinking ON — without an
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
        // JSON reply, so Ollama's JSON mode (constrained decoding) is on. Plain JSON
        // (no schema) because this one model instance serves TWO output shapes
        // (extraction + judge). Low temperature + tightened nucleus keep the
        // headlines faithful (no creative drift away from the cluster evidence).
        ChatModel agentModel = OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                // 768 backstop for the batched subjects array (~300 tokens). (No `\n\n`
                // stop — it truncated extraction to empty when the model led with a
                // blank line; see composeModel.)
                .numCtx(ctxTokens).numPredict(768)
                .responseFormat(ResponseFormat.JSON)
                .think(think)
                .timeout(timeout)
                .build();

        // Compose model — the SAME gemma4 (no extra load), with the compose output
        // SCHEMA-constrained (not just JSON mode): the grammar forces exactly the
        // {headline, trigger, highlight, sentiment, derivedFrom, newsUsed} shape with
        // the enums closed, so a malformed or truncated compose object is mechanically
        // impossible. trigger is the IMPORTANT anchor: the model must NAME the concrete
        // red trigger BEFORE it sets highlight (property order matters — the grammar
        // emits trigger first), and HeadlineWriter.reconcileHighlight demotes an
        // IMPORTANT whose trigger doesn't hold up.
        ChatModel composeModel = OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(1024)
                .responseFormat(buildComposeSchema())
                .think(think)
                .timeout(timeout)
                .build();

        // Prose model — the SAME gemma4 (same name + num_ctx, still ONE runner),
        // but FREE-FORM output: no responseFormat at all. Used by the daily
        // Wetterbericht map-reduce, whose two stages (digest lines, final report
        // prose) are plain text — JSON mode would only add escaping/truncation
        // risk to running prose. numPredict 1024 comfortably holds a ~200-word
        // report or a digest of a full batch.
        ChatModel proseModel = OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(1024)
                .think(think)
                .timeout(timeout)
                .build();

        // Dossier model — the SAME gemma4 (same name + num_ctx, still ONE runner),
        // plain JSON mode like the agent model but a ROOMIER numPredict: the
        // watchlist dossier is a sectioned ~2800-char report + tldr in one JSON
        // object (~1100-1300 tokens with escaping) — the agent model's 768 backstop
        // would truncate it mid-section. Not schema-constrained on purpose: the
        // report VALUE is free-running markdown prose; JSON mode alone keeps the
        // envelope parseable.
        ChatModel dossierModel = OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(1536)
                .responseFormat(ResponseFormat.JSON)
                .think(think)
                .timeout(timeout)
                .build();

        // Deep-dive model — the SAME gemma4 (same name + num_ctx, still ONE
        // runner), FREE-FORM like the prose model but with the roomiest
        // numPredict of the fleet: a full KI-DD pass returns the ENTIRE revised
        // report (~3-4k chars of markdown plus its own restatement overhead),
        // and 2560 was measured cutting the final pass mid-sentence (out=2560
        // in the live logs — "Der Raum" lost entirely). On-demand only (report
        // generation), so the fatter budget never competes with the wire's
        // steady-state calls.
        ChatModel deepDiveModel = OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(3584)
                .think(think)
                .timeout(timeout)
                .build();

        return new Models(agentModel, composeModel, proseModel, dossierModel,
                deepDiveModel, agentName);
    }

    /**
     * The schema-constrained compose response format: exactly
     * {@code {headline, trigger, highlight, sentiment, derivedFrom, newsUsed}} with
     * the enums closed and the provenance ordinal arrays.
     */
    private static ResponseFormat buildComposeSchema() {
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name("headline")
                        .rootElement(JsonObjectSchema.builder()
                                .addStringProperty("headline")
                                .addEnumProperty("trigger", java.util.List.of(
                                        "NONE", "RUNNER", "SQUEEZE", "BREAKOUT",
                                        "HARD_CATALYST", "POOLED_CALL", "EXTREME_DIRECTION"))
                                .addEnumProperty("highlight", java.util.List.of("NORMAL", "IMPORTANT"))
                                .addEnumProperty("sentiment", java.util.List.of(
                                        "BULLISH", "BEARISH", "MIXED", "FOMO", "CAPITULATION",
                                        "SQUEEZE", "REVERSAL", "BREAKOUT", "NEUTRAL"))
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
     * Verifies the target model exists in Ollama, falling back to any installed
     * model from the same family to prevent crashes.
     */
    private String resolveModel(String target, String familyPrefix) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags")).GET().build();
            String json = new String(
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body());

            if (json.contains("\"" + target + "\"")) {
                return target;
            }

            LOG.warn("Model '{}' not found. Resolving fallback for family '{}'...", target, familyPrefix);

            Pattern p = Pattern.compile("\"name\":\"(" + Pattern.quote(familyPrefix) + "[^\"]*)\"");
            Matcher m = p.matcher(json);
            if (m.find()) {
                String fallback = m.group(1);
                LOG.warn("Auto-resolved '{}' → '{}'", target, fallback);
                return fallback;
            }

            throw new IllegalStateException("No installed model found for family: " + familyPrefix);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ollama connection failed: " + e.getMessage(), e);
        }
    }
}
