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
        assertTrue(reading.interpretation().contains("AGREEMENT ECHO"), reading.interpretation());
        assertTrue(reading.interpretation().contains("Caution"), reading.interpretation());
    }

    @Test
    void mediumChainsAreMixed() {
        // 4 chains of depth 3: mean leaf depth 3 at n=12 -> index ~0.81.
        List<Comment> comments = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            comments.add(new Comment("root" + k, null));
            comments.add(new Comment("mid" + k, "root" + k));
            comments.add(new Comment("leaf" + k, "mid" + k));
        }
        SignalReading reading = ReplyTreeShape.measure(comments).orElseThrow();
        assertTrue(reading.value() > 0.5 && reading.value() <= 1.0, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("MIXED"), reading.interpretation());
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
        assertTrue(reading.interpretation().contains("CONTESTED"), reading.interpretation());
    }

    @Test
    void unknownParentsCountAsRootsAndBigThreadHasNoCaution() {
        List<Comment> comments = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            comments.add(new Comment("c" + i, null));
        }
        // Orphans with an unknown parent count as roots, not as errors.
        for (int i = 0; i < 5; i++) {
            comments.add(new Comment("o" + i, "missing" + i));
        }
        SignalReading reading = ReplyTreeShape.measure(comments).orElseThrow();
        assertTrue(reading.value() < 0.5, "value=" + reading.value());
        assertFalse(reading.interpretation().contains("Caution"), reading.interpretation());
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
