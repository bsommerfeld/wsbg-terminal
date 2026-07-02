package de.bsommerfeld.wsbg.terminal.finanznachrichten;

/**
 * Tuning knobs for {@link FnMonitorService}. Deliberately a plain POJO (not
 * wired into the app's central {@code GlobalConfig}) so the module stays
 * self-contained — it can be constructed and used in isolation, exactly like
 * the {@code currency} module's monitor. If it is ever wired into the terminal,
 * mirror these fields onto a {@code jshepherd}-annotated config in {@code core}.
 *
 * <h3>Why the interval is high by default</h3>
 * finanznachrichten.de states its feeds refresh at an
 * "Aktualisierungsfrequenz: 10 Minuten" and offers the feeds for free use that
 * it "may revoke without giving reasons". Polling faster than ~10 min fetches
 * nothing new and risks annoying a free service, so the default and the floor
 * sit well above a minute. {@link #pollIntervalSeconds} defaults to 600&nbsp;s
 * (10&nbsp;min); {@link FnMonitorService} additionally floors it at 300&nbsp;s.
 */
public class FinanznachrichtenConfig {

    /** How often the selected feeds are swept. Floored at 300 s by the monitor. */
    private int pollIntervalSeconds = 600;

    /** Per-request HTTP timeout when fetching a feed. */
    private int requestTimeoutSeconds = 15;

    /**
     * Polite pause between consecutive feed requests within one sweep. A full
     * sweep of all 134 feeds at 200&nbsp;ms spacing takes ~27&nbsp;s of mostly
     * idle waiting, keeping the request rate gentle on a free endpoint.
     */
    private long interRequestDelayMillis = 200;

    /**
     * Random jitter applied to the sweep interval, in percent of the base
     * (traffic blending — a machine-exact cadence is a bot signal). Mirrors
     * the terminal's {@code net.poll-jitter-percent} default; {@code 0}
     * restores the exact fixed cadence.
     */
    private int pollJitterPercent = 20;

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public long getInterRequestDelayMillis() {
        return interRequestDelayMillis;
    }

    public void setInterRequestDelayMillis(long interRequestDelayMillis) {
        this.interRequestDelayMillis = interRequestDelayMillis;
    }

    public int getPollJitterPercent() {
        return pollJitterPercent;
    }

    public void setPollJitterPercent(int pollJitterPercent) {
        this.pollJitterPercent = pollJitterPercent;
    }
}
