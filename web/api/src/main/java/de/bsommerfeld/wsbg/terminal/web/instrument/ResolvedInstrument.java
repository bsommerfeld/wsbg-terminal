package de.bsommerfeld.wsbg.terminal.web.instrument;

import java.util.Optional;

/**
 * What the register could make of an instrument query — every key that
 * resolved, none forced. Partial resolution is a VALID result: a German
 * small cap often has an ISIN and a name but no liquid US symbol, and a
 * meme ticker may resolve symbol-first with the ISIN following later.
 * Pipelines take from this what their sources can address.
 *
 * @param isin   the resolved ISIN, when the register knows one
 * @param ticker the resolved home symbol, when the register knows one
 * @param name   the canonical company name — the one key that always exists
 *               (worst case: the query echoed back)
 */
public record ResolvedInstrument(Optional<Isin> isin, Optional<Ticker> ticker, String name) {

    public ResolvedInstrument {
        if (isin == null) isin = Optional.empty();
        if (ticker == null) ticker = Optional.empty();
        if (name == null) name = "";
    }

    public static ResolvedInstrument ofName(String name) {
        return new ResolvedInstrument(Optional.empty(), Optional.empty(), name);
    }

    /** {@code true} when at least one hard key (ISIN or symbol) resolved. */
    public boolean hasHardKey() {
        return isin.isPresent() || ticker.isPresent();
    }
}
