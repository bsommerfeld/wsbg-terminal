package de.bsommerfeld.wsbg.terminal.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * The deterministic tail trimmer: cuts an abstract interpretive clause or a
 * "no-news dressed as news" filler clause off an otherwise concrete headline.
 * The mechanical backstop for the abstraction-ladder rule the 4B model keeps
 * bending. Extracted verbatim from {@link HeadlineWriter}.
 */
final class HeadlineTailTrimmer {

    private HeadlineTailTrimmer() {}

    private static final Logger LOG = LoggerFactory.getLogger(HeadlineTailTrimmer.class);

    /** A trailing ", was/wodurch/womit …" clause up to the end of the line. */
    private static final Pattern INTERPRETIVE_TAIL =
            Pattern.compile(",\\s*(was|wodurch|womit)\\s[^,]*$", Pattern.CASE_INSENSITIVE);
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
        // No-news filler first: cut ONLY when the head is still a full line. Unlike the
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
}
