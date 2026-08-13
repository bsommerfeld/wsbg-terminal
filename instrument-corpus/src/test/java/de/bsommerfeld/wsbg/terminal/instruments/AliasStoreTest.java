package de.bsommerfeld.wsbg.terminal.instruments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("aliases.jsonl");
    }

    private static long daysAgo(double days) {
        return System.currentTimeMillis() / 1000 - (long) (days * 24 * 3600);
    }

    /** Writes a raw ledger line — the only way to place a posting in the past. */
    private void posting(String json) throws Exception {
        Files.writeString(file(), json + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // -- the basics that must keep holding --

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

    @Test
    void theMemoryCollectedBeforeTheLedgerIsCarriedOver() throws Exception {
        // The shape the file had for its whole life so far: name, symbol, learnedAt.
        posting("{\"name\":\"nvidia\",\"symbol\":\"NVDA\",\"learnedAt\":" + daysAgo(1) + "}");
        AliasStore store = new AliasStore(file());
        assertEquals("NVDA", store.symbolFor("NVIDIA").orElseThrow());
    }

    // -- one spelling, several readings --

    @Test
    void twoReadingsOfTheSameSpellingBothSurvive() {
        // Two clean venue readings — the historical pair used 0O8X.IL, whose
        // numeric-prefixed-secondary shape the store now refuses outright.
        AliasStore store = new AliasStore(file());
        store.learn("wirecard ag", "WDI.DE");
        store.learn("wirecard ag", "WDI.HM");
        List<AliasCandidate> live = store.candidatesFor("wirecard ag");
        assertEquals(2, live.size(), "a second listing must not overwrite the first");
    }

    @Test
    void aContestedSpellingRefusesToAnswer() {
        // The old file settled this by last-line-wins. That hid the finding: the two
        // readings disagree, and only context can break the tie.
        AliasStore store = new AliasStore(file());
        store.learn("meta", "FB");
        store.learn("meta", "META");
        assertTrue(store.symbolFor("meta").isEmpty(), "contested memory is no memory");
    }

    @Test
    void theReadingTheRoomKeepsConfirmingWins() throws Exception {
        // One stale posting for the wrong reading, several fresh ones for the right.
        posting("{\"name\":\"meta\",\"symbol\":\"FB\",\"at\":" + daysAgo(9) + "}");
        for (int i = 0; i < 4; i++) {
            posting("{\"name\":\"meta\",\"symbol\":\"META\",\"at\":" + daysAgo(i * 0.2) + "}");
        }
        AliasStore store = new AliasStore(file());
        assertEquals("META", store.symbolFor("meta").orElseThrow());
    }

    @Test
    void theWholeLedgerIsReadableIncludingTheFadedReadings() throws Exception {
        posting("{\"name\":\"meta\",\"symbol\":\"FB\",\"at\":" + daysAgo(60) + "}");
        posting("{\"name\":\"meta\",\"symbol\":\"META\",\"at\":" + daysAgo(1) + "}");
        AliasStore store = new AliasStore(file());
        assertEquals(1, store.candidatesFor("meta").size(), "the faded reading is not an answer");
        assertEquals(2, store.ledgerFor("meta").size(), "but it is still history");
    }

    // -- vitality --

    @Test
    void aReadingNobodyMentionsAgainFadesOutOnItsOwn() throws Exception {
        posting("{\"name\":\"keinmetall\",\"symbol\":\"RHM.DE\",\"at\":" + daysAgo(30) + "}");
        AliasStore store = new AliasStore(file());
        assertTrue(store.candidatesFor("keinmetall").isEmpty(),
                "a single posting must not outlive three half-lives");
        assertFalse(store.ledgerFor("keinmetall").isEmpty(), "forgotten is not deleted");
    }

    @Test
    void aFadedReadingComesBackWithASingleNewPosting() throws Exception {
        posting("{\"name\":\"keinmetall\",\"symbol\":\"RHM.DE\",\"at\":" + daysAgo(40) + "}");
        AliasStore store = new AliasStore(file());
        assertTrue(store.candidatesFor("keinmetall").isEmpty());
        store.learn("keinmetall", "RHM.DE");
        assertEquals("RHM.DE", store.symbolFor("keinmetall").orElseThrow(),
                "nothing here is final — using the word revives it");
    }

    @Test
    void whatTheRoomKeepsWritingStaysStrong() throws Exception {
        for (int i = 0; i < 8; i++) {
            posting("{\"name\":\"rheinmetall\",\"symbol\":\"RHM.DE\",\"at\":" + daysAgo(i) + "}");
        }
        AliasStore store = new AliasStore(file());
        AliasCandidate c = store.candidatesFor("rheinmetall").get(0);
        assertTrue(c.isTrusted(), "eight postings across a week must clear the trust bar");
    }

    @Test
    void aSinglePostingIsNeverTrustedEnoughToShortCircuit() {
        AliasStore store = new AliasStore(file());
        store.learn("eps", "6724.T");
        AliasCandidate c = store.candidatesFor("eps").get(0);
        assertTrue(c.isLive(), "it is remembered");
        assertFalse(c.isTrusted(), "but one verdict must not answer for itself forever");
    }

    @Test
    void theSameReadingIsNotBookedTwiceWithinTheHour() throws Exception {
        AliasStore store = new AliasStore(file());
        store.learn("telekom", "DTE");
        int version = store.version();
        store.learn("telekom", "DTE");
        store.learn("telekom", "DTE");
        assertEquals(version, store.version(), "the heartbeat has a time grain");
        assertEquals(1, Files.readAllLines(file()).size());
    }

    // -- the considered negative --

    @Test
    void aConsideredNoIsRememberedAndDecaysLikeEverythingElse() throws Exception {
        AliasStore store = new AliasStore(file());
        for (int i = 0; i < 4; i++) {
            posting("{\"name\":\"bumsbude\",\"none\":true,\"at\":" + daysAgo(i * 0.3) + "}");
        }
        store = new AliasStore(file());
        assertTrue(store.isRuledOut("bumsbude"), "a settled no stops the re-judging");
        assertTrue(store.symbolFor("bumsbude").isEmpty());

        Path other = dir.resolve("old.jsonl");
        Files.writeString(other, "{\"name\":\"bumsbude\",\"none\":true,\"at\":" + daysAgo(40) + "}\n");
        assertFalse(new AliasStore(other).isRuledOut("bumsbude"),
                "an old no must stop blocking a fresh look");
    }

    // -- the reasons --

    @Test
    void thePostingCarriesItsReasonsAndTheySurviveARestart() {
        AliasStore store = new AliasStore(file());
        store.learn("rheinmetall", "RHM.DE", new AliasProvenance(
                "DeskMatcher", "DE0007030009", 42L, "STK", "Rheinmetall reisst aus", "HIGH"));
        AliasProvenance p = new AliasStore(file()).candidatesFor("rheinmetall").get(0).provenance();
        assertEquals("DeskMatcher", p.tier());
        assertEquals("DE0007030009", p.isin());
        assertEquals(42L, p.venueId());
        assertEquals("STK", p.category());
        assertEquals("Rheinmetall reisst aus", p.context());
        assertEquals("HIGH", p.confidence());
    }

    @Test
    void aThinnerLaterVerdictDoesNotEraseWhatWasKnown() throws Exception {
        AliasStore store = new AliasStore(file());
        store.learn("rheinmetall", "RHM.DE",
                new AliasProvenance("DeskMatcher", "DE0007030009", 42L, "STK", "erster Fund", null));
        // Second posting an hour later (past the grain), carrying no ISIN.
        posting("{\"name\":\"rheinmetall\",\"symbol\":\"RHM.DE\",\"at\":"
                + (System.currentTimeMillis() / 1000) + ",\"tier\":\"JudgeMatcher\"}");
        AliasProvenance p = new AliasStore(file()).candidatesFor("rheinmetall").get(0).provenance();
        assertEquals("JudgeMatcher", p.tier(), "the newer reason wins");
        assertEquals("DE0007030009", p.isin(), "but a blank field never erases a known one");
    }

    // -- housekeeping --

    @Test
    void anOvergrownLedgerIsFoldedDownWithoutLosingItsStrength() throws Exception {
        for (int i = 0; i < AliasStore.COMPACT_LINES + 10; i++) {
            posting("{\"name\":\"nvidia\",\"symbol\":\"NVDA\",\"at\":" + (daysAgo(1) + i) + "}");
        }
        AliasStore store = new AliasStore(file());
        assertTrue(Files.readAllLines(file()).size() < 10, "the file was folded down");
        assertTrue(store.candidatesFor("nvidia").get(0).isTrusted(),
                "and the accumulated strength rode along");
        assertEquals("NVDA", new AliasStore(file()).symbolFor("nvidia").orElseThrow(),
                "the folded file still reads");
    }

    @Test
    void everyUnambiguousLivePairIsListable() {
        AliasStore store = new AliasStore(file());
        store.learn("telekom", "DTE");
        store.learn("nvidia", "NVDA");
        store.learn("meta", "FB");
        store.learn("meta", "META");
        assertEquals(2, store.all().size(), "the contested spelling contributes no answer");
        assertNotNull(store.all().get("telekom"));
    }
}
