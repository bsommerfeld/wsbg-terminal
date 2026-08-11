package de.bsommerfeld.wsbg.terminal.signals.flow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsPositioningTest {

    /** A book of {@code expiries} expiries, each with one call and one put row. */
    private static List<OptionsPositioning.Contract> book(int expiries, long callVol,
            long putVol, long callOi, long putOi) {
        List<OptionsPositioning.Contract> out = new ArrayList<>();
        for (int e = 0; e < expiries; e++) {
            for (int strike = 0; strike < 12; strike++) {
                out.add(new OptionsPositioning.Contract(true, 20_000 + e * 7L,
                        callVol, callOi));
                out.add(new OptionsPositioning.Contract(false, 20_000 + e * 7L,
                        putVol, putOi));
            }
        }
        return out;
    }

    @Test
    void aBusyDayAgainstAThinBookReadsAsFreshPositioning() {
        var b = OptionsPositioning.measure(book(6, 400, 100, 800, 400)).orElseThrow();

        assertTrue(b.turnover() > 0.4, "turnover was " + b.turnover());
        var reading = OptionsPositioning.turnoverReading(b).orElseThrow();
        assertEquals("options-turnover", reading.id());
        assertTrue(reading.interpretation().contains("one-day offset"),
                "the offset must be stated: " + reading.interpretation());
    }

    @Test
    void aQuietDayAgainstADeepBookReadsAsOrdinary() {
        var b = OptionsPositioning.measure(book(6, 5, 5, 5_000, 5_000)).orElseThrow();

        assertTrue(b.turnover() < 0.05);
        assertTrue(OptionsPositioning.turnoverReading(b).orElseThrow()
                .interpretation().startsWith("Ordinary"));
    }

    @Test
    void aCallHeavyDayAgainstAPutHeavyBookShowsTheSkew() {
        // Flow 0.10 puts per call, book 1.50 - the arriving side differs.
        var b = OptionsPositioning.measure(book(6, 1_000, 100, 1_000, 1_500)).orElseThrow();

        assertTrue(b.callSkewVsBook() > 0.5, "skew was " + b.callSkewVsBook());
        var reading = OptionsPositioning.skewReading(b).orElseThrow();
        assertTrue(reading.interpretation().contains("FAR more call-heavy"));
        assertTrue(reading.interpretation().contains("NEVER a direction"),
                "a put/call ratio must never be handed over as a direction");
    }

    @Test
    void aPutHeavyDayAgainstACallHeavyBookShowsTheOtherSkew() {
        var b = OptionsPositioning.measure(book(6, 100, 1_000, 1_500, 1_000)).orElseThrow();

        assertTrue(b.callSkewVsBook() < -0.5);
        assertTrue(OptionsPositioning.skewReading(b).orElseThrow()
                .interpretation().contains("FAR more put-heavy"));
    }

    /**
     * The live-book finding of 2026-08-07: a mild positive skew is the NORMAL
     * state of an equity book, not a signal, and the reading must say so
     * instead of announcing a change of character on seven names out of eight.
     */
    @Test
    void theOrdinaryCallLeanOfAnEquityBookIsNotReportedAsASignal() {
        // Flow 0.50 puts per call, book 0.75 - skew +0.25, the ordinary margin.
        var b = OptionsPositioning.measure(book(6, 1_000, 500, 1_000, 750)).orElseThrow();

        assertTrue(b.callSkewVsBook() > 0 && b.callSkewVsBook() < 0.5,
                "skew was " + b.callSkewVsBook());
        String reading = OptionsPositioning.skewReading(b).orElseThrow().interpretation();
        assertTrue(reading.contains("ordinary margin"), reading);
        assertFalse(reading.contains("FAR more"), reading);
    }

    @Test
    void volumeCrowdingTheNearestExpiriesReadsAsShortDated() {
        List<OptionsPositioning.Contract> chain = new ArrayList<>();
        for (int e = 0; e < 8; e++) {
            for (int strike = 0; strike < 8; strike++) {
                long vol = e < 2 ? 2_000 : 10; // the front end carries the flow
                chain.add(new OptionsPositioning.Contract(true, 20_000 + e * 7L, vol, 900));
                chain.add(new OptionsPositioning.Contract(false, 20_000 + e * 7L, vol, 900));
            }
        }
        var b = OptionsPositioning.measure(chain).orElseThrow();

        assertTrue(b.frontShare() > 0.75, "front share was " + b.frontShare());
        assertTrue(OptionsPositioning.frontReading(b).orElseThrow()
                .interpretation().startsWith("SHORT-DATED"));
    }

    @Test
    void volumeSpreadAcrossTheCurveIsNotShortDated() {
        var b = OptionsPositioning.measure(book(10, 100, 100, 900, 900)).orElseThrow();

        assertTrue(b.frontShare() < 0.4, "front share was " + b.frontShare());
        assertFalse(OptionsPositioning.frontReading(b).orElseThrow()
                .interpretation().startsWith("SHORT-DATED"));
    }

    // ---- the guards ----

    @Test
    void aThinOrAbsentBookYieldsNothing() {
        assertTrue(OptionsPositioning.measure(null).isEmpty());
        assertTrue(OptionsPositioning.measure(List.of()).isEmpty());
        assertTrue(OptionsPositioning.measure(book(1, 1, 1, 1, 1)).isEmpty(),
                "24 rows is below the readable floor");
        assertTrue(OptionsPositioning.measure(book(6, 10, 10, 1, 1)).isEmpty(),
                "144 contracts of open interest cannot carry a ratio");
    }

    @Test
    void aSingleExpiryBookCarriesNoFrontEndReading() {
        var b = OptionsPositioning.measure(book(2, 100, 100, 900, 900)).orElseThrow();

        assertTrue(Double.isNaN(b.frontShare()),
                "with two expiries the 'nearest two' says nothing");
        assertTrue(OptionsPositioning.frontReading(b).isEmpty());
    }

    @Test
    void aBookWithoutTradedCallsCarriesNoSkew() {
        var b = OptionsPositioning.measure(book(6, 0, 500, 900, 900)).orElseThrow();

        assertTrue(Double.isNaN(b.flowPutCall()));
        assertTrue(OptionsPositioning.skewReading(b).isEmpty());
    }

    @Test
    void readingsTolerateAMissingBook() {
        assertTrue(OptionsPositioning.turnoverReading(null).isEmpty());
        assertTrue(OptionsPositioning.skewReading(null).isEmpty());
        assertTrue(OptionsPositioning.frontReading(null).isEmpty());
    }

    @Test
    void undatedContractsStillCountTowardsTheRatios() {
        List<OptionsPositioning.Contract> chain = new ArrayList<>(book(6, 100, 100, 900, 900));
        chain.add(new OptionsPositioning.Contract(true, Long.MIN_VALUE, 5_000, 5_000));
        var b = OptionsPositioning.measure(chain).orElseThrow();

        assertTrue(b.callVolume() > b.putVolume(),
                "an undated row is still a traded contract");
    }
}
