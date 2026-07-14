package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The macro group judge's verdict parsing: only known tokens inside the item
 * range count — a wrongly grouped indicator poisons the group statistics.
 */
class MacroClassifierTest {

    @Test
    void parsesValidVerdictsOnly() {
        String raw = """
            {"groups":[
              {"i":1,"group":"INFLATION"},
              {"i":2,"group":"arbeitsmarkt"},
              {"i":3,"group":"MOON_PHASE"},
              {"i":7,"group":"WACHSTUM"},
              {"group":"STIMMUNG"}
            ]}
            """;
        Map<Integer, String> verdicts = MacroClassifier.parseVerdicts(raw, 3);
        assertEquals(2, verdicts.size());
        assertEquals("INFLATION", verdicts.get(1));
        assertEquals("ARBEITSMARKT", verdicts.get(2));
    }

    @Test
    void brokenRepliesYieldNoVerdicts() {
        assertTrue(MacroClassifier.parseVerdicts("not json", 3).isEmpty());
        assertTrue(MacroClassifier.parseVerdicts("{\"classes\":[]}", 3).isEmpty());
    }

    @Test
    void groupListMatchesThePromptTwinTokens() {
        assertTrue(MacroClassifier.GROUPS.contains("SONSTIGES"));
        assertEquals(6, MacroClassifier.GROUPS.size());
    }
}
