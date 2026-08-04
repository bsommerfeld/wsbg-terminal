package de.bsommerfeld.wsbg.terminal.instruments;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionLexiconTest {

    private static InstrumentEntry entry(String symbol, String name) {
        return new InstrumentEntry(symbol, name, null, "XETR", "stock", "test");
    }

    private static final List<InstrumentEntry> CORPUS = List.of(
            entry("DTE", "Deutsche Telekom AG"),
            entry("TSLA", "Tesla Inc"),
            entry("SAP", "SAP SE"),
            entry("DBK", "Deutsche Bank AG"),
            entry("DPW", "Deutsche Post AG"),
            entry("DB1", "Deutsche Boerse AG"),
            entry("BAS", "BASF SE"));

    private static MentionLexicon lexicon() {
        return MentionLexicon.build(CORPUS, Map.of());
    }

    @Test
    void dollarShapesAreCountedPerOccurrence() {
        List<String> hits = lexicon().scan("$TSLA zum Mond, ich hab $TSLA und nochmal $tsla");
        assertEquals(List.of("$tsla", "$tsla", "$tsla"), hits);
    }

    @Test
    void spamIsNotDeduplicated() {
        List<String> hits = lexicon().scan("$GME $GME $GME $GME");
        assertEquals(4, hits.size());
    }

    @Test
    void registeredNameIsMatchedAsAWholeSequence() {
        List<String> hits = lexicon().scan("Habe heute Deutsche Telekom AG nachgekauft.");
        assertEquals(List.of("deutsche telekom ag"), hits);
        assertEquals("DTE", lexicon().symbolFor("deutsche telekom ag").orElseThrow());
    }

    @Test
    void longestNameWinsOverItsFirstWord() {
        // "Deutsche" alone owns 3 corpus names and must never swallow the phrase.
        List<String> hits = lexicon().scan("Deutsche Bank AG und Deutsche Post AG");
        assertEquals(List.of("deutsche bank ag", "deutsche post ag"), hits);
    }

    @Test
    void genericWordIsIgnoredBecauseTooManyInstrumentsCarryIt() {
        List<String> hits = lexicon().scan("Deutsche Aktien sind mir zu langweilig");
        assertTrue(hits.isEmpty(), "generic corpus word must not count: " + hits);
    }

    @Test
    void distinctiveWordIsCountedAsItsOwnSpellingUntilItIsLearned() {
        // Nobody may decide "Telekom" means DTE - it is counted as written.
        List<String> hits = lexicon().scan("Telekom laeuft gut");
        assertEquals(List.of("telekom"), hits);
        assertTrue(lexicon().symbolFor("telekom").isEmpty());
    }

    @Test
    void learnedAliasFoldsTheSpellingOntoItsSymbol() {
        MentionLexicon learned = MentionLexicon.build(CORPUS, Map.of("telekom", "DTE"));
        assertEquals(List.of("telekom"), learned.scan("Telekom laeuft gut"));
        assertEquals("DTE", learned.symbolFor("telekom").orElseThrow());
    }

    @Test
    void lowercaseProseWordIsNotACompany() {
        assertTrue(lexicon().scan("das war telekom-maessig langweilig").isEmpty());
    }

    @Test
    void unknownDollarShapeIsCountedButNotResolved() {
        assertEquals(List.of("$xyzq"), lexicon().scan("$XYZQ ist mein Geheimtipp"));
        assertTrue(lexicon().symbolFor("$xyzq").isEmpty());
    }

    @Test
    void dollarShapeIsNotCountedTwiceByTheWordScan() {
        // "$SAP" must not also register as the bare registered name "SAP".
        assertEquals(List.of("$sap"), lexicon().scan("$SAP"));
    }

    @Test
    void beforeTheCorpusLoadsOnlyTheRoomsOwnNotationCounts() {
        MentionLexicon empty = MentionLexicon.empty();
        assertEquals(List.of("$tsla"), empty.scan("Deutsche Telekom AG $TSLA"));
        assertTrue(empty.symbolFor("$tsla").isEmpty());
    }

    @Test
    void symbolResolutionSurvivesUnknownAndBlankInput() {
        MentionLexicon lex = lexicon();
        assertTrue(lex.symbolFor(null).isEmpty());
        assertTrue(lex.symbolFor("   ").isEmpty());
        assertFalse(lex.nameFor("DTE").isEmpty());
    }

    @Test
    void ambiguousNameFallsToTheHigherPrioritySource() {
        List<InstrumentEntry> both = new ArrayList<>(CORPUS);
        both.add(entry("TL0", "Tesla Inc")); // the German secondary listing, added later
        MentionLexicon lex = MentionLexicon.build(both, Map.of());
        assertEquals("TSLA", lex.symbolFor("tesla inc").orElseThrow());
    }
}
