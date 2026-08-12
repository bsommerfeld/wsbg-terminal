package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.article.Article;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Folds several outlets' reports of ONE story into one item.
 *
 * <p>The house identifies an article by {@code uuid}, which is the source's own
 * id or else the link — both of them source-local. Reuters, Bloomberg and an
 * agency carrying the same report are therefore three distinct articles to
 * every layer below this one, and with a broad collector sweep that is the
 * normal case rather than the exception: an agency report exists to be
 * reprinted. Left alone it costs three ways — three of a unit's twelve news
 * slots for one fact, three article digests (each an LLM call through the
 * shared gate), and a brief that reads as three developments where there was
 * one.
 *
 * <p>The near-duplicate machinery for that already existed one stage LATER, on
 * finished headlines ({@link NearDuplicateGuard}) — by which point the compose
 * had already run on the padded brief. This applies the same normalisation and
 * token overlap to article TITLES, before any of that is spent.
 *
 * <p>Deliberately conservative: the threshold sits below the headline guard's
 * (different outlets word the same report differently — a shared agency lede
 * rarely survives two rewrites intact) but a short title must match exactly,
 * because "Quartalszahlen" against "Quartalszahlen erwartet" is not evidence of
 * anything. Wrongly splitting a story costs one slot; wrongly merging two hides
 * a fact, so the doubt goes toward splitting.
 */
final class StoryClusterer {

    /**
     * Token overlap above which two titles are taken to be the same story.
     * Lower than the headline guard's 0.8: those compare the SAME writer's
     * output, these compare independent newsrooms.
     */
    private static final double SAME_STORY_THRESHOLD = 0.6;

    /**
     * Below this many significant tokens a title carries too little to judge
     * overlap on, and only an exact normalised match folds.
     */
    private static final int MIN_TOKENS_FOR_OVERLAP = 5;

    /** How many co-carriers the byline names before it just counts them. */
    private static final int MAX_NAMED_CARRIERS = 2;

    private StoryClusterer() {
    }

    /**
     * One item per story, in the order given (so a newest-first list stays
     * newest-first). The survivor of a cluster is its FIRST member — with the
     * usual newest-first input that is the freshest telling — and it carries a
     * byline naming who else ran it.
     */
    static List<Article> fold(List<Article> items) {
        if (items == null || items.size() < 2) return items == null ? List.of() : items;

        List<Article> reps = new ArrayList<>();
        List<Set<String>> repTokens = new ArrayList<>();
        List<String> repNorms = new ArrayList<>();
        List<Set<String>> carriers = new ArrayList<>();

        for (Article a : items) {
            if (a == null) continue;
            String norm = NearDuplicateGuard.normalizeForDup(a.title());
            Set<String> tokens = tokensOf(norm);
            int match = indexOfStory(norm, tokens, repNorms, repTokens);
            if (match < 0) {
                reps.add(a);
                repNorms.add(norm);
                repTokens.add(tokens);
                Set<String> own = new LinkedHashSet<>();
                if (a.publisher() != null && !a.publisher().isBlank()) own.add(a.publisher().trim());
                carriers.add(own);
            } else if (a.publisher() != null && !a.publisher().isBlank()) {
                carriers.get(match).add(a.publisher().trim());
            }
        }

        List<Article> out = new ArrayList<>(reps.size());
        for (int i = 0; i < reps.size(); i++) {
            out.add(reps.get(i).withPublisher(byline(carriers.get(i))));
        }
        return out;
    }

    /** Index of the cluster this title belongs to, or -1 for a new story. */
    private static int indexOfStory(String norm, Set<String> tokens,
            List<String> repNorms, List<Set<String>> repTokens) {
        if (norm.isEmpty()) return -1;
        for (int i = 0; i < repNorms.size(); i++) {
            if (norm.equals(repNorms.get(i))) return i;
            Set<String> other = repTokens.get(i);
            if (tokens.size() < MIN_TOKENS_FOR_OVERLAP || other.size() < MIN_TOKENS_FOR_OVERLAP) {
                continue; // too thin to judge — exact match above was the only chance
            }
            if (jaccard(tokens, other) >= SAME_STORY_THRESHOLD) return i;
        }
        return -1;
    }

    private static Set<String> tokensOf(String norm) {
        Set<String> out = new HashSet<>();
        if (norm.isEmpty()) return out;
        for (String t : Arrays.asList(norm.split(" "))) {
            // One- and two-letter leftovers are articles and prepositions, not subject matter.
            if (t.length() > 2) out.add(t);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        if (inter.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /**
     * "Reuters" alone, "Reuters · auch: Bloomberg" for a second carrier, and
     * "Reuters · auch: Bloomberg, dpa-AFX +3" once the list would run long.
     */
    private static String byline(Set<String> carriers) {
        if (carriers.isEmpty()) return null;
        List<String> all = new ArrayList<>(carriers);
        String head = all.get(0);
        if (all.size() == 1) return head;
        List<String> others = all.subList(1, all.size());
        StringBuilder sb = new StringBuilder(head).append(" · auch: ");
        int named = Math.min(MAX_NAMED_CARRIERS, others.size());
        for (int i = 0; i < named; i++) {
            if (i > 0) sb.append(", ");
            sb.append(others.get(i));
        }
        if (others.size() > named) sb.append(" +").append(others.size() - named);
        return sb.toString();
    }
}
