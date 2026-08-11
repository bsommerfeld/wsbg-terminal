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
            ChatModel dossierModel, ChatModel deepDiveModel, ChatModel deliberateModel,
            ChatModel verdictModel, String activeAgentModel) {
    }

    /**
     * The fixed seed of the verdict lane. Any constant does the job - what
     * matters is that it never changes between two runs of the same sheet.
     */
    static final int VERDICT_SEED = 20260804;

    /**
     * The ONE output ceiling for every lane. It used to be six hand-tuned
     * numbers (768/1024/1536/3584), each argued from the shape its lane
     * happened to emit — and every one of them was a latent truncation waiting
     * for its lane's output to grow. The 768 did exactly that: it was sized for
     * a ~300-token subject array, the DD triage later grew a per-item reason,
     * and ten of ~24 triage calls came back cut off (2026-08-11).
     *
     * <p>
     * A ceiling is not a reservation. Ollama allocates nothing for it, so a
     * lane that emits 200 tokens costs exactly 200 tokens whether the ceiling
     * sits at 1024 or 3584 — the tight numbers bought nothing and cost
     * verdicts. They were also copied by hand into {@code DeepDiveService} and
     * {@code WeatherReportService}, which need the output half to size their
     * char budgets; one number means there is nothing left to mirror.
     *
     * <p>
     * 3584 is the roomiest lane's measured need: a full KI-DD pass returns the
     * ENTIRE revised report, and 2560 was measured cutting the final pass
     * mid-sentence ("Der Raum" lost entirely).
     */
    static final int NUM_PREDICT = 3584;

    /**
     * Stands up all model instances on the ONE resident model. The concrete tag
     * comes from {@link AgentConfig#resolveModelTag()} — the user's hardware-based
     * choice (gemma4:e2b..26b, -mlx twins on Apple Silicon) or the managed default
     * gemma4:e4b. All variants share the same model name AND the same num_ctx, so
     * Ollama keeps a single runner resident.
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
        Model agentModelEnum = config.resolveEditorialModel();
        String agentName = askOllama
                ? resolveModel(config.resolveModelTag(), agentModelEnum.getFamilyPrefix())
                : config.resolveModelTag();

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
                // (No `\n\n` stop — it truncated extraction to empty when the
                // model led with a blank line; see composeModel.)
                .numCtx(ctxTokens).numPredict(NUM_PREDICT)
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
                .numCtx(ctxTokens).numPredict(NUM_PREDICT)
                .responseFormat(buildComposeSchema())
                .think(think)
                .timeout(timeout)
                .build();

        // Prose model — the SAME gemma4 (same name + num_ctx, still ONE runner),
        // but FREE-FORM output: no responseFormat at all. Used by the daily
        // Wetterbericht map-reduce, whose two stages (digest lines, final report
        // prose) are plain text — JSON mode would only add escaping/truncation
        // risk to running prose.
        ChatModel proseModel = OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(NUM_PREDICT)
                .think(think)
                .timeout(timeout)
                .build();

        // Dossier model — the SAME gemma4 (same name + num_ctx, still ONE runner),
        // plain JSON mode like the agent model. Not schema-constrained on purpose: the
        // report VALUE is free-running markdown prose; JSON mode alone keeps the
        // envelope parseable.
        ChatModel dossierModel = OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(NUM_PREDICT)
                .responseFormat(ResponseFormat.JSON)
                .think(think)
                .timeout(timeout)
                .build();

        // Deep-dive model — the SAME gemma4 (same name + num_ctx, still ONE
        // runner), FREE-FORM like the prose model. The fleet's ceiling
        // (NUM_PREDICT) is sized on THIS lane's need. On-demand only (report
        // generation), so the budget never competes with the wire's
        // steady-state calls.
        ChatModel deepDiveModel = OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(NUM_PREDICT)
                .think(think)
                .timeout(timeout)
                .build();

        // Thinking is STRUCK from the fleet (user decree 2026-08-05): it only
        // ever existed for the tool-call era, and on the DD judge tables it
        // was measured spiralling to the num_predict ceiling with the visible
        // verdict truncated to NOTHING (14/14 truncated calls, every
        // conference abstaining). The "deliberate" seam stays as a name so
        // the judge lanes remain separately routable, but it IS the sober
        // deep-dive model.
        // Verdict model - the SAME gemma4 (same name + num_ctx, still ONE
        // runner: temperature, top-k/p and seed are PER-REQUEST parameters and
        // do not fork a second weight copy), but decoded DETERMINISTICALLY.
        // The closing assessment's whole construction rests on "same fact base
        // -> same number"; the house owns the arithmetic, and the one thing the
        // model still decides - which anchor to follow, downgrade or reject -
        // must not come out differently on a second run of the same sheet.
        // temperature 0 + topK 1 makes the decode greedy, topP 1 keeps the
        // nucleus from re-introducing a cut, and the fixed seed pins whatever
        // tie-breaking is left. Free-form output: the reply is closed move
        // lines, which a JSON envelope would only wrap in escaping risk.
        ChatModel verdictModel = OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(agentName)
                .temperature(0.0).topP(1.0).topK(1)
                .seed(VERDICT_SEED)
                .numCtx(ctxTokens).numPredict(NUM_PREDICT)
                .think(think)
                .timeout(timeout)
                .build();

        return new Models(agentModel, composeModel, proseModel, dossierModel,
                deepDiveModel, deepDiveModel, verdictModel, agentName);
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
     * model from the same family to prevent crashes. The target's OWN family is
     * tried first and the anchor family only after. The catalog is single-family
     * again since 2026-08-11, so the two coincide today - the ordering stays
     * because it is what makes a second family safe to add back.
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
