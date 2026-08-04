package de.bsommerfeld.wsbg.terminal.instruments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("aliases.jsonl");
    }

    @Test
    void aLearnedSpellingSurvivesARestart() {
        new AliasStore(file()).learn("Telekom", "DTE");
        assertEquals("DTE", new AliasStore(file()).symbolFor("telekom").orElseThrow());
    }

    @Test
    void spellingIsLookedUpRegardlessOfCaseAndPunctuation() {
        AliasStore store = new AliasStore(file());
        store.learn("Mercedes-Benz Group", "MBG");
        assertEquals("MBG", store.symbolFor("  mercedes benz   group ").orElseThrow());
    }

    @Test
    void relearningTheSamePairWritesNothingNew() throws Exception {
        AliasStore store = new AliasStore(file());
        store.learn("Telekom", "DTE");
        int version = store.version();
        store.learn("Telekom", "DTE");
        assertEquals(version, store.version());
        assertEquals(1, Files.readAllLines(file()).size());
    }

    @Test
    void aChangedVerdictWins() {
        AliasStore store = new AliasStore(file());
        store.learn("Meta", "FB");
        store.learn("Meta", "META");
        assertEquals("META", store.symbolFor("meta").orElseThrow());
        assertEquals("META", new AliasStore(file()).symbolFor("meta").orElseThrow());
    }

    @Test
    void aNameThatReadsLikeItsSymbolIsStillWorthLearning() {
        // "Meta" is not the registered name ("Meta Platforms Inc") - without this
        // pair the bare word stays an unresolved spelling forever.
        AliasStore store = new AliasStore(file());
        store.learn("Meta", "META");
        assertEquals("META", store.symbolFor("meta").orElseThrow());
    }

    @Test
    void aTornLineDoesNotCostTheRest() throws Exception {
        Files.writeString(file(), """
                {"name":"telekom","symbol":"DTE"}
                {"name":"tesla","sym
                """);
        AliasStore store = new AliasStore(file());
        assertEquals("DTE", store.symbolFor("telekom").orElseThrow());
    }

    @Test
    void blankInputIsIgnoredRatherThanStored() {
        AliasStore store = new AliasStore(file());
        store.learn("  ", "DTE");
        store.learn("Telekom", "  ");
        store.learn(null, "DTE");
        assertEquals(0, store.size());
    }

    @Test
    void anAbsentFileIsSimplyAnEmptyMemory() {
        assertEquals(0, new AliasStore(dir.resolve("nope.jsonl")).size());
    }
}
