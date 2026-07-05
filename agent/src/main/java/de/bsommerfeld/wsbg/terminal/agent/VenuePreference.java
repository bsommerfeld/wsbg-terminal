package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;

import java.util.Locale;
import java.util.Set;

/**
 * Ranks a Yahoo listing by venue desirability — the "primary → Frankfurt → never
 * obscure" directive. Extracted verbatim from {@code TickerResolver}: symbol-suffix
 * is the most reliable venue signal Yahoo gives ({@code TTWO.WA} = Warsaw,
 * {@code RHM.DE} = Xetra); the US primary listing simply has no suffix. A listing's
 * venue is ranked into three tiers so a US/home primary beats a thin foreign
 * secondary (Take-Two → {@code TTWO}, not {@code TTWO.WA}) and, when no primary is
 * in the candidate set, Frankfurt (a real, EUR-quoted, accessible venue for the
 * German user base) beats any other obscure foreign line. Consumed by
 * {@link StrongTokenMatcher}.
 */
public final class VenuePreference {

    private VenuePreference() {}

    /** Yahoo exchange codes for OTC / grey-market venues — deprioritised. */
    private static final Set<String> OTC_EXCHANGES =
            Set.of("PNK", "OTC", "OQB", "OQX", "OTCBB", "OTCQ");

    /** Suffixes of primary/home venues — kept at tier 0 (no venue malus). */
    private static final Set<String> PRIMARY_SUFFIXES = Set.of(
            "DE",                       // Xetra (German home)
            "L",                        // London Stock Exchange
            "PA", "AS", "BR", "LS",     // Euronext Paris/Amsterdam/Brussels/Lisbon
            "MC",                       // Madrid
            "MI",                       // Borsa Italiana (primary; numeric secondaries already demoted)
            "SW",                       // SIX Swiss
            "VI",                       // Vienna
            "CO", "ST", "HE", "OL",     // Copenhagen/Stockholm/Helsinki/Oslo
            "TO", "V",                  // Toronto / TSX-V
            "HK", "T", "AX", "NZ");     // Hong Kong / Tokyo / Australia / New Zealand

    /** Suffixes of German regional venues — Frankfurt &amp; co., the fallback tier. */
    private static final Set<String> FRANKFURT_SUFFIXES = Set.of(
            "F",                                    // Frankfurt
            "BE", "MU", "SG", "HM", "DU", "HA");    // Berlin/Munich/Stuttgart/Hamburg/Düsseldorf/Hannover

    private static final int VENUE_FRANKFURT = 100; // German fallback venue
    private static final int VENUE_OBSCURE = 400;   // unclassified foreign secondary (e.g. .WA Warsaw)

    /**
     * Preference among name matches — <b>lower is better</b>. Ranks on reliable,
     * mostly language-neutral signals (additive, so they compose):
     * <ul>
     *   <li>an <b>exact symbol</b> match wins outright (the subject WAS a ticker);</li>
     *   <li><b>numeric-prefixed symbols</b> ({@code 1MUV2.MI}) are foreign
     *       secondary listings on Borsa Italiana &amp; co. → heavy demotion;</li>
     *   <li><b>OTC / grey-market</b> exchanges (PNK, …) → demotion;</li>
     *   <li><b>venue tier</b> (the directive): a primary/home listing (US no-suffix,
     *       {@code .DE}, {@code .L}, …) is preferred; Frankfurt ({@code .F}) is the
     *       fallback; any other obscure foreign line ({@code .WA}, …) is demoted —
     *       so Take-Two resolves to {@code TTWO} (Nasdaq), not {@code TTWO.WA};</li>
     *   <li>real <b>EQUITY</b> mildly preferred over a same-name ETF/index (soft,
     *       so an ETF still wins when it's the only/best match);</li>
     *   <li>ties fall back to <b>Yahoo's own order</b> (≈ relevance).</li>
     * </ul>
     */
    public static int preferenceRank(YahooQuote q, int yahooIndex, boolean exactSymbol) {
        if (exactSymbol) return -1000 + yahooIndex;
        int rank = yahooIndex; // Yahoo order is the baseline (≈ relevance)
        String sym = q.symbol() == null ? "" : q.symbol();
        if (!sym.isEmpty() && Character.isDigit(sym.charAt(0))) rank += 1000;
        String exch = q.exchange() == null ? "" : q.exchange().trim().toUpperCase(Locale.ROOT);
        if (OTC_EXCHANGES.contains(exch)) rank += 500;
        rank += venueMalus(sym);
        String type = q.quoteType() == null ? "" : q.quoteType().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("EQUITY")) rank += 50;
        // Prefer the cash index / spot over a derivative future: "NASDAQ 100" → ^NDX, not NQ=F.
        if (type.equals("FUTURE") || sym.toUpperCase(Locale.ROOT).endsWith("=F")) rank += 200;
        return rank;
    }

    /**
     * Venue tier from a symbol's exchange suffix: 0 for a primary/home listing
     * (incl. US, which has no suffix), {@link #VENUE_FRANKFURT} for a German
     * regional venue (Frankfurt &amp; co.), {@link #VENUE_OBSCURE} for an
     * unrecognised foreign suffix (treated as a thin secondary line).
     */
    public static int venueMalus(String symbol) {
        int dot = symbol.lastIndexOf('.');
        if (dot < 0 || dot == symbol.length() - 1) return 0; // no suffix → US/home primary
        String suffix = symbol.substring(dot + 1).toUpperCase(Locale.ROOT);
        if (PRIMARY_SUFFIXES.contains(suffix)) return 0;
        if (FRANKFURT_SUFFIXES.contains(suffix)) return VENUE_FRANKFURT;
        return VENUE_OBSCURE;
    }
}
