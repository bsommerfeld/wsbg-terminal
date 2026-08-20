package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.AiHealth;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.AiHealthEvent;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges {@link AiHealthEvent} from the agent to the frontend via
 * {@link PushHub} - built exactly like {@link RedditHealthPublisher}: one push
 * per transition, plus a replay on client connect so a fresh page load renders
 * the real state instead of an optimistic green.
 *
 * <p>
 * The replay is not cosmetic here. The endpoint's state is decided at boot and
 * then only changes when a call fails; without the snapshot a reload during an
 * outage would show a healthy terminal that composes nothing.
 */
@Singleton
public final class AiHealthPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(AiHealthPublisher.class);

    private final PushHub hub;
    private final AiHealth health;

    @Inject
    public AiHealthPublisher(PushHub hub, ApplicationEventBus bus, AiHealth health) {
        this.hub = hub;
        this.health = health;
        bus.register(this);
        hub.onClientOpen(this::pushSnapshot);
    }

    @Subscribe
    public void onHealth(AiHealthEvent event) {
        broadcast(event);
    }

    /** Replays the live state, read from {@link AiHealth} rather than a local copy. */
    private void pushSnapshot() {
        broadcast(health.snapshot());
    }

    private void broadcast(AiHealthEvent event) {
        try {
            hub.broadcast("ai-status", toJson(event));
        } catch (Exception e) {
            LOG.debug("ai-status broadcast failed: {}", e.getMessage());
        }
    }

    private static Map<String, Object> toJson(AiHealthEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", e.state().name());
        m.put("endpoint", e.endpoint());
        m.put("managed", e.managed());
        // The server's own words. Shown verbatim and NOT translated: it is
        // quoted evidence ("model not found"), and translating it would put our
        // wording on someone else's error.
        m.put("reason", e.reason());
        return m;
    }
}
