package de.bsommerfeld.wsbg.terminal.core.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The developer probe, driven with literal classpaths rather than the one the
 * test happens to run under.
 *
 * <p>The first version of this probe read {@code DevMode}'s own code source and
 * answered "shipped" on the developer's machine, so the debug button never
 * appeared: {@code run.sh} runs {@code mvn -pl terminal exec:exec} without
 * {@code -am}, which resolves every module except {@code terminal} from the
 * local repository as a JAR — including the one holding this class. The cases
 * below are the real classpaths, measured 2026-08-13.
 */
class DevModeTest {

    private static String cp(String... entries) {
        return String.join(java.io.File.pathSeparator, entries);
    }

    @Test
    void theShippedClasspathIsJarsOnly() {
        // AppLauncher.buildClasspath keeps only *.jar out of lib/.
        assertFalse(DevMode.classpathHasBuildOutput(cp(
                "/Applications/wsbg/lib/terminal-2.0.0.jar",
                "/Applications/wsbg/lib/core-2.0.0.jar",
                "/Applications/wsbg/lib/guice-7.0.0.jar")));
    }

    @Test
    void runShIsDeveloperEvenThoughItsModulesComeFromTheRepository() {
        // The case that was broken: only terminal is built from source, every
        // dependency is a repository JAR — including the one DevMode sits in.
        assertTrue(DevMode.classpathHasBuildOutput(cp(
                "/repo/terminal/target/classes",
                "/Users/x/.m2/repository/de/bsommerfeld/core/2.0.0/core-2.0.0.jar",
                "/Users/x/.m2/repository/org/apache/lucene/lucene-core-9.12.1.jar")));
    }

    @Test
    void anIdeRunIsDeveloper() {
        assertTrue(DevMode.classpathHasBuildOutput(cp(
                "/repo/terminal/target/classes",
                "/repo/core/target/classes",
                "/Users/x/.m2/repository/com/google/guice/guice-7.0.0.jar")));
    }

    @Test
    void aTrailingSeparatorStillCounts() {
        assertTrue(DevMode.classpathHasBuildOutput("/repo/core/target/classes/"));
    }

    @Test
    void aJarThatMerelyLivesInAFolderCalledClassesIsNotBuildOutput() {
        assertFalse(DevMode.classpathHasBuildOutput(cp(
                "/opt/classes/app.jar",
                "/opt/classes/lib.jar")));
    }

    @Test
    void nothingOnTheClasspathIsNotADeveloperRun() {
        assertFalse(DevMode.classpathHasBuildOutput(null));
        assertFalse(DevMode.classpathHasBuildOutput(""));
    }
}
