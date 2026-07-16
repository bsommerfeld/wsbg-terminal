package de.bsommerfeld.wsbg.terminal.signals.world;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Multivariater Abstand des heutigen Weltzustands vom Normalzustand ueber
 * alle Fischernetz-Pegel gleichzeitig.
 *
 * <p><b>Methode:</b> Aus der Tages-Historie (Zeilen = Tage, Spalten = Pegel)
 * werden Mittelwert-Vektor und Stichproben-Kovarianzmatrix geschaetzt und die
 * Mahalanobis-Distanz (Mahalanobis, "On the generalised distance in
 * statistics", 1936) des aktuellen Pegel-Vektors gerechnet. Gegen wacklige
 * bzw. singulaere Kovarianzen wird Ridge-regularisiert (Shrinkage-Gedanke
 * nach Ledoit/Wolf): auf die Diagonale kommt lambda = 0.1 * (Spur/d). Die
 * Inverse entsteht per Gauss-Jordan-Elimination mit Spalten-Pivotisierung
 * (d ist klein). Der Signalwert ist D/sqrt(d), also eine sigma-Anmutung
 * unabhaengig von der Pegel-Zahl. Zusaetzlich wird pro Pegel der univariate
 * z-Score gerechnet und die drei staerksten Treiber werden benannt.
 *
 * <p><b>Inputs im Terminal:</b> die taeglichen Fischernetz-Pegelstaende der
 * Welt-Kontext-Clients - Politik, Oel, Maritim sowie die Zivilschicht
 * (Blaulicht, Streiks, Oeffis, Kliniken) - als Matrix Tage x Pegel, plus der
 * heutige Pegel-Vektor.
 */
public final class WorldAnomalyIndex {

    /** Ridge-Anteil an der mittleren Diagonal-Varianz. */
    private static final double RIDGE_FRACTION = 0.1;
    /** Ab hier gilt die Welt als Ausnahmezustand (sigma-Einheiten). */
    private static final double EXTREME = 3.0;
    /** Ab hier gilt erhoehte Weltspannung (sigma-Einheiten). */
    private static final double ELEVATED = 1.5;

    private WorldAnomalyIndex() {
    }

    /**
     * Misst, wie viele sigma der heutige Weltzustand vom Normalzustand entfernt ist.
     *
     * @param history        Historie, Zeilen = Tage, Spalten = Pegel (t x d)
     * @param current        aktueller Pegel-Vektor (Laenge d)
     * @param dimensionNames Namen der Pegel (Laenge d)
     * @return Befund, oder empty bei d &lt; 2, t &lt; max(30, 3*d) oder inkonsistenten Laengen
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

        // Mittelwert-Vektor und Stichproben-Kovarianz (n-1)
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

        // Ridge-Regularisierung: lambda = 0.1 * (Spur/d) auf die Diagonale
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

        // Mahalanobis-Distanz des aktuellen Vektors
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

        // Univariate Einzel-z-Scores als Treiber-Ranking
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
        StringBuilder drivers = new StringBuilder("Top-Treiber: ");
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
            interpretation = "AUSNAHMEZUSTAND: die Welt ist heute " + MathKit.fmt(value, 1)
                    + " sigma vom Normalband entfernt - quantitative Rückwand für die Großwetterlage. "
                    + "Die benannten Treiber zuerst sezieren und mit der News-Lage abgleichen. "
                    + drivers;
        } else if (value >= ELEVATED) {
            interpretation = "Erhöhte Weltspannung: der heutige Weltzustand weicht spürbar vom "
                    + "Normalband ab - die benannten Treiber im Auge behalten. " + drivers;
        } else {
            interpretation = "Normalzustand: das Fischernetz ist ruhig, keine multivariate "
                    + "Auffälligkeit über die Pegel hinweg. " + drivers;
        }
        if (t < 2 * minRows) {
            interpretation += " Vorsicht: nur " + t + " Tage Historie bei " + d
                    + " Pegeln - die Kovarianz-Schätzung ist entsprechend wacklig.";
        }

        return Optional.of(new SignalReading(
                "world-anomaly-index",
                "Welt-Anomalie-Index (Mahalanobis)",
                value,
                MathKit.fmt(value, 2) + " sigma (Mahalanobis-Distanz / sqrt(d), 0 = Normalzustand)",
                "Misst den multivariaten Abstand des heutigen Weltzustands vom Normalzustand "
                        + "über alle Fischernetz-Pegel gleichzeitig - wie viele sigma seltsam die "
                        + "Welt heute ist und welche Pegel das treiben.",
                interpretation));
    }

    /**
     * Gauss-Jordan-Inversion mit Spalten-Pivotisierung; null bei (numerisch)
     * singulaerer Matrix - kommt nach der Ridge-Regularisierung praktisch nicht vor.
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
