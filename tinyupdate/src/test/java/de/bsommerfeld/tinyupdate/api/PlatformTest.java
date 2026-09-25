package de.bsommerfeld.tinyupdate.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformTest {

    @Test
    void mapsTheJvmNamesToTheReleaseNames() {
        assertEquals("macos-aarch64", Platform.of("Mac OS X", "aarch64"));
        assertEquals("macos-x86_64", Platform.of("Mac OS X", "x86_64"));
        assertEquals("windows-x86_64", Platform.of("Windows 11", "amd64"));
        assertEquals("linux-x86_64", Platform.of("Linux", "amd64"));
        assertEquals("linux-aarch64", Platform.of("Linux", "aarch64"));
    }

    @Test
    void rejectsWhatNoStreamIsPublishedFor() {
        assertThrows(IllegalStateException.class, () -> Platform.of("FreeBSD", "amd64"));
        assertThrows(IllegalStateException.class, () -> Platform.of("Linux", "riscv64"));
    }
}
