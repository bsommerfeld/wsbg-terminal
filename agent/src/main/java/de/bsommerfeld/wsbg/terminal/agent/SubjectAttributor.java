package de.bsommerfeld.wsbg.terminal.agent;

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
 *
 * <h3>Consolidation — one headline per STORY, not per extracted name (2026-07-01)</h3>
 * The cluster/thread IS the event context, so extraction naming several subjects
 * for the SAME story must not spawn several near-identical headlines (D-Wave +
 * "National Science Foundation" + "Chips and Science Act" all headlining one
 * funding thread). Per attribution pass ONE subject is the event's <b>primary</b>
 * — ranked: named in the cluster's initial thread title AND tradeable →
 * title-named → tradeable with most real mentions → most real mentions — and the
 * primary always composes. The initial title (stable across the cluster's life)
 * anchors the pick so a hot thread doesn't flip its story between units on every
 * re-prep.
 *
 * <p><b>Own-pick exception:</b> a picks/watchlist thread ("Sagt mir eure
 * Invests") is a CONTAINER of several independent stories, and the quiet noname
 * one-liner is the product's core discovery case — it must never be swallowed.
 * A co-subject therefore ALSO composes when it is tradeable (or merely
 * un-enriched by a Yahoo rate limit) AND carries at least one real mention the
 * primary does not share — its own comment = its own story. A co-subject whose
 * every mention is shared with the primary (same title, same comment, same
 * screenshot) or that isn't tradeable at all (orgs, acts, themes) is context of
 * the SAME story: it stays silent, its unit still accumulates the evidence
 * feed-wide, and its mentions ride on the primary as {@code reddit-context}
 * refs prefixed with the co-subject's name — the primary's brief carries the
 * whole event, co-movers included.
 */
public final class SubjectAttributor {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(SubjectAttributor.class);

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

    /** Attributes every resolved subject of {@code cluster} into {@code registry} (heuristic primary). */
    public void attribute(SubjectRegistry registry, InvestigationCluster cluster,
            List<ResolvedSubject> resolved) {
        attribute(registry, cluster, resolved, null);
    }

    /**
     * Attributes every resolved subject of {@code cluster} into {@code registry}.
     * {@code modelPrimary} is extraction's own event cut — the protagonist the model
     * named after reading the whole thread (tradeable or not). When it maps to an
     * evidence-backed candidate it wins the primary pick outright; the
     * title/tradeable/mention-count heuristic only backstops a missing or
     * phantom (evidence-less) model pick.
     */
    public void attribute(SubjectRegistry registry, InvestigationCluster cluster,
            List<ResolvedSubject> resolved, String modelPrimary) {
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
        List<Candidate> candidates = new ArrayList<>();
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

            if (unitKey(rs) == null) continue;
            candidates.add(new Candidate(rs, found));
        }
        if (candidates.isEmpty()) return;

        // Consolidation: ONE primary per event (see class doc). Every candidate's
        // unit accumulates its evidence feed-wide; the primary always composes.
        // A co-subject composes TOO only when it is its own story (own-pick rule
        // below) — otherwise it is context of the primary's event and stays silent.
        Candidate primary = pickModelPrimary(candidates, modelPrimary);
        boolean modelPicked = primary != null;
        if (primary == null) primary = pickPrimary(cluster, candidates, ambiguous);
        Set<String> primaryKeys = new HashSet<>();
        for (EvidenceRef ref : primary.found) primaryKeys.add(ref.key());

        SubjectUnit primaryUnit = null;
        boolean primaryGained = false;
        int ownStories = 0;
        for (Candidate c : candidates) {
            SubjectUnit unit = registry.findOrCreate(unitKey(c.rs), c.rs.canonicalName());
            unit.updateResolved(c.rs.canonicalName(), c.rs.ticker(), c.rs.snapshot(), c.rs.news());
            // Dirty only on genuinely-new REAL evidence: re-running attribution over
            // an unchanged cluster (same comments) must NOT re-wake a unit — otherwise
            // every pass re-composes the whole feed — and a price refresh alone
            // (updateResolved) is not a reason to re-write a headline. Conversation
            // CONTEXT (the ↳ ancestor chain) accumulates silently: it is "erwähnt im
            // Verhältnis zu", background that colours the next line, never a story of
            // its own — live-proven by a fees comment that rode in as the only fresh
            // ref and became a "Spesenkosten" headline wearing SK Hynix's price chip.
            boolean gained = false;
            for (EvidenceRef ref : c.found) {
                boolean added = unit.addEvidence(ref);
                if (!"reddit-context".equals(ref.source())) gained |= added;
            }
            if (c == primary) {
                primaryUnit = unit;
                primaryGained = gained;
                continue;
            }
            // Own-pick rule: a co-subject that is TRADEABLE (or merely un-enriched by a
            // Yahoo rate limit — never punish the gem for Yahoo being down) AND carries
            // at least one real mention the primary does NOT share (its own comment) is
            // its own story — "Nutzer nennt XYZ als Investment" — and composes its own
            // line. That keeps the quiet pennystock one-liner alive inside a picks
            // thread (the product's core discovery case). A co-subject whose every
            // mention is shared with the primary (same title, same comment, same
            // screenshot) — or that isn't tradeable at all (NSF, Chips Act, themes) —
            // is context of the SAME story and stays silent.
            boolean ownStory = (c.rs.isInstrument() || c.rs.unresolved())
                    && c.found.stream().anyMatch(ref ->
                            !"reddit-context".equals(ref.source()) && !primaryKeys.contains(ref.key()));
            if (ownStory && gained) {
                registry.markDirty(unit.id);
                ownStories++;
            }
        }
        // The co-subjects' mentions ALSO ride on the primary as named context, so the
        // event's headline sees the whole thread — including fresh evidence that only
        // named a co-subject (that too advances the primary's story, and is what
        // re-dirties it — the ONE deliberate exception to "context never wakes a
        // unit" above: these copies are fresh REAL mentions of the event, merely
        // demoted in rank). Key-level dedupe drops the copy when the primary already
        // holds the same comment as a real mention.
        for (Candidate c : candidates) {
            if (c == primary) continue;
            String label = displayName(c.rs);
            for (EvidenceRef ref : c.found) {
                if ("reddit-context".equals(ref.source())) continue; // don't chain context-of-context
                primaryGained |= primaryUnit.addEvidence(new EvidenceRef(ref.threadId(),
                        ref.commentId(), "[" + label + "] " + ref.snippet(), "reddit-context",
                        ref.addedAtEpoch()));
            }
        }
        // NOTHING from the thread is thrown away: comments that name NO subject at
        // all (mood, jokes, the room's voice around the event) still belong to the
        // event's story — they attach to the PRIMARY as conversation context,
        // "erwähnt im Verhältnis zu". Silent by design: atmosphere never wakes a
        // unit (the brief's char budget bounds what the model ultimately sees, the
        // TTL prune bounds retention, key-dedupe makes re-attribution a no-op).
        Set<String> matchedKeys = new HashSet<>(primaryKeys);
        for (Candidate c : candidates) {
            for (EvidenceRef ref : c.found) matchedKeys.add(ref.key());
        }
        for (String threadId : cluster.activeThreadIds) {
            for (RedditComment cm : repository.getCommentsForThread(threadId, 0)) {
                if (cm.body() == null || cm.body().isBlank()) continue;
                String key = threadId + "/" + cm.id();
                if (matchedKeys.contains(key)) continue;
                primaryUnit.addEvidence(new EvidenceRef(threadId, cm.id(),
                        contextSnippet(cm.body()), "reddit-context", now));
            }
        }

        if (primaryGained) registry.markDirty(primaryUnit.id);
        if (candidates.size() > 1) {
            LOG.info("[CONSOLIDATE] cluster {} → primary {} ({}, {}), {} own-story co-subject(s), {} demoted to context",
                    cluster.id, primaryUnit.id, displayName(primary.rs),
                    modelPicked ? "model" : "heuristic", ownStories,
                    candidates.size() - 1 - ownStories);
        }
    }

    /**
     * The candidate matching extraction's own primary pick, or {@code null} when the
     * model named none or its pick has no evidence (phantom guard — the heuristic
     * then decides). Matched against both the extracted query and the resolved
     * canonical name, case-insensitively.
     */
    private static Candidate pickModelPrimary(List<Candidate> candidates, String modelPrimary) {
        if (modelPrimary == null || modelPrimary.isBlank()) return null;
        String want = modelPrimary.trim().toLowerCase(Locale.ROOT);
        for (Candidate c : candidates) {
            if (want.equals(nz(c.rs.query()).trim().toLowerCase(Locale.ROOT))
                    || want.equals(nz(c.rs.canonicalName()).trim().toLowerCase(Locale.ROOT))) {
                return c;
            }
        }
        return null;
    }

    /** One attributable subject of the pass: its resolution + the evidence found for it. */
    private record Candidate(ResolvedSubject rs, List<EvidenceRef> found) {}

    /**
     * Picks the event's PRIMARY subject: named in the cluster's initial thread title
     * AND tradeable beats title-named beats tradeable beats the rest; within a tier,
     * the most real mentions win. The initial title is stable for the cluster's whole
     * life (cluster id == initial thread id), so the pick doesn't flip on re-preps.
     */
    private static Candidate pickPrimary(InvestigationCluster cluster,
            List<Candidate> candidates, Set<String> ambiguous) {
        String title = nz(cluster.initialTitle);
        Candidate best = null;
        long bestScore = -1;
        for (Candidate c : candidates) {
            String ticker = c.rs.isInstrument() ? c.rs.ticker().toUpperCase(Locale.ROOT) : null;
            Set<String> words = nameWords(c.rs.query(), c.rs.canonicalName());
            boolean titleNamed = matches(title, words, ticker, ambiguous);
            long score = (titleNamed ? 2_000_000L : 0)
                    + (c.rs.isInstrument() ? 1_000_000L : 0)
                    + realMentions(c.found);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** Real mentions (reddit/vision), excluding conversation-context refs. */
    private static long realMentions(List<EvidenceRef> found) {
        return found.stream().filter(r -> !"reddit-context".equals(r.source())).count();
    }

    /** The room-facing short name of a subject — the extracted form, not Yahoo's legal name. */
    private static String displayName(ResolvedSubject rs) {
        return rs.query() != null && !rs.query().isBlank() ? rs.query() : rs.canonicalName();
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

    // Deterministic, cluster-relative distinctiveness — cheap, fixes literal
    // collisions (MSCI World vs MSCI EM). It cannot resolve ABBREVIATIONS
    // ("Emerging Markets" ↔ a "MSCI EM IMI" row).

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
