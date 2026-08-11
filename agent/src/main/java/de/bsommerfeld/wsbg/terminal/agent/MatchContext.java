package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance.YahooQuote;

import java.util.List;

/**
 * Everything a guard stage needs, as ONE param object. Crucially carries the ONE
 * cached Yahoo {@link #quotes} response from the single per-subject search, so no
 * stage re-hits Yahoo (rate-limit sensitive).
 *
 * @param query      the subject name as the room wrote it (trimmed, nothing else —
 *                   the hand-kept slang glossary that used to rewrite it here is
 *                   gone, so a corruption now travels verbatim to the stages that
 *                   can learn it)
 * @param context    the room's handle on the subject (the thread title), fed to the
 *                   identity judge so „Kakao" in a commodity thread is distinguishable
 *                   from Kakao Corp; may be blank
 * @param quotes     the cached candidates from the single Yahoo search
 * @param neighbours the instruments THIS SAME thread already resolved, best first.
 *                   The strongest handle on a room coinage there is: a word nobody
 *                   outside the cage has ever written, appearing in a thread that is
 *                   demonstrably about one company, overwhelmingly means that
 *                   company — a signal letter distance cannot see, because a nickname
 *                   need not resemble the name at all.
 */
record MatchContext(String query, String context, List<YahooQuote> quotes,
        List<String> neighbours) {

    MatchContext {
        neighbours = neighbours == null ? List.of() : List.copyOf(neighbours);
    }

    MatchContext(String query, String context, List<YahooQuote> quotes) {
        this(query, context, quotes, List.of());
    }
}
