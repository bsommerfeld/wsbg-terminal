package de.bsommerfeld.wsbg.terminal.core.debug;

/**
 * Detects whether this process is a DEVELOPER run — decided once at class
 * initialisation, from the JVM's own truth, with no environment variable.
 *
 * <p><b>How:</b> the code-source location of this class. The launcher builds
 * the shipped classpath exclusively from JARs in {@code lib/}
 * ({@code AppLauncher.buildClasspath}: {@code p.toString().endsWith(".jar")}),
 * while both developer entries — {@code ./.script/run.sh} (exec-maven-plugin
 * with {@code <classpath/>}) and an IDE run — put {@code target/classes} on the
 * classpath. A code source ending in {@code /classes/} therefore means
 * "developer run" in every path that exists today, and never in a shipped
 * install.
 *
 * <p><b>Override:</b> {@code -Dwsbg.debug=true} forces dev mode on (useful to
 * inspect a packaged build), {@code -Dwsbg.debug=false} forces it off (useful
 * to measure the shipped behaviour from the IDE). The property wins over the
 * code-source probe in both directions.
 *
 * <p>The result is cached in a {@code static final} — after {@code <clinit>}
 * the JIT treats it as a constant, so {@code if (Debug.ENABLED)} guards compile
 * to nothing in a shipped build (see {@link Debug}).
 */
public final class DevMode {

    private static final boolean ACTIVE = detect();

    /** Whether this is a developer run. Constant for the process lifetime. */
    public static boolean active() {
        return ACTIVE;
    }

    private static boolean detect() {
        String forced = System.getProperty("wsbg.debug");
        if (forced != null) return Boolean.parseBoolean(forced);
        try {
            var source = DevMode.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return false;
            String location = source.getLocation().toString();
            return location.endsWith("/classes/") || location.endsWith("/classes");
        } catch (Throwable t) {
            // No permission to read the protection domain → treat as shipped.
            return false;
        }
    }

    private DevMode() {
    }
}
