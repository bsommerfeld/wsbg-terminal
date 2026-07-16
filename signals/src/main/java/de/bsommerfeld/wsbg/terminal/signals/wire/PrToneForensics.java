package de.bsommerfeld.wsbg.terminal.signals.wire;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PR tone inflation: forensic language and number analysis over an issuer's
 * chronological PR history.
 *
 * <p><b>Method:</b> two classic forensics tools, fused into a composite score in
 * [0,1]. (a) Benford's law (Benford 1938; established as a fraud and cosmetics
 * detector by Nigrini, "Benford's Law", 2012): the leading digits of all numbers
 * found in the texts are tested via chi-square statistic against the expected
 * distribution log10(1+1/d); scored only from 30 found numbers upward. (b)
 * Vocabulary trend: the type-token ratio (lexical diversity in Herdan's sense)
 * per text over the first 200 tokens, then an OLS regression line over the text
 * index - a falling TTR means more formulaic, hotter promotional language.
 * Composite score: 0.5*min(1, Chi²/20) + 0.5*min(1, max(0, -slope)*10).
 *
 * <p><b>Inputs in the terminal:</b> the PR full texts that the source clients
 * pull from the press-release rails, sorted chronologically ascending and
 * filtered to one issuer - the signal profiles the issuer, not the single
 * release.
 */
public final class PrToneForensics {

    /** Stable machine key of this signal. */
    public static final String ID = "pr-tone-forensics";

    private static final String TITLE = "PR tone forensics (Benford + vocabulary trend)";

    private static final int MIN_TEXTS = 5;
    private static final int COMFORTABLE_TEXTS = 8;
    private static final int MIN_NUMBERS_FOR_BENFORD = 30;
    private static final int TTR_TOKEN_WINDOW = 200;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private PrToneForensics() {
    }

    /**
     * @param prTextsChronological PR full texts of one issuer, chronologically ascending
     */
    public static Optional<SignalReading> measure(List<String> prTextsChronological) {
        if (prTextsChronological == null || prTextsChronological.size() < MIN_TEXTS) {
            return Optional.empty();
        }

        // (a) Benford: leading digits of all numbers across all texts.
        int[] leadingDigitCounts = new int[10];
        int numberCount = 0;
        for (String text : prTextsChronological) {
            if (text == null) {
                continue;
            }
            Matcher m = NUMBER.matcher(text);
            while (m.find()) {
                int digit = leadingDigit(m.group());
                if (digit > 0) {
                    leadingDigitCounts[digit]++;
                    numberCount++;
                }
            }
        }
        boolean benfordScored = numberCount >= MIN_NUMBERS_FOR_BENFORD;
        double chiSquare = 0;
        if (benfordScored) {
            for (int d = 1; d <= 9; d++) {
                double expected = numberCount * Math.log10(1.0 + 1.0 / d);
                double diff = leadingDigitCounts[d] - expected;
                chiSquare += diff * diff / expected;
            }
        }

        // (b) Vocabulary trend: TTR per text, OLS slope over the text index.
        List<Double> ttrSeries = new ArrayList<>();
        for (String text : prTextsChronological) {
            double ttr = typeTokenRatio(text);
            if (ttr >= 0) {
                ttrSeries.add(ttr);
            }
        }
        double slope = olsSlope(ttrSeries);

        double benfordComponent = benfordScored ? Math.min(1.0, chiSquare / 20.0) : 0.0;
        double ttrComponent = Math.min(1.0, Math.max(0.0, -slope) * 10.0);
        double value = 0.5 * benfordComponent + 0.5 * ttrComponent;

        String benfordText = benfordScored
                ? "Benford chi-square " + MathKit.fmt(chiSquare, 2) + " over " + numberCount + " numbers"
                : "Benford unscored (only " + numberCount + " numbers found, component 0)";
        String slopeText = "TTR slope " + MathKit.fmt(slope, 4) + " per release";

        String interpretation;
        if (value >= 0.6) {
            interpretation = "PROMOTION PATTERN: this issuer's PR language grows hotter over time"
                    + " and/or its numbers are Benford-anomalous (" + benfordText + "; "
                    + slopeText + ") - the classic pattern of number cosmetics and promotional"
                    + " language. Read this issuer's releases as advertising, not information.";
        } else if (value >= 0.3) {
            interpretation = "Slightly elevated (" + benfordText + "; " + slopeText
                    + ") - no clear pattern yet, but keep watching the issuer.";
        } else {
            interpretation = "Unremarkable (" + benfordText + "; " + slopeText
                    + ") - neither the number picture nor the language trend points to dressing-up.";
        }
        if (prTextsChronological.size() < COMFORTABLE_TEXTS || !benfordScored) {
            interpretation += " Caution: only thin data (few texts or too few numbers)"
                    + " - read the finding as a weak hint only.";
        }

        String formattedValue = MathKit.fmt(value, 2) + " (scale 0-1)";
        String definition = "Composite score of Benford deviation of leading digits (chi-square)"
                + " and a falling type-token-ratio trend over an issuer's PR texts"
                + " - measures number cosmetics and promotional language heating up.";

        return Optional.of(new SignalReading(ID, TITLE, value, formattedValue, definition, interpretation));
    }

    /** First significant digit of a number (thousands separators tolerated, leading zeros ignored); 0 if none. */
    private static int leadingDigit(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '1' && c <= '9') {
                return c - '0';
            }
        }
        return 0;
    }

    /** Type-token ratio over the first min(200, all) word tokens; -1 if the text has no tokens. */
    private static double typeTokenRatio(String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = WORD.matcher(text);
        Set<String> types = new HashSet<>();
        int tokens = 0;
        while (m.find() && tokens < TTR_TOKEN_WINDOW) {
            types.add(m.group().toLowerCase(Locale.ROOT));
            tokens++;
        }
        if (tokens == 0) {
            return -1;
        }
        return (double) types.size() / tokens;
    }

    /** OLS slope of ys over the index 0..n-1; 0 with fewer than 2 points. */
    private static double olsSlope(List<Double> ys) {
        int n = ys.size();
        if (n < 2) {
            return 0;
        }
        double meanX = (n - 1) / 2.0;
        double meanY = 0;
        for (double y : ys) {
            meanY += y;
        }
        meanY /= n;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            sxy += dx * (ys.get(i) - meanY);
            sxx += dx * dx;
        }
        if (sxx == 0) {
            return 0;
        }
        return sxy / sxx;
    }
}
