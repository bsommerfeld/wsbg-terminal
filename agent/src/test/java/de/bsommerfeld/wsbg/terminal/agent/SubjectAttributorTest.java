package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Word-level attribution matching (#2 step 2): the room writes the short/native
 * form while the subject is stored normalised, so matching is on significant
 * name words + the ticker — not a full-string substring.
 */
class SubjectAttributorTest {

    @Test
    void matchesShortFormAgainstNormalisedName() {
        // Subject stored as "Meta Platforms, Inc."; the room just says "Meta".
        Set<String> words = SubjectAttributor.nameWords("Meta", "Meta Platforms, Inc.");
        assertTrue(words.contains("meta"));
        assertTrue(SubjectAttributor.matches("ich würde Meta nehmen", words, "META"));
        assertFalse(SubjectAttributor.matches("ich kaufe Tesla", words, "META"));
    }

    @Test
    void matchesNativeFormViaCanonicalWord() {
        // Query normalised to English "Munich Re"; the room writes "Münchener rück".
        // The German canonical carries the word "münchener" → match.
        Set<String> words = SubjectAttributor.nameWords(
                "Munich Re", "Münchener Rückversicherungs-Gesellschaft Aktiengesellschaft in München");
        assertTrue(words.contains("münchener"));
        assertTrue(SubjectAttributor.matches("Asml oder allianz oder Münchener rück", words, "1MUV2.MI"));
    }

    @Test
    void matchesTickerSymbol() {
        Set<String> words = SubjectAttributor.nameWords("Nvidia", "NVIDIA Corporation");
        assertTrue(SubjectAttributor.matches("ich bin $NVDA all in", words, "NVDA"));
        assertTrue(SubjectAttributor.matches("nvidia diesmal wirklich", words, "NVDA")); // via name word
    }

    @Test
    void genericCompanyWordsDoNotMatchAlone() {
        // "Inc"/"Group"/"Holdings" must never carry a match by themselves.
        Set<String> words = SubjectAttributor.nameWords("Berkshire Hathaway", "Berkshire Hathaway Inc.");
        assertFalse(words.contains("inc"));
        assertFalse(SubjectAttributor.matches("die holding group inc ist toll", words, "BRK-B"));
        assertTrue(SubjectAttributor.matches("ich nehme Berkshire", words, "BRK-B"));
    }

    @Test
    void unmatchedSubjectHasNoNameWordHit() {
        // A subject the room never named: no shared word, no ticker → no match.
        Set<String> words = SubjectAttributor.nameWords("JPMorgan", "JPMorgan Chase & Co.");
        assertFalse(SubjectAttributor.matches("Alphabet und dann kommt Apple", words, "JPM"));
    }

    @Test
    void visionMatchingLinePicksTheSubjectRow() {
        // A transcribed watchlist: the evidence snippet should be the subject's own
        // row (with its price/move), not the top of the screenshot.
        String watchlist = "Oracle 185,00 € ▼ 8,48 %\n"
                + "Micron Technology 772,30 € ▼ 9,23 %\nNokia 12,34 € ▼ 11,95 %";
        Set<String> words = SubjectAttributor.nameWords("Micron", "Micron Technology, Inc.");
        assertEquals("Micron Technology 772,30 € ▼ 9,23 %",
                SubjectAttributor.matchingLine(watchlist, words, "MU"));
    }

    @Test
    void visionMatchingLineFallsBackToWholeTextWhenNoRowMatches() {
        String text = "some unrelated transcript with no subject row";
        Set<String> words = SubjectAttributor.nameWords("Micron", "Micron Technology, Inc.");
        assertEquals(text, SubjectAttributor.matchingLine(text, words, "MU"));
    }
}
