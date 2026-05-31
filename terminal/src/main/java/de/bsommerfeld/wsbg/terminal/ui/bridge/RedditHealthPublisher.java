package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.RedditHealthEvent;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges {@link RedditHealthEvent} from the scraper to the frontend
 * via {@link PushHub}. Emits one push per OK ⇄ DEGRADED transition; the
 * UI animates a status label in/out off the transition.
 *
 * <p>
 * On client connect, replays the last known state so a fresh page load
 * doesn't render a stale (or empty) status badge. The default state
 * before any scrape attempt has happened is OK — we don't want to scare
 * the user with a "rate limited" label that's actually just "haven't
 * polled yet".
 *
 * <p>
 * TODO(oauth-login): when state stays DEGRADED past a threshold (e.g.
 * 10 min — measured from {@code degradedSinceEpochMs}), this publisher
 * should set a {@code suggestLogin: true} flag in the broadcast payload
 * so the frontend swaps the static label for an interactive "Sign in to
 * Reddit" CTA. The login flow itself + persistent OAuth-token storage
 * are the next milestone; the frontend hook stays here.
 */
@Singleton
public final class RedditHealthPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(RedditHealthPublisher.class);

    private final PushHub hub;

    /**
     * Snapshot of the last received state, used to replay on fresh
     * client connects. Defaults to OK so the page renders clean on
     * boot before any scrape has happened.
     */
    private volatile RedditHealthEvent lastState = new RedditHealthEvent(
            RedditHealthEvent.State.OK, 0L);

    @Inject
    public RedditHealthPublisher(PushHub hub, ApplicationEventBus bus) {
        this.hub = hub;
        bus.register(this);
        hub.onClientOpen(this::pushSnapshot);
    }

    @Subscribe
    public void onHealth(RedditHealthEvent event) {
        lastState = event;
        try {
            hub.broadcast("reddit-status", toJson(event));
        } catch (Exception e) {
            LOG.debug("reddit-status broadcast failed: {}", e.getMessage());
        }
    }

    private void pushSnapshot() {
        try {
            hub.broadcast("reddit-status", toJson(lastState));
        } catch (Exception e) {
            LOG.debug("reddit-status snapshot push failed: {}", e.getMessage());
        }
    }

    private static Map<String, Object> toJson(RedditHealthEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", e.state().name());
        m.put("degradedSinceMs", e.degradedSinceEpochMs());
        return m;
    }
}
