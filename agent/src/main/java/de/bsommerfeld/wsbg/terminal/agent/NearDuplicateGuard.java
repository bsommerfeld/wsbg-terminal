package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The "is this line a repeat?" feature, isolated from the publish flow. Owns the
 * normalised near-duplicate detection AND the recent-wire scan that was inlined in
 * {@link HeadlineWriter#publishUnit}. Pure algorithm — it takes the wire as a
 * parameter so it stays unit-testable without a repository. Extracted verbatim.
 */
final class NearDuplicateGuard {

    private NearDuplicateGuard() {}

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** Skip a NEAR-duplicate of a unit's OWN recent headline within this window. Much longer
     *  than the rapid-double guard: with the compose settle/cooldown, a unit's re-composes are
     *  spaced minutes apart, and a hot thread re-dirties its unit all session — the ^GDAXI wire
     *  once carried 7 near-identical "wartet auf Katalysator" lines in 35 min, and re-tells of
     *  an unmoved story still surfaced 1.5h apart (SIVE.ST, live 2026-07-02). An EXACT match
     *  (normalised-equal) is checked against the whole 24h wire regardless of this window —
     *  zero new tokens is zero development, however much time passed. */
    private static final long NEAR_DUP_GUARD_SECS = 7200;
    /** Skip a NEAR-duplicate of ANY other unit's recent headline within this (shorter) window.
     *  Two units can legitimately tell one story from two angles hours apart, but the same
     *  sentence twice within half an hour is one story published twice — the merz/friedrich-merz
     *  twin units once wrote the same Reformpaket line 5 min apart, invisible to the per-unit
     *  guard. */
    private static final long CROSS_UNIT_DUP_GUARD_SECS = 1800;

    /** Token-overlap above which two normalised headlines count as the same line. */
    private static final double DUP_SIM_THRESHOLD = 0.8;

    /**
     * The recent-wire duplicate of {@code headline}, if any: an EXACT normalised match
     * anywhere in the 24h wire, or a near-duplicate inside the per-unit window (the unit's
     * own lines) / the shorter cross-unit window (any other unit's lines). Extracted from
     * {@code publishUnit}; the wire is passed in so this stays repo-free.
     */
    static Optional<HeadlineRecord> findDuplicate(String headline, String unitId,
            List<HeadlineRecord> wire, long nowSecs) {
        String normNew = normalizeForDup(headline);
        return wire.stream()
                .filter(h -> normNew.equals(normalizeForDup(h.headline())) // exact: whole 24h wire
                        || ((nowSecs - h.createdAt())
                                < (unitId.equals(h.clusterId()) ? NEAR_DUP_GUARD_SECS : CROSS_UNIT_DUP_GUARD_SECS)
                            && isNearDuplicate(headline, h.headline())))
                .findFirst();
    }

    /**
     * True when {@code a} is essentially the same line as {@code b} — identical once the
     * "-Update:" continuation marker, punctuation and numbers are stripped (the model
     * re-emitting a line as an update or with a ticked day-move), a light reword above
     * {@link #DUP_SIM_THRESHOLD} token overlap ("hat"→"hält"), or one line CONTAINING the
     * other (the model re-emitting a prior line with an appended clause — Jaccard alone
     * goes blind there because the union grows: the MU wire once carried the same
     * GM-Chip line three times, twice with a tacked-on subclause). Package-private for
     * testing.
     */
    static boolean isNearDuplicate(String a, String b) {
        String na = normalizeForDup(a), nb = normalizeForDup(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.equals(nb)) return true;
        Set<String> ta = new HashSet<>(Arrays.asList(na.split(" ")));
        Set<String> tb = new HashSet<>(Arrays.asList(nb.split(" ")));
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        double jaccard = (double) inter.size() / union.size();
        double containment = (double) inter.size() / Math.min(ta.size(), tb.size());
        return Math.max(jaccard, containment) >= DUP_SIM_THRESHOLD;
    }

    /**
     * Lower-cased core of a headline: HTML, the "-Update:" marker, punctuation and
     * NUMBERS removed. Numbers go because the day-move ticking is not a story
     * development — the ^GDAXI unit once published the same "wartet auf Katalysator"
     * line at +1,66 %, +1,78 % and +2,01 %; the quote strip carries the live figure
     * anyway. A genuinely new number (a price target, a contract volume) arrives with
     * new WORDS around it, which the token comparison still sees.
     */
    static String normalizeForDup(String s) {
        String t = stripHtml(s).toLowerCase(Locale.ROOT);
        t = t.replaceAll("(?i)-?\\s*update\\s*:", " ");          // drop the "-Update:" continuation label
        t = t.replaceAll("[^a-z0-9äöüß ]", " ");
        t = t.replaceAll("\\b\\d+\\b", " ");                     // numbers are not developments
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String stripHtml(String s) {
        return s == null ? "" : HTML_TAG.matcher(s).replaceAll("");
    }
}
