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
    void parsesHistorySkippingMalformedSamples() {
        String body = """
            {"fear_and_greed":{"score":50,"rating":"neutral","previous_close":49},
             "fear_and_greed_historical":{"data":[
               {"x":1719100000000,"y":55.2},
               {"x":1719000000000,"y":41.0},
               {"x":1719200000000,"y":140},
               {"x":"kaputt","y":33},
               {"x":1719300000000}
             ]}}
            """;
        Optional<FearGreedIndex> idx = new FearGreedClient().parse(body);
        assertTrue(idx.isPresent());
        // Two valid samples survive, sorted chronologically; the out-of-band and
        // malformed ones are skipped without failing the reading.
        assertEquals(2, idx.get().history().size());
        assertEquals(1719000000000L, idx.get().history().get(0).epochMs());
        assertEquals(41.0, idx.get().history().get(0).score(), 1e-9);
        assertEquals(55.2, idx.get().history().get(1).score(), 1e-9);
    }

    @Test
    void missingHistoryYieldsEmptyList() {
        Optional<FearGreedIndex> idx = new FearGreedClient()
                .parse("{\"fear_and_greed\":{\"score\":50,\"rating\":\"neutral\",\"previous_close\":49}}");
        assertTrue(idx.isPresent());
        assertTrue(idx.get().history().isEmpty());
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
