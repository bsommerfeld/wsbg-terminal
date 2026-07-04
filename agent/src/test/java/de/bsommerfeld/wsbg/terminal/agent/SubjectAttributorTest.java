package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        Set<String> words = NameMatcher.nameWords("Meta", "Meta Platforms, Inc.");
        assertTrue(words.contains("meta"));
        assertTrue(NameMatcher.matches("ich würde Meta nehmen", words, "META"));
        assertFalse(NameMatcher.matches("ich kaufe Tesla", words, "META"));
    }

    @Test
    void matchesNativeFormViaCanonicalWord() {
        // Query normalised to English "Munich Re"; the room writes "Münchener rück".
        // The German canonical carries the word "münchener", umlaut-transliterated to
        // "muenchener" → matches the room whether it writes "Münchener" or "Muenchener".
        Set<String> words = NameMatcher.nameWords(
                "Munich Re", "Münchener Rückversicherungs-Gesellschaft Aktiengesellschaft in München");
        assertTrue(words.contains("muenchener"));
        assertTrue(NameMatcher.matches("Asml oder allianz oder Münchener rück", words, "1MUV2.MI"));
        assertTrue(NameMatcher.matches("Asml oder allianz oder Muenchener rueck", words, "1MUV2.MI"),
                "the ue-spelling matches the umlaut canonical too");
    }

    @Test
    void matchesTickerSymbol() {
        Set<String> words = NameMatcher.nameWords("Nvidia", "NVIDIA Corporation");
        assertTrue(NameMatcher.matches("ich bin $NVDA all in", words, "NVDA"));
        assertTrue(NameMatcher.matches("nvidia diesmal wirklich", words, "NVDA")); // via name word
    }

    @Test
    void genericCompanyWordsDoNotMatchAlone() {
        // "Inc"/"Group"/"Holdings" must never carry a match by themselves.
        Set<String> words = NameMatcher.nameWords("Berkshire Hathaway", "Berkshire Hathaway Inc.");
        assertFalse(words.contains("inc"));
        assertFalse(NameMatcher.matches("die holding group inc ist toll", words, "BRK-B"));
        assertTrue(NameMatcher.matches("ich nehme Berkshire", words, "BRK-B"));
    }

    @Test
    void unmatchedSubjectHasNoNameWordHit() {
        // A subject the room never named: no shared word, no ticker → no match.
        Set<String> words = NameMatcher.nameWords("JPMorgan", "JPMorgan Chase & Co.");
        assertFalse(NameMatcher.matches("Alphabet und dann kommt Apple", words, "JPM"));
    }

    @Test
    void visionMatchingLinePicksTheSubjectRow() {
        // A transcribed watchlist: the evidence snippet should be the subject's own
        // row (with its price/move), not the top of the screenshot.
        String watchlist = "Oracle 185,00 € ▼ 8,48 %\n"
                + "Micron Technology 772,30 € ▼ 9,23 %\nNokia 12,34 € ▼ 11,95 %";
        Set<String> words = NameMatcher.nameWords("Micron", "Micron Technology, Inc.");
        assertEquals("Micron Technology 772,30 € ▼ 9,23 %",
                NameMatcher.matchingLine(watchlist, words, "MU"));
    }

    @Test
    void visionMatchingLineFallsBackToWholeTextWhenNoRowMatches() {
        String text = "some unrelated transcript with no subject row";
        Set<String> words = NameMatcher.nameWords("Micron", "Micron Technology, Inc.");
        assertEquals(text, NameMatcher.matchingLine(text, words, "MU"));
    }

    // ---- Option A: cluster-relative distinctiveness (similar names) ----

    @Test
    void ambiguousWordAloneDoesNotMatchButDistinctiveDoes() {
        Set<String> world = NameMatcher.nameWords("MSCI World Index", "MSCI World Index");
        Set<String> em = NameMatcher.nameWords("MSCI Emerging Markets Index", "MSCI Emerging Markets Index");
        Set<String> ambiguous = java.util.Set.of("msci"); // shared by both subjects

        String worldRow = "Core MSCI World (iShares) -1,84%";
        // World shares msci(ambiguous) + world(distinctive) → matches.
        assertTrue(NameMatcher.matches(worldRow, world, null, ambiguous));
        // EM shares only msci(ambiguous) → must NOT match the World row.
        assertFalse(NameMatcher.matches(worldRow, em, null, ambiguous));
    }

    @Test
    void shortFormStillMatchesViaDistinctiveWord() {
        // "berkshire"/"meta" are distinctive, so the room's short form still matches
        // (the gate only bites ambiguous words shared across cluster subjects).
        Set<String> berk = NameMatcher.nameWords("Berkshire Hathaway", "Berkshire Hathaway Inc.");
        assertTrue(NameMatcher.matches("ich nehme Berkshire", berk, null, java.util.Set.of()));
        assertTrue(NameMatcher.matches("ich nehme Berkshire", berk, null, java.util.Set.of("hathaway")));
    }

    @Test
    void ambiguousWordsCollectsSharedNames() {
        var resolved = java.util.List.of(
                new TickerResolver.ResolvedSubject("MSCI World", "MSCI World Index", null, null,
                        java.util.List.of(), java.util.List.of(), false),
                new TickerResolver.ResolvedSubject("MSCI EM", "MSCI Emerging Markets Index", null, null,
                        java.util.List.of(), java.util.List.of(), false));
        assertTrue(NameMatcher.ambiguousWords(resolved).contains("msci"));
        assertFalse(NameMatcher.ambiguousWords(resolved).contains("world"));
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

        var cluster = new InvestigationCluster(thread);
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

    // ---- Images attach twice: standalone (transcript names it) + inherited (post does) ----

    private static ResolvedSubject instrument(String query, String canonical, String ticker) {
        return new ResolvedSubject(query, canonical, ticker, null, List.of(), List.of(), false);
    }

    private static RedditThread threadWithImages(String id, String title, String body, List<String> images) {
        long now = System.currentTimeMillis() / 1000;
        return new RedditThread(id, "wsb", title, "op", body, now, "/p", 10, 0.9, 0, now, images);
    }

    @Test
    void inheritedThreadImageAttachesEvenWhenTranscriptNeverNamesTheSubject() {
        // The post says NVIDIA; the attached image is an unlabelled chart that never
        // names NVIDIA. It must still attach to NVIDIA — the poster put it on a NVIDIA
        // post, so the placement IS the link.
        String transcript = "Live chart, daily: price 145, +3%, RSI cooling from overbought";
        Set<String> nvidiaWords = NameMatcher.nameWords("Nvidia", "NVIDIA Corporation");
        assertFalse(NameMatcher.matches(transcript, nvidiaWords, "NVDA"),
                "precondition: the transcript itself does NOT name NVIDIA");

        var thread = threadWithImages("t3_1", "NVIDIA läuft heiß", "", List.of("img1"));
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of());
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached("img1")).thenReturn(transcript);

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        new SubjectAttributor(repository, brain)
                .attribute(registry, cluster, List.of(instrument("Nvidia", "NVIDIA Corporation", "NVDA")));

        SubjectUnit nvidia = registry.get("NVDA");
        assertNotNull(nvidia, "NVIDIA is a unit even though only the post text named it");
        assertTrue(nvidia.evidence().stream().anyMatch(
                        e -> "_vision#0".equals(e.commentId()) && "vision".equals(e.source())),
                "the unlabelled chart is attached to NVIDIA as inherited vision evidence");
    }

    @Test
    void imageNamingAnotherSubjectAttachesToBoth_inheritedAndStandalone() {
        // An AMD chart posted on a NVIDIA thread. NVIDIA gets it (inherited from the
        // post), AMD gets it (standalone — its own transcript names AMD). Both at once.
        String transcript = "Live AMD chart, daily: price 145, +3%";
        var thread = threadWithImages("t3_1", "NVIDIA läuft heiß", "", List.of("img1"));
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of());
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached("img1")).thenReturn(transcript);

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        new SubjectAttributor(repository, brain).attribute(registry, cluster, List.of(
                instrument("Nvidia", "NVIDIA Corporation", "NVDA"),
                instrument("AMD", "Advanced Micro Devices, Inc.", "AMD")));

        SubjectUnit nvidia = registry.get("NVDA");
        SubjectUnit amd = registry.get("AMD");
        assertNotNull(nvidia, "NVIDIA unit exists (post text + inherited image)");
        assertNotNull(amd, "AMD unit exists (standalone image)");
        // NVIDIA: post text (reddit) AND the image inherited (vision).
        assertTrue(nvidia.evidence().stream().anyMatch(e -> "reddit".equals(e.source())),
                "NVIDIA carries the post text as a real mention");
        assertTrue(nvidia.evidence().stream().anyMatch(
                        e -> "_vision#0".equals(e.commentId()) && "vision".equals(e.source())),
                "the AMD chart is also inherited onto the NVIDIA post it sits on");
        // AMD: the image standalone, snippet = the row that names it.
        var amdImg = amd.evidence().stream()
                .filter(e -> "_vision#0".equals(e.commentId())).findFirst().orElseThrow();
        assertEquals("vision", amdImg.source());
        assertTrue(amdImg.snippet().contains("AMD"), "AMD's evidence snippet is the row naming it");
        // AMD never picked up the NVIDIA post text.
        assertFalse(amd.evidence().stream().anyMatch(e -> "reddit".equals(e.source())),
                "AMD does not inherit the NVIDIA post text");
    }

    @Test
    void multipleImagesOnOnePostGetSeparateKeys() {
        // Two images on one post must not collapse into one evidence slot.
        var thread = threadWithImages("t3_1", "NVIDIA Doppelpost", "", List.of("a", "b"));
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of());
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached("a")).thenReturn("Unlabelled chart one, +2%");
        when(brain.describeImageIfCached("b")).thenReturn("Unlabelled chart two, -1%");

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        new SubjectAttributor(repository, brain)
                .attribute(registry, cluster, List.of(instrument("Nvidia", "NVIDIA Corporation", "NVDA")));

        SubjectUnit nvidia = registry.get("NVDA");
        assertNotNull(nvidia);
        long visionRefs = nvidia.evidence().stream()
                .filter(e -> e.commentId() != null && e.commentId().startsWith("_vision#"))
                .count();
        assertEquals(2, visionRefs, "both images attach under distinct per-image keys");
    }

    @Test
    void inheritedCommentImageAttachesWhenTheCommentNamedTheSubject() {
        long now = System.currentTimeMillis() / 1000;
        var thread = threadWithImages("t3_1", "Daily Diskussion", "", List.of());
        var comment = new RedditComment("t1_c", "t3_1", "t3_1", "ape",
                "NVIDIA all in, schaut euch das an", 4, now, now, now, List.of("c1"));
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(comment));
        var brain = mock(AgentBrain.class);
        // The comment's image is an unlabelled meme that never names NVIDIA.
        when(brain.describeImageIfCached("c1")).thenReturn("Meme: rocket pointing up");

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        new SubjectAttributor(repository, brain)
                .attribute(registry, cluster, List.of(instrument("Nvidia", "NVIDIA Corporation", "NVDA")));

        SubjectUnit nvidia = registry.get("NVDA");
        assertNotNull(nvidia);
        assertTrue(nvidia.evidence().stream().anyMatch(
                        e -> "t1_c".equals(e.commentId()) && "reddit".equals(e.source())),
                "the comment text is a real mention");
        assertTrue(nvidia.evidence().stream().anyMatch(
                        e -> "t1_c#img0".equals(e.commentId()) && "vision".equals(e.source())),
                "the comment's unlabelled image rides along on the comment that named NVIDIA");
    }

    // ---- Consolidation: ONE primary per event, co-subjects demoted to context ----

    @Test
    void onlyTheTitleNamedInstrumentComposes_coSubjectsAccumulateAsNamedContext() {
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_1", "wsb", "D-Wave nach NSF-Förderung +18%", "op",
                "QBTS läuft nach der Förderzusage", now, "/p", 50, 0.95, 2, now, null);
        var c1 = new RedditComment("t1_a", "t3_1", "t3_1", "ape",
                "Die National Science Foundation pumpt richtig Geld rein", 5, now, now, now);
        var c2 = new RedditComment("t1_b", "t3_1", "t3_1", "ape",
                "Chips and Science Act macht das möglich, D-Wave profitiert", 3, now, now, now);
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(c1, c2));
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached(org.mockito.ArgumentMatchers.anyString())).thenReturn("");

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        new SubjectAttributor(repository, brain).attribute(registry, cluster, List.of(
                instrument("D-Wave", "D-Wave Quantum Inc.", "QBTS"),
                new ResolvedSubject("National Science Foundation", "National Science Foundation",
                        null, null, List.of(), List.of(), false),
                new ResolvedSubject("Chips and Science Act", "Chips and Science Act",
                        null, null, List.of(), List.of(), false)));

        // Only the event's primary (title-named + tradeable) is dirty — one headline per event.
        assertEquals(Set.of("QBTS"), registry.drainDirty(), "only the primary composes");
        // The co-subjects still became units and accumulated their evidence feed-wide.
        assertNotNull(registry.get("name:national science foundation"));
        assertNotNull(registry.get("name:chips and science act"));
        // The primary's brief carries the co-subject mentions as NAMED context.
        SubjectUnit qbts = registry.get("QBTS");
        assertTrue(qbts.evidence().stream().anyMatch(e -> "reddit-context".equals(e.source())
                        && e.snippet().startsWith("[National Science Foundation]")),
                "the co-subject's mention rides on the primary as named context");
    }

    @Test
    void picksThread_eachTradeableOwnMentionComposesItsOwnStory() {
        // "Sagt mir eure Invests": AMD has its OWN comment the primary doesn't share —
        // an independent pick, so it composes too (the quiet one-liner is the gem).
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_1", "wsb", "Was kauft ihr diese Woche?", "op", "", now,
                "/p", 10, 0.9, 3, now, null);
        var c1 = new RedditComment("t1_a", "t3_1", "t3_1", "ape", "Nvidia, ganz klar", 5, now, now, now);
        var c2 = new RedditComment("t1_b", "t3_1", "t3_1", "ape", "Nvidia vor den Earnings", 2, now, now, now);
        var c3 = new RedditComment("t1_c", "t3_1", "t3_1", "ape", "AMD ist günstiger", 1, now, now, now);
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(c1, c2, c3));
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached(org.mockito.ArgumentMatchers.anyString())).thenReturn("");

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        new SubjectAttributor(repository, brain).attribute(registry, cluster, List.of(
                instrument("Nvidia", "NVIDIA Corporation", "NVDA"),
                instrument("AMD", "Advanced Micro Devices, Inc.", "AMD")));

        assertEquals(Set.of("NVDA", "AMD"), registry.drainDirty(),
                "the primary AND the own-pick co-subject compose — a picks thread is a container of stories");
        assertTrue(registry.get("NVDA").evidence().stream().anyMatch(
                        e -> "reddit-context".equals(e.source()) && e.snippet().startsWith("[AMD]")),
                "the AMD mention still rides on the primary as named context");
    }

    @Test
    void modelPrimaryOverridesTheHeuristic_evenWhenNotTradeable() {
        // Extraction read the whole thread and named the NON-tradeable protagonist.
        // The heuristic would pick the more-mentioned instrument — the model wins.
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_1", "wsb", "Spritpreise explodieren wieder", "op", "", now,
                "/p", 10, 0.9, 2, now, null);
        var c1 = new RedditComment("t1_a", "t3_1", "t3_1", "ape",
                "Diesel bei 1,90, tanke nur noch nachts", 5, now, now, now);
        var c2 = new RedditComment("t1_b", "t3_1", "t3_1", "ape",
                "Shell verdient sich dumm daran, Shell long, Shell!", 3, now, now, now);
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(c1, c2));
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached(org.mockito.ArgumentMatchers.anyString())).thenReturn("");

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        new SubjectAttributor(repository, brain).attribute(registry, cluster, List.of(
                instrument("Shell", "Shell plc", "SHEL"),
                new ResolvedSubject("Diesel", "Diesel", null, null, List.of(), List.of(), false)),
                "Diesel");

        assertTrue(registry.drainDirty().contains("name:diesel"),
                "the model's protagonist composes, tradeable or not");
    }

    @Test
    void phantomModelPrimaryFallsBackToTheHeuristic() {
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_1", "wsb", "Nvidia läuft", "op", "", now,
                "/p", 10, 0.9, 1, now, null);
        var c1 = new RedditComment("t1_a", "t3_1", "t3_1", "ape", "Nvidia knallt", 5, now, now, now);
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(c1));
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached(org.mockito.ArgumentMatchers.anyString())).thenReturn("");

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        // The model claims a primary nothing in the thread evidences → heuristic decides.
        new SubjectAttributor(repository, brain).attribute(registry, cluster,
                List.of(instrument("Nvidia", "NVIDIA Corporation", "NVDA")), "Quantum Hype AG");

        assertEquals(Set.of("NVDA"), registry.drainDirty(),
                "an evidence-less model pick never becomes a phantom primary");
    }

    @Test
    void coInstrumentSharingAllEvidenceWithThePrimaryStaysSilent() {
        // "Juli-Saisonalität bei Apple und Microsoft": both title-named, both in the
        // same comment — ONE story, so only the primary composes (no near-dup pair).
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_1", "wsb", "Juli-Saisonalität bei Apple und Microsoft",
                "op", "", now, "/p", 10, 0.9, 1, now, null);
        var c1 = new RedditComment("t1_a", "t3_1", "t3_1", "ape",
                "Apple und Microsoft sind der Free-Money-Glitch im Juli", 5, now, now, now);
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of(c1));
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached(org.mockito.ArgumentMatchers.anyString())).thenReturn("");

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        new SubjectAttributor(repository, brain).attribute(registry, cluster, List.of(
                instrument("Apple", "Apple Inc.", "AAPL"),
                instrument("Microsoft", "Microsoft Corporation", "MSFT")));

        assertEquals(Set.of("AAPL"), registry.drainDirty(),
                "same title + same comment = same story → one headline, not two near-dups");
        assertNotNull(registry.get("MSFT"), "the co-subject still accumulates its evidence");
    }

    @Test
    void imageNamingNothingOnAPostNamingNothingIsNotAttributed() {
        // No phantom: an off-topic image on a post that never names the subject must
        // not attach — there is nothing tying it to NVIDIA.
        var thread = threadWithImages("t3_1", "Schönes Wochenende euch", "", List.of("m1"));
        var repository = mock(RedditRepository.class);
        when(repository.getThread("t3_1")).thenReturn(thread);
        when(repository.getCommentsForThread("t3_1", 0)).thenReturn(List.of());
        var brain = mock(AgentBrain.class);
        when(brain.describeImageIfCached("m1")).thenReturn("Off-topic meme.");

        var cluster = new InvestigationCluster(thread);
        var registry = new SubjectRegistry();
        new SubjectAttributor(repository, brain)
                .attribute(registry, cluster, List.of(instrument("Nvidia", "NVIDIA Corporation", "NVDA")));

        assertNull(registry.get("NVDA"), "no evidence ties the post or image to NVIDIA → no unit");
    }
}
