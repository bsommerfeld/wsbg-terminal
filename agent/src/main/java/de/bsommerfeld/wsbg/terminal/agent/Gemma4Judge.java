package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * The two discrete gemma4 JSON-mode judge calls: the resolver's identity/tier-2
 * instrument match, and the same-story near-duplicate check. Both build a numbered
 * candidate/prior prompt, call the model through the shared {@link ChatGateway}, and
 * parse a single {@code {match}} / {@code {repeat}} field. Extracted verbatim from
 * {@link EditorialAgent}.
 *
 * <p><b>Fail semantics are load-bearing:</b> {@link #matchInstrument} fails CLOSED
 * ({@code -1}, never a guess), {@link #isSameStoryRepeat} fails OPEN ({@code false},
 * never blocks a publish).
 */
final class Gemma4Judge {

    private static final Logger LOG = LoggerFactory.getLogger(Gemma4Judge.class);

    private final AgentBrain brain;
    private final ChatGateway chatGateway;

    Gemma4Judge(AgentBrain brain, ChatGateway chatGateway) {
        this.brain = brain;
        this.chatGateway = chatGateway;
    }

    /**
     * The resolver's identity judge ({@link TickerResolver.MatchJudge}): a discrete
     * gemma4 pick — "which of these search candidates IS the subject, or none" —
     * where the old embedding-cosine ranker matched on sounds-alike and promoted
     * themes to bogus tickers. Serves both the tier-2 fallback AND the tier-1 veto
     * (verdicts cached in the resolver, ~one call per unique subject). The thread
     * title rides along as context so a generic word is separable from a same-named
     * instrument. One small JSON-mode call, gated by the shared llm semaphore like
     * every other model call. Fail-closed: any error or model absence returns -1
     * (unresolved), never a guess.
     */
    int matchInstrument(String subject, String context, List<String> candidateNames) {
        ChatModel model = brain.getAgentModel();
        if (model == null || subject == null || candidateNames == null || candidateNames.isEmpty()) {
            return -1;
        }
        try {
            String sys = PromptLoader.loadLocalized("ticker-match", brain.getUserLanguage().code());
            StringBuilder user = new StringBuilder("SUBJECT: ").append(subject).append('\n');
            if (context != null && !context.isBlank()) {
                user.append("CONTEXT: ").append(context.strip()).append('\n');
            }
            for (int i = 0; i < candidateNames.size(); i++) {
                user.append(i + 1).append(". ").append(candidateNames.get(i)).append('\n');
            }
            JsonNode obj = JsonReplies.parseJson(chatGateway.chat(model, sys, user.toString()));
            int match = obj == null ? 0 : obj.path("match").asInt(0);
            return match >= 1 && match <= candidateNames.size() ? match - 1 : -1;
        } catch (Exception e) {
            LOG.debug("resolver identity judge failed (fail-closed): {}", e.getMessage());
            return -1;
        }
    }

    /** Only priors this recent are compared — an old story may legitimately resurface.
     *  2h: live pairs 1.5h apart were still verbatim re-tells of an unmoved story. */
    private static final long SEMANTIC_DUP_WINDOW_SECS = 7200;

    /**
     * True when {@code line} is a re-tell of one of the unit's recent headlines
     * (last {@link #SEMANTIC_DUP_WINDOW_SECS}): one small gemma4 verdict over the
     * fresh line + the numbered recent priors ({@code same-story-check} prompt) —
     * a discrete judgment that replaced the old embedding-cosine threshold, whose
     * calibration was language-sensitive and had to be re-tuned on live pairs.
     * The prompt carries the novel-figure semantics (a fresh figure is a
     * development; a merely ticked live number on the same sentence is not).
     * Fail-open: a model error or absence never blocks a publish.
     */
    boolean isSameStoryRepeat(String line, List<SubjectUnit.UnitHeadline> priors) {
        if (line == null || line.isBlank() || priors.isEmpty()) return false;
        long now = Instant.now().getEpochSecond();
        List<SubjectUnit.UnitHeadline> recent = priors.stream()
                .filter(p -> now - p.atEpoch() <= SEMANTIC_DUP_WINDOW_SECS)
                .toList();
        if (recent.isEmpty()) return false;
        ChatModel model = brain.getAgentModel();
        if (model == null) return false;
        try {
            String sys = PromptLoader.loadLocalized("same-story-check", brain.getUserLanguage().code());
            StringBuilder user = new StringBuilder("FRESH LINE: ").append(line).append('\n');
            for (int i = 0; i < recent.size(); i++) {
                user.append(i + 1).append(". ").append(recent.get(i).text()).append('\n');
            }
            JsonNode obj = JsonReplies.parseJson(chatGateway.chat(model, sys, user.toString()));
            return obj != null && obj.path("repeat").asBoolean(false);
        } catch (Exception e) {
            LOG.debug("semantic dup check failed (fail-open): {}", e.getMessage());
            return false;
        }
    }
}
