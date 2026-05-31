package de.bsommerfeld.wsbg.terminal.currency;

import java.time.Instant;

/**
 * One snapshot of the EUR&rarr;USD exchange rate from the EUR perspective.
 *
 * <p>
 * The {@link #rate} field always answers the question "how many USD do I get
 * for 1 EUR?" — values around {@code 1.05–1.20} are typical. Higher value
 * means EUR strengthened against USD.
 *
 * <p>
 * {@link #direction} compares {@link #rate} against the previous tick:
 * {@link Direction#UP} when EUR strengthened, {@link Direction#DOWN} when it
 * weakened, {@link Direction#NEUTRAL} on the first observation or when the
 * rate is unchanged.
 *
 * <p>
 * {@link #source} records which API actually answered (primary vs fallback)
 * so the UI can surface degraded-source warnings if the primary is down.
 */
public record EurUsdQuote(
        double rate,
        Double previousRate,
        Direction direction,
        Source source,
        Instant fetchedAt) {

    /**
     * Direction of change versus the previous successful poll. Drives the
     * UI flash colour: green on UP, red on DOWN, white on NEUTRAL.
     */
    public enum Direction {
        UP,
        DOWN,
        NEUTRAL
    }

    /** Which endpoint produced this quote. */
    public enum Source {
        /** Yahoo Finance {@code /v7/finance/quote?symbols=EURUSD=X}. */
        YAHOO,
        /** Frankfurter {@code /v1/latest?base=EUR&symbols=USD} (ECB ref rate). */
        FRANKFURTER
    }

    /**
     * Builds a quote and derives the direction from the previous rate. Pass
     * {@code null} as {@code previousRate} for the very first observation;
     * direction will be {@link Direction#NEUTRAL}.
     */
    public static EurUsdQuote of(double rate, Double previousRate, Source source, Instant fetchedAt) {
        Direction direction;
        if (previousRate == null || rate == previousRate) {
            direction = Direction.NEUTRAL;
        } else if (rate > previousRate) {
            direction = Direction.UP;
        } else {
            direction = Direction.DOWN;
        }
        return new EurUsdQuote(rate, previousRate, direction, source, fetchedAt);
    }
}
