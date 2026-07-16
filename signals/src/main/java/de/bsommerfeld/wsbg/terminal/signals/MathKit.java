package de.bsommerfeld.wsbg.terminal.signals;

/**
 * Geteilte Statistik-Grundfunktionen fuer alle Signal-Algorithmen.
 * Bewusst klein gehalten: nur was mehrere Algorithmen brauchen; spezielle
 * Schaetzer (Weibull-MLE, Baum-Welch, Hawkes-EM) leben beim jeweiligen Signal.
 */
public final class MathKit {

    private MathKit() {
    }

    // ---- Momente ----

    public static double mean(double[] xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.length;
    }

    /** Stichproben-Varianz (n-1). */
    public static double variance(double[] xs) {
        if (xs.length < 2) return 0;
        double m = mean(xs);
        double s = 0;
        for (double x : xs) s += (x - m) * (x - m);
        return s / (xs.length - 1);
    }

    public static double std(double[] xs) {
        return Math.sqrt(variance(xs));
    }

    /** z-Score von x gegen die Verteilung history; 0 wenn history degeneriert ist. */
    public static double zScore(double x, double[] history) {
        double sd = std(history);
        if (sd == 0 || !Double.isFinite(sd)) return 0;
        return (x - mean(history)) / sd;
    }

    // ---- Entropie ----

    /** Shannon-Entropie in Nats ueber nicht-negative Gewichte (werden normiert). */
    public static double shannonEntropy(double[] weights) {
        double total = 0;
        for (double w : weights) total += Math.max(0, w);
        if (total <= 0) return 0;
        double h = 0;
        for (double w : weights) {
            if (w > 0) {
                double p = w / total;
                h -= p * Math.log(p);
            }
        }
        return h;
    }

    /** Entropie normiert auf [0,1] (geteilt durch ln der Kategorien-Anzahl); 0 bei weniger als 2 Kategorien. */
    public static double normalizedEntropy(double[] weights) {
        if (weights.length < 2) return 0;
        return shannonEntropy(weights) / Math.log(weights.length);
    }

    // ---- Empirische Verteilung ----

    /** Empirisches Perzentil von x in history als Anteil [0,1] (mid-rank bei Gleichstand). */
    public static double empiricalPercentile(double x, double[] history) {
        if (history.length == 0) return 0.5;
        int below = 0;
        int equal = 0;
        for (double h : history) {
            if (h < x) below++;
            else if (h == x) equal++;
        }
        return (below + 0.5 * equal) / history.length;
    }

    // ---- Korrelation ----

    /** Pearson-Korrelation; 0 bei degenerierten Reihen. */
    public static double pearson(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 2) return 0;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx, dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        if (sxx == 0 || syy == 0) return 0;
        return sxy / Math.sqrt(sxx * syy);
    }

    /**
     * Kreuzkorrelation bei Verschiebung lag: corr(x[t], y[t+lag]), lag >= 0.
     * Positives Ergebnis bei positivem lag heisst: x laeuft y voraus.
     */
    public static double crossCorrelationAtLag(double[] x, double[] y, int lag) {
        int n = Math.min(x.length, y.length) - lag;
        if (lag < 0 || n < 2) return 0;
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = x[i];
            ys[i] = y[i + lag];
        }
        return pearson(xs, ys);
    }

    // ---- Konfidenzintervalle fuer Anteile ----

    /** Wilson-Intervall fuer einen Anteil, z.B. z=1.645 fuer 90%. Rueckgabe {lo, hi}. */
    public static double[] wilsonInterval(int successes, int n, double z) {
        if (n <= 0) return new double[]{0, 1};
        double p = (double) successes / n;
        double z2 = z * z;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }

    /**
     * Jeffreys-Intervall (Beta(s+0.5, n-s+0.5)-Quantile) fuer einen Anteil.
     * level z.B. 0.90. Rueckgabe {lo, hi}.
     */
    public static double[] jeffreysInterval(int successes, int n, double level) {
        if (n <= 0) return new double[]{0, 1};
        double a = successes + 0.5;
        double b = n - successes + 0.5;
        double alpha = 1 - level;
        double lo = successes == 0 ? 0 : betaQuantile(alpha / 2, a, b);
        double hi = successes == n ? 1 : betaQuantile(1 - alpha / 2, a, b);
        return new double[]{lo, hi};
    }

    // ---- Spezielle Funktionen ----

    /** log Gamma(x) via Lanczos-Approximation. */
    public static double logGamma(double x) {
        double[] g = {
                676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012,
                9.9843695780195716e-6, 1.5056327351493116e-7
        };
        if (x < 0.5) {
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
        }
        x -= 1;
        double a = 0.99999999999980993;
        double t = x + 7.5;
        for (int i = 0; i < g.length; i++) {
            a += g[i] / (x + i + 1);
        }
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** Regularisierte unvollstaendige Betafunktion I_x(a,b) via Lentz-Kettenbruch. */
    public static double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double lnBeta = logGamma(a) + logGamma(b) - logGamma(a + b);
        double front = Math.exp(a * Math.log(x) + b * Math.log(1 - x) - lnBeta);
        boolean swap = x > (a + 1) / (a + b + 2);
        if (swap) {
            return 1 - regularizedIncompleteBeta(1 - x, b, a);
        }
        // Lentz' Algorithmus fuer den Kettenbruch
        double tiny = 1e-30;
        double c = 1;
        double d = 1 - (a + b) * x / (a + 1);
        if (Math.abs(d) < tiny) d = tiny;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            int m2 = 2 * m;
            double num = m * (b - m) * x / ((a + m2 - 1) * (a + m2));
            d = 1 + num * d;
            if (Math.abs(d) < tiny) d = tiny;
            c = 1 + num / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            h *= d * c;
            num = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));
            d = 1 + num * d;
            if (Math.abs(d) < tiny) d = tiny;
            c = 1 + num / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1) < 1e-12) break;
        }
        return front * h / a;
    }

    /** Quantil der Beta(a,b)-Verteilung via Bisektion auf der CDF. */
    public static double betaQuantile(double p, double a, double b) {
        if (p <= 0) return 0;
        if (p >= 1) return 1;
        double lo = 0, hi = 1;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (regularizedIncompleteBeta(mid, a, b) < p) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    // ---- Formatierung ----

    /** Kompakte Zahl mit fester Nachkommastellen-Zahl, US-Punkt als Dezimaltrenner. */
    public static String fmt(double v, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
    }
}
