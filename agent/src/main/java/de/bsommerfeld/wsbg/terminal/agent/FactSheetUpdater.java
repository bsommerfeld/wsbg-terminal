package de.bsommerfeld.wsbg.terminal.agent;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a unit's ROOM SHEET ({@link SubjectUnit#factSheet()}): 1–3 short lines
 * per round on what the room claims about the subject <em>and</em> what mood it
 * says it in. Runs AFTER a compose (any non-whiff outcome), off the headline's
 * critical path but on the compose worker thread — the call rides the same
 * {@link ChatGateway} gate as every other wire call, so it can never outrank one.
 *
 * <p><b>Naming trap:</b> the field is called {@code factSheet} but the brief
 * renders it as RAUM-BLATT, the room's unverified voice. The block the brief
 * labels FAKTENBLATT is the news dossier
 * ({@link de.bsommerfeld.wsbg.terminal.db.SubjectDossierArchive}) — verified
 * article facts, room sentiment explicitly barred. Two sheets, two domains:
 * facts come from news, mood comes from the room.
 *
 * <p>News is deliberately NOT absorbed here: articles keep their own brief block
 * with the [N#] provenance ordinals; the sheet carries what the ROOM said, which
 * the evidence prune would otherwise silently lose.
 *
 * <p><b>{@code MOOD} evidence is material here, not noise.</b> The thread's
 * undirected chatter reaches no other consumer — the compose brief drops it — so
 * this is where a subject that the room talks <em>around</em> rather than about
 * still leaves a trace. Sampling it is legitimate in a way it never was for
 * mentions: a mood read from 20 of 200 comments is the same mood, whereas 20 of
 * 200 mentions is a hole. That is why the input budget below serves mentions
 * first and lets the chatter take what is left.
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
        int[] droppedMentions = new int[1];
        String user = buildInput(unit, fresh, lbl, droppedMentions);
        try {
            String reply = chatGateway.chat(model, sys, user);
            List<String> lines = parseFacts(reply);
            unit.appendFacts(lines, upTo);
            LOG.info("[FACTS] {} ({}): {} ref(s) → {} sheet line(s), sheet now {} line(s)",
                    unit.id, unit.canonicalName(), fresh.size(), lines.size(),
                    unit.factSheet().size());
            // Never a silent cap: the watermark advances over the WHOLE inbox, so a
            // mention the budget could not fit reaches no later round either. It was
            // still rendered raw in the compose brief that just ran (which serves
            // mentions first too) — the loss is long-term memory, not visibility.
            if (droppedMentions[0] > 0) {
                LOG.warn("[FACTS] {} ({}): {} mention(s) did not fit the sheet input budget"
                        + " — absorbed without being read",
                        unit.id, unit.canonicalName(), droppedMentions[0]);
            }
        } catch (Exception e) {
            // Watermark NOT advanced: the inbox stays, the next compose re-tries.
            LOG.warn("[FACTS] absorb failed for {} ({}): {}",
                    unit.id, unit.canonicalName(), e.getMessage());
        }
    }

    /**
     * Subject + current sheet + the fresh material, in two blocks: the mentions of
     * the subject, then the room around them. Newest kept within one shared budget,
     * <b>mentions first</b> — they are the scarce, non-substitutable half, and the
     * walk-from-the-end would otherwise hand the whole budget to the chatter, which
     * is appended last and outnumbers them by an order of magnitude.
     *
     * @return the rendered input; {@code droppedMentions} of the caller's counter
     *         records how many mentions the budget still could not fit
     */
    private static String buildInput(SubjectUnit unit, List<SubjectUnit.EvidenceRef> fresh,
            BriefLabels lbl, int[] droppedMentions) {
        List<SubjectUnit.EvidenceRef> mentions = new ArrayList<>();
        List<SubjectUnit.EvidenceRef> around = new ArrayList<>();
        for (SubjectUnit.EvidenceRef e : fresh) {
            (e.isStory() ? mentions : around).add(e);
        }
        int budget = EVIDENCE_INPUT_BUDGET;
        int mStart = mentions.size();
        while (mStart > 0 && budget - mentions.get(mStart - 1).snippet().length() - 4 >= 0) {
            mStart--;
            budget -= mentions.get(mStart).snippet().length() + 4;
        }
        int aStart = around.size();
        while (aStart > 0 && budget - around.get(aStart - 1).snippet().length() - 4 >= 0) {
            aStart--;
            budget -= around.get(aStart).snippet().length() + 4;
        }
        droppedMentions[0] = mStart;

        StringBuilder sb = new StringBuilder();
        sb.append(lbl.factsInputSubject(unit.canonicalName()));
        List<SubjectUnit.FactLine> sheet = unit.factSheet();
        if (!sheet.isEmpty()) {
            sb.append(lbl.factsInputKnown());
            for (SubjectUnit.FactLine f : sheet) {
                sb.append("- ").append(f.text()).append('\n');
            }
        }
        if (mStart < mentions.size()) {
            sb.append(lbl.factsInputFresh());
            for (SubjectUnit.EvidenceRef e : mentions.subList(mStart, mentions.size())) {
                sb.append("- ").append(e.snippet()).append('\n');
            }
        }
        if (aStart < around.size()) {
            sb.append(lbl.factsInputMood());
            for (SubjectUnit.EvidenceRef e : around.subList(aStart, around.size())) {
                sb.append("- ").append(e.snippet()).append('\n');
            }
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
