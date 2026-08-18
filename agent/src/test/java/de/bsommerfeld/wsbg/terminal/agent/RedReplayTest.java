package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The D4 stand's arithmetic, held to account without a model. Every case here is a
 * failure the stand actually produced or could produce silently — it has already
 * reported "0 of 102 classified" (only the wrapper reply shape was accepted) and an
 * undercounted actor formula (a name hit standing first blocked a later actor hit),
 * and both read exactly like "the idea does not work".
 */
class RedReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> CLASSES =
            Set.of("UEBERNAHME", "GROSSAUFTRAG", "INDEXAENDERUNG", "RUECKBLICK", "SONSTIGES");
    private static final Set<String> RED = Set.of("UEBERNAHME", "GROSSAUFTRAG", "INDEXAENDERUNG");
    private static final long STALE = 36 * 3600L;
    private static final long NOW = 1_786_700_000L;

    private static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ codec

    @Nested
    @DisplayName("verdict wire form")
    class Codec {

        @Test
        void roundTripsClassAndActor() {
            RedReplay.Verdict v = new RedReplay.Verdict("UEBERNAHME", "Silver Lake");
            assertEquals(v, RedReplay.Verdict.decode(v.encode()));
        }

        @Test
        void keepsTheSeparatorForABlankActor() {
            String wire = new RedReplay.Verdict("MARKTBERICHT", "").encode();
            assertTrue(RedReplay.Verdict.isCurrentWireForm(wire),
                    "a blank actor must still be distinguishable from a pre-actor verdict");
            assertEquals("", RedReplay.Verdict.decode(wire).actor());
        }

        @Test
        void readsALegacyVerdictAsActorless() {
            RedReplay.Verdict v = RedReplay.Verdict.decode("UEBERNAHME");
            assertEquals("UEBERNAHME", v.cls());
            assertEquals("", v.actor());
        }

        @Test
        void recognisesAPreActorVerdictSoItIsReclassifiedRatherThanTrusted() {
            assertFalse(RedReplay.Verdict.isCurrentWireForm("UEBERNAHME"));
            assertFalse(RedReplay.Verdict.isCurrentWireForm(null));
        }

        @Test
        void survivesAnActorContainingTheSeparatorlessEdgeCases() {
            RedReplay.Verdict v = new RedReplay.Verdict("GROSSAUFTRAG", "Iris Energy / IREN");
            assertEquals("Iris Energy / IREN", RedReplay.Verdict.decode(v.encode()).actor());
        }
    }

    // ------------------------------------------------------------------ parsing

    @Nested
    @DisplayName("reply parsing")
    class Parsing {

        @Test
        void readsTheWrapperObjectThePromptAsksFor() {
            Map<Integer, RedReplay.Verdict> out = RedReplay.parseReply(
                    json("{\"classes\":[{\"i\":1,\"class\":\"UEBERNAHME\",\"akteur\":\"Workday\"}]}"),
                    1, CLASSES);
            assertEquals(1, out.size());
            assertEquals(new RedReplay.Verdict("UEBERNAHME", "Workday"), out.get(1));
        }

        @Test
        void readsTheBareArrayTheMlxRunnerActuallyReturns() {
            // The regression that made the whole stand report 0 of 102 classified.
            Map<Integer, RedReplay.Verdict> out = RedReplay.parseReply(
                    json("[{\"i\":1,\"class\":\"UEBERNAHME\",\"akteur\":\"Workday\"},"
                            + "{\"i\":2,\"class\":\"SONSTIGES\",\"akteur\":\"\"}]"),
                    2, CLASSES);
            assertEquals(2, out.size());
            assertEquals("Workday", out.get(1).actor());
            assertEquals("", out.get(2).actor());
        }

        @Test
        void readsTheBareSingleObjectReturnedWhenTheBatchHoldsOneItem() {
            // The unbundled arm's shape: asked about one title, the model drops the
            // envelope entirely. Found by the smoke, before it cost a measurement.
            Map<Integer, RedReplay.Verdict> out = RedReplay.parseReply(
                    json("{\"i\":1,\"class\":\"UEBERNAHME\",\"akteur\":\"Microsoft\"}"),
                    1, CLASSES);
            assertEquals(1, out.size());
            assertEquals(new RedReplay.Verdict("UEBERNAHME", "Microsoft"), out.get(1));
        }

        @Test
        void yieldsNothingForAnObjectThatCarriesNoVerdictAtAll() {
            assertTrue(RedReplay.parseReply(json("{\"note\":\"nichts\"}"), 1, CLASSES).isEmpty());
        }

        @Test
        void yieldsNothingForAReplyThatWasNotJsonAtAll() {
            assertTrue(RedReplay.parseReply(null, 5, CLASSES).isEmpty(),
                    "an unusable reply must be empty so the caller retries it");
        }

        @Test
        void dropsATokenOutsideTheClosedList() {
            assertTrue(RedReplay.parseReply(
                    json("[{\"i\":1,\"class\":\"KURSZIEL\",\"akteur\":\"X\"}]"), 1, CLASSES)
                    .isEmpty());
        }

        @Test
        void dropsAnIndexOutsideTheBatch() {
            Map<Integer, RedReplay.Verdict> out = RedReplay.parseReply(
                    json("[{\"i\":0,\"class\":\"UEBERNAHME\"},{\"i\":3,\"class\":\"UEBERNAHME\"},"
                            + "{\"i\":-1,\"class\":\"UEBERNAHME\"}]"), 2, CLASSES);
            assertTrue(out.isEmpty(), "0, -1 and 3 are all outside a two-item batch");
        }

        @Test
        void keepsTheFirstVerdictWhenTheModelAnswersAnItemTwice() {
            Map<Integer, RedReplay.Verdict> out = RedReplay.parseReply(
                    json("[{\"i\":1,\"class\":\"UEBERNAHME\"},{\"i\":1,\"class\":\"SONSTIGES\"}]"),
                    1, CLASSES);
            assertEquals("UEBERNAHME", out.get(1).cls());
        }

        @Test
        void normalisesCaseAndAMissingActor() {
            Map<Integer, RedReplay.Verdict> out = RedReplay.parseReply(
                    json("[{\"i\":1,\"class\":\" uebernahme \"}]"), 1, CLASSES);
            assertEquals(new RedReplay.Verdict("UEBERNAHME", ""), out.get(1));
        }
    }

    // ------------------------------------------------------------------ selection

    @Nested
    @DisplayName("which evidence a line rests on")
    class Selection {

        private RedReplay.Evidence ev(String cls, String actor, String title, long ageHours) {
            return new RedReplay.Evidence(new RedReplay.Verdict(cls, actor), title,
                    NOW - ageHours * 3600L);
        }

        @Test
        void doesNotFireWithoutEvidence() {
            assertFalse(RedReplay.bestHit("Workday, Inc.", NOW, List.of(), RED, STALE).fired());
        }

        @Test
        void doesNotFireOnAClassThatNeverEarnsRed() {
            RedReplay.Hit h = RedReplay.bestHit("Workday, Inc.", NOW,
                    List.of(ev("RUECKBLICK", "Workday", "Workday steigt 18 Prozent", 1)),
                    RED, STALE);
            assertFalse(h.fired());
        }

        @Test
        void doesNotFireOnAStaleItem() {
            RedReplay.Hit h = RedReplay.bestHit("Workday, Inc.", NOW,
                    List.of(ev("UEBERNAHME", "Workday", "Silver Lake to buy Workday", 40)),
                    RED, STALE);
            assertFalse(h.fired(), "40h is past the 36h window");
        }

        @Test
        void treatsAnUnknownPublicationDateAsFreshRatherThanStale() {
            RedReplay.Hit h = RedReplay.bestHit("Workday, Inc.", NOW,
                    List.of(new RedReplay.Evidence(new RedReplay.Verdict("UEBERNAHME", "Workday"),
                            "Silver Lake to buy Workday", 0L)),
                    RED, STALE);
            assertTrue(h.fired());
        }

        @Test
        void firesOnTierAAloneWhenTheLineHasNoSubjectToTest() {
            RedReplay.Hit h = RedReplay.bestHit("", NOW,
                    List.of(ev("GROSSAUFTRAG", "Ondas", "Ondas wins tender", 1)), RED, STALE);
            assertTrue(h.fired());
            assertFalse(h.named());
            assertFalse(h.isActor(), "with no subject neither name nor actor is decidable");
        }

        @Test
        void seesTheNameWithoutTheActor() {
            RedReplay.Hit h = RedReplay.bestHit("Red Cat Holdings", NOW,
                    List.of(ev("GROSSAUFTRAG", "Ondas", "Red Cat and Ondas rise on tariffs", 1)),
                    RED, STALE);
            assertTrue(h.fired());
            assertTrue(h.named());
            assertFalse(h.isActor(), "the piece names Red Cat but the event belongs to Ondas");
        }

        @Test
        void seesTheActorWhenTheSubjectOwnsTheEvent() {
            RedReplay.Hit h = RedReplay.bestHit("Workday, Inc.", NOW,
                    List.of(ev("UEBERNAHME", "Workday", "Silver Lake in talks to buy Workday", 1)),
                    RED, STALE);
            assertTrue(h.isActor());
            assertTrue(h.named());
        }

        @Test
        void doesNotMistakeANeighbourForTheActor() {
            RedReplay.Hit h = RedReplay.bestHit("Micron Technology, Inc.", NOW,
                    List.of(ev("UEBERNAHME", "SK Hynix", "Micron slips as SK Hynix doubles HBM share", 1)),
                    RED, STALE);
            assertTrue(h.fired());
            assertTrue(h.named());
            assertFalse(h.isActor());
        }

        @Test
        void doesNotLetAnEarlierNameHitMaskALaterActorHit() {
            // The bug the actor formula shipped with: the first item was recorded and
            // the genuine actor hit behind it could never replace it, so C undercounted.
            RedReplay.Hit h = RedReplay.bestHit("Nemetschek SE", NOW, List.of(
                            ev("UEBERNAHME", "Workday", "Nemetschek profitiert von Workday-Übernahme", 1),
                            ev("GROSSAUFTRAG", "Nemetschek", "Nemetschek gewinnt Großauftrag", 1)),
                    RED, STALE);
            assertTrue(h.isActor(), "the actor hit stands behind a mere name hit and must win");
            assertEquals("GROSSAUFTRAG", h.cls());
            assertEquals("Nemetschek", h.actor());
        }

        @Test
        void keepsTheActorHitWhenItComesFirst() {
            RedReplay.Hit h = RedReplay.bestHit("Nemetschek SE", NOW, List.of(
                            ev("GROSSAUFTRAG", "Nemetschek", "Nemetschek gewinnt Großauftrag", 1),
                            ev("UEBERNAHME", "Workday", "Nemetschek profitiert von Workday-Übernahme", 1)),
                    RED, STALE);
            assertTrue(h.isActor());
            assertEquals("GROSSAUFTRAG", h.cls());
        }

        @Test
        void ignoresAStaleActorHitInFavourOfAFreshWeakerOne() {
            RedReplay.Hit h = RedReplay.bestHit("Nemetschek SE", NOW, List.of(
                            ev("GROSSAUFTRAG", "Nemetschek", "Nemetschek gewinnt Großauftrag", 50),
                            ev("UEBERNAHME", "Workday", "Nemetschek profitiert von Workday-Übernahme", 1)),
                    RED, STALE);
            assertTrue(h.fired());
            assertFalse(h.isActor(), "the actor hit is stale and must not count");
            assertEquals("UEBERNAHME", h.cls());
        }
    }

    // ------------------------------------------------------------------ repetition

    @Nested
    @DisplayName("a catalyst fires once")
    class Repetition {

        @Test
        void aFirstRedIsNeverARepeat() {
            assertFalse(RedReplay.isRepeat(null, NOW, STALE));
        }

        @Test
        void aFollowUpInsideTheWindowIsARepeat() {
            assertTrue(RedReplay.isRepeat(NOW - 3600L, NOW, STALE));
        }

        @Test
        void theWindowEdgeStillCounts() {
            assertTrue(RedReplay.isRepeat(NOW - STALE, NOW, STALE));
        }

        @Test
        void aNewCatalystPastTheWindowFiresAgain() {
            assertFalse(RedReplay.isRepeat(NOW - STALE - 1, NOW, STALE));
        }
    }
}
