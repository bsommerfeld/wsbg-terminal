package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;

import java.util.List;

/**
 * Everything a guard stage needs, as ONE param object. Crucially carries the ONE
 * cached Yahoo {@link #quotes} response from the single per-subject search, so no
 * stage re-hits Yahoo (rate-limit sensitive).
 *
 * @param query   the canonicalised subject name (slang already resolved)
 * @param context the room's handle on the subject (the thread title), fed to the
 *                identity judge so „Kakao" in a commodity thread is distinguishable
 *                from Kakao Corp; may be blank
 * @param quotes  the cached candidates from the single Yahoo search
 */
record MatchContext(String query, String context, List<YahooQuote> quotes) {}
