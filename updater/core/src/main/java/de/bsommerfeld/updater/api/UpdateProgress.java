package de.bsommerfeld.updater.api;

/**
 * Snapshot of update/setup progress for UI display.
 *
 * @param phase         high-level phase description (e.g. "Downloading update")
 * @param detail        optional detail line (e.g. "javafx-web-25.jar — 3.2 MB / 12.4 MB")
 * @param progressRatio 0.0 to 1.0 within the current phase, or -1 for indeterminate
 */
public record UpdateProgress(String phase, String detail, double progressRatio) {

    public static UpdateProgress indeterminate(String phase) {
        return new UpdateProgress(phase, null, -1);
    }

    public static UpdateProgress of(String phase, double ratio) {
        return new UpdateProgress(phase, null, Math.clamp(ratio, 0.0, 1.0));
    }

    public static UpdateProgress of(String phase, String detail, double ratio) {
        return new UpdateProgress(phase, detail, Math.clamp(ratio, 0.0, 1.0));
    }

    public static UpdateProgress indeterminate(String phase, String detail) {
        return new UpdateProgress(phase, detail, -1);
    }
}
