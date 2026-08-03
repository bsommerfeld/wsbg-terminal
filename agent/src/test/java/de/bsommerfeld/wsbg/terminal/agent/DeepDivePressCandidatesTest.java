package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The press half of the fishing net (wired 2026-08-03): the day's macro and
 * market wire, the German broadcaster's business desk, the Rhine level, the
 * weather at market locations and the federal debt level.
 *
 * <p>None of these feeds knows what an ISIN is, so none can be asked about the
 * subject - which is exactly why they go to the SAME subject-scoped judge the
 * world signals go through instead of onto a shelf of their own. This test
 * pins that routing: press lines must arrive as judge CANDIDATES, and they
 * must never appear on a shelf without having been judged. A general feed
 * rendered unjudged would be the noise this design exists to avoid.
 */
class DeepDivePressCandidatesTest {

    private static DeepDiveService.Material bare() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "BASF SE";
        m.ticker = "BAS.DE";
        m.isin = "DE000BASF111";
        return m;
    }

    @Test
    void pressLinesArriveAsJudgeCandidates() {
        DeepDiveService.Material m = bare();
        m.pressCandidates = List.of(
                "Rhine level at Kaub: 78.00 cm (low water) [affects: German chemicals and "
                        + "steel move bulk on this river]",
                "Macro wire: ECB holds rates [affects: the whole tape]");

        List<String> candidates = DeepDiveService.worldSignalCandidateLines(m);
        assertTrue(candidates.size() >= 2, String.valueOf(candidates));
        assertTrue(candidates.get(0).startsWith("Rhine level at Kaub"), candidates.get(0));
        assertTrue(candidates.get(1).startsWith("Macro wire"), candidates.get(1));
        // The transmission anchor rides along - it is what the judge reads to
        // decide whether a path to this subject exists at all.
        assertTrue(candidates.get(0).contains("[affects:"), candidates.get(0));
    }

    /**
     * The safeguard: an UNJUDGED press line never reaches a shelf. Only the
     * judge's survivors do, and they arrive through the world-signal block.
     */
    @Test
    void unjudgedPressLinesNeverReachAShelf() {
        DeepDiveService.Material m = bare();
        m.pressCandidates = List.of("Market press: some general headline [affects: whatever]");
        for (String shelf : DeepDiveService.sectionMaterials(m)) {
            if (shelf == null) continue;
            assertFalse(shelf.contains("some general headline"), shelf);
        }
        // And no source number is earned by candidates alone.
        assertFalse(DeepDiveService.sourceNumbers(m).containsKey("world"));
    }

    /** Once judged, a press line rides the world-signal block like any other. */
    @Test
    void judgedPressLinesRideTheWorldBlock() {
        DeepDiveService.Material m = bare();
        m.pressCandidates = List.of("Market press: chemical demand weak [affects: chemicals]");
        m.worldSignalKeep = List.of("Rhine level at Kaub: 78.00 cm (low water)");

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("world"));
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
        assertTrue(shelf.contains("Rhine level at Kaub: 78.00 cm (low water)"), shelf);
        // The register names the press feeds now, not only the world sources.
        String register = DeepDiveService.sourcesSection(m, true);
        assertTrue(register.contains("Pegelstände"), register);
        assertTrue(register.contains("Makro- und Marktpresse"), register);
        assertTrue(register.contains("je Subjekt KI-beurteilt"), register);
    }

    @Test
    void withoutPressLinesTheCandidateSetIsUnchanged() {
        DeepDiveService.Material m = bare();
        assertTrue(DeepDiveService.worldSignalCandidateLines(m).isEmpty());
    }
}
