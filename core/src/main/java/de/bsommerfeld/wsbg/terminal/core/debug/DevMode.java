package de.bsommerfeld.wsbg.terminal.core.debug;

/**
 * Detects whether this process is a DEVELOPER run — decided once at class
 * initialisation, from the JVM's own truth, with no environment variable.
 *
 * <p><b>How:</b> whether the classpath carries a build-output DIRECTORY. The
 * launcher composes the shipped classpath exclusively from JARs in {@code lib/}
 * ({@code AppLauncher.buildClasspath}: {@code p.toString().endsWith(".jar")}),
 * while both developer entries — {@code ./.script/run.sh} (exec-maven-plugin
 * with {@code <classpath/>}) and an IDE run — put at least one
 * {@code target/classes} on it. An entry ending in {@code /classes} therefore
 * means "developer run" in every path that exists today, and never in a shipped
 * install.
 *
 * <p><b>Why not this class's own code source</b> — the obvious probe, and
 * wrong: {@code DevMode} lives in {@code core}, and {@code run.sh} runs
 * {@code mvn -pl terminal exec:exec} without {@code -am}, so every module but
 * {@code terminal} resolves from the local repository as a JAR. Measured
 * 2026-08-13: the code source is {@code …/.m2/…/core-2.0.0.jar} and the probe
 * answered "shipped" on the developer's own machine — the button never
 * appeared. A probe that depends on which module happens to hold it is not a
 * probe.
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

    /**
     * One line naming the verdict AND the evidence, for the boot log. A probe
     * that silently answers "no" is indistinguishable from a broken console —
     * which is exactly how the first version of this class cost a session.
     */
    public static String explain() {
        String forced = System.getProperty("wsbg.debug");
        if (forced != null) {
            return "debug console " + (ACTIVE ? "ON" : "OFF") + " — forced by -Dwsbg.debug=" + forced;
        }
        return "debug console " + (ACTIVE ? "ON" : "OFF") + " — classpath "
                + (ACTIVE ? "carries" : "carries no") + " build output (…/classes)";
    }

    private static boolean detect() {
        String forced = System.getProperty("wsbg.debug");
        if (forced != null) return Boolean.parseBoolean(forced);
        return classpathHasBuildOutput(System.getProperty("java.class.path"));
    }

    /**
     * {@code true} when any classpath entry is a build-output directory.
     * Package-private so a test can drive it with a literal classpath instead
     * of the one it happens to run under.
     */
    static boolean classpathHasBuildOutput(String classpath) {
        if (classpath == null || classpath.isEmpty()) return false;
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            String e = entry.endsWith("/") || entry.endsWith("\\")
                    ? entry.substring(0, entry.length() - 1)
                    : entry;
            // Only a trailing path SEGMENT counts: "/home/me/classes/app.jar"
            // is a jar that happens to sit in such a folder, not build output.
            if (e.endsWith("/classes") || e.endsWith("\\classes")) return true;
        }
        return false;
    }

    private DevMode() {
    }
}
