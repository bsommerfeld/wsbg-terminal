package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.Deflater;

/**
 * Neuheitsgrad einer Headline gegen das Headline-Archiv desselben Papiers.
 *
 * <p><b>Methode:</b> Pro Archiv-Zeile wird eine hybride Aehnlichkeit gerechnet:
 * 0.5 mal Kosinus-Aehnlichkeit ueber TF-Vektoren (lowercase-Wort-Token) plus
 * 0.5 mal (1 - NCD), wobei NCD die Normalized Compression Distance nach
 * Cilibrasi/Vitanyi ("Clustering by Compression", IEEE Trans. Inf. Theory 2005)
 * ist, hier via {@link java.util.zip.Deflater} approximiert:
 * NCD(x,y) = (C(x+y) - min(C(x),C(y))) / max(C(x),C(y)), geklemmt auf [0,1].
 * Der Signalwert ist 1 minus der maximalen Aehnlichkeit ueber das Archiv -
 * also die Distanz zur naechstgelegenen je gelaufenen Zeile.
 *
 * <p><b>Inputs im Terminal:</b> die neue Headline kommt vom News-Wire bzw. aus
 * dem Redaktions-Tick, das Vergleichs-Archiv sind die per Ticker indizierten
 * Zeilen aus dem append-only Headline-Archiv (JSONL).
 */
public final class NoveltyScore {

    /** Unter dieser Archiv-Groesse ist kein Vergleich moeglich. */
    private static final int MIN_ARCHIVE = 10;
    /** Unter dieser Archiv-Groesse traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_ARCHIVE = 30;

    private NoveltyScore() {
    }

    /**
     * Misst den Neuheitsgrad der Headline gegen das Archiv.
     *
     * @param headline         die neue Zeile
     * @param archiveHeadlines alle bisherigen Zeilen zu diesem Papier
     * @return Befund, oder empty bei weniger als {@value #MIN_ARCHIVE} Archiv-Zeilen
     */
    public static Optional<SignalReading> measure(String headline, List<String> archiveHeadlines) {
        if (headline == null || headline.isBlank() || archiveHeadlines == null) {
            return Optional.empty();
        }
        List<String> archive = archiveHeadlines.stream()
                .filter(h -> h != null && !h.isBlank())
                .toList();
        if (archive.size() < MIN_ARCHIVE) {
            return Optional.empty();
        }

        Map<String, Integer> newTf = termFrequencies(headline);
        double maxSimilarity = 0;
        for (String old : archive) {
            double cos = cosine(newTf, termFrequencies(old));
            double ncd = ncd(headline, old);
            double sim = 0.5 * cos + 0.5 * (1 - ncd);
            maxSimilarity = Math.max(maxSimilarity, clamp01(sim));
        }
        double value = clamp01(1 - maxSimilarity);

        String interpretation;
        if (value >= 0.85) {
            interpretation = "REGIMEWECHSEL-KANDIDAT: maximale Distanz zur eigenen Geschichte - "
                    + "diese Zeile deckt sich mit nichts, was zu diesem Papier je lief, "
                    + "und verdient eine Highlight-Prüfung.";
        } else if (value <= 0.25) {
            interpretation = "Wiederholung: hohe Nähe zu mindestens einer Archiv-Zeile - "
                    + "Dup-Verdacht, kein neuer Informationsgehalt.";
        } else {
            interpretation = "Normale Fortschreibung: die Zeile setzt eine bekannte Geschichte fort - "
                    + "weder Dublette noch Bruch.";
        }
        if (archive.size() < COMFORTABLE_ARCHIVE) {
            interpretation += " Vorsicht: nur " + archive.size()
                    + " Archiv-Zeilen als Vergleichsbasis - der Neuheitsgrad ist entsprechend unsicher.";
        }

        return Optional.of(new SignalReading(
                "novelty-score",
                "Neuheitsgrad gegen das Archiv",
                value,
                MathKit.fmt(value, 2) + " (Skala 0-1, 1 = maximal neu)",
                "Misst die Distanz einer neuen Headline zu allem, was je zu diesem Papier lief - "
                        + "Neuheit ist die eigentliche Ware.",
                interpretation));
    }

    // ---- Kosinus ueber TF-Vektoren ----

    private static Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                tf.merge(token, 1, Integer::sum);
            }
        }
        return tf;
    }

    private static double cosine(Map<String, Integer> a, Map<String, Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        double dot = 0;
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            Integer other = b.get(e.getKey());
            if (other != null) {
                dot += (double) e.getValue() * other;
            }
        }
        if (dot == 0) {
            return 0;
        }
        return dot / (norm(a) * norm(b));
    }

    private static double norm(Map<String, Integer> tf) {
        double s = 0;
        for (int c : tf.values()) {
            s += (double) c * c;
        }
        return Math.sqrt(s);
    }

    // ---- Normalized Compression Distance ----

    private static double ncd(String x, String y) {
        int cx = compressedSize(x);
        int cy = compressedSize(y);
        int cxy = compressedSize(x + y);
        int max = Math.max(cx, cy);
        if (max == 0) {
            return 0;
        }
        return clamp01((cxy - Math.min(cx, cy)) / (double) max);
    }

    private static int compressedSize(String s) {
        byte[] input = s.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(input);
            deflater.finish();
            byte[] buffer = new byte[256];
            int total = 0;
            while (!deflater.finished()) {
                total += deflater.deflate(buffer);
            }
            return total;
        } finally {
            deflater.end();
        }
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
