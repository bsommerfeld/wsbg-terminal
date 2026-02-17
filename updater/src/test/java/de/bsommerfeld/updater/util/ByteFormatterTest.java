package de.bsommerfeld.updater.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ByteFormatterTest {

    @Test
    void format_shouldReturnBytesForSmallValues() {
        assertEquals("0 B", ByteFormatter.format(0));
        assertEquals("512 B", ByteFormatter.format(512));
        assertEquals("1023 B", ByteFormatter.format(1023));
    }

    @Test
    void format_shouldReturnKilobytes() {
        String result = ByteFormatter.format(1024);
        assertTrue(result.endsWith("KB"), "Expected KB unit, got: " + result);
        assertTrue(result.startsWith("1"), "Expected 1.0 KB, got: " + result);
    }

    @Test
    void format_shouldReturnMegabytes() {
        String result = ByteFormatter.format(1024 * 1024);
        assertTrue(result.endsWith("MB"), "Expected MB unit, got: " + result);
    }

    @Test
    void format_shouldReturnGigabytes() {
        String result = ByteFormatter.format(1024L * 1024 * 1024);
        assertTrue(result.endsWith("GB"), "Expected GB unit, got: " + result);
    }

    @Test
    void format_shouldCapAtGigabytes() {
        long terabyte = 1024L * 1024 * 1024 * 1024;
        String result = ByteFormatter.format(terabyte);
        assertTrue(result.endsWith("GB"));
    }

    @Test
    void format_shouldHandleNegativeValues() {
        assertEquals("? B", ByteFormatter.format(-1));
        assertEquals("? B", ByteFormatter.format(-999));
    }

    @Test
    void format_shouldScaleCorrectly() {
        // 1.5 KB = 1536 bytes — verify the numeric value is ~1.5 regardless of locale
        String result = ByteFormatter.format(1536);
        assertTrue(result.endsWith("KB"));
        // Extract numeric part, handle both '.' and ',' decimal separators
        String numeric = result.replace(" KB", "").replace(",", ".");
        double value = Double.parseDouble(numeric);
        assertEquals(1.5, value, 0.01);
    }
}
