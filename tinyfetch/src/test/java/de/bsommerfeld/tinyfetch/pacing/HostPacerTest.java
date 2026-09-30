package de.bsommerfeld.tinyfetch.pacing;

import de.bsommerfeld.tinyfetch.api.HostPolicy;
import de.bsommerfeld.tinyfetch.api.Wall;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostPacerTest {

    private static final long START = 1_000_000L;

    private final AtomicLong clock = new AtomicLong(START);
    private final HostPolicy policy = new HostPolicy(Duration.ofSeconds(2), 0.5,
            Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofHours(6));

    private HostPacer pacer(double random) {
        return new HostPacer(policy, clock::get, fixed(random));
    }

    @Test
    void firstRequestGoesOutAtOnce() {
        assertEquals(0, pacer(0).waitMillis());
    }

    @Test
    void answersAreSpacedByMinIntervalPlusJitter() {
        HostPacer pacer = pacer(1.0);
        pacer.recordAnswer(Wall.NONE, noHeaders());
        assertEquals(3_000, pacer.waitMillis()); // 2 s + 50 % of 2 s

        clock.addAndGet(1_000);
        assertEquals(2_000, pacer.waitMillis());
    }

    @Test
    void failedTransfersCountAgainstThePaceToo() {
        HostPacer pacer = pacer(0);
        pacer.recordNoAnswer();
        assertEquals(2_000, pacer.waitMillis());
    }

    @Test
    void throttleBacksOffAndDoublesPerWallInARow() {
        HostPacer pacer = pacer(0);

        pacer.recordAnswer(Wall.THROTTLED, noHeaders());
        assertEquals(Optional.of(START + 60_000), pacer.pausedUntil());
        assertEquals(Wall.THROTTLED, pacer.pauseReason());

        clock.set(START + 60_000);
        pacer.recordAnswer(Wall.THROTTLED, noHeaders());
        assertEquals(Optional.of(START + 60_000 + 120_000), pacer.pausedUntil());
    }

    @Test
    void retryAfterWinsWhenLongerThanTheBackoff() {
        HostPacer pacer = pacer(0);
        pacer.recordAnswer(Wall.THROTTLED, headers(Map.of("retry-after", "300")));
        assertEquals(Optional.of(START + 300_000), pacer.pausedUntil());
    }

    @Test
    void retryAfterBeyondMaxBackoffIsCapped() {
        HostPacer pacer = pacer(0);
        long eightHours = Duration.ofHours(8).toSeconds();
        pacer.recordAnswer(Wall.THROTTLED, headers(Map.of("retry-after", String.valueOf(eightHours))));
        assertEquals(Optional.of(START + Duration.ofHours(6).toMillis()), pacer.pausedUntil());
    }

    @Test
    void retryAfterAsHttpDate() {
        clock.set(784_111_777_000L - 90_000); // 90 s before the date below
        HostPacer pacer = pacer(0);
        pacer.recordAnswer(Wall.THROTTLED, headers(Map.of("retry-after", "Sun, 06 Nov 1994 08:49:37 GMT")));
        assertEquals(Optional.of(784_111_777_000L), pacer.pausedUntil());
    }

    @Test
    void challengeUsesTheLongBackoffAndIsCapped() {
        HostPacer pacer = pacer(0);
        for (int i = 0; i < 10; i++) {
            pacer.recordAnswer(Wall.CHALLENGE, noHeaders());
        }
        assertEquals(Optional.of(START + Duration.ofHours(6).toMillis()), pacer.pausedUntil());
    }

    @Test
    void anAnswerThatGetsThroughResetsTheStreak() {
        HostPacer pacer = pacer(0);
        pacer.recordAnswer(Wall.THROTTLED, noHeaders());
        clock.set(START + 60_000);
        pacer.recordAnswer(Wall.NONE, noHeaders());
        pacer.recordAnswer(Wall.THROTTLED, noHeaders());
        assertEquals(Optional.of(START + 60_000 + 60_000), pacer.pausedUntil());
    }

    @Test
    void pauseEndsOnItsOwn() {
        HostPacer pacer = pacer(0);
        pacer.recordAnswer(Wall.THROTTLED, noHeaders());
        clock.set(START + 60_000);
        assertTrue(pacer.pausedUntil().isEmpty());
    }

    @Test
    void nearlySpentBudgetWaitsForTheReset() {
        HostPacer pacer = pacer(0);
        pacer.recordAnswer(Wall.NONE, headers(Map.of("x-ratelimit-remaining", "1.0", "x-ratelimit-reset", "42")));
        assertEquals(43_000, pacer.waitMillis()); // reset + 1 s margin
    }

    @Test
    void healthyBudgetKeepsTheNormalPace() {
        HostPacer pacer = pacer(0);
        pacer.recordAnswer(Wall.NONE, headers(Map.of("x-ratelimit-remaining", "95.0", "x-ratelimit-reset", "42")));
        assertEquals(2_000, pacer.waitMillis());
    }

    private static Function<String, Optional<String>> noHeaders() {
        return name -> Optional.empty();
    }

    private static Function<String, Optional<String>> headers(Map<String, String> values) {
        return name -> Optional.ofNullable(values.get(name));
    }

    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }
}
