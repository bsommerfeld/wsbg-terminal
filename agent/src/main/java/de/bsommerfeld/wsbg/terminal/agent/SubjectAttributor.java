package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.SubjectUnit.EvidenceRef;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wires a cluster's resolved subjects into the {@link SubjectRegistry} (#2):
 * for each subject it finds the thread/comments that mention it and attaches
 * them as evidence to the unit (by ticker or normalised name). Multi-membership
 * falls out naturally — a comment naming "NVIDIA, AMD oder Intel" is attributed
 * to all three units.
 *
 * <h3>Matching (word-level, not substring)</h3>
 * The subject is stored normalised ("Meta Platforms, Inc.", "Münchener
 * Rückversicherungs-Gesellschaft…") while the room writes the short/native form
 * ("Meta", "Münchener rück"). So matching is on <b>significant words</b> of the
 * name (+ the ticker via {@link TickerExtractor}), not a full-string substring:
 * a comment shares a word like {@code meta} / {@code münchener} → match.
 * Generic company words ({@code inc}, {@code holdings}, …) are filtered so they
 * never carry a match on their own. Matching is deliberately lenient — context
 * is "never decisive", a missed mention costs more than a loose one.
 *
 * <h3>No phantom units</h3>
 * A subject the model named but no thread/comment actually mentions (a
 * hallucination, or a name the matcher genuinely can't tie to the text) gets
 * <b>zero evidence and is dropped</b> — a unit must reflect something the room
 * really said.
 */
public final class SubjectAttributor {

    /** Generic words that must never carry a match by themselves. */
    private static final Set<String> STOP = Set.of(
            "inc", "incorporated", "corp", "corporation", "company", "holdings",
            "holding", "group", "the", "and", "für", "und", "fund", "trust",
            "plc", "ltd", "limited", "gmbh", "kgaa", "aktiengesellschaft",
            "gesellschaft", "technologies", "technology", "international",
            "systems", "solutions", "index", "etf");

    private final RedditRepository repository;
    private final AgentBrain brain;

    public SubjectAttributor(RedditRepository repository, AgentBrain brain) {
        this.repository = repository;
        this.brain = brain;
    }

    /** Attributes every resolved subject of {@code cluster} into {@code registry}. */
    public void attribute(SubjectRegistry registry, InvestigationCluster cluster,
            List<ResolvedSubject> resolved) {
        long now = Instant.now().getEpochSecond();
        for (ResolvedSubject rs : resolved) {
            // A Yahoo-rate-limited subject (rs.unresolved()) is NOT skipped — the
            // ROOM is the story, Yahoo only enriches. It's attributed as a tickerless
            // unit and still gets a headline from the evidence; when Yahoo recovers it
            // re-resolves to its ticker and the identity-merge folds the duplicate.
            // (Headlines must never depend on Yahoo being up.)
            String ticker = rs.isInstrument() ? rs.ticker().toUpperCase(Locale.ROOT) : null;
            Set<String> words = nameWords(rs.query(), rs.canonicalName());
            if (words.isEmpty() && ticker == null) continue;

            // Collect evidence first — a subject with no real mention is dropped.
            List<EvidenceRef> found = new ArrayList<>();
            for (String threadId : cluster.activeThreadIds) {
                RedditThread t = repository.getThread(threadId);
                if (t == null) continue;
                if (matches(nz(t.title()) + " " + nz(t.textContent()), words, ticker)) {
                    found.add(new EvidenceRef(threadId, null, snippet(t.title()), "reddit", now));
                }
                // Image content is a normal evidence source, like a comment — a
                // portfolio/watchlist screenshot, a meme of a person, a product shot.
                // Yahoo stays pure enrichment; the picture itself is the context.
                String tv = visionText(t.imageUrls());
                if (!tv.isEmpty() && matches(tv, words, ticker)) {
                    found.add(new EvidenceRef(threadId, "_vision",
                            snippet(matchingLine(tv, words, ticker)), "vision", now));
                }
                for (RedditComment c : repository.getCommentsForThread(threadId, 0)) {
                    if (c.body() != null && !c.body().isBlank() && matches(c.body(), words, ticker)) {
                        found.add(new EvidenceRef(threadId, c.id(), snippet(c.body()), "reddit", now));
                    }
                    String cv = visionText(c.imageUrls());
                    if (!cv.isEmpty() && matches(cv, words, ticker)) {
                        found.add(new EvidenceRef(threadId, c.id() + "#img",
                                snippet(matchingLine(cv, words, ticker)), "vision", now));
                    }
                }
            }
            if (found.isEmpty()) continue; // no real mention → no phantom unit

            String id = unitKey(rs);
            if (id == null) continue;
            SubjectUnit unit = registry.findOrCreate(id, rs.canonicalName());
            unit.updateResolved(rs.canonicalName(), rs.ticker(), rs.snapshot(), rs.news());
            // Dirty ONLY when this attribution actually added new evidence. Re-running
            // attribution over an unchanged cluster (same comments) must NOT re-wake a
            // unit — otherwise every pass re-composes the whole feed. A price refresh
            // alone (updateResolved) is not a reason to re-write a headline.
            boolean gainedEvidence = false;
            for (EvidenceRef ref : found) gainedEvidence |= unit.addEvidence(ref);
            if (gainedEvidence) registry.markDirty(id);
        }
    }

    /** Identity key: ticker for instruments, normalised canonical name otherwise. */
    static String unitKey(ResolvedSubject rs) {
        if (rs.isInstrument()) return rs.ticker().toUpperCase(Locale.ROOT);
        String name = rs.canonicalName();
        if (name == null || name.isBlank()) name = rs.query();
        if (name == null || name.isBlank()) return null;
        return "name:" + name.trim().toLowerCase(Locale.ROOT);
    }

    /** Significant words of the room's term + the canonical name (stop-words dropped). */
    static Set<String> nameWords(String query, String canonical) {
        Set<String> out = new LinkedHashSet<>();
        addWords(out, query);
        addWords(out, canonical);
        return out;
    }

    /** Significant words of a single string (stop-words + sub-3-char dropped). */
    static Set<String> significantWords(String s) {
        Set<String> out = new LinkedHashSet<>();
        addWords(out, s);
        return out;
    }

    private static void addWords(Set<String> out, String s) {
        if (s == null) return;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9äöüß]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
        }
    }

    /** Joined cached vision transcripts for a set of image URLs (cache-only — never blocks on vision). */
    private String visionText(List<String> urls) {
        if (urls == null || urls.isEmpty() || brain == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String url : urls) {
            String d = brain.describeImageIfCached(url);
            if (d != null && !d.isBlank()) sb.append(d).append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * The first line of an image transcript that mentions the subject, so the
     * evidence snippet is the relevant row (e.g. "Micron Technology 772,30 € ▼ 9,23%")
     * rather than the top of the screenshot. Falls back to the whole text.
     */
    static String matchingLine(String visionText, Set<String> nameWords, String ticker) {
        for (String line : visionText.split("\n")) {
            if (!line.isBlank() && matches(line, nameWords, ticker)) return line.strip();
        }
        return visionText;
    }

    /** True if the text carries the ticker (as a symbol) or shares a significant name word. */
    static boolean matches(String text, Set<String> nameWords, String ticker) {
        if (text == null || text.isBlank()) return false;
        if (ticker != null && TickerExtractor.extract(text).contains(ticker)) return true;
        Set<String> textWords = new HashSet<>(Arrays.asList(
                text.toLowerCase(Locale.ROOT).split("[^a-z0-9äöüß]+")));
        for (String w : nameWords) {
            if (textWords.contains(w)) return true;
        }
        return false;
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').strip();
        return one.length() > 140 ? one.substring(0, 140) + "…" : one;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
