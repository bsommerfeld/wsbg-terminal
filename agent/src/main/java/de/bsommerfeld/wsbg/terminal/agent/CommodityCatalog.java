package de.bsommerfeld.wsbg.terminal.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny curated map of <b>commodity names → Yahoo futures symbol</b> (the
 * {@code GC=F}-style symbols), the sibling of {@link IndexCatalog} for raw materials.
 * Used by {@link TickerResolver} to bind a subject like „Gold" or „Öl" straight to its
 * commodity price instead of letting it fuzzy-match a same-named tradeable security.
 *
 * <p>Why this exists: the room trades the commodity itself („3 Hebel auf Gold",
 * „Gold Long") but the word „Gold" is <em>not</em> unambiguous to the vendors — a name
 * search returns gold-MINING stocks (Alamos Gold, Harmony Gold) or, worse, a random
 * „Gold.com Inc." pennystock priced at €0.12. The actual gold price is the Yahoo future
 * {@code GC=F} (~$4&nbsp;000/oz). Mapping the handful of commodities the room talks about
 * to their proper future fixes that at the source — and it is NOT a guess: „Gold" IS gold.
 *
 * <p>Priced via Yahoo in the commodity's <b>native currency</b> (USD/oz, USD/bbl — the
 * universal benchmark), never FX-converted: gold-in-EUR isn't how the market reads it
 * (see {@code FallbackPriceSource.toEur}, which short-circuits {@code =F} like {@code ^}).
 * Extend as new commodities show up in the wire.
 */
public final class CommodityCatalog {

    private CommodityCatalog() {}

    /** One commodity: its Yahoo {@code =F} future symbol and a clean display name. */
    public record Commodity(String symbol, String displayName) {}

    /** Normalised alias (lower-case, alphanumerics only) → commodity. */
    private static final Map<String, Commodity> BY_ALIAS = new LinkedHashMap<>();

    private static void put(Commodity c, String... aliases) {
        for (String a : aliases) BY_ALIAS.put(normalize(a), c);
    }

    static {
        // --- precious metals ---
        put(new Commodity("GC=F", "Gold"), "Gold", "Goldpreis", "XAU", "XAUUSD");
        put(new Commodity("SI=F", "Silber"), "Silber", "Silver", "Silberpreis", "XAG", "XAGUSD");
        put(new Commodity("PL=F", "Platin"), "Platin", "Platinum");
        put(new Commodity("PA=F", "Palladium"), "Palladium");
        // --- energy ---
        put(new Commodity("CL=F", "Rohöl (WTI)"), "Öl", "Oel", "Oil", "Rohöl", "Rohoel", "Crude",
                "Crude Oil", "WTI", "Erdöl", "Erdoel");
        put(new Commodity("BZ=F", "Brent"), "Brent", "Brent Oil", "Brent Crude");
        put(new Commodity("NG=F", "Erdgas"), "Erdgas", "Natural Gas", "Gas", "Henry Hub");
        // --- industrial / agric ---
        put(new Commodity("HG=F", "Kupfer"), "Kupfer", "Copper");
    }

    /**
     * The commodity a subject name refers to, or {@code null} if it isn't a known
     * commodity. Matches the full normalised name (no substring matching), so „Gold"
     * hits but „Barrick Gold" (a mining stock) does not.
     */
    public static Commodity lookup(String name) {
        if (name == null) return null;
        return BY_ALIAS.get(normalize(name));
    }

    /** True when {@code symbol} is a Yahoo commodity-future symbol (native unit, never FX-converted). */
    public static boolean isCommoditySymbol(String symbol) {
        return symbol != null && symbol.endsWith("=F");
    }

    /** lower-case, keep only {@code [a-z0-9]} — collapses spaces, punctuation, case. */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
