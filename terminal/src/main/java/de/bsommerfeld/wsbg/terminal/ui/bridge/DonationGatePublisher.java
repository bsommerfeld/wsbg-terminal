package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.TimeTracker;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tells the page whether the <em>active</em> donation nudge layer (the rotating
 * footer banner) may run, based on {@link TimeTracker}'s 12&nbsp;h time gate and
 * the snooze window. The page keeps the ad slide out of its rotation until this
 * says {@code unlocked: true}. The persistent heart icon is independent of this
 * and is always shown by the page.
 *
 * <p>Pushed on every client open (so a freshly loaded page learns the state
 * immediately) and on every mid-session flip of the active state — the 12&nbsp;h
 * unlock crossing <em>and</em> a snooze expiring while the terminal keeps
 * running (via {@link TimeTracker#onActiveChange}). Also receives the inbound
 * {@code donation} command: when the user clicks the donate heart or dismisses
 * the banner, that engagement snoozes the active layer for a long cooldown, and
 * the new (suppressed) state is pushed back so the banner stops at once. A click
 * that actually opened the donate page carries {@code donated: true} and gilds
 * the heart permanently (honor system — Ko-fi is external, no accounts exist,
 * the click is the only signal there is).
 *
 * <p>The payload also carries the reciprocity stats the banner copy
 * personalises with: {@code activeHours} (cumulative use) and {@code openCount}
 * (sessions). Both already existed in {@code UserConfig}; this is where they
 * stop being dead signals.
 */
@Singleton
public final class DonationGatePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(DonationGatePublisher.class);

    private final PushHub hub;
    private final TimeTracker tracker;

    @Inject
    public DonationGatePublisher(TimeTracker tracker, PushHub hub) {
        this.hub = hub;
        this.tracker = tracker;
        hub.onClientOpen(this::push);
        tracker.onActiveChange(this::push);
        hub.on("donation", this::onDonation);
    }

    private void onDonation(Map<String, Object> payload) {
        Object action = payload.get("action");
        if ("snooze".equals(action)) {
            if (Boolean.TRUE.equals(payload.get("donated"))) {
                tracker.markDonationClicked();
            }
            tracker.snoozeDonation();
            push();
        }
    }

    private void push() {
        try {
            hub.broadcast("donation-gate", Map.of(
                    "unlocked", tracker.isDonationActive(),
                    "supporter", tracker.isDonationClicked(),
                    "activeHours", tracker.getActiveMillis() / 3_600_000L,
                    "openCount", tracker.getOpenCount()));
        } catch (Exception e) {
            LOG.warn("donation-gate broadcast failed: {}", e.getMessage());
        }
    }
}
