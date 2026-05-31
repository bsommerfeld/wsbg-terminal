package de.bsommerfeld.wsbg.terminal.ui.market;

/**
 * One concrete trading window for a region on a specific calendar day.
 * Timestamps are epoch milliseconds in UTC — the page renders them in
 * the user's local time without needing time-zone information.
 */
public record MarketSession(String state, long startUtcMs, long endUtcMs) {

    public static final String STATE_PRE = "pre";
    public static final String STATE_MAIN = "main";
    public static final String STATE_POST = "post";
}
