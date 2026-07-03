package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unit's story memory: news is merged (never replaced) and capped, the first
 * verified price is pinned as an anchor, and a headline records the sentiment +
 * price of its moment. Together this is what keeps a long-lived unit smart after
 * the evidence prune.
 */
class SubjectUnitTest {

    private static MarketSnapshot snap(double price) {
        return new MarketSnapshot("NVDA", price, Double.NaN, 1.5, Double.NaN, Double.NaN,
                -1, Double.NaN, Double.NaN, "USD", "", 0, List.of());
    }

    private static RawNewsItem news(String uuid, Instant publishedAt) {
        return new RawNewsItem(uuid, "title " + uuid, "pub", "http://x/" + uuid,
                publishedAt, List.of());
    }

    @Test
    void newsIsMergedByUuidNotReplaced() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        Instant now = Instant.now();
        u.updateResolved("NVIDIA", "NVDA", null,
                List.of(news("a", now.minus(2, ChronoUnit.HOURS)), news("b", now.minus(1, ChronoUnit.HOURS))));
        // A later Yahoo search returns a DIFFERENT set — the earlier items
        // (possibly already cited by a headline) must not vanish.
        u.updateResolved("NVIDIA", "NVDA", null, List.of(news("c", now)));

        List<String> uuids = u.news().stream().map(RawNewsItem::uuid).toList();
        assertTrue(uuids.containsAll(List.of("a", "b", "c")), "merged, not replaced: " + uuids);
        assertEquals("c", uuids.get(0), "newest first");
    }

    @Test
    void newsCapDropsOldestAndReleasesItsCoveredId() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        Instant now = Instant.now();
        List<RawNewsItem> first = new ArrayList<>();
        for (int i = 0; i < SubjectUnit.MAX_NEWS; i++) {
            first.add(news("n" + i, now.minus(i + 10, ChronoUnit.HOURS))); // n0 newest … n11 oldest
        }
        u.updateResolved("NVIDIA", "NVDA", null, first);
        u.markNewsCovered(List.of("n" + (SubjectUnit.MAX_NEWS - 1))); // cover the oldest

        // Two fresh items push the two oldest off the cap.
        u.updateResolved("NVIDIA", "NVDA", null,
                List.of(news("fresh1", now), news("fresh2", now.minus(1, ChronoUnit.HOURS))));

        assertEquals(SubjectUnit.MAX_NEWS, u.news().size(), "capped");
        List<String> uuids = u.news().stream().map(RawNewsItem::uuid).toList();
        assertTrue(uuids.contains("fresh1") && uuids.contains("fresh2"));
        assertFalse(uuids.contains("n" + (SubjectUnit.MAX_NEWS - 1)), "oldest fell off");
        assertFalse(u.isNewsCovered("n" + (SubjectUnit.MAX_NEWS - 1)),
                "covered-id of a dropped item is released, not leaked");
    }

    @Test
    void firstPriceAnchorIsPinnedOnceAndSurvivesPrune() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        u.updateResolved("NVIDIA", "NVDA", snap(100.0), null);
        u.updateResolved("NVIDIA", "NVDA", snap(112.0), null);

        assertEquals(100.0, u.firstPrice(), 1e-9, "anchor = FIRST verified price, not the latest");
        assertNotNull(u.firstPriceAt());
        assertEquals(112.0, u.snapshot().price(), 1e-9, "snapshot itself stays live");

        u.pruneOlderThan(java.time.Duration.ofMinutes(-1)); // everything "too old"
        assertEquals(100.0, u.firstPrice(), 1e-9, "anchor survives the evidence prune");
    }

    private static SubjectUnit.EvidenceRef ev(String threadId) {
        return new SubjectUnit.EvidenceRef(threadId, null, "snippet", "reddit",
                Instant.now().getEpochSecond());
    }

    @Test
    void uncomposedEvidenceGuardSuppressesRedundantRecompose() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        // Fresh unit with evidence has something to say.
        assertTrue(u.addEvidence(ev("t1")));
        assertTrue(u.hasUncomposedEvidence(), "new evidence → eligible to compose");

        // A compose runs against the version captured before it started.
        long composedV = u.evidenceVersion();
        u.markComposedAt(composedV);
        assertFalse(u.hasUncomposedEvidence(),
                "same evidence already composed → a second compose is redundant");

        // A duplicate source bumps nothing (not new evidence).
        assertFalse(u.addEvidence(ev("t1")));
        assertFalse(u.hasUncomposedEvidence(), "duplicate evidence is not a reason to recompose");

        // Genuinely new evidence re-arms it.
        assertTrue(u.addEvidence(ev("t2")));
        assertTrue(u.hasUncomposedEvidence(), "fresh evidence → recompose warranted");
    }

    @Test
    void markComposedIsMonotonic() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        u.addEvidence(ev("t1"));
        u.addEvidence(ev("t2")); // version == 2
        u.markComposedAt(2);
        // A stale stamp from a slower path can't drag the watermark back below where
        // a later compose already advanced it (would otherwise re-open a closed gap).
        u.markComposedAt(1);
        assertFalse(u.hasUncomposedEvidence(), "older stamp must not move the watermark backwards");
    }

    @Test
    void headlineRecordsSentimentAndPriceOfItsMoment() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        u.updateResolved("NVIDIA", "NVDA", snap(100.0), null);
        u.addHeadline("NVIDIA +2% — der Käfig feiert", "BULLISH");
        u.updateResolved("NVIDIA", "NVDA", snap(90.0), null);
        u.addHeadline("NVIDIA-Update: −10% — Stimmung kippt", "BEARISH");

        List<SubjectUnit.UnitHeadline> h = u.headlines();
        assertEquals(2, h.size());
        assertEquals("BULLISH", h.get(0).sentiment());
        assertEquals(100.0, h.get(0).priceAtTime(), 1e-9);
        assertEquals("BEARISH", h.get(1).sentiment());
        assertEquals(90.0, h.get(1).priceAtTime(), 1e-9);
    }
}
