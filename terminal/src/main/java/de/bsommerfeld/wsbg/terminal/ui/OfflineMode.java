package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code WSBG_OFFLINE=true}: the terminal comes up exactly as always - window,
 * intro, cached headlines from the archive - but touches no data source: no
 * web collectors, no Reddit, no market monitors, no model server, no hidden
 * fetch browsers, no update check. For working on the UI itself (rendering,
 * animations, layout) without hammering third parties on every restart.
 *
 * <p>A development switch, read once from the environment. Every outbound
 * starting point consults {@link #ACTIVE}; the browser-backed fetcher refuses
 * outright as the backstop for anything that slipped past the starters.
 */
public final class OfflineMode {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineMode.class);

    public static final boolean ACTIVE = "true".equalsIgnoreCase(System.getenv("WSBG_OFFLINE"));

    private OfflineMode() {}

    /** Logs why something stayed off. Returns {@link #ACTIVE} for inline use. */
    public static boolean skipping(String what) {
        if (ACTIVE) LOG.info("WSBG_OFFLINE: {} stays off.", what);
        return ACTIVE;
    }
}
