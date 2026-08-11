package de.bsommerfeld.wsbg.terminal.web.schedule;

/**
 * The bounds a collector's next delay is drawn from: after each pass the
 * scheduler picks {@code ThreadLocalRandom.nextInt(minMinutes, maxMinutes+1)}
 * minutes and re-schedules. Random-in-bounds means the collectors drift apart
 * naturally (no synchronized burst across sources) and a host never sees a
 * metronome.
 *
 * @param minMinutes lower bound, at least 2 — the house floor; nothing polls
 *                   an outside host faster than every two minutes
 * @param maxMinutes upper bound, {@code >= minMinutes}
 */
public record FetchInterval(int minMinutes, int maxMinutes) {

    /** The house floor: no collector polls faster than this. */
    public static final int FLOOR_MINUTES = 2;

    /** Sensible default for ordinary feeds. */
    public static final FetchInterval DEFAULT = new FetchInterval(8, 15);

    /** For fast wires that tolerate tight polling. */
    public static final FetchInterval FAST = new FetchInterval(2, 5);

    /** For sensitive hosts (anti-bot walls, rate-limit-happy). */
    public static final FetchInterval SLOW = new FetchInterval(15, 30);

    public FetchInterval {
        if (minMinutes < FLOOR_MINUTES) {
            throw new IllegalArgumentException(
                    "interval floor is " + FLOOR_MINUTES + " minutes, got " + minMinutes);
        }
        if (maxMinutes < minMinutes) {
            throw new IllegalArgumentException(
                    "max " + maxMinutes + " < min " + minMinutes);
        }
    }

    public static FetchInterval of(int minMinutes, int maxMinutes) {
        return new FetchInterval(minMinutes, maxMinutes);
    }
}
