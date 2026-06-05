package de.bsommerfeld.wsbg.terminal.finanznachrichten;

/**
 * Coarse grouping of the FinanzNachrichten.de RSS feeds, derived from the
 * feed slug. Lets callers pull a whole bucket at once instead of naming
 * individual {@link FnFeed} constants, e.g.
 * {@code FnFeed.of(FnCategory.BRANCHE)}.
 */
public enum FnCategory {

    /** General news streams (Aktien-Nachrichten, Ad-hoc, Marktberichte, IPO, Devisen, …). */
    NEWS,

    /** Index- and region-scoped news streams (DAX, S&amp;P 500, Asien, …). */
    INDEX,

    /** Sector / "Branche" streams (Pharma, Halbleiter, Öl/Gas, …). */
    BRANCHE,

    /** Analyst recommendation streams (Kaufen / Halten / Verkaufen / Top). */
    EMPFEHLUNG,

    /** Chart- and stock-analysis streams. */
    ANALYSE
}
