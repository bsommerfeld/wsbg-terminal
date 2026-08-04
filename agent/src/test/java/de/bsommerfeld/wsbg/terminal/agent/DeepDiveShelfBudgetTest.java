package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shelf's discipline. A section shelf has a hard capacity, and for weeks
 * the only thing enforcing it was a prefix cut with no judgement: the
 * multi-year press archive sat SEVENTH of seventeen blocks and, being able to
 * run to six figures of characters, pushed everything appended behind it -
 * performance, calendar years, the PRICE TABLE, the tape, the signals - off the
 * shelf in silence.
 *
 * <p>The archive is no longer pushed at all: every entry carries its own source
 * number, rides the author's index, and is fetched through the artikel(n) tool.
 * These tests pin what that requires - one number per entry, one addressable
 * block per entry, and an index line that shows the headline WITHOUT its facts.
 */
class DeepDiveShelfBudgetTest {

    private static DeepDiveService.Material materialWithHistory(int entries) {
        DeepDiveService.Material m = new DeepDiveService.Material();
        List<RawNewsItem> history = new ArrayList<>();
        Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 0; i < entries; i++) {
            String link = "https://example.invalid/a" + i;
            history.add(new RawNewsItem("uuid" + i,
                    "Schlagzeile Nummer " + i + " mit etwas Länge im Titel",
                    "Publisher", link, Instant.parse("2016-01-01T00:00:00Z"),
                    List.of(), null, null, false, null));
            facts.put(link, "- 2016-01-01: eine geprüfte Faktenzeile zu Eintrag " + i);
        }
        m.pressHistory = history;
        m.factNotes = facts;
        return m;
    }

    @Test
    void everyArchiveEntryBecomesItsOwnAddressableSource() {
        DeepDiveService.Material m = materialWithHistory(400);
        Map<String, Integer> nums = DeepDiveService.sourceNumbers(m);
        // One number per entry - the whole archive used to share a single one,
        // which is exactly why it could not be pulled and had to be pushed
        // into the prompt as text (and then capped by characters).
        assertTrue(nums.containsKey("history:0"), "first archive entry is numbered");
        assertTrue(nums.containsKey("history:399"), "and so is the four-hundredth");
        assertFalse(nums.containsKey("history"), "the single shared number is gone");
        // Distinct numbers, so a marker addresses exactly one article.
        assertEquals(400, nums.keySet().stream().filter(k -> k.startsWith("history:")).count());
    }

    @Test
    void anArchiveBlockCarriesItsOwnMarkerAndItsFacts() {
        DeepDiveService.Material m = materialWithHistory(3);
        Map<String, Integer> nums = DeepDiveService.sourceNumbers(m);
        List<String> blocks = DeepDiveService.historyBlocksFor(m, nums);
        assertEquals(3, blocks.size(), "one block per entry, nothing capped away");
        for (String b : blocks) {
            assertTrue(DeepDiveService.markerOf(b) > 0, "each block is addressable: " + b);
            assertTrue(b.contains("Schlagzeile Nummer"), "and carries its headline");
        }
        // The head line is what the author's index shows - facts stay behind
        // the tool, which is the whole point of the index being cheap.
        assertFalse(DeepDiveService.headLine(blocks.get(0)).contains("geprüfte Faktenzeile"));
    }

    @Test
    void takeReportsWhatItHadToDrop() {
        StringBuilder sb = new StringBuilder();
        assertNull(DeepDiveService.take(sb, "LEER"), "a blank shelf is no shelf");

        sb.setLength(0);
        sb.append("kurz\n");
        assertEquals("kurz\n", DeepDiveService.take(sb, "KURZ"), "a shelf that fits is untouched");

        sb.setLength(0);
        for (int i = 0; i < 20_000; i++) sb.append("x");
        String trimmed = DeepDiveService.take(sb, "LANG");
        assertTrue(trimmed.endsWith("(material trimmed)\n"), "an overrun is marked in the material");
        assertTrue(trimmed.length() < 20_000, "and it actually shortened");
    }

    @Test
    void headLineIsTheSourceLineWithoutItsFacts() {
        String block = "  -[7] [2026-08-03 10:00] Rheinmetall hebt die Prognose an · Reuters\n"
                + "      - 2026-08-03: Umsatzprognose auf 12 Mrd. EUR angehoben\n"
                + "      - 2026-08-03: Marge bestätigt\n";
        String head = DeepDiveService.headLine(block);
        assertTrue(head.contains("Rheinmetall hebt die Prognose an"));
        assertTrue(head.contains("Reuters"));
        assertFalse(head.contains("Umsatzprognose"),
                "the shelf keeper judges by the source line, the facts stay on the shelf");
    }
}
