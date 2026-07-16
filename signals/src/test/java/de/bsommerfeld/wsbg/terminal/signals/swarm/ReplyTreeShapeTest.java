package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import de.bsommerfeld.wsbg.terminal.signals.swarm.ReplyTreeShape.Comment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyTreeShapeTest {

    @Test
    void flatBroadThreadIsAgreementEcho() {
        List<Comment> comments = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            comments.add(new Comment("c" + i, null));
        }
        SignalReading reading = ReplyTreeShape.measure(comments).orElseThrow();
        assertTrue(reading.value() < 0.5, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("Zustimmungs-Echo"), reading.interpretation());
        assertTrue(reading.interpretation().contains("Vorsicht"), reading.interpretation());
    }

    @Test
    void mediumChainsAreMixed() {
        // 4 Ketten der Tiefe 3: mittlere Blatt-Tiefe 3 bei n=12 -> Index ~0.81.
        List<Comment> comments = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            comments.add(new Comment("root" + k, null));
            comments.add(new Comment("mid" + k, "root" + k));
            comments.add(new Comment("leaf" + k, "mid" + k));
        }
        SignalReading reading = ReplyTreeShape.measure(comments).orElseThrow();
        assertTrue(reading.value() > 0.5 && reading.value() <= 1.0, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("emischt"), reading.interpretation());
    }

    @Test
    void singleDeepChainIsContested() {
        List<Comment> comments = new ArrayList<>();
        comments.add(new Comment("c0", null));
        for (int i = 1; i < 15; i++) {
            comments.add(new Comment("c" + i, "c" + (i - 1)));
        }
        SignalReading reading = ReplyTreeShape.measure(comments).orElseThrow();
        assertTrue(reading.value() > 1.0, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("mkämpft"), reading.interpretation());
    }

    @Test
    void unknownParentsCountAsRootsAndBigThreadHasNoCaution() {
        List<Comment> comments = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            comments.add(new Comment("c" + i, null));
        }
        // Waisen mit unbekanntem Parent gelten als Wurzeln, nicht als Fehler.
        for (int i = 0; i < 5; i++) {
            comments.add(new Comment("o" + i, "missing" + i));
        }
        SignalReading reading = ReplyTreeShape.measure(comments).orElseThrow();
        assertTrue(reading.value() < 0.5, "value=" + reading.value());
        assertFalse(reading.interpretation().contains("Vorsicht"), reading.interpretation());
    }

    @Test
    void tooFewCommentsYieldEmpty() {
        List<Comment> comments = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            comments.add(new Comment("c" + i, null));
        }
        assertTrue(ReplyTreeShape.measure(comments).isEmpty());
        assertTrue(ReplyTreeShape.measure(null).isEmpty());
    }
}
