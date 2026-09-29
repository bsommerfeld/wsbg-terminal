package de.bsommerfeld.tinybrowser;

/**
 * The engine's log: lines on stderr, the first character the level
 * ({@code I}, {@code W}, {@code D}) - TinyFetch forwards them to its own log
 * at that level. Anything else on stderr (JCEF's own lines) arrives as debug.
 *
 * <p>Every line starts on a fresh one: on macOS the JVM reports a callback
 * that overflows on the AppKit thread as {@code Exception in thread "AppKit
 * Thread" } without a line break (see {@code HeadlessCefBrowser}), and the
 * next line glued to it would lose its level.
 */
final class Log {

    private Log() {
    }

    static void info(String message) {
        write('I', message);
    }

    static void warn(String message) {
        write('W', message);
    }

    static void debug(String message) {
        write('D', message);
    }

    private static void write(char level, String message) {
        System.err.print("\n" + level + " " + message + "\n");
    }
}
