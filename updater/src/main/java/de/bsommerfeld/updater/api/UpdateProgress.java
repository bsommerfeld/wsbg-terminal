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
 * @param phase            high-level phase name shown as the primary label
 * @param detail           optional secondary label with specifics
 * @param progressRatio    0.0–1.0 within the current phase, or -1 for indeterminate
 * @param speedBytesPerSec current download speed in bytes/s, or -1 when not downloading
 */
public record UpdateProgress(String phase, String detail, double progressRatio, long speedBytesPerSec) {

    /** Speed not recalculated this tick — UI should keep previous value. */
    public static final long SPEED_UNCHANGED = -2;

    public static UpdateProgress indeterminate(String phase) {
        return new UpdateProgress(phase, null, -1, -1);
    }

    public static UpdateProgress of(String phase, double ratio) {
        return new UpdateProgress(phase, null, Math.clamp(ratio, 0.0, 1.0), -1);
    }

    public static UpdateProgress of(String phase, String detail, double ratio) {
        return new UpdateProgress(phase, detail, Math.clamp(ratio, 0.0, 1.0), -1);
    }

    public static UpdateProgress indeterminate(String phase, String detail) {
        return new UpdateProgress(phase, detail, -1, -1);
    }

    /** Creates a download progress event carrying transfer speed. */
    public static UpdateProgress download(String phase, String detail, double ratio, long speedBytesPerSec) {
        return new UpdateProgress(phase, detail, Math.clamp(ratio, 0.0, 1.0), speedBytesPerSec);
    }
}
