package de.bsommerfeld.updater.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileEntryTest {

    @Test
    void accessors_shouldReturnConstructorValues() {
        var entry = new FileEntry("lib/core.jar", "abc123", 12345);
        assertEquals("lib/core.jar", entry.path());
        assertEquals("abc123", entry.sha256());
        assertEquals(12345, entry.size());
    }

    @Test
    void equality_shouldMatchOnAllFields() {
        var a = new FileEntry("lib/a.jar", "hash", 100);
        var b = new FileEntry("lib/a.jar", "hash", 100);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equality_shouldDifferOnPath() {
        var a = new FileEntry("lib/a.jar", "hash", 100);
        var b = new FileEntry("lib/b.jar", "hash", 100);
        assertNotEquals(a, b);
    }

    @Test
    void equality_shouldDifferOnHash() {
        var a = new FileEntry("lib/a.jar", "hash1", 100);
        var b = new FileEntry("lib/a.jar", "hash2", 100);
        assertNotEquals(a, b);
    }

    @Test
    void shouldAllowZeroSize() {
        var entry = new FileEntry("empty", "hash", 0);
        assertEquals(0, entry.size());
    }
}
