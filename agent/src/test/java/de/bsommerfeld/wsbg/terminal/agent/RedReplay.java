package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The model-free half of the D4 stand: everything that turns model replies into a
 * number. Extracted from {@link NewsClassifyBenchIT} so it can be tested WITHOUT a
 * model — that stand has twice reported a silent falsehood (every batch dropped
 * because only the wrapper shape was accepted; a genuine actor hit lost because an
 * earlier name hit had already been recorded), and both were arithmetic, not
 * inference. Arithmetic is testable, so it is tested.
 *
 * <p>Test-scope on purpose: nothing in production reads a class verdict yet.
 */
final class RedReplay {

    private RedReplay() {}

    /** Empty array, template for the single-object shape and the no-shape fallback. */
    private static final com.fasterxml.jackson.databind.node.ArrayNode JSON_ARRAY =
            new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();

    /** Separator between class and actor in the wire/cache form. */
    static final char SEP = '\0';

    /**
     * One classifier verdict. The wire form ALWAYS carries the separator, even for a
     * blank actor: that is what lets a cached verdict from before the actor field be
     * told apart from one whose actor is genuinely empty, instead of silently costing
     * the actor formula every hit it could not have had the data for.
     */
    record Verdict(String cls, String actor) {

        String encode() {
            return cls + SEP + actor;
        }

        static Verdict decode(String wire) {
            if (wire == null) return new Verdict("", "");
            int cut = wire.indexOf(SEP);
            return cut < 0 ? new Verdict(wire, "")
                    : new Verdict(wire.substring(0, cut), wire.substring(cut + 1));
        }

        /** Whether {@code wire} was written by a version that knew about the actor. */
        static boolean isCurrentWireForm(String wire) {
            return wire != null && wire.indexOf(SEP) >= 0;
        }
    }

    /**
     * Parses one classify reply into item-number → verdict.
     *
     * <p>Both reply shapes are accepted. The prompt asks for {@code {"classes":[…]}},
     * but the MLX runner ignores the {@code format} grammar, so the model is free to
     * answer with the bare array — and does. An empty result means the reply was
     * unusable, which the caller must treat as a retryable failure rather than as
     * "nothing to classify here".
     *
     * @param root  the parsed reply, or null when it was not JSON at all
     * @param items how many items the batch asked about; indices outside are dropped
     */
    static Map<Integer, Verdict> parseReply(JsonNode root, int items, Set<String> classes) {
        Map<Integer, Verdict> out = new LinkedHashMap<>();
        if (root == null) return out;
        // Three shapes, all seen from the same model: the wrapper the prompt asks
        // for, the bare array it answers a multi-item batch with, and — at ONE item
        // per call — a bare single object with no envelope at all. Each was found
        // by a reply that parsed to nothing while looking like a working stand.
        JsonNode entries;
        if (root.isArray()) {
            entries = root;
        } else if (root.has("classes")) {
            entries = root.path("classes");
        } else if (root.has("class")) {
            entries = JSON_ARRAY.deepCopy();
            ((com.fasterxml.jackson.databind.node.ArrayNode) entries).add(root);
        } else {
            entries = JSON_ARRAY;
        }
        for (JsonNode entry : entries) {
            int i = entry.path("i").asInt(-1);
            String token = entry.path("class").asText("").trim().toUpperCase(Locale.ROOT);
            if (i < 1 || i > items || !classes.contains(token)) continue;
            out.putIfAbsent(i, new Verdict(token, entry.path("akteur").asText("").trim()));
        }
        return out;
    }

    /** One piece of evidence a published line leaned on, with its verdict. */
    record Evidence(Verdict verdict, String title, long publishedAt) {}

    /**
     * How far up the formula ladder a line reaches.
     * {@code cls} is null when no red-capable, fresh evidence stands at all.
     */
    record Hit(String cls, String actor, boolean named, boolean isActor) {
        static final Hit NONE = new Hit(null, "", false, false);

        boolean fired() {
            return cls != null;
        }
    }

    /**
     * The strongest evidence a line rests on — actor hit over mere name hit over any
     * hit. Reported at its strongest ON PURPOSE: a weak item standing earlier in the
     * evidence list must not mask a strong one behind it, which is exactly the bug
     * that made the actor formula undercount.
     *
     * @param subject   the line's subject, blank when it has none (then only tier A
     *                  is reachable — neither name nor actor can be tested)
     * @param createdAt when the line was published, for the staleness window
     */
    static Hit bestHit(String subject, long createdAt, List<Evidence> evidence,
            Set<String> redCapable, long staleAfterSeconds) {
        Hit best = Hit.NONE;
        int bestRank = -1;
        for (Evidence e : evidence) {
            Verdict v = e.verdict();
            if (v == null || !redCapable.contains(v.cls())) continue;
            if (e.publishedAt() > 0 && createdAt - e.publishedAt() > staleAfterSeconds) continue;

            boolean namesSubject = !subject.isBlank()
                    && HeadlineGilder.displayFormIn(e.title(), subject) != null;
            // The subject must BE the event's actor, not a company the piece merely
            // lists: a sector wrap names many, the catalyst it reports has one owner.
            boolean isActor = !subject.isBlank() && !v.actor().isBlank()
                    && (HeadlineGilder.displayFormIn(v.actor(), subject) != null
                        || HeadlineGilder.displayFormIn(subject, v.actor()) != null);

            int rank = isActor ? 2 : namesSubject ? 1 : 0;
            if (rank > bestRank) {
                bestRank = rank;
                best = new Hit(v.cls(), v.actor(), namesSubject, isActor);
            }
            if (isActor) break; // nothing outranks it
        }
        return best;
    }

    /**
     * Formula D's cap: a catalyst fires ONCE. Without it the same article re-reddens
     * every follow-up line of the same story for as long as it stays fresh, and "red
     * is rare" does not survive a red that repeats itself for a day and a half.
     *
     * <p>Only a red that actually FIRED starts a window — a string of follow-ups must
     * not walk it forward and keep the subject muted forever.
     *
     * @param previousRedAt when this subject last went red, or null for never
     */
    static boolean isRepeat(Long previousRedAt, long createdAt, long staleAfterSeconds) {
        return previousRedAt != null && createdAt - previousRedAt <= staleAfterSeconds;
    }
}
