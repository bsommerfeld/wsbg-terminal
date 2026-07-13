package de.bsommerfeld.wsbg.terminal.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * The deterministic tail trimmer: cuts an abstract interpretive clause, a
 * "no-news dressed as news" filler clause, a brief-marker leak ({@code [N1]})
 * or a self-echo clause (the second clause re-telling the first one's words)
 * off an otherwise concrete headline. The mechanical backstop for the prompt
 * rules the 4B model keeps bending. Extracted verbatim from {@link HeadlineWriter}.
 */
final class HeadlineTailTrimmer {

    private HeadlineTailTrimmer() {}

    private static final Logger LOG = LoggerFactory.getLogger(HeadlineTailTrimmer.class);

    /** A trailing ", was/wodurch/womit …" clause up to the end of the line. */
    private static final Pattern INTERPRETIVE_TAIL =
            Pattern.compile(",\\s*(was|wodurch|womit)\\s[^,]*$", Pattern.CASE_INSENSITIVE);

    /**
     * A trailing connective clause that leaks a brief-internal news ordinal
     * ("…, während die Analysten in [N1] andere Titel hervorheben"): the marker
     * is the compose brief's citation handle, never reader-facing German — the
     * clause built around it carries no standalone meaning, so it is cut whole.
     */
    private static final Pattern MARKER_TAIL = Pattern.compile(
            ",\\s*(während|wobei|doch|aber|obwohl|da|weil|denn|und|dass|was|wodurch|womit)\\s"
            + "[^,]*\\[N\\d+\\][^,]*$",
            Pattern.CASE_INSENSITIVE);

    /** A brief-internal news ordinal anywhere else in the line — stripped, not trimmed. */
    private static final Pattern MARKER_TOKEN = Pattern.compile("\\s*\\[N\\d+\\]");

    /**
     * The final connective clause, candidate for the self-echo cut: the 4B model
     * padding thin evidence re-tells the head's own words as a second clause
     * ("… seit dem letzten Kauf bei 151.63 EUR nicht weiter anzieht, während die
     * Marktstimmung nach dem letzten Kauf bei 151.63 EUR verharrt" — 2026-07-13
     * live run). Cut ONLY when {@link #echoesHead} proves the repetition.
     */
    private static final Pattern ECHO_TAIL = Pattern.compile(
            ",\\s*(während|wobei|doch|aber|obwohl|da|weil|denn|und)\\s[^,]*$",
            Pattern.CASE_INSENSITIVE);
    /**
     * A trailing "no-news" filler clause: ", während/da/weil/obwohl … keine(n)
     * (neuen) Katalysator(en) / (die) Nachrichtenlage keine|unklar / keine
     * aktuelle Nachricht …". The empty-line-in-disguise the prompt forbids — the
     * absence of news dressed as news. The 4B still emits it ~5 % of the time
     * (prose rules bend, a mechanical gate holds), so we cut it deterministically.
     */
    private static final Pattern NO_NEWS_TAIL = Pattern.compile(
            ",\\s*(während|wobei|da|weil|obwohl)\\s+[^,]*?"
            + "(kein(e[nr]?)?\\s+(neue[nr]?\\s+)?katalysator"
            + "|nachrichtenlage\\s+(keine|unklar|unverändert|unveraendert|dünn|duenn)"
            + "|keine\\s+(aktuelle|neue[nr]?)\\s+nachricht)"
            + "[^,]*$",
            Pattern.CASE_INSENSITIVE);
    /** A clause carrying any of these is DETAIL, not interpretation — never cut. */
    private static final Pattern TAIL_CONCRETE = Pattern.compile("[0-9%€$£]");
    /** The head must still be a full wire line after the cut. */
    private static final int TAIL_MIN_HEAD_WORDS = 6;

    /**
     * Deterministic backstop for the abstraction-ladder rule: the 4B model keeps
     * appending an interpretation clause to an otherwise concrete line ("…, was die
     * Aufmerksamkeit auf den Halbleitersektor lenkt") — measured live at a stable
     * ~20 % of lines (21 % before a prompt rule against it, 19 % after: prose rules
     * bend, a mechanical gate holds — the trigger-gate lesson). The clause construes
     * in the abstract what the concrete head already showed. Cuts it ONLY when it
     * carries no concrete token (no digit, no %, no currency — a figure-bearing
     * clause is detail, not interpretation) and the remaining head is still a full
     * line. Package-private for testing.
     */
    static String trimInterpretiveTail(String headline) {
        if (headline == null || headline.isEmpty()) return headline;
        // A clause built around a leaked brief marker first — it has no reader-facing
        // meaning at all, so it goes before any judgement call. A marker OUTSIDE the
        // final clause is merely stripped (the sentence around it usually survives).
        var mk = MARKER_TAIL.matcher(headline);
        if (mk.find()) {
            String head = headline.substring(0, mk.start()).stripTrailing();
            if (head.split("\\s+").length >= TAIL_MIN_HEAD_WORDS) {
                String trimmed = head.endsWith(".") || head.endsWith("!") ? head : head + ".";
                LOG.info("[WRITE] trimmed brief-marker tail: \"{}\" → \"{}\"",
                        headline.substring(mk.start()).strip(), trimmed);
                headline = trimmed;
            }
        }
        if (MARKER_TOKEN.matcher(headline).find()) {
            String scrubbed = MARKER_TOKEN.matcher(headline).replaceAll("")
                    .replaceAll("\\s{2,}", " ").strip();
            LOG.info("[WRITE] scrubbed leaked brief marker(s): \"{}\" → \"{}\"", headline, scrubbed);
            headline = scrubbed;
        }
        // Self-echo next: the padded second clause that re-tells the head's own words.
        headline = trimEchoTail(headline);
        // No-news filler: cut ONLY when the head is still a full line. Unlike the
        // interpretive tail this needs no concrete-token exemption — a "no fresh catalyst"
        // clause carries no real figure by nature. (After the cut a mis-resolved unit's
        // line often no longer names its instrument, so the name-guard then drops the
        // bogus ticker too — the Polen→Polenergia case collapses to the clean geo line.)
        var f = NO_NEWS_TAIL.matcher(headline);
        if (f.find()) {
            String head = headline.substring(0, f.start()).stripTrailing();
            if (head.split("\\s+").length >= TAIL_MIN_HEAD_WORDS) {
                String trimmed = head.endsWith(".") || head.endsWith("!") ? head : head + ".";
                LOG.info("[WRITE] trimmed no-news filler: \"{}\" → \"{}\"",
                        headline.substring(f.start()).strip(), trimmed);
                headline = trimmed;
            }
        }
        var m = INTERPRETIVE_TAIL.matcher(headline);
        if (!m.find()) return headline;
        String clause = headline.substring(m.start());
        if (TAIL_CONCRETE.matcher(clause).find()) return headline;
        String head = headline.substring(0, m.start()).stripTrailing();
        if (head.split("\\s+").length < TAIL_MIN_HEAD_WORDS) return headline;
        // Re-close the sentence the way the line would have ended.
        String trimmed = head.endsWith(".") || head.endsWith("!") ? head : head + ".";
        LOG.info("[WRITE] trimmed interpretive tail: \"{}\" → \"{}\"", clause.strip(), trimmed);
        return trimmed;
    }

    /**
     * The self-echo cut: drops the final connective clause when it demonstrably
     * repeats the head — a contiguous three-word run (with at least one substantial
     * word) that the head already contains verbatim. Thin-evidence composes pad
     * this way instead of stopping, re-telling the same price anchor twice in one
     * sentence. Concrete figures do NOT exempt the clause here (the repeated figure
     * survives in the head), but a figure the head does NOT carry marks genuinely
     * new detail and blocks the cut.
     */
    private static String trimEchoTail(String headline) {
        var m = ECHO_TAIL.matcher(headline);
        if (!m.find()) return headline;
        String head = headline.substring(0, m.start()).stripTrailing();
        if (head.split("\\s+").length < TAIL_MIN_HEAD_WORDS) return headline;
        String clause = headline.substring(m.start());
        if (!echoesHead(head, clause)) return headline;
        String trimmed = head.endsWith(".") || head.endsWith("!") ? head : head + ".";
        LOG.info("[WRITE] trimmed self-echo tail: \"{}\" → \"{}\"", clause.strip(), trimmed);
        return trimmed;
    }

    /** True when the clause repeats a contiguous 3-word run of the head and adds no new figure. */
    private static boolean echoesHead(String head, String clause) {
        java.util.List<String> h = words(head);
        java.util.List<String> c = words(clause);
        for (String t : c) {
            // A digit token the head does not carry = new detail, never an echo.
            if (t.chars().allMatch(Character::isDigit) && !h.contains(t)) return false;
        }
        for (int i = 0; i + 3 <= c.size(); i++) {
            java.util.List<String> gram = c.subList(i, i + 3);
            // A run of nothing but short function words proves no repetition.
            if (gram.stream().noneMatch(w -> w.length() >= 4)) continue;
            if (containsContiguous(h, gram)) return true;
        }
        return false;
    }

    private static boolean containsContiguous(java.util.List<String> tokens, java.util.List<String> gram) {
        for (int i = 0; i + gram.size() <= tokens.size(); i++) {
            if (tokens.subList(i, i + gram.size()).equals(gram)) return true;
        }
        return false;
    }

    private static java.util.List<String> words(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String w : s.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }
}
