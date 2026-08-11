package de.bsommerfeld.wsbg.terminal.web.instrument;

import java.util.Locale;
import java.util.Optional;

/**
 * An ISIN as an immutable, validated value — two letters, nine alphanumerics,
 * one check digit (ISO 6166, Luhn over digit-expanded characters). Once one of
 * these exists, it IS a valid ISIN; the stringly-typed guessing at every call
 * site ends here.
 */
public final class Isin {

    private final String value;

    private Isin(String value) {
        this.value = value;
    }

    /**
     * Parses and validates. Lenient about case and surrounding whitespace,
     * strict about everything else including the check digit.
     */
    public static Optional<Isin> parse(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.strip().toUpperCase(Locale.ROOT);
        if (!s.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]")) return Optional.empty();
        if (!checkDigitValid(s)) return Optional.empty();
        return Optional.of(new Isin(s));
    }

    /** Like {@link #parse} but throws — for call sites that already validated. */
    public static Isin of(String raw) {
        return parse(raw).orElseThrow(
                () -> new IllegalArgumentException("not a valid ISIN: " + raw));
    }

    /** {@code true} when {@code raw} would parse — a cheap shape probe. */
    public static boolean looksLike(String raw) {
        return parse(raw).isPresent();
    }

    private static boolean checkDigitValid(String s) {
        StringBuilder digits = new StringBuilder(24);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') digits.append(c - 'A' + 10);
            else digits.append(c);
        }
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (i == digits.length() - 1) { sum += d; dbl = true; continue; }
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    public String value() {
        return value;
    }

    /** ISO country prefix ({@code "DE"}, {@code "US"}, …). */
    public String country() {
        return value.substring(0, 2);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Isin other && value.equals(other.value);
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
