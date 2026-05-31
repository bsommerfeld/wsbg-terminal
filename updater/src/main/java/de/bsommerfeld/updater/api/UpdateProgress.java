package de.bsommerfeld.updater.api;

/**
 * Immutable snapshot of update or setup progress, consumed by the
 * launcher UI to drive the progress bar and status labels.
 *
 * <h3>Progress model</h3>
 * Each phase tracks its own ratio from 0.0 to 1.0 independently.
 * The step counter (e.g. "2/5") tells the user where in the overall
 * pipeline they are, while the progress bar reflects only the
 * current phase's completion.
 *
 * @param phase            high-level phase name shown as the primary label
 * @param step             current step (1-based), or 0 if not in a pipeline
 * @param totalSteps       total number of pipeline steps, or 0 if not in a pipeline
 * @param progressRatio    0.0–1.0 within the current phase, or -1 for indeterminate
 * @param speedBytesPerSec current download speed in bytes/s, or -1 when not downloading
 */
public record UpdateProgress(String phase, int step, int totalSteps,
        double progressRatio, long speedBytesPerSec) {

    /** Speed not recalculated this tick — UI should keep previous value. */
    public static final long SPEED_UNCHANGED = -2;

    public static UpdateProgress indeterminate(String phase) {
        return new UpdateProgress(phase, 0, 0, -1, -1);
    }

    public static UpdateProgress indeterminate(String phase, int step, int totalSteps) {
        return new UpdateProgress(phase, step, totalSteps, -1, -1);
    }

    public static UpdateProgress of(String phase, double ratio) {
        return new UpdateProgress(phase, 0, 0, Math.clamp(ratio, 0.0, 1.0), -1);
    }

    public static UpdateProgress of(String phase, int step, int totalSteps, double ratio) {
        return new UpdateProgress(phase, step, totalSteps, Math.clamp(ratio, 0.0, 1.0), -1);
    }

    /** Creates a download progress event carrying transfer speed. */
    public static UpdateProgress download(String phase, int step, int totalSteps,
            double ratio, long speedBytesPerSec) {
        return new UpdateProgress(phase, step, totalSteps,
                Math.clamp(ratio, 0.0, 1.0), speedBytesPerSec);
    }
}
