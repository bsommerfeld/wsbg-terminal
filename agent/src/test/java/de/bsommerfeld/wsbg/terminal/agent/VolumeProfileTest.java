package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.Bar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The volume-at-price profile: the POC lands where the volume traded, the
 * value area brackets it, and inputs without volume or range yield nothing
 * (a wrong profile is worse than none).
 */
class VolumeProfileTest {

    private static Bar bar(double low, double high, long volume) {
        return new Bar(1_700_000_000L, low, high, low, (low + high) / 2, volume);
    }

    @Test
    void pocLandsInTheVolumeHeavyZone() {
        List<Bar> bars = new ArrayList<>();
        // Heavy trading 130-134, light wings 100-150.
        for (int i = 0; i < 20; i++) bars.add(bar(130, 134, 1_000_000));
        bars.add(bar(100, 104, 10_000));
        bars.add(bar(146, 150, 10_000));

        VolumeProfile.Profile p = VolumeProfile.build(bars).orElseThrow();
        assertTrue(p.poc() > 128 && p.poc() < 136, "POC " + p.poc() + " should sit in 130-134");
        assertTrue(p.val() <= p.poc() && p.poc() <= p.vah());
        assertTrue(p.vah() < 140, "value area must hug the heavy zone, was " + p.vah());
        assertEquals(20_020_000L, p.totalUnits());
        assertEquals(100.0, p.low(), 1e-9);
        assertEquals(150.0, p.high(), 1e-9);
    }

    @Test
    void volumeLessOrRangeLessInputYieldsNothing() {
        assertTrue(VolumeProfile.build(List.of()).isEmpty());
        assertTrue(VolumeProfile.build(null).isEmpty());
        // An index series: prices but zero volume everywhere.
        assertTrue(VolumeProfile.build(List.of(bar(100, 110, 0), bar(105, 115, 0))).isEmpty());
        // A single price point: no range to bucket.
        assertTrue(VolumeProfile.build(List.of(
                new Bar(1L, 100, 100, 100, 100, 500))).isEmpty());
    }

    @Test
    void nanRangeFallsBackToTheClose() {
        // Bars without high/low (some venues) put their volume at the close.
        List<Bar> bars = List.of(
                new Bar(1L, Double.NaN, Double.NaN, Double.NaN, 132, 900_000),
                new Bar(2L, Double.NaN, Double.NaN, Double.NaN, 133, 900_000),
                new Bar(3L, 100, 150, 100, 125, 1_000));
        VolumeProfile.Profile p = VolumeProfile.build(bars).orElseThrow();
        assertTrue(p.poc() > 128 && p.poc() < 137, "POC " + p.poc() + " should hug 132-133");
    }
}
