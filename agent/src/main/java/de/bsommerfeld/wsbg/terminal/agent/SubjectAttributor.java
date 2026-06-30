package de.bsommerfeld.wsbg.terminal.agent;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;

import de.bsommerfeld.wsbg.terminal.agent.SubjectUnit.EvidenceRef;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wires a cluster's resolved subjects into the {@link SubjectRegistry} (#2):
 * for each subject it finds the thread/comments that mention it and attaches
 * them as evidence to the unit (by ticker or normalised name). Multi-membership
 * falls out naturally — a comment naming "NVIDIA, AMD oder Intel" is attributed
 * to all three units.
 *
 * <h3>Images attach twice — standalone and inherited</h3>
 * An attached image is evidence in two independent ways, both as plain
 * {@code "vision"} source:
 * <ul>
 *   <li><b>standalone</b> — the image's own transcript names a subject, so it
 *       lands at that subject regardless of what its post said (an AMD chart under
 *       an NVIDIA thread is also attributed to AMD);</li>
 *   <li><b>inherited</b> — the carrying post/comment named a subject, so the image
 *       rides along as the poster's own context for it, even when the transcript
 *       never says the name (an unlabelled chart, a logo-only depot, a meme on an
 *       NVIDIA thread is attributed to NVIDIA). The placement IS the link.</li>
 * </ul>
 * Both can fire at once across different subjects; for the same image+subject the
 * per-image evidence key dedupes them to one ref (standalone snippet preferred).
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
        // Reply tree per thread, built once for the whole cluster: when a subject
        // is named deep in a chain ("E.ON und Constellation"), the conversation it
        // answers (the energy thesis it hangs under) is attached as CONTEXT to the
        // same unit, so the headline can carry the shared story. Cheap, and on RSS
        // (no parent linkage) every chain is empty — context simply isn't added.
        Map<String, CommentTree> trees = new HashMap<>();
        for (String threadId : cluster.activeThreadIds) {
            trees.put(threadId, CommentTree.of(repository.getCommentsForThread(threadId, 0)));
        }
        // Words that appear in ≥2 of THIS cluster's subject names are "ambiguous"
        // (e.g. "msci" across MSCI World + MSCI Emerging Markets). A match on an
        // ambiguous word ALONE isn't enough — otherwise MSCI EM would glom onto the
        // "Core MSCI World −1,84%" row. A distinctive word ("world", "berkshire",
        // "meta") still matches on its own, so short forms keep working.
        Set<String> ambiguous = ambiguousWords(resolved);
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
                boolean threadNames = matches(nz(t.title()) + " " + nz(t.textContent()),
                        words, ticker, ambiguous);
                if (threadNames) {
                    found.add(new EvidenceRef(threadId, null, snippet(t.title()), "reddit", now));
                }
                // A post's images attach to this subject in TWO ways, both as plain
                // "vision" evidence:
                //   (1) standalone — the image's own transcript names the subject (an
                //       AMD chart lands at AMD no matter which post carries it), or
                //   (2) inherited — the POST that carries it named the subject, so the
                //       image rides along as the poster's own context even when the
                //       transcript never says the name (an unlabelled chart, a logo-only
                //       depot, a meme). The poster attached it to THIS post about THIS
                //       subject; that placement IS the link — no explicit join needed.
                // Vision already frames numbers as observed (PERSONAL P/L etc.), so an
                // inherited image needs no separate "unverified" marker.
                attachImages(found, threadId, "_vision#", t.imageUrls(),
                        threadNames, words, ticker, ambiguous, now);
                for (RedditComment c : repository.getCommentsForThread(threadId, 0)) {
                    boolean commentNames = c.body() != null && !c.body().isBlank()
                            && matches(c.body(), words, ticker, ambiguous);
                    if (commentNames) {
                        found.add(new EvidenceRef(threadId, c.id(), snippet(c.body()), "reddit", now));
                    }
                    // Same dual rule for a comment's images: standalone, or inherited
                    // when the comment body itself named the subject.
                    attachImages(found, threadId, c.id() + "#img", c.imageUrls(),
                            commentNames, words, ticker, ambiguous, now);
                }
            }
            if (found.isEmpty()) continue; // no real mention → no phantom unit

            // Conversation context: for every real comment-mention, pull in the
            // chain it replies to as CONTEXT evidence (root-first, the thesis
            // first). An ancestor that is itself a real mention of THIS subject
            // stays a real mention — we don't downgrade it to context.
            Set<String> realKeys = new HashSet<>();
            for (EvidenceRef ref : found) realKeys.add(ref.key());
            List<EvidenceRef> context = new ArrayList<>();
            Set<String> contextKeys = new HashSet<>();
            for (EvidenceRef ref : found) {
                String commentId = baseCommentId(ref.commentId());
                if (commentId == null) continue; // post body / thread image — no ancestors
                CommentTree tree = trees.get(ref.threadId());
                if (tree == null) continue;
                for (RedditComment anc : tree.ancestorsOf(commentId)) {
                    if (anc.body() == null || anc.body().isBlank()) continue;
                    String key = ref.threadId() + "/" + anc.id();
                    if (realKeys.contains(key) || !contextKeys.add(key)) continue;
                    context.add(new EvidenceRef(ref.threadId(), anc.id(),
                            contextSnippet(anc.body()), "reddit-context", now));
                }
            }
            found.addAll(context);

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
        for (String w : deUmlaut(s).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
        }
    }

    /**
     * Lowercase + German umlaut transliteration (ä→ae, ö→oe, ü→ue, ß→ss) so the room's
     * "Muenchener" and a canonical "Münchener" tokenise to the SAME word ("muenchener") and
     * match. Applied symmetrically to both the name words and the scanned text.
     */
    static String deUmlaut(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    /**
     * Attaches a thread's or comment's images as evidence for one subject. Each
     * image is keyed individually ({@code prefix + index}) so a multi-image post
     * doesn't collapse into a single evidence slot. An image attaches when its own
     * transcript names the subject (standalone — the snippet is the matching row),
     * OR when {@code containerNames} is set, i.e. the carrying post/comment named
     * the subject and the image rides along as inherited context (the snippet is
     * then the transcript's lead clause, since no row names the subject). Vision is
     * cache-only here — a not-yet-analysed image is simply skipped, never blocked on.
     */
    private void attachImages(List<EvidenceRef> found, String threadId, String prefix,
            List<String> urls, boolean containerNames,
            Set<String> words, String ticker, Set<String> ambiguous, long now) {
        if (urls == null || urls.isEmpty() || brain == null) return;
        for (int i = 0; i < urls.size(); i++) {
            String d = brain.describeImageIfCached(urls.get(i));
            if (d == null || d.isBlank()) continue;
            boolean imageNames = matches(d, words, ticker, ambiguous);
            if (!imageNames && !containerNames) continue;
            String snip = imageNames
                    ? snippet(matchingLine(d, words, ticker, ambiguous))
                    : snippet(leadLine(d));
            found.add(new EvidenceRef(threadId, prefix + i, snip, "vision", now));
        }
    }

    /** First non-blank line of a transcript — the vision lead clause used as an inherited-image snippet. */
    private static String leadLine(String visionText) {
        for (String line : visionText.split("\n")) {
            if (!line.isBlank()) return line.strip();
        }
        return visionText;
    }

    // OPTION A (this): deterministic, cluster-relative distinctiveness — cheap, no
    // embedding, fixes literal collisions (MSCI World vs MSCI EM). It cannot resolve
    // ABBREVIATIONS ("Emerging Markets" ↔ a "MSCI EM IMI" row).
    // TODO(B): semantic line-matching via the shared embedding service (cosine of the
    // subject name vs each candidate line) — most reliable, handles abbreviations.
    // Wire it here once the generic EmbeddingService abstraction exists.

    /** Words shared by ≥2 of the cluster's subject names — ambiguous, can't carry a match alone. */
    static Set<String> ambiguousWords(List<ResolvedSubject> resolved) {
        Map<String, Integer> freq = new HashMap<>();
        for (ResolvedSubject rs : resolved) {
            for (String w : nameWords(rs.query(), rs.canonicalName())) {
                freq.merge(w, 1, Integer::sum);
            }
        }
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() >= 2) out.add(e.getKey());
        }
        return out;
    }

    /**
     * The BEST line of an image transcript for the subject (most distinctive-word
     * overlap), so the evidence snippet is the subject's own row — "MSCI World"
     * gets the World row, not whichever "msci" line came first. Falls back to the
     * whole text.
     */
    static String matchingLine(String visionText, Set<String> nameWords, String ticker) {
        return matchingLine(visionText, nameWords, ticker, Set.of());
    }

    static String matchingLine(String visionText, Set<String> nameWords, String ticker, Set<String> ambiguous) {
        String best = null;
        int bestScore = 0;
        for (String line : visionText.split("\n")) {
            if (line.isBlank()) continue;
            if (ticker != null && TickerExtractor.extract(line).contains(ticker)) return line.strip();
            int[] ov = overlap(line, nameWords, ambiguous);
            int score = ov[0] * 100 + ov[1]; // distinctive words dominate, then total
            if ((ov[0] >= 1 || ov[1] >= 2) && score > bestScore) {
                bestScore = score;
                best = line.strip();
            }
        }
        return best != null ? best : visionText;
    }

    /** True if the text carries the ticker (as a symbol) or shares a significant name word. */
    static boolean matches(String text, Set<String> nameWords, String ticker) {
        return matches(text, nameWords, ticker, Set.of());
    }

    /**
     * Cluster-aware match: the text matches if it carries the ticker, OR shares a
     * DISTINCTIVE name word, OR shares ≥2 words total. A lone {@code ambiguous}
     * word (shared by ≥2 cluster subjects, e.g. "msci") is not enough — that's what
     * stops MSCI EM from matching the MSCI World row. Short forms still work because
     * "berkshire"/"meta"/"world" are distinctive.
     */
    static boolean matches(String text, Set<String> nameWords, String ticker, Set<String> ambiguous) {
        if (text == null || text.isBlank()) return false;
        if (ticker != null && TickerExtractor.extract(text).contains(ticker)) return true;
        int[] ov = overlap(text, nameWords, ambiguous);
        return ov[0] >= 1 || ov[1] >= 2;
    }

    /** {@code {distinctiveHits, totalHits}} of {@code nameWords} present in {@code text}. */
    private static int[] overlap(String text, Set<String> nameWords, Set<String> ambiguous) {
        Set<String> textWords = new HashSet<>(Arrays.asList(
                deUmlaut(text).split("[^a-z0-9]+")));
        int distinctive = 0;
        int total = 0;
        for (String w : nameWords) {
            if (textWords.contains(w)) {
                total++;
                if (!ambiguous.contains(w)) distinctive++;
            }
        }
        return new int[]{distinctive, total};
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').strip();
        return one.length() > 140 ? one.substring(0, 140) + "…" : one;
    }

    /** A wider snippet for context lines — the thesis a pick answers needs room to read. */
    private static String contextSnippet(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').strip();
        return one.length() > 240 ? one.substring(0, 240) + "…" : one;
    }

    /**
     * The underlying comment id of an evidence ref, or {@code null} when the ref
     * isn't a comment. Strips the {@code #img<n>} suffix a comment-image vision ref
     * carries and filters the post-level markers ({@code null} = post body,
     * {@code _vision<n>} = a thread image) that have no comment ancestors.
     */
    private static String baseCommentId(String commentId) {
        if (commentId == null || commentId.startsWith("_vision")) return null;
        int img = commentId.indexOf("#img");
        return img >= 0 ? commentId.substring(0, img) : commentId;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
