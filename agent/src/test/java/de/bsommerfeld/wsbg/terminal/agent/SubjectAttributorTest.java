package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    // ---- Option A: cluster-relative distinctiveness (similar names) ----

    @Test
    void ambiguousWordAloneDoesNotMatchButDistinctiveDoes() {
        Set<String> world = SubjectAttributor.nameWords("MSCI World Index", "MSCI World Index");
        Set<String> em = SubjectAttributor.nameWords("MSCI Emerging Markets Index", "MSCI Emerging Markets Index");
        Set<String> ambiguous = java.util.Set.of("msci"); // shared by both subjects

        String worldRow = "Core MSCI World (iShares) -1,84%";
        // World shares msci(ambiguous) + world(distinctive) → matches.
        assertTrue(SubjectAttributor.matches(worldRow, world, null, ambiguous));
        // EM shares only msci(ambiguous) → must NOT match the World row.
        assertFalse(SubjectAttributor.matches(worldRow, em, null, ambiguous));
    }

    @Test
    void shortFormStillMatchesViaDistinctiveWord() {
        // "berkshire"/"meta" are distinctive, so the room's short form still matches
        // (the gate only bites ambiguous words shared across cluster subjects).
        Set<String> berk = SubjectAttributor.nameWords("Berkshire Hathaway", "Berkshire Hathaway Inc.");
        assertTrue(SubjectAttributor.matches("ich nehme Berkshire", berk, null, java.util.Set.of()));
        assertTrue(SubjectAttributor.matches("ich nehme Berkshire", berk, null, java.util.Set.of("hathaway")));
    }

    @Test
    void ambiguousWordsCollectsSharedNames() {
        var resolved = java.util.List.of(
                new TickerResolver.ResolvedSubject("MSCI World", "MSCI World Index", null, null,
                        java.util.List.of(), java.util.List.of(), false),
                new TickerResolver.ResolvedSubject("MSCI EM", "MSCI Emerging Markets Index", null, null,
                        java.util.List.of(), java.util.List.of(), false));
        assertTrue(SubjectAttributor.ambiguousWords(resolved).contains("msci"));
        assertFalse(SubjectAttributor.ambiguousWords(resolved).contains("world"));
    }

    // ---- Conversation context: a pick named deep in a reply chain carries the thesis ----

    @Test
    void attributeAttachesTheReplyChainAsContextToTheNamedSubject() {
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_1", "wsb", "Energie-Thread", "op", "", now,
                "/p", 10, 0.9, 3, now, null);
        // thesis (names no stock) ← OP question ← the pick naming "Constellation Energy".
        var thesis = new RedditComment("t1_thesis", "t3_1", "t3_1", "ape",
                "Ich sehe Potential im Energiebereich und der Strominfrastruktur", 5, now, now, now);
        var question = new RedditComment("t1_q", "t3_1", "t1_thesis", "op",
                "Denkst du an eine bestimmte Aktie?", 1, now + 10, now, now);
        var pick = new RedditComment("t1_pick", "t3_1", "t1_q", "ape",
                "Constellation Energy sieht interessant aus", 4, now + 20, now, now);

        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(pick, thesis, question));
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached(org.mockito.ArgumentMatchers.anyString())).thenReturn("");

        var cluster = new InvestigationCluster(thread, Embedding.from(new float[768]));
        var registry = new SubjectRegistry();
        var subject = new ResolvedSubject("Constellation Energy", "Constellation Energy",
                null, null, List.of(), List.of(), false);

        new SubjectAttributor(repository, brain).attribute(registry, cluster, List.of(subject));

        SubjectUnit unit = registry.get("name:constellation energy");
        assertNotNull(unit, "the named subject became a unit");

        var refs = unit.evidence();
        // The pick itself is a real reddit mention.
        assertTrue(refs.stream().anyMatch(e -> "t1_pick".equals(e.commentId()) && "reddit".equals(e.source())),
                "the pick is attributed as a real mention");
        // The thesis it answers is attached as context (root of the chain), even
        // though the thesis text never names the subject.
        assertTrue(refs.stream().anyMatch(e ->
                        "reddit-context".equals(e.source()) && e.snippet().contains("Energiebereich")),
                "the thesis the pick replied to is attached as conversation context");
        // The thesis is NOT counted as a real mention (it names no stock).
        assertFalse(refs.stream().anyMatch(e -> "t1_thesis".equals(e.commentId()) && "reddit".equals(e.source())),
                "the thesis stays context, never a phantom real mention");
    }
}
