package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingSequenceSurpriseTest {

    /** 5x [ADHOC, SR] und 5x [SR, ADHOC] - symmetrische Melde-Grammatik. */
    private static List<List<String>> history() {
        List<List<String>> h = new ArrayList<>();
        for (int i = 0; i < 5; i++) h.add(List.of("ADHOC", "SR"));
        for (int i = 0; i < 5; i++) h.add(List.of("SR", "ADHOC"));
        return h;
    }

    @Test
    void typicalSequenceScoresLow() {
        Optional<SignalReading> r = FilingSequenceSurprise.measure(history(), List.of("ADHOC", "SR"));
        assertTrue(r.isPresent());
        assertTrue(r.get().value() <= 1.1);
        assertTrue(r.get().interpretation().contains("Typische Kette"));
        assertTrue(r.get().interpretation().contains("ADHOC→SR"));
    }

    @Test
    void slightlyOffSequenceIsElevated() {
        Optional<SignalReading> r = FilingSequenceSurprise.measure(
                history(), List.of("ADHOC", "SR", "ADHOC", "SR", "SR"));
        assertTrue(r.isPresent());
        // Ratio ~1.46: ueber 1.1, unter 1.5.
        assertTrue(r.get().value() > 1.1 && r.get().value() < 1.5);
        assertTrue(r.get().interpretation().contains("Leicht erhoehte"));
    }

    @Test
    void unusualSequenceIsFlagged() {
        Optional<SignalReading> r = FilingSequenceSurprise.measure(history(), List.of("SR", "SR"));
        assertTrue(r.isPresent());
        // Ratio ~3.1: klar ueber 1.5.
        assertTrue(r.get().value() >= 1.5);
        assertTrue(r.get().interpretation().contains("UNTYPISCHE MELDEKETTE"));
        assertTrue(r.get().interpretation().contains("SR→SR"));
    }

    @Test
    void thinHistoryCarriesCautionNote() {
        Optional<SignalReading> r = FilingSequenceSurprise.measure(history(), List.of("ADHOC", "SR"));
        assertTrue(r.isPresent());
        // 10 Ketten < 15 -> Vorsichts-Zusatz.
        assertTrue(r.get().interpretation().contains("Vorsicht"));
        assertTrue(r.get().interpretation().contains("duenne Datenbasis"));
    }

    @Test
    void emptyOnTooFewHistoricalSequences() {
        List<List<String>> h = new ArrayList<>();
        for (int i = 0; i < 9; i++) h.add(List.of("ADHOC", "SR"));
        assertTrue(FilingSequenceSurprise.measure(h, List.of("ADHOC", "SR")).isEmpty());
    }

    @Test
    void emptyOnTooShortCurrentSequence() {
        assertTrue(FilingSequenceSurprise.measure(history(), List.of("ADHOC")).isEmpty());
    }
}
