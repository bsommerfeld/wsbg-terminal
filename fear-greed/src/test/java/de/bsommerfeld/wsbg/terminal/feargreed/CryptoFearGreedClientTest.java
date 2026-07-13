package de.bsommerfeld.wsbg.terminal.feargreed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the alternative.me response (stringly-typed, newest first). No network. */
class CryptoFearGreedClientTest {

    @Test
    void parsesReadingPreviousCloseAndChronologicalHistory() {
        String body = """
            {"name":"Fear and Greed Index",
             "data":[
               {"value":"23","value_classification":"Extreme Fear","timestamp":"1783641600","time_until_update":"26334"},
               {"value":"22","value_classification":"Extreme Fear","timestamp":"1783555200"},
               {"value":"27","value_classification":"Fear","timestamp":"1783468800"}
             ],
             "metadata":{"error":null}}
            """;
        CryptoFearGreedIndex idx = new CryptoFearGreedClient().parse(body).orElseThrow();
        assertEquals(23, idx.score(), 1e-9);
        assertEquals("Extreme Fear", idx.rating());
        assertEquals(FearGreedIndex.Band.EXTREME_FEAR, idx.band());
        assertEquals(22, idx.previousClose(), 1e-9);
        // Newest-first response becomes a chronological series in epoch millis.
        assertEquals(3, idx.history().size());
        assertEquals(1783468800_000L, idx.history().get(0).epochMs());
        assertEquals(27, idx.history().get(0).score(), 1e-9);
        assertEquals(23, idx.history().get(2).score(), 1e-9);
    }

    @Test
    void bandComesFromTheLabelNotCnnThresholds() {
        // alternative.me calls 47 "Fear" where CNN's numeric cut would say NEUTRAL —
        // the label wins so color and text never contradict each other.
        String body = """
            {"data":[{"value":"47","value_classification":"Fear","timestamp":"1783641600"}]}
            """;
        CryptoFearGreedIndex idx = new CryptoFearGreedClient().parse(body).orElseThrow();
        assertEquals(FearGreedIndex.Band.FEAR, idx.band());
        assertNull(idx.previousClose());
    }

    @Test
    void unknownLabelFallsBackToScoreThresholds() {
        CryptoFearGreedIndex idx = new CryptoFearGreedIndex(80, "Mondgier", null,
                java.time.Instant.EPOCH, java.util.List.of());
        assertEquals(FearGreedIndex.Band.EXTREME_GREED, idx.band());
    }

    @Test
    void skipsMalformedSamplesWithoutFailingTheReading() {
        String body = """
            {"data":[
               {"value":"40","value_classification":"Fear","timestamp":"1783641600"},
               {"value":"kaputt","value_classification":"Fear","timestamp":"1783555200"},
               {"value":"140","value_classification":"Fear","timestamp":"1783468800"},
               {"value":"39","value_classification":"Fear"}
             ]}
            """;
        CryptoFearGreedIndex idx = new CryptoFearGreedClient().parse(body).orElseThrow();
        assertEquals(40, idx.score(), 1e-9);
        assertEquals(1, idx.history().size());
    }

    @Test
    void rejectsEmptyDataAndGarbage() {
        assertTrue(new CryptoFearGreedClient().parse("{\"data\":[]}").isEmpty());
        assertTrue(new CryptoFearGreedClient()
                .parse("{\"data\":[{\"value\":\"kaputt\",\"timestamp\":\"1\"}]}").isEmpty());
        assertTrue(new CryptoFearGreedClient().parse("not json").isEmpty());
    }
}
