package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellLocatorTest {

    @TempDir
    Path appDir;

    @Test
    void platform_namesFollowTheReleaseArtifacts() {
        assertEquals("macos-aarch64", ShellLocator.platform("Mac OS X", "aarch64"));
        assertEquals("macos-x86_64", ShellLocator.platform("Mac OS X", "x86_64"));
        assertEquals("windows-x86_64", ShellLocator.platform("Windows 11", "amd64"));
        assertEquals("linux-x86_64", ShellLocator.platform("Linux", "amd64"));
        assertEquals("linux-aarch64", ShellLocator.platform("Linux", "arm64"));
    }

    @Test
    void find_prefersThePackagedBinaryForThisPlatform() throws IOException {
        Path packaged = appDir.resolve("bin/shell/macos-aarch64/wsbg-shell");
        Files.createDirectories(packaged.getParent());
        Files.writeString(packaged, "#!/bin/sh\n");
        Path local = appDir.resolve("shell/wsbg-shell");
        Files.createDirectories(local.getParent());
        Files.writeString(local, "#!/bin/sh\n");

        Optional<Path> found = ShellLocator.find(appDir, "Mac OS X", "aarch64");
        assertEquals(packaged, found.orElseThrow());
        assertTrue(Files.isExecutable(packaged), "the execute bit is restored after extraction");
    }

    @Test
    void find_fallsBackToALocallyPlacedBuild() throws IOException {
        Path local = appDir.resolve("shell/wsbg-shell");
        Files.createDirectories(local.getParent());
        Files.writeString(local, "#!/bin/sh\n");
        assertEquals(local, ShellLocator.find(appDir, "Mac OS X", "aarch64").orElseThrow());
    }

    @Test
    void find_picksThePackagedBinaryOnWindowsAndLinux() throws IOException {
        Path exe = appDir.resolve("bin/shell/windows-x86_64/wsbg-shell.exe");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "MZ");
        Path linux = appDir.resolve("bin/shell/linux-x86_64/wsbg-shell");
        Files.createDirectories(linux.getParent());
        Files.writeString(linux, "#!/bin/sh\n");
        assertEquals(exe, ShellLocator.find(appDir, "Windows 11", "amd64").orElseThrow());
        assertEquals(linux, ShellLocator.find(appDir, "Linux", "amd64").orElseThrow());
        assertTrue(ShellLocator.find(appDir, "Mac OS X", "x86_64").isEmpty(),
                "a platform the release does not build for is never picked up");
    }

    @Test
    void find_isEmptyWithoutAShell() {
        assertTrue(ShellLocator.find(appDir, "Mac OS X", "aarch64").isEmpty());
    }

    @Test
    void wrap_putsTheShellInFrontAndTheJvmBehindBackend() {
        List<String> jvm = List.of("/jre/bin/java", "-XX:+UseZGC", "-cp", "a.jar", "de.Main", "--x");
        List<String> cmd = ShellLocator.wrap(Path.of("/app/bin/shell/macos-aarch64/wsbg-shell"), jvm);
        assertEquals("/app/bin/shell/macos-aarch64/wsbg-shell", cmd.get(0));
        assertEquals("--backend", cmd.get(1));
        assertEquals(jvm, cmd.subList(2, cmd.size()));
    }
}
