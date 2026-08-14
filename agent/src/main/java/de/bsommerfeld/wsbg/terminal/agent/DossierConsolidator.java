package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.DossierFact;
import de.bsommerfeld.wsbg.terminal.db.SubjectDossierArchive;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps a subject's permanent dossier a dense Steckbrief instead of an
 * ever-growing log: past {@link #MAX_FACTS}, the OLDEST facts (all but the
 * newest {@link #KEEP_RECENT}) are folded into a few dated summary lines by one
 * prose call, and the archive is rewritten atomically. Runs on the compose
 * worker's tail, behind the same {@link ChatGateway} gate as every wire call —
 * and only rarely, so it is upkeep, not load.
 *
 * <p>The named loss: the folded facts' per-article granularity (and their
 * value-copied source refs) is gone after a fold — the summary lines carry no
 * single source. The told story itself is still held forever by the headline
 * archive; a failed model call folds nothing (the dossier just stays long
 * until the next attempt).
 */
final class DossierConsolidator {

    private static final Logger LOG = LoggerFactory.getLogger(DossierConsolidator.class);

    /** Facts a subject key may hold before a fold is due. */
    static final int MAX_FACTS = 40;

    /** Newest facts that keep their per-article granularity through a fold. */
    static final int KEEP_RECENT = 24;

    /** Summary lines accepted per fold — the prompt asks for 2–6. */
    private static final int MAX_SUMMARY_LINES = 6;

    /** A single summary line's cap (a runaway model reply must not eat the dossier). */
    private static final int SUMMARY_LINE_MAX_CHARS = 300;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final AgentBrain brain;
    private final ChatGateway chatGateway;
    private final SubjectDossierArchive archive;

    DossierConsolidator(AgentBrain brain, ChatGateway chatGateway, SubjectDossierArchive archive) {
        this.brain = brain;
        this.chatGateway = chatGateway;
        this.archive = archive;
    }

    /** Folds the unit's dossier when it has outgrown {@link #MAX_FACTS}; a no-op otherwise. */
    void consolidateIfOvergrown(SubjectUnit unit) {
        if (unit == null || !unit.isInstrument()) return;
        String key = unit.ticker().trim().toUpperCase(Locale.ROOT);
        if (archive.factCount(key) <= MAX_FACTS) return;
        ChatModel model = brain.getProseModel();
        if (model == null) return;

        // Exact-key facts only: an ISIN twin's facts stay under its own key —
        // the read-side union presents them together anyway.
        List<DossierFact> all = new ArrayList<>();
        for (DossierFact f : archive.bySubject(key, null)) {
            if (key.equalsIgnoreCase(f.subjectKey())) all.add(f);
        }
        if (all.size() <= MAX_FACTS) return;
        List<DossierFact> fold = all.subList(0, all.size() - KEEP_RECENT);
        List<DossierFact> keep = all.subList(all.size() - KEEP_RECENT, all.size());

        StringBuilder input = new StringBuilder();
        input.append(unit.canonicalName()).append(" (").append(key).append(")\n\n");
        for (DossierFact f : fold) {
            input.append("- [").append(DAY.format(Instant.ofEpochSecond(f.atEpoch())));
            if (f.sourcePublisher() != null && !f.sourcePublisher().isBlank()) {
                input.append(", ").append(f.sourcePublisher());
            }
            input.append("] ").append(f.text().replace('\n', ' ')).append('\n');
        }
        try {
            String sys = PromptLoader.loadLocalized("dossier-consolidate",
                    brain.getUserLanguage().code());
            String reply = chatGateway.chat(model, sys, input.toString());
            List<String> lines = parseSummaries(reply);
            if (lines.isEmpty()) {
                LOG.warn("[DOSSIER] consolidation for {} returned nothing usable — keeping the long dossier", key);
                return;
            }
            // Summary lines carry the newest folded fact's timestamp so they sort
            // (and render their age) ahead of the kept recent facts.
            long foldedUpTo = fold.get(fold.size() - 1).atEpoch();
            List<DossierFact> replacement = new ArrayList<>();
            for (String line : lines) {
                replacement.add(new DossierFact(key, unit.isin(), unit.canonicalName(),
                        line, foldedUpTo, null, null, null, null, true));
            }
            replacement.addAll(keep);
            archive.rewriteSubject(key, replacement);
            LOG.info("[DOSSIER] {} consolidated: {} fact(s) folded into {} summary line(s), {} recent kept",
                    key, fold.size(), lines.size(), keep.size());
        } catch (Exception e) {
            LOG.warn("[DOSSIER] consolidation failed for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Reply → summary lines: one per line, bullets/numbering stripped, capped in
     * count and length. Package-private for testing.
     */
    static List<String> parseSummaries(String reply) {
        if (reply == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (String raw : reply.split("\n")) {
            String line = raw.strip()
                    .replaceFirst("^[-*•]\\s*", "")
                    .replaceFirst("^\\d+[.)]\\s*", "")
                    .strip();
            if (line.isEmpty()) continue;
            if (line.length() > SUMMARY_LINE_MAX_CHARS) {
                line = line.substring(0, SUMMARY_LINE_MAX_CHARS) + "…";
            }
            lines.add(line);
            if (lines.size() >= MAX_SUMMARY_LINES) break;
        }
        return lines;
    }
}
