package de.bsommerfeld.wsbg.terminal.web.impl.text;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextMatchTest {

    @Test
    void significantWordsDropLegalFormsAndShortTokens() {
        assertEquals(Set.of("rheinmetall"), TextMatch.significantWords("Rheinmetall AG"));
        assertEquals(Set.of("porsche"), TextMatch.significantWords("Porsche SE"));
        assertTrue(TextMatch.significantWords("The Inc Co").isEmpty());
        assertTrue(TextMatch.significantWords(null).isEmpty());
    }

    @Test
    void umlautsAreUnfoldedOnBothSides() {
        Set<String> words = TextMatch.significantWords("Süss MicroTec");
        assertTrue(TextMatch.matchesAny("Suess MicroTec gewinnt Auftrag", words));
    }

    @Test
    void matchesRequireWordBoundaries() {
        Set<String> words = TextMatch.significantWords("BMW");
        assertFalse(words.isEmpty());
        assertTrue(TextMatch.matchesAny("BMW liefert aus", words));
        assertFalse(TextMatch.matchesAny("Submwoofer news", words));
    }

    @Test
    void allModeNeedsEveryWordAnyModeNeedsOne() {
        Set<String> words = TextMatch.significantWords("Lithium Chile");
        assertTrue(TextMatch.matchesAny("Chile hebt Steuern", words));
        assertFalse(TextMatch.matchesAll("Chile hebt Steuern", words));
        assertTrue(TextMatch.matchesAll("Lithium aus Chile wird teurer", words));
    }
}
