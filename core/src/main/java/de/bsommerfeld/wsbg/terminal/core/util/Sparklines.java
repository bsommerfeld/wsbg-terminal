package de.bsommerfeld.wsbg.terminal.core.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure sparkline helpers shared by the price venues (L&amp;S, NASDAQ, ...).
 * <p>
 * Dependency-free (only {@link List}/{@link Math}) so it can live in {@code core}.
 */
public final class Sparklines {

    private Sparklines() {
    }

    /**
     * Evenly thins {@code series} to at most {@code maxPoints} points using a
     * fixed even stride. Returns the input unchanged when it already fits.
     * <p>
     * The stride is {@code (n-1)/(maxPoints-1)} and each output index is
     * {@code Math.round(i * stride)}, so the first and last elements are always
     * kept.
     */
    public static List<Double> downsample(List<Double> series, int maxPoints) {
        if (series.size() <= maxPoints) return series;
        List<Double> out = new ArrayList<>(maxPoints);
        double step = (series.size() - 1) / (double) (maxPoints - 1);
        for (int i = 0; i < maxPoints; i++) out.add(series.get((int) Math.round(i * step)));
        return out;
    }
}
