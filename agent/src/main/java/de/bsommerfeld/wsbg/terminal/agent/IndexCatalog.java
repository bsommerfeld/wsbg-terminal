package de.bsommerfeld.wsbg.terminal.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny curated map of <b>stock-index names → Yahoo index symbol</b> (the
 * {@code ^GDAXI}-style caret symbols), used by {@link TickerResolver} to bind a
 * subject like „DAX" or „Nasdaq 100" straight to its index instead of letting it
 * fuzzy-/exact-match a same-named tradeable security.
 *
 * <p>Why this exists: the bare word „DAX" is <em>not</em> unambiguous to the data
 * vendors — the index is {@code ^GDAXI} (~24&nbsp;000 points), but a same-spelled
 * ticker {@code DAX} is a small US-listed ETF (~$44). The resolver's exact-symbol
 * fast-path grabbed that ETF and then FX-converted it to a nonsensical „38,86&nbsp;€".
 * Mapping the handful of indices the room actually talks about to their proper
 * {@code ^}-symbol fixes that at the source. Index symbols are then priced in
 * <b>points</b> (never FX-converted) by the price chain.
 *
 * <p>Deliberately <b>only true indices with a clean Yahoo {@code ^}-symbol</b>.
 * Benchmarks without one (MSCI World, FTSE All-World) are intentionally left out —
 * they keep resolving to a tracking ETF, which carries a sensible EUR unit price.
 * Extend as new indices show up in the wire.
 */
public final class IndexCatalog {

    private IndexCatalog() {}

    /** One index: its Yahoo {@code ^}-symbol and a clean display name. */
    public record Index(String symbol, String displayName) {}

    /** Normalised alias (lower-case, alphanumerics only) → index. */
    private static final Map<String, Index> BY_ALIAS = new LinkedHashMap<>();

    private static void put(Index idx, String... aliases) {
        for (String a : aliases) BY_ALIAS.put(normalize(a), idx);
    }

    static {
        // --- German indices ---
        Index dax = new Index("^GDAXI", "DAX");
        put(dax, "DAX", "DAX 40", "DAX40", "Deutscher Aktienindex", "Germany 40", "Germany40", "GER40", "DE40",
                // WSBG-GER nicknames for the DAX (the room rarely writes the bare „DAX").
                "Rentnerindex", "DAX Rentnerindex", "Deutscher Rentnerindex");
        put(new Index("^MDAXI", "MDAX"), "MDAX");
        put(new Index("^TECDAX", "TecDAX"), "TecDAX");
        put(new Index("^SDAXI", "SDAX"), "SDAX");

        // --- US indices ---
        Index sp500 = new Index("^GSPC", "S&P 500");
        put(sp500, "S&P 500", "S&P500", "SP 500", "SPX", "Standard & Poors 500", "S und P 500",
                "US 500", "US500");
        Index nasdaqComp = new Index("^IXIC", "Nasdaq Composite");
        put(nasdaqComp, "Nasdaq", "Nasdaq Composite");
        // The Nasdaq 100 also goes by its CFD-broker nicknames in the room ("US Tech 100",
        // "Tech 100", "US 100") — all the same index, so map them straight to ^NDX.
        put(new Index("^NDX", "Nasdaq 100"), "Nasdaq 100", "NDX", "Nasdaq100",
                "US Tech 100", "Tech 100", "US Tech100", "US 100", "US100", "USTech 100");
        put(new Index("^DJI", "Dow Jones"), "Dow Jones", "Dow", "Dow Jones Industrial", "DJIA",
                "US 30", "US30");
        put(new Index("^RUT", "Russell 2000"), "Russell 2000", "Russell");
        put(new Index("^VIX", "VIX"), "VIX", "Volatilitätsindex", "Angstindex");

        // --- Europe / other ---
        put(new Index("^STOXX50E", "Euro Stoxx 50"), "Euro Stoxx 50", "EuroStoxx 50", "EuroStoxx", "SX5E");
        put(new Index("^STOXX", "Stoxx Europe 600"), "Stoxx 600", "Stoxx Europe 600", "SXXP");
        put(new Index("^FTSE", "FTSE 100"), "FTSE 100", "FTSE", "Footsie");
        put(new Index("^FCHI", "CAC 40"), "CAC 40", "CAC40");
        put(new Index("^SSMI", "SMI"), "SMI", "Swiss Market Index");
        put(new Index("^ATX", "ATX"), "ATX");
        put(new Index("^IBEX", "IBEX 35"), "IBEX 35", "IBEX");
        put(new Index("^N225", "Nikkei 225"), "Nikkei 225", "Nikkei", "Nikkei225");
        put(new Index("^HSI", "Hang Seng"), "Hang Seng", "HSI");
    }

    /**
     * The index a subject name refers to, or {@code null} if it isn't a known
     * index. Matches the full normalised name (no substring matching), so
     * „DAX" hits but „DAX-ETF" (genuinely an ETF) does not.
     */
    public static Index lookup(String name) {
        if (name == null) return null;
        return BY_ALIAS.get(normalize(name));
    }

    /** True when {@code symbol} is a Yahoo index symbol (priced in points, never FX-converted). */
    public static boolean isIndexSymbol(String symbol) {
        return symbol != null && symbol.startsWith("^");
    }

    /** lower-case, keep only {@code [a-z0-9]} — collapses spaces, punctuation, case. */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
