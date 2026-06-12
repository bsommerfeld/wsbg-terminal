package de.bsommerfeld.wsbg.terminal.ui.bridge;

import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The "54% BEARISH" badge maths: two directional camps, non-directional
 * sentiments excluded, percent always over the directional total (so the
 * implied counter-share is exactly 100 − percent).
 */
class MarketMoodPublisherTest {

    private static HeadlineRecord rec(HeadlineSentiment s) {
        return new HeadlineRecord("t3_x", "Zeile", "", 0, List.of(), List.of(),
                HeadlineHighlight.NORMAL, null, List.of(), null, List.of(),
                null, s, null);
    }

    private static List<HeadlineRecord> recs(HeadlineSentiment... sentiments) {
        List<HeadlineRecord> out = new ArrayList<>();
        for (HeadlineSentiment s : sentiments) out.add(rec(s));
        return out;
    }

    @Test
    void dominantCampAndPercentOverDirectionalOnly() {
        // 7 bearish-camp vs 6 bullish-camp, plus noise that must not dilute.
        Map<String, Object> m = MarketMoodPublisher.compute(recs(
                HeadlineSentiment.BEARISH, HeadlineSentiment.BEARISH, HeadlineSentiment.BEARISH,
                HeadlineSentiment.BEARISH, HeadlineSentiment.BEARISH, HeadlineSentiment.CAPITULATION,
                HeadlineSentiment.CAPITULATION,
                HeadlineSentiment.BULLISH, HeadlineSentiment.BULLISH, HeadlineSentiment.FOMO,
                HeadlineSentiment.BREAKOUT, HeadlineSentiment.SQUEEZE, HeadlineSentiment.BULLISH,
                HeadlineSentiment.NEUTRAL, HeadlineSentiment.MIXED, HeadlineSentiment.REVERSAL));
        assertEquals(6, m.get("bullish"));
        assertEquals(7, m.get("bearish"));
        assertEquals(13, m.get("directional"));
        assertEquals(16, m.get("total"));
        assertEquals("BEARISH", m.get("dominant"));
        assertEquals(54L, m.get("percent"), "7/13 → 54% (implies 46% bullish)");
    }

    @Test
    void noDirectionalHeadlinesMeansNoBadge() {
        Map<String, Object> m = MarketMoodPublisher.compute(recs(
                HeadlineSentiment.NEUTRAL, HeadlineSentiment.MIXED));
        assertNull(m.get("dominant"));
        assertNull(m.get("percent"));
        assertEquals(2, m.get("total"));
    }

    @Test
    void aDeadTieReportsMixedAtFifty() {
        Map<String, Object> m = MarketMoodPublisher.compute(recs(
                HeadlineSentiment.BULLISH, HeadlineSentiment.BEARISH));
        assertEquals("MIXED", m.get("dominant"));
        assertEquals(50, m.get("percent"));
    }
}
