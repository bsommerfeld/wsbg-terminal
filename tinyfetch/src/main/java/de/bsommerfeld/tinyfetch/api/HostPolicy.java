package de.bsommerfeld.tinyfetch.api;

import java.time.Duration;
import java.util.Objects;

/**
 * How considerately one host is treated.
 *
 * <h3>Pace</h3>
 * Requests to a host never overlap, and consecutive ones are at least
 * {@code minInterval} apart, stretched by a random share of up to
 * {@code jitter} - a person's clicks are neither simultaneous nor metronomic.
 *
 * <h3>Back-off</h3>
 * A wall ({@link Wall}) pauses the host: every request during the pause fails
 * at once with a {@link CooldownException}, without touching the network. The
 * pause doubles with every wall in a row and never exceeds
 * {@code maxBackoff}; the first answer that gets through resets it.
 *
 * @param minInterval      smallest gap between two requests to the host
 * @param jitter           extra random gap as a share of {@code minInterval}, {@code 0..1}
 * @param throttleBackoff  first pause after {@link Wall#THROTTLED} or {@link Wall#FORBIDDEN}
 * @param challengeBackoff first pause after {@link Wall#CHALLENGE}
 * @param maxBackoff       ceiling for every pause, however many walls in a row
 */
public record HostPolicy(
        Duration minInterval,
        double jitter,
        Duration throttleBackoff,
        Duration challengeBackoff,
        Duration maxBackoff) {

    private static final HostPolicy DEFAULTS = new HostPolicy(
            Duration.ofSeconds(2), 0.5, Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofHours(6));

    public HostPolicy {
        Objects.requireNonNull(minInterval, "minInterval");
        Objects.requireNonNull(throttleBackoff, "throttleBackoff");
        Objects.requireNonNull(challengeBackoff, "challengeBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (minInterval.isNegative() || throttleBackoff.isNegative()
                || challengeBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("durations must not be negative");
        }
        if (jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("jitter must be within 0..1, was " + jitter);
        }
    }

    /**
     * 2 s apart plus up to 50 %, one minute after a throttle, half an hour
     * after a challenge, six hours at most.
     */
    public static HostPolicy defaults() {
        return DEFAULTS;
    }

    public HostPolicy withMinInterval(Duration minInterval) {
        return new HostPolicy(minInterval, jitter, throttleBackoff, challengeBackoff, maxBackoff);
    }

    public HostPolicy withJitter(double jitter) {
        return new HostPolicy(minInterval, jitter, throttleBackoff, challengeBackoff, maxBackoff);
    }

    public HostPolicy withThrottleBackoff(Duration throttleBackoff) {
        return new HostPolicy(minInterval, jitter, throttleBackoff, challengeBackoff, maxBackoff);
    }

    public HostPolicy withChallengeBackoff(Duration challengeBackoff) {
        return new HostPolicy(minInterval, jitter, throttleBackoff, challengeBackoff, maxBackoff);
    }

    public HostPolicy withMaxBackoff(Duration maxBackoff) {
        return new HostPolicy(minInterval, jitter, throttleBackoff, challengeBackoff, maxBackoff);
    }
}
