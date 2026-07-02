package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * Network traffic-blending knobs shared by every external data source (see
 * {@code docs/network-traffic-blending.md}). The goal is to look like ordinary
 * browser traffic without any functional loss: perfectly periodic polling and
 * unconditionally re-fetched bodies are the two bot signals a real browser
 * fingerprint cannot hide, so both get their lever here.
 */
public class NetConfig {

    @Key("net.poll-jitter-percent")
    @Comment("Random jitter applied to external poll intervals, in percent of the base interval (interval varies between base*(1-p/100) and base*(1+p/100)). 0 = off, exact fixed cadence like before.")
    private int pollJitterPercent = 20;

    @Key("net.conditional-requests")
    @Comment("Revalidate unchanged endpoints via ETag/If-Modified-Since and serve 304s from an in-memory cache. Cuts request volume; safe to disable.")
    private boolean conditionalRequests = true;

    public int getPollJitterPercent() {
        return pollJitterPercent;
    }

    public void setPollJitterPercent(int pollJitterPercent) {
        this.pollJitterPercent = pollJitterPercent;
    }

    public boolean isConditionalRequests() {
        return conditionalRequests;
    }

    public void setConditionalRequests(boolean conditionalRequests) {
        this.conditionalRequests = conditionalRequests;
    }
}
