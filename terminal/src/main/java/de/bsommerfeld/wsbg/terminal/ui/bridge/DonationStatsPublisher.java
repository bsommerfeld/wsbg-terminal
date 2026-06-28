package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.TimeTracker;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Pushes the reciprocity stats the footer donation banner personalises its copy
 * with: {@code activeHours} (cumulative use) and {@code openCount} (sessions),
 * both owned by {@link TimeTracker}. Sent once on every client open, which is
 * all the page needs — the banner runs unconditionally (no time gate, no
 * snooze, no per-session cap), so there is no live gate state to push.
 */
@Singleton
public final class DonationStatsPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(DonationStatsPublisher.class);

    private final PushHub hub;
    private final TimeTracker tracker;

    @Inject
    public DonationStatsPublisher(TimeTracker tracker, PushHub hub) {
        this.hub = hub;
        this.tracker = tracker;
        hub.onClientOpen(this::push);
    }

    private void push() {
        try {
            hub.broadcast("donation-stats", Map.of(
                    "activeHours", tracker.getActiveMillis() / 3_600_000L,
                    "openCount", tracker.getOpenCount()));
        } catch (Exception e) {
            LOG.warn("donation-stats broadcast failed: {}", e.getMessage());
        }
    }
}
