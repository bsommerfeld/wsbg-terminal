package de.bsommerfeld.wsbg.terminal.signals.flow;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dates an attention wave from price and volume alone: is it IGNITING, running,
 * long in the tooth, or already FADING?
 *
 * <p><b>Method:</b> volume is measured against a baseline the wave itself
 * cannot lift - the MEDIAN daily volume of the window's older stretch, with the
 * most recent {@value #RECENT_EXCLUDE} sessions deliberately excluded. Every
 * session then carries a relative volume (RVOL, smoothed over
 * {@value #SMOOTH} sessions). A session is part of a wave once it trades at
 * {@value #ELEVATED} times baseline; a run of such sessions (tolerating
 * {@value #GAP_TOLERANCE} quiet session inside it) is the WAVE, and the length
 * of the currently running run is the wave's AGE - the number that dates the
 * phase. A wave only counts once its smoothed peak reached {@value #WAVE_MIN}
 * times baseline, which is the level at which participation is substantially
 * above normal rather than noise.
 *
 * <p>The decisive distinction is deliberately NOT "how much volume" but "how
 * long already, and is it still growing": high volume that is RECEDING from its
 * own peak is a wave being unwound, not one being entered - the same reading
 * options traders get from volume against falling open interest, here derived
 * from the tape alone.
 *
 * <p><b>Terminal inputs:</b> the daily closes and daily volumes of the
 * instrument's home venue, oldest first - the series the report already holds.
 * Everything is measured against the instrument's OWN history, never against a
 * global constant: a low-float name lives at RVOL levels that would be
 * extraordinary for a blue chip, and the baseline absorbs exactly that.
 *
 * <p>Statistics in code, reading in the model: the kernel dates the wave, it
 * never says what the wave MEANS for the name.
 */
public final class HypePhase {

    /** Sessions considered at most; older history only dilutes the baseline. */
    private static final int WINDOW_MAX = 90;
    /** Below this many sessions there is no measurement. */
    private static final int MIN_DAYS = 40;
    /** Recent sessions kept OUT of the baseline so a wave cannot lift its own yardstick. */
    private static final int RECENT_EXCLUDE = 10;
    /** Below this many baseline sessions the yardstick is too thin to trust. */
    private static final int MIN_BASELINE_DAYS = 25;
    /** Sessions the relative volume is smoothed over. */
    private static final int SMOOTH = 3;
    /** From this multiple of baseline a session participates in a wave. */
    private static final double ELEVATED = 1.5;
    /** A wave must have peaked at this multiple of baseline to count as one at all. */
    private static final double WAVE_MIN = 2.0;
    /** Quiet sessions tolerated inside a running wave. */
    private static final int GAP_TOLERANCE = 1;
    /** Up to this age a running wave is still IGNITING. */
    private static final int IGNITION_MAX = 3;
    /** Up to this age a running wave is in its MIDDLE. */
    private static final int MIDDLE_MAX = 12;
    /** At or below this share of its own peak a running wave counts as receding. */
    private static final double RECEDED = 0.5;
    /** The peak must be at least this old before receding volume means anything. */
    private static final int RECEDE_MIN_AGE = 3;
    /** Consecutive sessions below half the peak before a retreat is called. */
    private static final int RECEDE_CONFIRM = 2;
    /**
     * How long after a CONFIRMED wave ended it still reads as FADING rather
     * than QUIET. Anchored on the end of a wave that actually reached wave
     * size - not on the last merely-busy session, which let blue chips read as
     * fading waves forever (real-tape finding 2026-08-07: AAPL, KO and SMCI all
     * reported FADING off peaks 27, 28 and 37 sessions old), and not on the
     * peak either, which let long waves expire straight into QUIET without ever
     * passing through FADING (walk-forward finding, same day).
     */
    private static final int FADE_MEMORY = 10;
    /** Sessions the price/volume coupling is measured over. */
    private static final int COUPLING_DAYS = 10;
    /** Sessions the short-term realized volatility is measured over. */
    private static final int EXPANSION_DAYS = 10;

    private HypePhase() {
    }

    /** Where a name stands in an attention wave. */
    public enum Phase {
        /** No wave in the window - normal participation. */
        QUIET,
        /** A wave started within the last {@value #IGNITION_MAX} sessions. */
        IGNITION,
        /** A wave is running and has not yet receded from its peak. */
        MIDDLE,
        /** A wave is still running but older than {@value #MIDDLE_MAX} sessions. */
        LATE,
        /** Volume is falling away from the wave's peak, or the wave has ended. */
        FADING
    }

    /**
     * The dated wave. Indices point into the arrays the caller passed, so the
     * figure layer can shade and mark its OWN bars without recomputing
     * anything.
     *
     * @param phase             where the name stands
     * @param waveAgeDays       sessions the running wave has lasted, {@code 0} when none runs
     * @param rvolNow           latest smoothed volume, in baselines
     * @param wavePeakRvol      the wave's biggest smoothed volume, in baselines
     * @param peakAgoDays       sessions since that peak
     * @param drawdownPercent   how far the close sits below the window's highest close
     * @param sincePeakPercent  price change from the volume peak's close to now
     * @param volExpansion      short-term realized volatility over the window's, {@code 1} = unchanged
     * @param upDayVolumeShare  percent of recent volume that traded on up sessions
     * @param baselineVolume    the median session volume the wave is measured against
     * @param windowFrom        first input index of the measured window
     * @param waveStartIndex    input index the running wave began at, {@code -1} when none runs
     * @param peakIndex         input index of the peak session, {@code -1} when no wave at all
     * @param sampleDays        sessions measured
     * @param thinBaseline      true when the baseline rests on few sessions
     */
    public record Verdict(
            Phase phase,
            int waveAgeDays,
            double rvolNow,
            double wavePeakRvol,
            int peakAgoDays,
            double drawdownPercent,
            double sincePeakPercent,
            double volExpansion,
            double upDayVolumeShare,
            double baselineVolume,
            int windowFrom,
            int waveStartIndex,
            int peakIndex,
            int sampleDays,
            boolean thinBaseline) {

        /** True while a wave is actually running (an ended wave reads FADING too). */
        public boolean running() {
            return waveStartIndex >= 0;
        }

        /** True when a wave is worth talking about at all. */
        public boolean waveFound() {
            return phase != Phase.QUIET;
        }

        /** The phase as the closed token the report and the figure share. */
        public String phaseToken() {
            return phase.name();
        }
    }

    /**
     * Dates the wave.
     *
     * @param closes  daily closes, oldest first
     * @param volumes daily volumes, oldest first, same length as {@code closes}
     * @return the verdict, or empty when the series is too short or degenerate
     */
    public static Optional<Verdict> measure(double[] closes, double[] volumes) {
        if (closes == null || volumes == null) return Optional.empty();
        int n = Math.min(closes.length, volumes.length);
        if (n < MIN_DAYS) return Optional.empty();

        int from = Math.max(0, n - WINDOW_MAX);
        int end = n - 1;
        int days = end - from + 1;
        if (days < MIN_DAYS) return Optional.empty();
        for (int i = from; i <= end; i++) {
            if (!Double.isFinite(closes[i]) || closes[i] <= 0) return Optional.empty();
            if (!Double.isFinite(volumes[i]) || volumes[i] < 0) return Optional.empty();
        }

        int baselineTo = end - RECENT_EXCLUDE;
        int baselineDays = baselineTo - from + 1;
        if (baselineDays < MIN_BASELINE_DAYS) return Optional.empty();
        double[] baseSlice = new double[baselineDays];
        System.arraycopy(volumes, from, baseSlice, 0, baselineDays);
        double base = MathKit.median(baseSlice);
        if (!Double.isFinite(base) || base <= 0) return Optional.empty();

        // Smoothed relative volume per session, indexed like the input.
        double[] rvol = new double[n];
        for (int i = from; i <= end; i++) {
            int lo = Math.max(from, i - SMOOTH + 1);
            double sum = 0;
            for (int j = lo; j <= i; j++) sum += volumes[j];
            rvol[i] = sum / (i - lo + 1) / base;
        }

        // Every wave in the window, then the two that matter: the one still
        // running (if any) and the last one that ever reached wave size. They
        // are different anchors on purpose - a running wave is dated by its
        // START, a finished one by how long ago it ENDED.
        List<Run> runs = runs(volumes, from, end, base);
        Run running = null;
        Run lastConfirmed = null;
        for (Run r : runs) {
            int peakAt = argMax(rvol, r.start(), Math.min(end, r.end() + GAP_TOLERANCE));
            if (rvol[peakAt] >= WAVE_MIN) lastConfirmed = r;
            if (r.end() >= end - GAP_TOLERANCE) running = r;
        }
        boolean confirmed = running != null
                && rvol[argMax(rvol, running.start(), end)] >= WAVE_MIN;

        int windowPeakIndex = argMax(rvol, from, end);
        double rvolNow = rvol[end];
        int waveStart = confirmed ? running.start() : -1;
        int waveAge = confirmed ? end - waveStart + 1 : 0;
        int wavePeakIndex = confirmed ? argMax(rvol, waveStart, end) : windowPeakIndex;

        Phase phase = classify(confirmed, waveAge, rvol[wavePeakIndex], rvolNow,
                end - wavePeakIndex, belowHalfPeakRun(rvol, end, rvol[wavePeakIndex]),
                lastConfirmed == null ? Integer.MAX_VALUE : end - lastConfirmed.end());

        // What the figure may mark: the running wave's peak, else the peak of
        // the wave the name faded FROM, else nothing at all.
        int peakIndex;
        if (confirmed) {
            peakIndex = wavePeakIndex;
        } else if (phase == Phase.FADING && lastConfirmed != null) {
            peakIndex = argMax(rvol, lastConfirmed.start(),
                    Math.min(end, lastConfirmed.end() + GAP_TOLERANCE));
        } else {
            peakIndex = -1;
        }
        // The peak on record always keeps its true age, even when no wave is
        // marked: "1.7x, 34 sessions ago" is a busiest-day-in-the-window fact
        // and honest; a peak reported with age 0 would read as fresh.
        int reportedPeak = peakIndex >= 0 ? peakIndex : windowPeakIndex;
        double peak = rvol[reportedPeak];
        int peakAgo = end - reportedPeak;

        double high = closes[from];
        for (int i = from; i <= end; i++) high = Math.max(high, closes[i]);
        double drawdown = (high - closes[end]) / high * 100.0;
        double sincePeak = peakIndex >= 0
                ? (closes[end] / closes[peakIndex] - 1) * 100.0 : 0.0;

        return Optional.of(new Verdict(phase, waveAge, rvolNow, peak, peakAgo,
                drawdown, sincePeak, expansion(closes, from, end), upShare(closes, volumes, end),
                base, from, waveStart, peakIndex, days, baselineDays < MIN_BASELINE_DAYS * 2));
    }

    /**
     * The phase decision - the ONE place a wave gets dated, so the rule is
     * readable in a single glance instead of scattered over the measurement.
     */
    private static Phase classify(boolean confirmed, int waveAge, double wavePeak, double rvolNow,
            int peakAgo, int belowHalfPeak, int endedAgo) {
        if (confirmed) {
            if (waveAge <= IGNITION_MAX) return Phase.IGNITION;
            if (peakAgo >= RECEDE_MIN_AGE && belowHalfPeak >= RECEDE_CONFIRM) return Phase.FADING;
            return waveAge <= MIDDLE_MAX ? Phase.MIDDLE : Phase.LATE;
        }
        // Nothing running: a wave that ENDED recently still reads as fading -
        // a big wave two sessions dead is the news, the same wave a month dead
        // is history and the name is quiet again.
        return endedAgo <= FADE_MEMORY ? Phase.FADING : Phase.QUIET;
    }

    /** A run of elevated sessions, gaps already absorbed; both bounds elevated. */
    private record Run(int start, int end) {
    }

    /**
     * Every wave in the window. A session joins a run once it trades at
     * {@value #ELEVATED} times baseline; up to {@value #GAP_TOLERANCE} quiet
     * session inside a run is absorbed, more ends it. Runs are closed on their
     * last ELEVATED session, never on a trailing lull.
     */
    private static List<Run> runs(double[] volumes, int from, int end, double base) {
        List<Run> out = new ArrayList<>();
        int start = -1;
        int last = -1;
        int gap = 0;
        for (int i = from; i <= end; i++) {
            if (volumes[i] >= ELEVATED * base) {
                if (start < 0) start = i;
                last = i;
                gap = 0;
            } else if (start >= 0 && ++gap > GAP_TOLERANCE) {
                out.add(new Run(start, last));
                start = -1;
                last = -1;
                gap = 0;
            }
        }
        if (start >= 0) out.add(new Run(start, last));
        return out;
    }

    /**
     * How many of the latest sessions in a row sit at or below half the wave's
     * peak. A single weak session is a lull, not a retreat - without this the
     * phase flapped between MIDDLE and FADING day by day on real tape
     * (walk-forward finding 2026-08-07).
     */
    private static int belowHalfPeakRun(double[] rvol, int end, double wavePeak) {
        int count = 0;
        for (int i = end; i >= 0 && rvol[i] <= wavePeak * RECEDED; i--) count++;
        return count;
    }

    /** Index of the largest value in {@code [from, to]}. */
    private static int argMax(double[] xs, int from, int to) {
        int best = from;
        for (int i = from; i <= to; i++) {
            if (xs[i] > xs[best]) best = i;
        }
        return best;
    }

    /** Short-term realized volatility over the window's, from log returns. */
    private static double expansion(double[] closes, int from, int end) {
        double[] all = logReturns(closes, from, end);
        if (all.length < EXPANSION_DAYS + 2) return 1.0;
        double[] recent = new double[EXPANSION_DAYS];
        System.arraycopy(all, all.length - EXPANSION_DAYS, recent, 0, EXPANSION_DAYS);
        double wide = MathKit.std(all);
        if (wide <= 0 || !Double.isFinite(wide)) return 1.0;
        double value = MathKit.std(recent) / wide;
        return Double.isFinite(value) ? value : 1.0;
    }

    /** Share of the recent volume that traded on up sessions, in percent. */
    private static double upShare(double[] closes, double[] volumes, int end) {
        int start = Math.max(1, end - COUPLING_DAYS + 1);
        double up = 0;
        double all = 0;
        for (int i = start; i <= end; i++) {
            all += volumes[i];
            if (closes[i] > closes[i - 1]) up += volumes[i];
        }
        return all > 0 ? up / all * 100.0 : 50.0;
    }

    private static double[] logReturns(double[] closes, int from, int end) {
        double[] out = new double[Math.max(0, end - from)];
        for (int i = from + 1; i <= end; i++) {
            out[i - from - 1] = Math.log(closes[i] / closes[i - 1]);
        }
        return out;
    }

    // ---- readings ----

    /** "1 session" / "4 sessions" - the model reads these lines as prose. */
    private static String sessions(int n) {
        return n == 1 ? "1 session" : n + " sessions";
    }

    /**
     * The phase itself - the line that dates the wave for the model.
     *
     * @param v the verdict, may be null
     */
    public static Optional<SignalReading> phaseReading(Verdict v) {
        if (v == null) return Optional.empty();
        String state = switch (v.phase()) {
            case QUIET -> "NO WAVE: participation stays inside this name's own normal range - "
                    + "there is no attention wave to date, and a story here has to stand on its "
                    + "substance, not on a crowd.";
            case IGNITION -> "IGNITING (session " + v.waveAgeDays() + " of the wave): "
                    + "participation broke out of the name's own range only days ago - "
                    + "whatever is happening here, it started this week.";
            case MIDDLE -> "WAVE RUNNING (session " + v.waveAgeDays() + "): elevated participation "
                    + "has held for days - this is the middle of the move, not its start. "
                    + "Whether the crowd is still growing is the volume-thrust line's answer, "
                    + "not this one's.";
            case LATE -> "LATE IN THE WAVE (session " + v.waveAgeDays() + "): participation is "
                    + "still elevated but the wave has been running unusually long - late arrivals "
                    + "buy from those who were early.";
            case FADING -> v.running()
                    ? "FADING: volume is falling away from its own peak " + sessions(v.peakAgoDays())
                            + " ago while the wave is still nominally running - "
                            + "that is unwinding, not entering."
                    : "FADED: the wave has ended, participation is back inside the name's normal "
                            + "range - whatever the price still does, the crowd has left.";
        };
        String caution = v.thinBaseline()
                ? " Caution: the baseline rests on few sessions - the multiples are accordingly soft."
                : "";
        return Optional.of(new SignalReading(
                "hype-phase",
                "Attention wave phase (price and volume)",
                v.waveAgeDays(),
                v.phase().name() + (v.running() ? ", session " + v.waveAgeDays() : "")
                        + ", peak " + MathKit.fmt(v.wavePeakRvol(), 1) + "x normal volume",
                "Dates where the name stands in an attention wave, from its own daily closes "
                        + "and volumes over " + v.sampleDays() + " sessions.",
                state + caution
                        + " Never restate the phase as a price forecast - it says WHEN, not WHERE."));
    }

    /**
     * How much volume is running right now, and whether it still grows.
     *
     * @param v the verdict, may be null
     */
    public static Optional<SignalReading> thrustReading(Verdict v) {
        if (v == null) return Optional.empty();
        double share = v.wavePeakRvol() > 0 ? v.rvolNow() / v.wavePeakRvol() : 0;
        String interpretation;
        if (v.phase() == Phase.QUIET) {
            interpretation = "Volume sits at the name's normal level - nothing here needs "
                    + "explaining by a crowd.";
        } else if (share >= 0.9) {
            interpretation = "Volume is AT its wave peak - participation is still building, and "
                    + "the move has its full crowd behind it.";
        } else if (share >= 0.6) {
            interpretation = "Volume holds well below its peak but stays clearly elevated - "
                    + "the wave carries on with a thinning crowd.";
        } else {
            interpretation = "Volume has fallen to " + MathKit.fmt(share * 100, 0)
                    + " % of its own peak - the crowd is leaving; a price that still holds up is "
                    + "being carried by fewer and fewer hands.";
        }
        if (v.volExpansion() >= 1.5) {
            interpretation += " Price swings have widened to "
                    + MathKit.fmt(v.volExpansion(), 1) + "x the window's normal - the tape is "
                    + "genuinely unsettled, not just busy.";
        } else if (v.volExpansion() <= 0.7 && v.waveFound()) {
            interpretation += " Price swings have narrowed despite the volume - a busy tape that "
                    + "moves little is distribution, not discovery.";
        }
        return Optional.of(new SignalReading(
                "hype-volume-thrust",
                "Volume thrust against own normal",
                v.rvolNow(),
                MathKit.fmt(v.rvolNow(), 1) + "x normal (peak " + MathKit.fmt(v.wavePeakRvol(), 1)
                        + "x, " + sessions(v.peakAgoDays()) + " ago)",
                "Current session volume against the median session volume of this name's own "
                        + "quiet stretch.",
                interpretation));
    }

    /**
     * Which side of the tape the volume trades on - the difference between a
     * wave being bought and one being sold.
     *
     * @param v the verdict, may be null
     */
    public static Optional<SignalReading> couplingReading(Verdict v) {
        if (v == null) return Optional.empty();
        double up = v.upDayVolumeShare();
        String interpretation;
        if (up >= 60) {
            interpretation = "The volume trades on UP sessions - the wave is being bought.";
        } else if (up <= 40) {
            interpretation = "The volume trades on DOWN sessions - the wave is being SOLD, "
                    + "whatever the headline count suggests.";
        } else {
            interpretation = "Neither side clearly owns the tape: the volume splits across up "
                    + "and down sessions without a decisive lean either way.";
        }
        if (v.waveFound() && v.drawdownPercent() >= 10) {
            interpretation += " The close sits " + MathKit.fmt(v.drawdownPercent(), 0)
                    + " % below the window's high - the wave has already given back a large part "
                    + "of its move.";
        }
        return Optional.of(new SignalReading(
                "hype-price-coupling",
                "Which side the volume trades on",
                up,
                MathKit.fmt(up, 0) + " % of recent volume on up sessions (scale 0-100, 50 = even)",
                "Share of the last " + COUPLING_DAYS + " sessions' volume that traded on days "
                        + "closing higher than the day before.",
                interpretation));
    }
}
