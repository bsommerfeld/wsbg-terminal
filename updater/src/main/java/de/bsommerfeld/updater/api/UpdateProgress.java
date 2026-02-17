package de.bsommerfeld.updater.api;

/**
 * Immutable snapshot of update or setup progress, consumed by the
 * launcher UI to drive the progress bar and status labels.
 *
 * <h3>Progress model</h3>
 * Progress is tracked per phase, not globally. Each phase (e.g.
 * "Downloading update") reports its own ratio from 0.0 to 1.0.
 * A ratio of {@code -1} signals indeterminate progress — the UI
 * should show a pulsing bar instead of a filled one.
 *
 * @param phase         high-level phase name shown as the primary label
 *                      (e.g. "Downloading update", "Extracting files")
 * @param detail        optional secondary label with specifics
 *                      (e.g. "javafx-web-25.jar — 3.2 MB / 12.4 MB")
 * @param progressRatio 0.0–1.0 within the current phase, or -1 for
 *                      indeterminate
 */
public record UpdateProgress(String phase, String detail, double progressRatio) {

    /** Creates an indeterminate progress event with no detail text. */
    public static UpdateProgress indeterminate(String phase) {
        return new UpdateProgress(phase, null, -1);
    }

    /** Creates a determinate progress event with no detail text. */
    public static UpdateProgress of(String phase, double ratio) {
        return new UpdateProgress(phase, null, Math.clamp(ratio, 0.0, 1.0));
    }

    /** Creates a determinate progress event with detail text. */
    public static UpdateProgress of(String phase, String detail, double ratio) {
        return new UpdateProgress(phase, detail, Math.clamp(ratio, 0.0, 1.0));
    }

    /** Creates an indeterminate progress event with detail text. */
    public static UpdateProgress indeterminate(String phase, String detail) {
        return new UpdateProgress(phase, detail, -1);
    }
}
