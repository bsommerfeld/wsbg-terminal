package de.bsommerfeld.wsbg.terminal.signals.world;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Multivariate distance of today's world state from the normal state across
 * all of the fishing net's gauges at once.
 *
 * <p><b>Method:</b> from the daily history (rows = days, columns = gauges)
 * the mean vector and sample covariance matrix are estimated and the
 * Mahalanobis distance (Mahalanobis, "On the generalised distance in
 * statistics", 1936) of the current gauge vector is computed. Against shaky
 * or singular covariances a ridge regularisation is applied (shrinkage idea
 * after Ledoit/Wolf): lambda = 0.1 * (trace/d) goes onto the diagonal. The
 * inverse comes from Gauss-Jordan elimination with column pivoting (d is
 * small). The signal value is D/sqrt(d), a sigma-like reading independent of
 * the gauge count. Additionally the univariate z-score per gauge is computed
 * and the three strongest drivers are named.
 *
 * <p><b>Terminal inputs:</b> the daily fishing-net gauge levels of the
 * world-context clients - politics, oil, maritime plus the civil layer
 * (emergency services, strikes, transit, clinics) - as a days x gauges
 * matrix, plus today's gauge vector.
 */
public final class WorldAnomalyIndex {

    /** Ridge share of the mean diagonal variance. */
    private static final double RIDGE_FRACTION = 0.1;
    /** From here the world counts as an exceptional state (sigma units). */
    private static final double EXTREME = 3.0;
    /** From here elevated world tension applies (sigma units). */
    private static final double ELEVATED = 1.5;

    private WorldAnomalyIndex() {
    }

    /**
     * Measures how many sigma today's world state sits from the normal state.
     *
     * @param history        history, rows = days, columns = gauges (t x d)
     * @param current        current gauge vector (length d)
     * @param dimensionNames gauge names (length d)
     * @return reading, or empty for d &lt; 2, t &lt; max(30, 3*d) or inconsistent lengths
     */
    public static Optional<SignalReading> measure(double[][] history, double[] current, String[] dimensionNames) {
        if (history == null || current == null || dimensionNames == null) {
            return Optional.empty();
        }
        int d = current.length;
        if (d < 2 || dimensionNames.length != d) {
            return Optional.empty();
        }
        int t = history.length;
        int minRows = Math.max(30, 3 * d);
        if (t < minRows) {
            return Optional.empty();
        }
        for (double[] row : history) {
            if (row == null || row.length != d) {
                return Optional.empty();
            }
        }

        // Mean vector and sample covariance (n-1)
        double[] mean = new double[d];
        for (double[] row : history) {
            for (int j = 0; j < d; j++) {
                mean[j] += row[j];
            }
        }
        for (int j = 0; j < d; j++) {
            mean[j] /= t;
        }
        double[][] cov = new double[d][d];
        for (double[] row : history) {
            for (int j = 0; j < d; j++) {
                double dj = row[j] - mean[j];
                for (int k = j; k < d; k++) {
                    cov[j][k] += dj * (row[k] - mean[k]);
                }
            }
        }
        for (int j = 0; j < d; j++) {
            for (int k = j; k < d; k++) {
                cov[j][k] /= (t - 1);
                cov[k][j] = cov[j][k];
            }
        }

        // Ridge regularisation: lambda = 0.1 * (trace/d) onto the diagonal
        double trace = 0;
        for (int j = 0; j < d; j++) {
            trace += cov[j][j];
        }
        double lambda = RIDGE_FRACTION * (trace / d);
        for (int j = 0; j < d; j++) {
            cov[j][j] += lambda;
        }

        double[][] inv = invert(cov);
        if (inv == null) {
            return Optional.empty();
        }

        // Mahalanobis distance of the current vector
        double[] diff = new double[d];
        for (int j = 0; j < d; j++) {
            diff[j] = current[j] - mean[j];
        }
        double d2 = 0;
        for (int j = 0; j < d; j++) {
            for (int k = 0; k < d; k++) {
                d2 += diff[j] * inv[j][k] * diff[k];
            }
        }
        double distance = Math.sqrt(Math.max(0, d2));
        double value = distance / Math.sqrt(d);

        // Univariate per-gauge z-scores as the driver ranking
        double[] z = new double[d];
        for (int j = 0; j < d; j++) {
            double[] column = new double[t];
            for (int i = 0; i < t; i++) {
                column[i] = history[i][j];
            }
            double sd = MathKit.std(column);
            z[j] = (sd == 0 || !Double.isFinite(sd)) ? 0 : Math.abs(current[j] - mean[j]) / sd;
        }
        Integer[] order = new Integer[d];
        for (int j = 0; j < d; j++) {
            order[j] = j;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(z[y], z[x]));
        StringBuilder drivers = new StringBuilder("Top drivers: ");
        int top = Math.min(3, d);
        for (int r = 0; r < top; r++) {
            if (r > 0) {
                drivers.append(", ");
            }
            int j = order[r];
            drivers.append(dimensionNames[j]).append(" (z=").append(MathKit.fmt(z[j], 1)).append(")");
        }
        drivers.append(".");

        String interpretation;
        if (value >= EXTREME) {
            interpretation = "EXCEPTIONAL STATE: the world is " + MathKit.fmt(value, 1)
                    + " sigma strange today, driven by the named gauges - quantitative backstop "
                    + "for the big-picture read. Dissect the drivers first and cross-check them "
                    + "against the news picture. " + drivers;
        } else if (value >= ELEVATED) {
            interpretation = "Elevated world tension: today's world state deviates noticeably from "
                    + "the normal band - keep the named drivers in view. " + drivers;
        } else {
            interpretation = "Normal state: the fishing net is quiet, no multivariate anomaly "
                    + "across the gauges. " + drivers;
        }
        if (t < 2 * minRows) {
            interpretation += " Caution: only " + t + " days of history against " + d
                    + " gauges - the covariance estimate is shaky accordingly.";
        }

        return Optional.of(new SignalReading(
                "world-anomaly-index",
                "World anomaly index (Mahalanobis)",
                value,
                MathKit.fmt(value, 2) + " sigma (Mahalanobis distance / sqrt(d), 0 = normal state)",
                "Measures the multivariate distance of today's world state from the normal state "
                        + "across all fishing-net gauges at once - how many sigma strange the "
                        + "world is today and which gauges drive it.",
                interpretation));
    }

    /**
     * Gauss-Jordan inversion with column pivoting; null on a (numerically)
     * singular matrix - practically ruled out after the ridge regularisation.
     */
    private static double[][] invert(double[][] m) {
        int d = m.length;
        double[][] a = new double[d][2 * d];
        for (int i = 0; i < d; i++) {
            System.arraycopy(m[i], 0, a[i], 0, d);
            a[i][d + i] = 1;
        }
        for (int col = 0; col < d; col++) {
            int pivot = col;
            for (int r = col + 1; r < d; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12) {
                return null;
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            double p = a[col][col];
            for (int j = 0; j < 2 * d; j++) {
                a[col][j] /= p;
            }
            for (int r = 0; r < d; r++) {
                if (r == col) {
                    continue;
                }
                double f = a[r][col];
                if (f == 0) {
                    continue;
                }
                for (int j = 0; j < 2 * d; j++) {
                    a[r][j] -= f * a[col][j];
                }
            }
        }
        double[][] inv = new double[d][d];
        for (int i = 0; i < d; i++) {
            System.arraycopy(a[i], d, inv[i], 0, d);
        }
        return inv;
    }
}
