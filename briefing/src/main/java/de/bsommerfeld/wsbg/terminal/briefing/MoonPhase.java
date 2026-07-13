package de.bsommerfeld.wsbg.terminal.briefing;

import java.time.Instant;

/**
 * Local lunar arithmetic — no network, no API: the mean synodic month against
 * a known new-moon epoch is accurate to a few hours, which is plenty for the
 * one line the evening report carries. Zum Mond is the house religion; the
 * report simply states how far away it currently is.
 */
public final class MoonPhase {

    /** Reference new moon: 2000-01-06 18:14 UTC (a standard lunation epoch). */
    private static final double EPOCH_NEW_MOON_MS = 947_182_440_000.0;
    private static final double SYNODIC_MONTH_DAYS = 29.530588853;
    private static final double DAY_MS = 86_400_000.0;

    /**
     * One reading. {@code phase} is a stable token the UI localizes
     * ({@code NEW_MOON}, {@code WAXING_CRESCENT}, {@code FIRST_QUARTER},
     * {@code WAXING_GIBBOUS}, {@code FULL_MOON}, {@code WANING_GIBBOUS},
     * {@code LAST_QUARTER}, {@code WANING_CRESCENT}); {@code daysToFull} is 0
     * on the full-moon day itself.
     */
    public record MoonInfo(String phase, int illuminationPercent, int daysToFull) {
    }

    private MoonPhase() {
    }

    public static MoonInfo at(Instant instant) {
        double ageDays = age(instant);
        double fraction = ageDays / SYNODIC_MONTH_DAYS; // 0 = new, 0.5 = full
        int illumination = (int) Math.round(
                (1 - Math.cos(2 * Math.PI * fraction)) / 2 * 100);
        double daysToFull = (SYNODIC_MONTH_DAYS / 2 - ageDays + SYNODIC_MONTH_DAYS)
                % SYNODIC_MONTH_DAYS;
        return new MoonInfo(phaseToken(fraction), illumination, (int) Math.round(daysToFull));
    }

    /** Days since the last new moon, in [0, synodic month). */
    static double age(Instant instant) {
        double days = (instant.toEpochMilli() - EPOCH_NEW_MOON_MS) / DAY_MS;
        double age = days % SYNODIC_MONTH_DAYS;
        return age < 0 ? age + SYNODIC_MONTH_DAYS : age;
    }

    private static String phaseToken(double fraction) {
        // Eight equal windows, centred on the four principal phases.
        int slot = (int) Math.floor(((fraction + 1.0 / 16) % 1.0) * 8);
        return switch (slot) {
            case 0 -> "NEW_MOON";
            case 1 -> "WAXING_CRESCENT";
            case 2 -> "FIRST_QUARTER";
            case 3 -> "WAXING_GIBBOUS";
            case 4 -> "FULL_MOON";
            case 5 -> "WANING_GIBBOUS";
            case 6 -> "LAST_QUARTER";
            default -> "WANING_CRESCENT";
        };
    }
}
