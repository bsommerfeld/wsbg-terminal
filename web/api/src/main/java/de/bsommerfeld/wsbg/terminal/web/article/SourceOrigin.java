package de.bsommerfeld.wsbg.terminal.web.article;

import java.util.Locale;

/**
 * Where a news item CAME FROM, as the source itself declares it: the language
 * it was written in and the press SPHERE whose gatekeeping it passed.
 *
 * <p>The sphere is the load-bearing half. Confirmation used to be counted in
 * distinct DOMAINS, which silently equates two independent desks with four
 * roofs over the same wire copy — and once the net reaches beyond one language
 * area, a story echoed through a single state-aligned sphere would stamp itself
 * "confirmed" exactly as a Reuters piece beside an SEC filing does. Counting
 * spheres instead makes the echo visible; the contradiction between spheres
 * then becomes what it is — a finding, not an error to be corrected away.
 *
 * <p>Self-description on the contract, never a curated map anywhere else: a
 * house names its own origin (a wire out of Moscow says so), and an aggregator
 * names the EDITION it was asked through — which is the honest answer, because
 * "the Chinese-language index carried it" is a fact about our query, not a
 * judgement about the outlet. Sources that cannot say stay {@link #UNKNOWN},
 * and an unknown origin is never read as sameness (see the card index).
 *
 * @param language ISO 639-1 code, lower case ({@code "de"}, {@code "ru"},
 *                 {@code "zh"}), or empty when the source cannot say
 * @param sphere   short upper-case label of the press sphere ({@code "DE"},
 *                 {@code "RU"}, {@code "CN"}, {@code "US"}), or empty
 */
public record SourceOrigin(String language, String sphere) {

    /** The source says nothing about its origin - the default everywhere. */
    public static final SourceOrigin UNKNOWN = new SourceOrigin("", "");

    public SourceOrigin {
        language = language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
        sphere = sphere == null ? "" : sphere.strip().toUpperCase(Locale.ROOT);
    }

    public static SourceOrigin of(String language, String sphere) {
        return new SourceOrigin(language, sphere);
    }

    /** {@code true} when the source named a sphere - the only half that counts. */
    public boolean known() {
        return !sphere.isEmpty();
    }

    /** {@code "RU/ru"}, {@code "RU"} when no language was named, or {@code ""}. */
    public String marke() {
        if (sphere.isEmpty()) return language.isEmpty() ? "" : language;
        return language.isEmpty() ? sphere : sphere + "/" + language;
    }
}
