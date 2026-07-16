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
 * Novelty of a headline against the headline archive of the same instrument.
 *
 * <p><b>Method:</b> For each archive line a hybrid similarity is computed:
 * 0.5 times cosine similarity over TF vectors (lowercase word tokens) plus
 * 0.5 times (1 - NCD), where NCD is the Normalized Compression Distance after
 * Cilibrasi/Vitanyi ("Clustering by Compression", IEEE Trans. Inf. Theory 2005),
 * approximated here via {@link java.util.zip.Deflater}:
 * NCD(x,y) = (C(x+y) - min(C(x),C(y))) / max(C(x),C(y)), clamped to [0,1].
 * The signal value is 1 minus the maximum similarity over the archive -
 * i.e. the distance to the nearest line that ever ran.
 *
 * <p><b>Terminal inputs:</b> the new headline comes from the news wire or the
 * editorial tick; the comparison archive is the per-ticker indexed lines from
 * the append-only headline archive (JSONL).
 */
public final class NoveltyScore {

    /** Below this archive size no comparison is possible. */
    private static final int MIN_ARCHIVE = 10;
    /** Below this archive size the interpretation carries a caution note. */
    private static final int COMFORTABLE_ARCHIVE = 30;

    private NoveltyScore() {
    }

    /**
     * Measures the novelty of the headline against the archive.
     *
     * @param headline         the new line
     * @param archiveHeadlines all previous lines for this instrument
     * @return reading, or empty with fewer than {@value #MIN_ARCHIVE} archive lines
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
            interpretation = "REGIME-CHANGE CANDIDATE: maximum distance to the instrument's own history - "
                    + "this line matches nothing that ever ran on this instrument "
                    + "and deserves a highlight check.";
        } else if (value <= 0.25) {
            interpretation = "Repetition: high proximity to at least one archive line - "
                    + "duplicate suspicion, no new information content.";
        } else {
            interpretation = "Normal continuation: the line extends a known story - "
                    + "neither duplicate nor break.";
        }
        if (archive.size() < COMFORTABLE_ARCHIVE) {
            interpretation += " Caution: only n=" + archive.size()
                    + " archive lines as comparison base - the novelty is accordingly uncertain.";
        }

        return Optional.of(new SignalReading(
                "novelty-score",
                "Novelty vs own archive",
                value,
                MathKit.fmt(value, 2) + " (scale 0-1, 1 = maximally new)",
                "Measures the distance of a new headline to everything that ever ran on this "
                        + "instrument - novelty is the actual commodity.",
                interpretation));
    }

    // ---- Cosine over TF vectors ----

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
