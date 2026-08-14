package de.bsommerfeld.wsbg.terminal.agent;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Distills a unit's freshly-arrived room evidence into 1–3 short fact lines and
 * appends them to the unit's rolling fact sheet ({@link SubjectUnit#factSheet()}).
 * Runs AFTER a compose (any non-whiff outcome), off the headline's critical path
 * but on the compose worker thread — the call rides the same {@link ChatGateway}
 * gate as every other wire call, so it can never outrank one.
 *
 * <p>News is deliberately NOT absorbed here: articles keep their own brief block
 * with the [N#] provenance ordinals; the sheet carries what the ROOM said, which
 * the evidence prune would otherwise silently lose.
 */
final class FactSheetUpdater {

    private static final Logger LOG = LoggerFactory.getLogger(FactSheetUpdater.class);

    /** New fact lines accepted per absorb — the prompt asks for 1–3; extras are model runaway. */
    private static final int MAX_NEW_FACTS = 3;

    /** A single fact line's cap (a runaway model reply must not eat the sheet). */
    private static final int FACT_LINE_MAX_CHARS = 240;

    /** Char budget for the fresh-evidence input, newest kept — mirrors the brief's evidence economy. */
    private static final int EVIDENCE_INPUT_BUDGET = 4000;

    /** The prompt's declared "nothing factual here" verdict. */
    private static final String EMPTY_VERDICT = "EMPTY";

    private final AgentBrain brain;
    private final ChatGateway chatGateway;

    FactSheetUpdater(AgentBrain brain, ChatGateway chatGateway) {
        this.brain = brain;
        this.chatGateway = chatGateway;
    }

    /**
     * Distills {@code fresh} (the unit's evidence beyond its watermark, captured
     * BEFORE the compose so mid-compose arrivals stay in the inbox) onto the
     * unit's sheet and advances the watermark. A model failure leaves the
     * watermark untouched — the same evidence simply re-feeds the next absorb.
     */
    void absorb(SubjectUnit unit, List<SubjectUnit.EvidenceRef> fresh) {
        if (unit == null || fresh == null || fresh.isEmpty()) return;
        ChatModel model = brain.getProseModel();
        if (model == null) return; // brain not ready (tests, startup) — inert
        long upTo = 0;
        for (SubjectUnit.EvidenceRef e : fresh) {
            if (e.addedAtEpoch() > upTo) upTo = e.addedAtEpoch();
        }
        String lang = brain.getUserLanguage().code();
        BriefLabels lbl = BriefLabels.of(lang);
        String sys = PromptLoader.loadLocalized("fact-sheet-update", lang);
        String user = buildInput(unit, fresh, lbl);
        try {
            String reply = chatGateway.chat(model, sys, user);
            List<String> lines = parseFacts(reply);
            unit.appendFacts(lines, upTo);
            LOG.info("[FACTS] {} ({}): {} mention(s) → {} fact line(s), sheet now {} line(s)",
                    unit.id, unit.canonicalName(), fresh.size(), lines.size(),
                    unit.factSheet().size());
        } catch (Exception e) {
            // Watermark NOT advanced: the inbox stays, the next compose re-tries.
            LOG.warn("[FACTS] absorb failed for {} ({}): {}",
                    unit.id, unit.canonicalName(), e.getMessage());
        }
    }

    /** Subject + current sheet + the fresh mentions (newest kept within budget). */
    private static String buildInput(SubjectUnit unit, List<SubjectUnit.EvidenceRef> fresh,
            BriefLabels lbl) {
        StringBuilder sb = new StringBuilder();
        sb.append(lbl.factsInputSubject(unit.canonicalName()));
        List<SubjectUnit.FactLine> sheet = unit.factSheet();
        if (!sheet.isEmpty()) {
            sb.append(lbl.factsInputKnown());
            for (SubjectUnit.FactLine f : sheet) {
                sb.append("- ").append(f.text()).append('\n');
            }
        }
        sb.append(lbl.factsInputFresh());
        int start = fresh.size();
        int budget = EVIDENCE_INPUT_BUDGET;
        while (start > 0 && budget - fresh.get(start - 1).snippet().length() - 4 >= 0) {
            start--;
            budget -= fresh.get(start).snippet().length() + 4;
        }
        for (SubjectUnit.EvidenceRef e : fresh.subList(start, fresh.size())) {
            sb.append("- ").append(e.snippet()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Reply → fact lines: one per line, leading bullets/numbering stripped, the
     * {@code EMPTY} verdict (and blank noise) dropped, capped in count and length.
     * Package-private for testing.
     */
    static List<String> parseFacts(String reply) {
        if (reply == null) return List.of();
        List<String> facts = new ArrayList<>();
        for (String raw : reply.split("\n")) {
            String line = raw.strip()
                    .replaceFirst("^[-*•]\\s*", "")
                    .replaceFirst("^\\d+[.)]\\s*", "")
                    .strip();
            if (line.isEmpty() || EMPTY_VERDICT.equalsIgnoreCase(line)) continue;
            if (line.length() > FACT_LINE_MAX_CHARS) {
                line = line.substring(0, FACT_LINE_MAX_CHARS) + "…";
            }
            facts.add(line);
            if (facts.size() >= MAX_NEW_FACTS) break;
        }
        return facts;
    }
}
