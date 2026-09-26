package de.bsommerfeld.tinyfetch.pacing;

import de.bsommerfeld.tinyfetch.api.HostPolicy;
import de.bsommerfeld.tinyfetch.api.Wall;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * The pace of one host: when the next request may go out, and whether the
 * host is paused after a wall. Pure bookkeeping - it never sleeps and never
 * touches the network, so it can be driven by a fake clock.
 *
 * <h3>What moves the next slot</h3>
 * <ul>
 *   <li>every answer: {@code minInterval} plus random jitter</li>
 *   <li>a nearly spent rate budget ({@code x-ratelimit-remaining} below 2,
 *       as Reddit sends it): no request before {@code x-ratelimit-reset}</li>
 *   <li>a wall: a pause, doubled per wall in a row, at least
 *       {@code Retry-After}, at most {@code maxBackoff}</li>
 * </ul>
 */
public final class HostPacer {

    /** Requests left in the window below which the pacer waits for the reset. */
    private static final double BUDGET_FLOOR = 2.0;

    private final HostPolicy policy;
    private final LongSupplier clock;
    private final RandomGenerator random;

    private long nextSlot;
    private long pausedUntil;
    private Wall pauseReason = Wall.NONE;
    private int wallsInRow;

    /**
     * @param clock epoch milliseconds
     */
    public HostPacer(HostPolicy policy, LongSupplier clock, RandomGenerator random) {
        this.policy = policy;
        this.clock = clock;
        this.random = random;
    }

    /** The pause in force, as epoch milliseconds - empty when there is none. */
    public synchronized Optional<Long> pausedUntil() {
        return clock.getAsLong() < pausedUntil ? Optional.of(pausedUntil) : Optional.empty();
    }

    public synchronized Wall pauseReason() {
        return pauseReason;
    }

    /** Milliseconds until the next request may go out, {@code 0} for now. */
    public synchronized long waitMillis() {
        return Math.max(0, nextSlot - clock.getAsLong());
    }

    /**
     * Books an answer.
     *
     * @param header looks up a response header by name, any case
     */
    public synchronized void recordAnswer(Wall wall, Function<String, Optional<String>> header) {
        long now = clock.getAsLong();
        nextSlot = now + spacing();
        budgetReset(header).ifPresent(resetAt -> nextSlot = Math.max(nextSlot, resetAt));

        if (wall == Wall.NONE) {
            wallsInRow = 0;
            pauseReason = Wall.NONE;
            return;
        }

        wallsInRow++;
        Duration base = wall == Wall.CHALLENGE ? policy.challengeBackoff() : policy.throttleBackoff();
        long backoff = doubled(base.toMillis(), wallsInRow - 1);
        long retryAfter = retryAfterMillis(header, now).orElse(0L);
        long pause = Math.min(Math.max(backoff, retryAfter), policy.maxBackoff().toMillis());
        pausedUntil = now + pause;
        pauseReason = wall;
        nextSlot = Math.max(nextSlot, pausedUntil);
    }

    /** Ends a pause early - the host's session was renewed, the reason for the pause is gone. */
    public synchronized void clearPause() {
        pausedUntil = 0;
        wallsInRow = 0;
        pauseReason = Wall.NONE;
        nextSlot = Math.min(nextSlot, clock.getAsLong() + spacing());
    }

    /** Books a request that got no answer - it still counts against the pace. */
    public synchronized void recordNoAnswer() {
        nextSlot = clock.getAsLong() + spacing();
    }

    private long spacing() {
        long base = policy.minInterval().toMillis();
        return base + (long) (base * policy.jitter() * random.nextDouble());
    }

    /**
     * {@code x-ratelimit-remaining} / {@code -reset} (Reddit), or the IETF
     * draft's {@code ratelimit-remaining} / {@code -reset}: seconds until the
     * window refills. Only consulted when the budget is nearly spent.
     */
    private Optional<Long> budgetReset(Function<String, Optional<String>> header) {
        Optional<Double> remaining = number(header, "x-ratelimit-remaining")
                .or(() -> number(header, "ratelimit-remaining"));
        if (remaining.isEmpty() || remaining.get() >= BUDGET_FLOOR) {
            return Optional.empty();
        }
        return number(header, "x-ratelimit-reset")
                .or(() -> number(header, "ratelimit-reset"))
                .map(seconds -> clock.getAsLong() + (long) (seconds * 1000) + 1000);
    }

    /** {@code Retry-After}: delta seconds or an HTTP date. */
    static Optional<Long> retryAfterMillis(Function<String, Optional<String>> header, long now) {
        Optional<String> value = header.apply("retry-after").map(String::trim);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Math.max(0, Long.parseLong(value.get()) * 1000));
        } catch (NumberFormatException notSeconds) {
            try {
                long at = ZonedDateTime.parse(value.get(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli();
                return Optional.of(Math.max(0, at - now));
            } catch (DateTimeParseException notDate) {
                return Optional.empty();
            }
        }
    }

    private static Optional<Double> number(Function<String, Optional<String>> header, String name) {
        try {
            return header.apply(name).map(String::trim).map(Double::parseDouble);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static long doubled(long base, int times) {
        long value = base;
        for (int i = 0; i < times && value < Long.MAX_VALUE / 2; i++) {
            value *= 2;
        }
        return value;
    }
}
