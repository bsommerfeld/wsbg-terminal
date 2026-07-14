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
 * Classifies German ad-hoc disclosure TITLES into the market memory's closed
 * event-class list — the one place the model participates in the memory, and
 * deliberately as a discrete enum choice, not free text (the compose schema's
 * trigger-enum lesson: a 4B model is far more consistent picking one of
 * twelve tokens than judging in the abstract). German ad-hocs carry no
 * official category enum (unlike 8-K items), so the formulaic title is the
 * classification surface.
 *
 * <p>Verdict discipline: an unknown token, a missing item or a parse failure
 * yields NO verdict for that item — never a guessed class (a wrong class
 * poisons the base-rate statistics; the harvester retries and eventually
 * gives up to SONSTIGES). Batches stay small so a title can never drown.
 */
@Singleton
class AdhocClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(AdhocClassifier.class);

    /** The closed class list — must match the prompt twins token for token. */
    static final Set<String> CLASSES = Set.of(
            "GEWINNWARNUNG", "PROGNOSEANHEBUNG", "KAPITALERHOEHUNG", "UEBERNAHME",
            "ZULASSUNG", "GROSSAUFTRAG", "INSOLVENZ", "RESTRUKTURIERUNG",
            "FUEHRUNGSWECHSEL", "DIVIDENDE", "RESTATEMENT", "SONSTIGES");

    private final AgentBrain brain;
    private final ChatGateway gateway;

    @Inject
    AdhocClassifier(AgentBrain brain, LlmGate llmGate) {
        this.brain = brain;
        this.gateway = new ChatGateway(brain, llmGate);
    }

    /**
     * One judge call over a small batch of titles: 1-based index → class
     * token. Items the model skipped or answered with an unknown token are
     * simply absent. Empty map on any failure.
     */
    Map<Integer, String> classify(List<String> titles) {
        if (titles == null || titles.isEmpty()) return Map.of();
        try {
            String system = PromptLoader.loadLocalized("event-classify",
                    brain.getUserLanguage().code());
            StringBuilder items = new StringBuilder("ITEMS:\n");
            for (int i = 0; i < titles.size(); i++) {
                items.append(i + 1).append(". ").append(titles.get(i)).append('\n');
            }
            String raw = gateway.chat(brain.getAgentModel(), system, items.toString());
            return parseVerdicts(raw, titles.size());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Ad-hoc classification failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /** Package-private for tests: reply JSON → validated verdict map. */
    static Map<Integer, String> parseVerdicts(String raw, int itemCount) {
        Map<Integer, String> out = new LinkedHashMap<>();
        JsonNode root = JsonReplies.parseJson(raw);
        if (root == null) return out;
        for (JsonNode entry : root.path("classes")) {
            int i = entry.path("i").asInt(-1);
            String token = entry.path("class").asText("").trim().toUpperCase();
            if (i < 1 || i > itemCount || !CLASSES.contains(token)) continue;
            out.putIfAbsent(i, token);
        }
        return out;
    }
}
