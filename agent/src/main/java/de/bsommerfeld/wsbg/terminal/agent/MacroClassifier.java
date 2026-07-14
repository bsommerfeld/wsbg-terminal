package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sorts economic-calendar indicator TITLES into the market memory's macro
 * groups — the {@link AdhocClassifier} pattern for the macro-surprise leg:
 * the surprise DIRECTION is deterministic (actual vs forecast), only the
 * question "which kind of quantity is this" needs the model, as a discrete
 * enum choice. Calendar titles recur (the same "Core CPI m/m" every month),
 * so the harvester caches verdicts per title — the model classifies each
 * indicator roughly once, ever.
 *
 * <p>Same verdict discipline as the ad-hoc judge: an unknown token or a
 * missing item yields NO verdict, never a guess.
 */
@Singleton
class MacroClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(MacroClassifier.class);

    /** The closed group list — must match the prompt twins token for token. */
    static final Set<String> GROUPS = Set.of(
            "INFLATION", "ARBEITSMARKT", "WACHSTUM", "ZINSENTSCHEID", "STIMMUNG", "SONSTIGES");

    private final AgentBrain brain;
    private final ChatGateway gateway;

    @Inject
    MacroClassifier(AgentBrain brain, LlmGate llmGate) {
        this.brain = brain;
        this.gateway = new ChatGateway(brain, llmGate);
    }

    /**
     * One judge call over a small batch of indicator titles: 1-based index →
     * group token. Items the model skipped or answered with an unknown token
     * are simply absent. Empty map on any failure.
     */
    Map<Integer, String> classify(List<String> titles) {
        if (titles == null || titles.isEmpty()) return Map.of();
        try {
            String system = PromptLoader.loadLocalized("macro-classify",
                    brain.getUserLanguage().code());
            StringBuilder items = new StringBuilder("ITEMS:\n");
            for (int i = 0; i < titles.size(); i++) {
                items.append(i + 1).append(". ").append(titles.get(i)).append('\n');
            }
            String raw = gateway.chat(brain.getAgentModel(), system, items.toString());
            return parseVerdicts(raw, titles.size());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Macro classification failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /** Package-private for tests: reply JSON → validated verdict map. */
    static Map<Integer, String> parseVerdicts(String raw, int itemCount) {
        Map<Integer, String> out = new LinkedHashMap<>();
        JsonNode root = JsonReplies.parseJson(raw);
        if (root == null) return out;
        for (JsonNode entry : root.path("groups")) {
            int i = entry.path("i").asInt(-1);
            String token = entry.path("group").asText("").trim().toUpperCase();
            if (i < 1 || i > itemCount || !GROUPS.contains(token)) continue;
            out.putIfAbsent(i, token);
        }
        return out;
    }
}
