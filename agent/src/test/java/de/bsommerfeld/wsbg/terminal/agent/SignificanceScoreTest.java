package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignificanceScoreTest {

    @Test
    void zero_shouldHaveZeroScore() {
        assertEquals(0.0, SignificanceScore.ZERO.score(), 0.001);
    }

    @Test
    void zero_shouldHaveReasoning() {
        assertNotNull(SignificanceScore.ZERO.reasoning());
        assertEquals("No data", SignificanceScore.ZERO.reasoning());
    }

    @Test
    void meetsThreshold_shouldReturnTrueAboveThreshold() {
        var score = new SignificanceScore(15.0, "High activity");
        assertTrue(score.meetsThreshold(10.0));
    }

    @Test
    void meetsThreshold_shouldReturnFalseBelowThreshold() {
        var score = new SignificanceScore(5.0, "Low activity");
        assertFalse(score.meetsThreshold(10.0));
    }

    @Test
    void meetsThreshold_shouldReturnTrueAtExactThreshold() {
        var score = new SignificanceScore(10.0, "Exact match");
        assertTrue(score.meetsThreshold(10.0));
    }

    @Test
    void meetsThreshold_shouldReturnTrueForZeroThreshold() {
        var score = new SignificanceScore(0.1, "Minimal");
        assertTrue(score.meetsThreshold(0.0));
    }

    @Test
    void meetsThreshold_shouldHandleNegativeScore() {
        var score = new SignificanceScore(-5.0, "Negative");
        assertFalse(score.meetsThreshold(0.0));
    }

    @Test
    void accessors_shouldReturnConstructorValues() {
        var score = new SignificanceScore(42.5, "Very significant");
        assertEquals(42.5, score.score(), 0.001);
        assertEquals("Very significant", score.reasoning());
    }

    @Test
    void equality_shouldMatchOnBothFields() {
        var a = new SignificanceScore(10.0, "reason");
        var b = new SignificanceScore(10.0, "reason");
        assertEquals(a, b);
    }
}
