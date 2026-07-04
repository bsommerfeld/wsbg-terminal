package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which news a published line leans on ("konkret eingewoben") and which
 * refs it inherits from cited prior lines (provenance chaining). Also selects the
 * fresh-news list shown in the compose brief, so the {@code [N#]} ordinals and the
 * citation resolution can never drift apart. Extracted verbatim from
 * {@link EditorialAgent}.
 */
final class NewsProvenance {

    private NewsProvenance() {}

    /**
     * The news items shown to the model in the brief, in their exact render order —
     * the SAME list the {@code [N#]} ordinals number, so a {@code newsUsed} ordinal
     * resolves deterministically back to its item. Kept as one helper so the brief
     * and the citation resolution can never drift apart.
     */
    static List<RawNewsItem> briefNews(SubjectUnit unit, boolean newsCoverageEnabled) {
        List<RawNewsItem> fresh = new ArrayList<>();
        for (RawNewsItem n : unit.news()) {
            // News coverage is OFF by default: news enriches freely and may back
            // several headlines on a topic (it's cached, so reuse is free). Only when
            // explicitly enabled do we hide a unit's already-cited news.
            if (!newsCoverageEnabled || !unit.isNewsCovered(n.uuid())) fresh.add(n);
        }
        return fresh;
    }

    /**
     * The "konkret eingewoben" test: the published line reflects a news item when it
     * shares at least {@link #NEWS_WOVEN_MIN_OVERLAP} significant tokens (length ≥ 4,
     * umlaut-normalised) with the item's title+summary — company names, event words,
     * figures. A sentiment-only line shares none and leaves the item uncovered.
     * Package-private for testing.
     */
    static final int NEWS_WOVEN_MIN_OVERLAP = 2;

    static boolean headlineReflectsNews(String headline, RawNewsItem n) {
        if (headline == null || n == null) return false;
        Set<String> line = significantTokens(headline);
        Set<String> news = significantTokens(nz(n.title()) + " " + nz(n.summary()));
        int overlap = 0;
        for (String tok : line) {
            if (news.contains(tok) && ++overlap >= NEWS_WOVEN_MIN_OVERLAP) return true;
        }
        return false;
    }

    private static Set<String> significantTokens(String s) {
        Set<String> out = new java.util.HashSet<>();
        for (String w : SubjectAttributor.deUmlaut(s).split("[^a-z0-9]+")) {
            if (w.length() >= 4) out.add(w);
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * The news sources INHERITED by a line that cites prior lines ("derivedFrom"
     * ordinals, 1-based within the story-memory window rendered by
     * {@code appendStoryMemory}): each cited prior line's archived {@code newsRefs}
     * carry over. Out-of-range ordinals are the model mis-counting — skipped, never
     * fatal. Prior records are matched by exact headline text (the unit's story
     * memory and the repository both hold the published text verbatim).
     * Package-private for tests.
     */
    static List<HeadlineNewsRef> inheritedRefs(
            List<SubjectUnit.UnitHeadline> priors, List<Integer> derivedFrom,
            List<HeadlineRecord> records) {
        return inheritedRefs(priors, derivedFrom, records, null);
    }

    /**
     * Same, gated on textual CONTINUITY when {@code newHeadline} is given: a citation
     * only inherits when the new line demonstrably shares content with the cited one
     * (significant-token overlap, the woven-in test's sibling). A schema-required
     * array makes a 4B cite EAGERLY — live, nearly every line cited [1] — and an
     * unconnected citation then launders whatever the old record carried (including
     * pool-scoped refs from before the line-scoped cutover) onto an unrelated line.
     * Citation is the model's claim; the overlap is the evidence check.
     */
    static List<HeadlineNewsRef> inheritedRefs(
            List<SubjectUnit.UnitHeadline> priors, List<Integer> derivedFrom,
            List<HeadlineRecord> records, String newHeadline) {
        if (priors == null || priors.isEmpty() || derivedFrom == null || derivedFrom.isEmpty()
                || records == null || records.isEmpty()) {
            return List.of();
        }
        int shownFrom = Math.max(0, priors.size() - UnitBriefWriter.PRIOR_HEADLINES_SHOWN);
        List<SubjectUnit.UnitHeadline> shown = priors.subList(shownFrom, priors.size());
        List<HeadlineNewsRef> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        for (Integer ord : derivedFrom) {
            if (ord == null || ord < 1 || ord > shown.size()) continue;
            String citedText = shown.get(ord - 1).text();
            if (newHeadline != null && !linesConnect(newHeadline, citedText)) continue;
            for (HeadlineRecord r : records) {
                if (!citedText.equals(r.headline())) continue;
                for (HeadlineNewsRef ref : r.newsRefs()) {
                    if (ref.url() != null && seenUrls.add(ref.url())) out.add(ref);
                }
            }
        }
        return out;
    }

    /** True when two headlines share enough significant tokens to be one continued
     *  story — the continuity evidence behind a derivedFrom citation. */
    static boolean linesConnect(String a, String b) {
        Set<String> ta = significantTokens(a);
        Set<String> tb = significantTokens(b);
        int overlap = 0;
        for (String tok : ta) {
            if (tb.contains(tok) && ++overlap >= NEWS_WOVEN_MIN_OVERLAP) return true;
        }
        return false;
    }
}
