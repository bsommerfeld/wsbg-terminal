package de.bsommerfeld.wsbg.terminal.signals.wire;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import de.bsommerfeld.wsbg.terminal.signals.wire.CoMentionDiffusion.Edge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoMentionDiffusionTest {

    @Test
    void chainGraphConcentratesMassOnClearContagionPath() {
        // Chain seedco - alpha - beta - gamma: mass flows far away from the seed.
        List<Edge> edges = List.of(
                new Edge("seedco", "alpha", 1),
                new Edge("alpha", "beta", 1),
                new Edge("beta", "gamma", 1));

        Optional<SignalReading> reading = CoMentionDiffusion.measure(edges, "seedco", 3);

        assertTrue(reading.isPresent());
        assertEquals("co-mention-diffusion", reading.get().id());
        assertTrue(reading.get().value() >= 0.5, "chain graph pushes mass off the seed");
        assertTrue(reading.get().interpretation().contains("contagion path"));
        assertTrue(reading.get().interpretation().contains("alpha (0."),
                "top candidate must be listed with its score");
        assertTrue(reading.get().interpretation().contains("smoke"));
    }

    @Test
    void smallStarGraphIsMediumCoupling() {
        List<Edge> edges = List.of(
                new Edge("seedco", "alpha", 1),
                new Edge("seedco", "beta", 1),
                new Edge("seedco", "gamma", 1));

        Optional<SignalReading> reading = CoMentionDiffusion.measure(edges, "seedco", 3);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 0.25 && reading.get().value() < 0.5);
        assertTrue(reading.get().interpretation().contains("Medium coupling"));
    }

    @Test
    void wideStarGraphIsDiffuse() {
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            edges.add(new Edge("seedco", "satellit" + (char) ('a' + i), 1));
        }

        Optional<SignalReading> reading = CoMentionDiffusion.measure(edges, "seedco", 3);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() < 0.25, "mass spread over 12 equal satellites");
        assertTrue(reading.get().interpretation().contains("Diffuse surroundings"));
        assertTrue(reading.get().interpretation().contains("satellita (0."));
    }

    @Test
    void smallGraphCarriesCautionNote() {
        List<Edge> edges = List.of(
                new Edge("seedco", "alpha", 1),
                new Edge("seedco", "beta", 1),
                new Edge("seedco", "gamma", 1));

        Optional<SignalReading> reading = CoMentionDiffusion.measure(edges, "seedco", 3);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Caution"));
    }

    @Test
    void seedWithoutEdgesYieldsEmpty() {
        List<Edge> edges = List.of(
                new Edge("alpha", "beta", 1),
                new Edge("beta", "gamma", 1));

        assertTrue(CoMentionDiffusion.measure(edges, "seedco", 3).isEmpty());
    }

    @Test
    void tooSmallGraphYieldsEmpty() {
        List<Edge> edges = List.of(new Edge("seedco", "alpha", 1));

        assertTrue(CoMentionDiffusion.measure(edges, "seedco", 3).isEmpty());
        assertTrue(CoMentionDiffusion.measure(List.of(), "seedco", 3).isEmpty());
        assertTrue(CoMentionDiffusion.measure(null, "seedco", 3).isEmpty());
    }
}
