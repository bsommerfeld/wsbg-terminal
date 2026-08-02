package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.ApeWisdomClient.SocialTicker;
import de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.CompanyFacts;
import de.bsommerfeld.wsbg.terminal.briefing.WikidataClient.PageviewPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MEASURED attention on the room shelf (wired 2026-08-03): ApeWisdom's
 * cross-subreddit mention ranking and the Wikipedia lookup curve, plus the
 * Wikidata entity facts beside them. Both clients existed only for the evening
 * report until now.
 *
 * <p>The point of the block is that neither number is an opinion. The cage's
 * own evidence count says how loud OUR room is; these say whether anyone else
 * is looking. What is pinned here is the arithmetic that makes them readable -
 * a raw view count is meaningless without the name's own baseline - and the
 * ISIN pin, without which the curve could belong to a different company
 * entirely.
 */
class DeepDiveAttentionLegsTest {

    private static DeepDiveService.Material bare() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "GameStop";
        m.ticker = "GME";
        m.isin = "US36467W1099";
        return m;
    }

    private static String roomShelf(DeepDiveService.Material m) {
        return DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ROOM];
    }

    // ---- the retail rooms we do not read -----------------------------------

    @Test
    void theSocialRankLandsOnTheRoomShelfWithItsDailyMove() {
        DeepDiveService.Material m = bare();
        m.socialRank = new SocialTicker("GME", "GameStop", 412, 3_180, 4, 11, 240);

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("attention"));
        String shelf = roomShelf(m);

        assertContains(shelf, "MEASURED ATTENTION (verified)");
        assertContains(shelf, "[" + nums.get("attention") + "]");
        assertContains(shelf, "rank 4 with 412 mention(s) and 3180 upvote(s)");
        assertContains(shelf, "up 7 place(s) in 24 h");
        assertContains(DeepDiveService.sourcesSection(m, true),
                "[" + nums.get("attention") + "] Gemessene Aufmerksamkeit");
    }

    /** Yesterday unknown means no movement claim - not a claim of no movement. */
    @Test
    void anUnknownYesterdayProducesNoMovementClause() {
        DeepDiveService.Material m = bare();
        m.socialRank = new SocialTicker("GME", "GameStop", 12, 40, 88, -1, -1);
        String shelf = roomShelf(m);
        assertContains(shelf, "rank 88 with 12 mention(s)");
        assertFalse(shelf.contains("in 24 h"), shelf);
        assertFalse(shelf.contains("unchanged against yesterday"), shelf);
    }

    // ---- the public that never posts ---------------------------------------

    @Test
    void theLookupCurveIsStatedAgainstItsOwnBaseline() {
        DeepDiveService.Material m = bare();
        List<PageviewPoint> curve = new ArrayList<>();
        // Nine quiet days at 100, then one at 500 - the spike only means
        // something next to the level it broke out of.
        for (int i = 1; i <= 9; i++) {
            curve.add(new PageviewPoint(LocalDate.of(2026, 7, i), 100));
        }
        curve.add(new PageviewPoint(LocalDate.of(2026, 7, 10), 500));
        m.attentionCurve = List.copyOf(curve);

        String shelf = roomShelf(m);
        assertContains(shelf, "public lookups (Wikipedia, article pinned to this ISIN)");
        assertContains(shelf, "500 view(s) on 2026-07-10");
        assertContains(shelf, "10-day average of 140.00");
        assertContains(shelf, "(+257.14%, house-computed)");
    }

    /** One point is not a curve - there is nothing to compare it against. */
    @Test
    void aSinglePointIsNotACurve() {
        DeepDiveService.Material m = bare();
        m.attentionCurve = List.of(new PageviewPoint(LocalDate.of(2026, 7, 10), 500));
        String shelf = roomShelf(m);
        assertTrue(shelf == null || !shelf.contains("public lookups"), String.valueOf(shelf));
    }

    // ---- entity facts as a gap filler --------------------------------------

    @Test
    void entityFactsFillTheAboutShelfWhereTheHousesOwnProfileIsSilent() {
        DeepDiveService.Material m = bare();
        m.entityFacts = new CompanyFacts("Q1972092", "US36467W1099", "5493009RUZ9J2ZBTZQ48",
                "8000", "", "1980-01-01", "https://gamestop.com");

        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ABOUT];
        assertContains(shelf, "ENTITY FACTS (verified, Wikidata, item pinned to this ISIN)");
        assertContains(shelf, "[" + nums.get("attention") + "]");
        assertContains(shelf, "legal entity identifier 5493009RUZ9J2ZBTZQ48");
        assertContains(shelf, "founded 1980-01-01");
        assertContains(shelf, "employees 8000");
        // The literals are declared as literals - Wikidata stores no units here.
        assertContains(shelf, "raw literals");
    }

    /** With the house's own profile standing, the filler stays out of the way. */
    @Test
    void theFillerIsSilentWhileTheHousesOwnProfileSpeaks() {
        DeepDiveService.Material m = bare();
        m.entityFacts = new CompanyFacts("Q1972092", "US36467W1099", "549300", "8000", "",
                "1980-01-01", "https://gamestop.com");
        m.facts = new de.bsommerfeld.wsbg.terminal.core.price.InstrumentFacts(
                "GameStop Corp", "USA", "Einzelhandel", "Handel", 9.1e9, 8000, "8.000",
                12.4, "12,4", 0.0, "-", 4_500_000, 0L);
        m.deepDive = new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive(
                "US36467W1099",
                new de.bsommerfeld.wsbg.terminal.core.price.CompanyDeepDive.Profile(
                        "https://gamestop.com", "portrait", "Grapevine", "USA", 9.1e9, 300_000_000L),
                List.of(), List.of(), List.of(), null, List.of(), null, "S&P 500", 0L);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ABOUT];
        assertFalse(shelf.contains("ENTITY FACTS"), shelf);
    }

    // ---- absence -----------------------------------------------------------

    @Test
    void withoutTheLegsNothingChanges() {
        DeepDiveService.Material m = bare();
        assertFalse(DeepDiveService.sourceNumbers(m).containsKey("attention"));
        for (String shelf : DeepDiveService.sectionMaterials(m)) {
            if (shelf == null) continue;
            assertFalse(shelf.contains("MEASURED ATTENTION"), shelf);
            assertFalse(shelf.contains("ENTITY FACTS"), shelf);
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle),
                "expected to find:\n  " + needle + "\nin:\n" + haystack);
    }
}
