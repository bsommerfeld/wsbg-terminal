package de.bsommerfeld.updater.launcher;

/**
 * Parses human-readable byte-size strings ("739 MB", "3.3 GB", "1024 B") into
 * raw bytes. Consolidates the three near-identical size parsers that used to
 * live in {@code LauncherMain}, {@code EnvironmentSetup} and the curl-meter
 * handling — the only difference between them was error handling, captured here
 * as the throwing {@link #parse} vs the lenient {@link #parseOrZero}.
 */
final class ByteSizes {

    private ByteSizes() {
    }

    /**
     * Parses a size string to bytes. Recognises GB/MB/KB suffixes (decimal,
     * 1000-based); anything without a unit is treated as bytes.
     *
     * @throws NumberFormatException if the string carries no numeric part
     */
    static long parse(String sizeStr) {
        String normalized = sizeStr.toUpperCase().replaceAll("\\s+", "");
        double val = Double.parseDouble(normalized.replaceAll("[^0-9.]", ""));
        if (normalized.endsWith("GB")) return (long) (val * 1_000_000_000L);
        if (normalized.endsWith("MB")) return (long) (val * 1_000_000L);
        if (normalized.endsWith("KB")) return (long) (val * 1_000L);
        return (long) val;
    }

    /**
     * Lenient variant of {@link #parse} that returns {@code 0} instead of
     * throwing when the input carries no parseable number.
     */
    static long parseOrZero(String sizeStr) {
        try {
            return parse(sizeStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
