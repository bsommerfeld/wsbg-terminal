package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enum judge's verdict parsing: only known tokens inside the item range
 * count, everything else yields NO verdict (never a guessed class).
 */
class AdhocClassifierTest {

    @Test
    void parsesValidVerdictsOnly() {
        String raw = """
            {"classes":[
              {"i":1,"class":"GEWINNWARNUNG"},
              {"i":2,"class":"gewinnwarnung"},
              {"i":3,"class":"MOON_ROCKET"},
              {"i":9,"class":"UEBERNAHME"},
              {"i":0,"class":"DIVIDENDE"},
              {"class":"INSOLVENZ"}
            ]}
            """;
        Map<Integer, String> verdicts = AdhocClassifier.parseVerdicts(raw, 4);
        assertEquals(2, verdicts.size());
        assertEquals("GEWINNWARNUNG", verdicts.get(1));
        assertEquals("GEWINNWARNUNG", verdicts.get(2)); // case-normalized
        assertTrue(!verdicts.containsKey(3));           // unknown token — no verdict
        assertTrue(!verdicts.containsKey(9));           // out of range
    }

    @Test
    void brokenRepliesYieldNoVerdicts() {
        assertTrue(AdhocClassifier.parseVerdicts("not json", 3).isEmpty());
        assertTrue(AdhocClassifier.parseVerdicts("{\"other\":[]}", 3).isEmpty());
        assertTrue(AdhocClassifier.parseVerdicts("", 3).isEmpty());
    }

    @Test
    void classListMatchesThePromptTwinTokens() {
        // The closed list the prompts carry — a drifting token here would make
        // every model verdict of that class invisible.
        assertTrue(AdhocClassifier.CLASSES.contains("SONSTIGES"));
        assertEquals(12, AdhocClassifier.CLASSES.size());
    }
}
