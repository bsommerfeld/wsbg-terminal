package de.bsommerfeld.wsbg.terminal.signals.wire;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrToneForensicsTest {

    /** Deterministisches Buchstaben-Wort fuer Index i (nur \p{L}-Zeichen). */
    private static String word(int i) {
        StringBuilder sb = new StringBuilder("q");
        int v = i;
        do {
            sb.append((char) ('a' + (v % 26)));
            v /= 26;
        } while (v > 0);
        return sb.toString();
    }

    /** Text mit exakt 100 Wort-Token, davon {@code distinct} verschiedene (TTR = distinct/100). */
    private static String textWithTtr(int distinct) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < distinct; i++) {
            sb.append(word(i)).append(' ');
        }
        for (int i = distinct; i < 100; i++) {
            sb.append(word(0)).append(' ');
        }
        return sb.toString();
    }

    @Test
    void steadyLanguageWithoutNumbersIsUnremarkable() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            texts.add(textWithTtr(50));
        }

        Optional<SignalReading> reading = PrToneForensics.measure(texts);

        assertTrue(reading.isPresent());
        assertEquals("pr-tone-forensics", reading.get().id());
        assertTrue(reading.get().value() < 0.3);
        assertTrue(reading.get().interpretation().contains("Unauffällig"));
        assertTrue(reading.get().interpretation().contains("Vorsicht"),
                "unscored Benford component must carry a caution note");
    }

    @Test
    void fallingVocabularyAloneIsSlightlyElevated() {
        List<String> texts = List.of(
                textWithTtr(100), textWithTtr(90), textWithTtr(80),
                textWithTtr(70), textWithTtr(60));

        Optional<SignalReading> reading = PrToneForensics.measure(texts);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 0.3 && reading.get().value() < 0.6,
                "TTR component alone caps the composite at 0.5");
        assertTrue(reading.get().interpretation().contains("Leicht erhöht"));
        assertTrue(reading.get().interpretation().contains("TTR-Steigung"));
        assertTrue(reading.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void hotLanguagePlusBenfordAnomalyIsPromotionPattern() {
        // Fallende TTR plus 40 Zahlen, die alle mit 9 beginnen (massiv Benford-widrig).
        String nines = " 91 92 93 94 95 96 9,100 9.200";
        List<String> texts = List.of(
                textWithTtr(100) + nines, textWithTtr(90) + nines, textWithTtr(80) + nines,
                textWithTtr(70) + nines, textWithTtr(60) + nines);

        Optional<SignalReading> reading = PrToneForensics.measure(texts);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 0.6);
        assertTrue(reading.get().interpretation().contains("PROMOTIONS-MUSTER"));
        assertTrue(reading.get().interpretation().contains("Benford-Chi²"));
    }

    @Test
    void tooFewTextsYieldsEmpty() {
        List<String> texts = List.of(
                textWithTtr(50), textWithTtr(50), textWithTtr(50), textWithTtr(50));

        assertTrue(PrToneForensics.measure(texts).isEmpty());
        assertTrue(PrToneForensics.measure(null).isEmpty());
    }
}
