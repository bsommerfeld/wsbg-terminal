package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoveltyScoreTest {

    private static final List<String> ARCHIVE = List.of(
            "Quartalszahlen kommen am Donnerstag vor Handelsstart",
            "Analysten erwarten stabile Margen im Kerngeschäft",
            "Dividende bleibt laut Vorstand unverändert",
            "Aufsichtsrat verlängert Vertrag des Finanzchefs",
            "Umsatz im Kerngeschäft wächst leicht",
            "Prognose für das Gesamtjahr bestätigt",
            "Neue Produktlinie startet im Herbst",
            "Werk in Norddeutschland wird modernisiert",
            "Tarifverhandlungen ohne Ergebnis vertagt",
            "Kartellamt genehmigt kleinere Übernahme");

    @Test
    void maximalDistantHeadlineIsRegimeChangeCandidate() {
        Optional<SignalReading> reading = NoveltyScore.measure(
                "Insolvenzantrag gestellt - Handel sofort ausgesetzt, "
                        + "Staatsanwaltschaft durchsucht Zentrale wegen Bilanzbetrug",
                ARCHIVE);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 0.85, "erwartet Neuheit >= 0.85, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("REGIME-CHANGE CANDIDATE"));
        assertEquals("novelty-score", reading.get().id());
    }

    @Test
    void exactArchiveDuplicateIsRepetition() {
        Optional<SignalReading> reading = NoveltyScore.measure(
                "Quartalszahlen kommen am Donnerstag vor Handelsstart", ARCHIVE);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= 0.25, "erwartet Neuheit <= 0.25, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("Repetition"));
    }

    @Test
    void partialOverlapIsNormalContinuation() {
        Optional<SignalReading> reading = NoveltyScore.measure(
                "Quartalszahlen am Donnerstag: Analysten erwarten leichtes Wachstum", ARCHIVE);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() > 0.25 && reading.get().value() < 0.85,
                "erwartet Mittelband, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("Normal continuation"));
    }

    @Test
    void thinArchiveCarriesCautionNote() {
        Optional<SignalReading> reading = NoveltyScore.measure(
                "Quartalszahlen kommen am Donnerstag vor Handelsstart", ARCHIVE);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Caution"));
    }

    @Test
    void tooSmallArchiveYieldsEmpty() {
        assertTrue(NoveltyScore.measure("irgendeine Zeile", ARCHIVE.subList(0, 9)).isEmpty());
        assertTrue(NoveltyScore.measure("irgendeine Zeile", List.of()).isEmpty());
    }
}
