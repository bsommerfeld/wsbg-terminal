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
 * PR-Sprachinflation: forensische Sprach- und Zahlenanalyse ueber die
 * chronologische PR-Historie eines Absenders.
 *
 * <p><b>Methode:</b> zwei klassische Forensik-Werkzeuge, zu einem Kompositscore
 * in [0,1] verschmolzen. (a) Benfords Gesetz (Benford 1938; als Betrugs- und
 * Kosmetik-Detektor etabliert durch Nigrini, "Benford's Law", 2012): die
 * fuehrenden Ziffern aller in den Texten gefundenen Zahlen werden per
 * Chi-Quadrat-Statistik gegen die erwartete Verteilung log10(1+1/d) getestet;
 * gewertet wird nur ab 30 gefundenen Zahlen. (b) Wortschatz-Trend: die
 * Type-Token-Ratio (lexikalische Diversitaet im Sinne von Herdan) pro Text ueber
 * die ersten 200 Token, darueber eine OLS-Regressionsgerade ueber den Text-Index
 * - eine fallende TTR heisst formelhaftere, heissere Werbesprache. Kompositscore:
 * 0.5*min(1, Chi²/20) + 0.5*min(1, max(0, -Steigung)*10).
 *
 * <p><b>Inputs im Terminal:</b> die PR-Volltexte, die die Quellen-Clients aus
 * den Pressemeldungs-Schienen ziehen, chronologisch aufsteigend sortiert und
 * auf einen Absender gefiltert - das Signal profiliert den Absender, nicht die
 * einzelne Meldung.
 */
public final class PrToneForensics {

    /** Stabiler Maschinen-Schluessel dieses Signals. */
    public static final String ID = "pr-tone-forensics";

    private static final String TITLE = "PR-Sprachinflation (Benford + Wortschatz-Trend)";

    private static final int MIN_TEXTS = 5;
    private static final int COMFORTABLE_TEXTS = 8;
    private static final int MIN_NUMBERS_FOR_BENFORD = 30;
    private static final int TTR_TOKEN_WINDOW = 200;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private PrToneForensics() {
    }

    /**
     * @param prTextsChronological PR-Volltexte eines Absenders, chronologisch aufsteigend
     */
    public static Optional<SignalReading> measure(List<String> prTextsChronological) {
        if (prTextsChronological == null || prTextsChronological.size() < MIN_TEXTS) {
            return Optional.empty();
        }

        // (a) Benford: fuehrende Ziffern aller Zahlen ueber alle Texte.
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

        // (b) Wortschatz-Trend: TTR pro Text, OLS-Steigung ueber den Text-Index.
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
                ? "Benford-Chi² " + MathKit.fmt(chiSquare, 2) + " über " + numberCount + " Zahlen"
                : "Benford ungewertet (nur " + numberCount + " Zahlen gefunden, Komponente 0)";
        String slopeText = "TTR-Steigung " + MathKit.fmt(slope, 4) + " pro Meldung";

        String interpretation;
        if (value >= 0.6) {
            interpretation = "PROMOTIONS-MUSTER: die PR-Sprache dieses Absenders wird über die Zeit"
                    + " heißer und/oder seine Zahlen sind Benford-auffällig (" + benfordText + "; "
                    + slopeText + ") - klassisches Muster von Zahlenkosmetik und Werbesprache."
                    + " Meldungen dieses Absenders als Werbung lesen, nicht als Information.";
        } else if (value >= 0.3) {
            interpretation = "Leicht erhöht (" + benfordText + "; " + slopeText
                    + ") - noch kein klares Muster, aber den Absender beobachten.";
        } else {
            interpretation = "Unauffällig (" + benfordText + "; " + slopeText
                    + ") - weder Zahlenbild noch Sprachtrend deuten auf Aufhübschung.";
        }
        if (prTextsChronological.size() < COMFORTABLE_TEXTS || !benfordScored) {
            interpretation += " Vorsicht: dünne Datenlage (wenige Texte bzw. zu wenige Zahlen)"
                    + " - Befund nur als schwaches Indiz lesen.";
        }

        String formattedValue = MathKit.fmt(value, 2) + " (Skala 0-1)";
        String definition = "Kompositscore aus Benford-Abweichung der führenden Ziffern (Chi²)"
                + " und fallendem Type-Token-Ratio-Trend über die PR-Texte eines Absenders"
                + " - misst Zahlenkosmetik und heißer werdende Werbesprache.";

        return Optional.of(new SignalReading(ID, TITLE, value, formattedValue, definition, interpretation));
    }

    /** Erste signifikante Ziffer einer Zahl (Tausender-Trenner toleriert, fuehrende Nullen ignoriert); 0 wenn keine. */
    private static int leadingDigit(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '1' && c <= '9') {
                return c - '0';
            }
        }
        return 0;
    }

    /** Type-Token-Ratio ueber die ersten min(200, alle) Wort-Token; -1 wenn der Text keine Token hat. */
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

    /** OLS-Steigung von ys ueber den Index 0..n-1; 0 bei weniger als 2 Punkten. */
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
