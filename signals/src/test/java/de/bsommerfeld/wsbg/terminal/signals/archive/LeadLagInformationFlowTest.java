package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeadLagInformationFlowTest {

    private static final int N = 40;
    private static final int[] PATTERN = {1, 0, 0, 1, 1, 0, 1, 0, 0, 0, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1};

    private static int[] redditSeries() {
        int[] reddit = new int[N];
        for (int i = 0; i < N; i++) {
            reddit[i] = PATTERN[i % PATTERN.length];
        }
        return reddit;
    }

    /** Wire folgt Reddit um genau einen Bin. */
    private static int[] laggedCopy(int[] source) {
        int[] lagged = new int[source.length];
        for (int i = 1; i < source.length; i++) {
            lagged[i] = source[i - 1];
        }
        return lagged;
    }

    @Test
    void redditLeadingWireIsEarlyIndicator() {
        int[] reddit = redditSeries();
        int[] wire = laggedCopy(reddit);
        Optional<SignalReading> reading = LeadLagInformationFlow.measure(reddit, wire);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() > 0.02, "erwartet TE-Differenz > 0.02, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("VORAUS"));
        assertEquals("lead-lag-information-flow", reading.get().id());
    }

    @Test
    void wireLeadingRedditIsLaggingCage() {
        int[] reddit = redditSeries();
        int[] wire = laggedCopy(reddit);
        Optional<SignalReading> reading = LeadLagInformationFlow.measure(wire, reddit);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() < -0.02, "erwartet TE-Differenz < -0.02, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("plappert nach"));
    }

    @Test
    void unrelatedSeriesShowNoMeasurableFlow() {
        int[] reddit = redditSeries();
        int[] flatWire = new int[N];
        Optional<SignalReading> reading = LeadLagInformationFlow.measure(reddit, flatWire);

        assertTrue(reading.isPresent());
        assertTrue(Math.abs(reading.get().value()) <= 0.02,
                "erwartet |TE-Differenz| <= 0.02, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("Kein belastbarer Informationsfluss"));
    }

    @Test
    void interpretationReportsBothDirectionsAndCaution() {
        Optional<SignalReading> reading = LeadLagInformationFlow.measure(redditSeries(), laggedCopy(redditSeries()));

        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("TE(Reddit->Presse)="));
        assertTrue(reading.get().interpretation().contains("TE(Presse->Reddit)="));
        assertTrue(reading.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void tooFewOrMismatchedBinsYieldEmpty() {
        assertTrue(LeadLagInformationFlow.measure(new int[29], new int[29]).isEmpty());
        assertTrue(LeadLagInformationFlow.measure(new int[30], new int[31]).isEmpty());
        assertTrue(LeadLagInformationFlow.measure(null, new int[30]).isEmpty());
    }
}
