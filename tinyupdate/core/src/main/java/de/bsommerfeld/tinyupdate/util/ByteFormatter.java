package de.bsommerfeld.tinyupdate.util;

/**
 * Formats byte sizes into human-readable strings.
 */
public final class ByteFormatter {

    private static final String[] UNITS = {"B", "KB", "MB", "GB"};

    private ByteFormatter() {}

    /**
     * Formats a byte count as a human-readable string (e.g. "14.3 MB").
     */
    public static String format(long bytes) {
        if (bytes < 0) return "? B";

        double value = bytes;
        int unitIdx = 0;
        while (value >= 1024 && unitIdx < UNITS.length - 1) {
            value /= 1024;
            unitIdx++;
        }

        if (unitIdx == 0) return bytes + " B";
        return String.format("%.1f %s", value, UNITS[unitIdx]);
    }
}
