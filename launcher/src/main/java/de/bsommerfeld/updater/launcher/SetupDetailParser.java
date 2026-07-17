package de.bsommerfeld.updater.launcher;

/**
 * Pure scrapers for the {@code detail} half of a setup event ("45% — 739 MB /
 * 3.3 GB", "2/2", a bare "45%"). Consumed by {@link SetupProgressAdapter} to
 * drive the progress bar, model pips and speed readout. Extracted from
 * {@code LauncherMain} so the number-parsing lives apart from the UI wiring.
 */
final class SetupDetailParser {

    private SetupDetailParser() {
    }

    /**
     * Parses the leading "NN%" out of a setup detail string ("45% — 739 MB /
     * 3.3 GB" or a bare "45%"), or {@code -1} when the detail carries no
     * percentage (a status word, or {@code null} on a phase transition).
     */
    static int parsePercent(String detail) {
        if (detail == null || detail.isEmpty() || !Character.isDigit(detail.charAt(0))) {
            return -1;
        }
        int pctIdx = detail.indexOf('%');
        if (pctIdx <= 0) return -1;
        try {
            return Integer.parseInt(detail.substring(0, pctIdx).strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses a {@code "total/started"} model-count control detail into
     * {@code [total, started]}, or {@code null} if it is malformed. Feeds the
     * model pips (one dot per model, filled once its install has begun).
     */
    static int[] parseModelCount(String detail) {
        if (detail == null) return null;
        String[] parts = detail.split("/");
        if (parts.length != 2) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0].strip()),
                    Integer.parseInt(parts[1].strip())
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extracts the verbatim "downloaded / total" half of a rich
     * "NN% — 739 MB / 3.3 GB" detail for direct display next to the progress
     * bar, or {@code null} when the detail carries no byte figures (or no
     * total — a lone figure reads as a size, not as progress). Verbatim on
     * purpose: the script's own formatting can't drift from what we show.
     */
    static String parseByteFigures(String detail) {
        if (detail == null) return null;
        int dashIdx = detail.indexOf('—');
        if (dashIdx <= 0) return null;
        String figures = detail.substring(dashIdx + 1).strip();
        if (!figures.contains("/")) return null;
        try {
            ByteSizes.parse(figures.split("/")[0].strip());
            return figures;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the downloaded-bytes figure from a rich "NN% — downloaded / total"
     * detail, or {@code -1} when the detail has no byte figures (e.g. curl's
     * {@code --progress-bar} output, which is a bar + percent only).
     */
    static long parseProgressBytes(String detail) {
        if (detail == null) return -1;
        int dashIdx = detail.indexOf('—');
        if (dashIdx <= 0) return -1;
        try {
            return ByteSizes.parse(detail.substring(dashIdx + 1).strip().split("/")[0].strip());
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return -1;
        }
    }
}
