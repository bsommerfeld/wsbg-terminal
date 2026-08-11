package de.bsommerfeld.wsbg.terminal.web.instrument;

import java.util.Locale;
import java.util.Optional;

/**
 * A ticker symbol as an immutable value — upper-cased, trimmed, never blank.
 *
 * <p>Deliberately venue-agnostic: a symbol is only globally unique WITH a
 * venue (Apple is {@code AAPL} on Nasdaq and {@code APC} on Xetra), so this
 * value carries the HOME symbol the register resolved, and
 * {@link #baseSymbol()} strips a venue suffix ({@code "RHM.DE"} → {@code
 * "RHM"}) for sources that index under the base.
 */
public final class Ticker {

    private final String value;

    private Ticker(String value) {
        this.value = value;
    }

    /** Parses; empty for null/blank or clearly non-symbol input. */
    public static Optional<Ticker> parse(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.strip().toUpperCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 24) return Optional.empty();
        if (!s.matches("[A-Z0-9][A-Z0-9.\\-^=]*")) return Optional.empty();
        return Optional.of(new Ticker(s));
    }

    /** Like {@link #parse} but throws — for call sites that already validated. */
    public static Ticker of(String raw) {
        return parse(raw).orElseThrow(
                () -> new IllegalArgumentException("not a usable ticker: " + raw));
    }

    public String value() {
        return value;
    }

    /** The symbol without a venue suffix: {@code "RHM.DE"} → {@code "RHM"}. */
    public String baseSymbol() {
        int dot = value.indexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Ticker other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
