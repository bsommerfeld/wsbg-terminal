package de.bsommerfeld.wsbg.terminal.ui.bridge;

import java.util.Locale;

/**
 * Coercion helpers for reading loosely-typed inbound socket payload maps (JSON
 * decoded to {@code Map<String,Object>}). Every bridge parsing an inbound
 * command previously carried its own copies of these; consolidated here so the
 * parsing rules stay uniform across bridges.
 */
final class Payloads {

    private Payloads() {
    }

    /** A non-blank string value, else {@code null}. */
    static String str(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    /** An int value, else {@code fallback}. */
    static int intOr(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    /** A long value, else {@code fallback}. */
    static long longOr(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }

    /** A boolean value, accepting both a real {@code Boolean} and a {@code "true"/"false"} string. */
    static boolean asBool(Object value) {
        if (value instanceof Boolean b) return b;
        return value instanceof String s && Boolean.parseBoolean(s.toLowerCase(Locale.ROOT));
    }
}
