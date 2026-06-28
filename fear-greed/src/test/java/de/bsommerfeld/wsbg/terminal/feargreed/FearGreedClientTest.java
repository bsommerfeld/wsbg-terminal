package de.bsommerfeld.wsbg.terminal.feargreed;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the CNN dataviz response + the score→band thresholds. No network. */
class FearGreedClientTest {

    @Test
    void parsesScoreRatingAndPreviousClose() {
        String body = """
            {"fear_and_greed":{"score":63.27,"rating":"greed","timestamp":"2026-06-24T20:00:00+00:00",
             "previous_close":60.1,"previous_1_week":55,"previous_1_month":48,"previous_1_year":70},
             "fear_and_greed_historical":{"data":[]}}
            """;
        Optional<FearGreedIndex> idx = new FearGreedClient().parse(body);
        assertTrue(idx.isPresent());
        assertEquals(63.27, idx.get().score(), 1e-9);
        assertEquals("greed", idx.get().rating());
        assertEquals(60.1, idx.get().previousClose(), 1e-9);
        assertEquals(FearGreedIndex.Band.GREED, idx.get().band());
    }

    @Test
    void rejectsOutOfBandAndMissingScore() {
        assertTrue(new FearGreedClient().parse("{\"fear_and_greed\":{\"score\":140}}").isEmpty());
        assertTrue(new FearGreedClient().parse("{\"fear_and_greed\":{\"rating\":\"fear\"}}").isEmpty());
        assertTrue(new FearGreedClient().parse("not json").isEmpty());
    }

    @Test
    void bandThresholds() {
        assertEquals(FearGreedIndex.Band.EXTREME_FEAR, band(10));
        assertEquals(FearGreedIndex.Band.FEAR, band(30));
        assertEquals(FearGreedIndex.Band.NEUTRAL, band(50));
        assertEquals(FearGreedIndex.Band.GREED, band(70));
        assertEquals(FearGreedIndex.Band.EXTREME_GREED, band(90));
    }

    private static FearGreedIndex.Band band(double score) {
        return new FearGreedIndex(score, "", score, java.time.Instant.EPOCH).band();
    }
}
