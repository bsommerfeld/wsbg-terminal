package de.bsommerfeld.tinyupdate.api;

/**
 * Represents the phases and progress of an update operation.
 *
 * @param phase          current phase description
 * @param progressRatio  0.0 to 1.0 within the current phase, or -1 for indeterminate
 */
public record UpdateProgress(String phase, double progressRatio) {

    public static UpdateProgress indeterminate(String phase) {
        return new UpdateProgress(phase, -1);
    }

    public static UpdateProgress of(String phase, double ratio) {
        return new UpdateProgress(phase, Math.clamp(ratio, 0.0, 1.0));
    }
}
