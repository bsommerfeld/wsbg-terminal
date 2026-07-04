package de.bsommerfeld.wsbg.terminal.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared curated-alias machinery for the resolver's tiny lookup catalogues: a
 * normalised-alias → value map with the {@code put}/{@code lookup}/{@code normalize}
 * plumbing that {@link IndexCatalog} and {@link CommodityCatalog} were duplicating
 * verbatim. A DRY base only — the two catalogues stay <b>distinct feature units</b>
 * (each has its own curated entries and its own {@code is…Symbol} test), never merged.
 *
 * @param <T> the catalogued value (an {@code Index} or {@code Commodity} record)
 */
abstract class AliasMap<T> {

    /** Normalised alias (lower-case, alphanumerics only) → value. */
    private final Map<String, T> byAlias = new LinkedHashMap<>();

    /** Registers {@code value} under every given alias (normalised). */
    protected void put(T value, String... aliases) {
        for (String a : aliases) byAlias.put(normalize(a), value);
    }

    /**
     * The value a name refers to, or {@code null}. Matches the full normalised name
     * (no substring matching), so „Gold" hits but „Barrick Gold" does not.
     */
    protected T lookup(String name) {
        if (name == null) return null;
        return byAlias.get(normalize(name));
    }

    /** lower-case, keep only {@code [a-z0-9]} — collapses spaces, punctuation, case. */
    protected static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
