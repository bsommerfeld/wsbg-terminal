package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * Configuration for the EUR/USD currency monitor.
 * Endpoints are hard-coded in {@code EurUsdClient}; only the polling
 * cadence and request timeout are user-configurable.
 *
 * <p>The primary Yahoo {@code v8/chart} endpoint refreshes the EUR/USD
 * spot quote roughly once per minute and tolerates sub-minute polling
 * without blocking. The default of 30 s catches each refresh promptly
 * (~half a cycle stale on average) without wasting requests on data
 * that hasn't moved.
 */
public class CurrencyConfig {

    @Key("currency.poll-interval-seconds")
    @Comment("How often the EUR/USD rate is refreshed. Minimum 30.")
    private int pollIntervalSeconds = 30;

    @Key("currency.request-timeout-seconds")
    @Comment("Per-request HTTP timeout when talking to Yahoo / Frankfurter.")
    private int requestTimeoutSeconds = 10;

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
}
