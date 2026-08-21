package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the executable the terminal is spawned with. The parameterised
 * {@code resolve(os, javaHome, envJavaHome)} seam lets every platform branch be
 * verified regardless of the host OS.
 *
 * <p>The macOS branch carries the dock-tile fix: the tile is labelled by the
 * executable's file name, so the branded copy must win over {@code bin/java}
 * wherever the CI build produced one.
 */
class JavaExecutableResolverTest {

    private static final String MAC = "Mac OS X";
    private static final String WINDOWS = "Windows 11";
    private static final String LINUX = "Linux";

    // ── macOS: the branded binary decides the dock tile's name ─────────────

    @Test
    void mac_prefersBrandedBinary_overPlainJava(@TempDir Path home) throws IOException {
        binary(home, "java");
        Path branded = binary(home, "WSBG Terminal");

        assertEquals(branded.toString(), JavaExecutableResolver.resolve(MAC, home.toString(), null));
    }

    @Test
    void mac_fallsBackToPlainJava_whenBrandedBinaryAbsent(@TempDir Path home) throws IOException {
        Path java = binary(home, "java");

        // Dev runs use a system JDK, which has no branded copy — still starts.
        assertEquals(java.toString(), JavaExecutableResolver.resolve(MAC, home.toString(), null));
    }

    // ── The same probe, asked as a question: is this hull current? ─────────

    @Test
    void brandedBinaryProbe_seesTheCopyWhenItIsThere(@TempDir Path home) throws IOException {
        binary(home, "java");
        binary(home, "WSBG Terminal");
        assertTrue(JavaExecutableResolver.hasBrandedBinary(MAC, home.toString()));
    }

    @Test
    void brandedBinaryProbe_reportsAHullThatPredatesTheCopy(@TempDir Path home) throws IOException {
        binary(home, "java");
        // This is what drives the terminal's amber "renew launcher" button: an
        // installed hull cannot grow the branded copy by itself, so everything
        // it spawns keeps sitting in the Dock as "java" until it is reinstalled.
        assertFalse(JavaExecutableResolver.hasBrandedBinary(MAC, home.toString()));
    }

    @Test
    void brandedBinaryProbe_isAlwaysSatisfiedOffMac(@TempDir Path home) {
        // Nothing to brand there — the tile takes its name from the icon/app.
        assertTrue(JavaExecutableResolver.hasBrandedBinary(WINDOWS, home.toString()));
        assertTrue(JavaExecutableResolver.hasBrandedBinary(LINUX, home.toString()));
    }

    // ── The other platforms are untouched by the fix ───────────────────────

    @Test
    void windows_usesJavaExe_andIgnoresABrandedFile(@TempDir Path home) throws IOException {
        binary(home, "WSBG Terminal");
        Path javaExe = binary(home, "java.exe");

        assertEquals(javaExe.toString(), JavaExecutableResolver.resolve(WINDOWS, home.toString(), null));
    }

    @Test
    void linux_usesPlainJava(@TempDir Path home) throws IOException {
        binary(home, "WSBG Terminal");
        Path java = binary(home, "java");

        assertEquals(java.toString(), JavaExecutableResolver.resolve(LINUX, home.toString(), null));
    }

    // ── Fallback chain ────────────────────────────────────────────────────

    @Test
    void fallsBackToEnvJavaHome_whenTheRuntimeHomeHasNoBinary(@TempDir Path runtime, @TempDir Path env)
            throws IOException {
        Path java = binary(env, "java");

        assertEquals(java.toString(),
                JavaExecutableResolver.resolve(LINUX, runtime.toString(), env.toString()));
    }

    @Test
    void fallsBackToBareJava_whenNothingResolves(@TempDir Path empty) {
        assertEquals("java", JavaExecutableResolver.resolve(LINUX, empty.toString(), null));
        assertEquals("java", JavaExecutableResolver.resolve(MAC, null, "   "));
    }

    private static Path binary(Path home, String name) throws IOException {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path exe = Files.createFile(bin.resolve(name));
        assertTrue(exe.toFile().setExecutable(true) || Files.isExecutable(exe),
                "test fixture must be executable: " + exe);
        return exe;
    }
}
