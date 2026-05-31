package de.bsommerfeld.wsbg.terminal.agent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts unambiguous ticker mentions from free-form text.
 *
 * <p>
 * Only {@code $-prefixed} shapes count. The bare-ALL-CAPS heuristic
 * was removed because German Reddit prose throws off too many false
 * positives that no static stoplist can catch reliably ("REIN DA",
 * "CONO", "OFEN", index labels from screenshots, etc.). Those false
 * positives leaked into {@code cluster.tickers} and confused both the
 * ticker-overlap cluster matching and the agent's report block.
 *
 * <p>
 * The expensive name→symbol resolution is delegated to
 * {@code LookupTickerTool}, where Yahoo Finance is the single source
 * of truth and the agent provides the context to disambiguate.
 *
 * <p>
 * What this still catches:
 * <ul>
 *   <li>{@code $XYZ}, {@code $TSLA}, {@code $BRK.A}, {@code $TSM-P} —
 *       any dollar-prefixed shape, 1-5 letters + optional class suffix</li>
 * </ul>
 */
public final class TickerExtractor {

    private static final Pattern DOLLAR_TICKER = Pattern.compile(
            "\\$([A-Za-z]{1,5}(?:[.-][A-Za-z]{1,3})?)\\b");

    public static Set<String> extract(String text) {
        if (text == null || text.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        Matcher m = DOLLAR_TICKER.matcher(text);
        while (m.find()) {
            out.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private TickerExtractor() {}
}
