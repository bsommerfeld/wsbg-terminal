package de.bsommerfeld.wsbg.terminal.reddit.support;

import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.RedditHealthEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks the degraded/OK state of a single Reddit source and posts a
 * {@link RedditHealthEvent} only on state transitions (idempotent within a
 * state — repeated failures while already degraded don't spam the bus).
 *
 * <p>In production the delegate sources are wired with a {@code null} event bus
 * and {@code FallbackRedditSource} owns the aggregate health signal; the
 * null-bus path is a deliberate no-op so a single delegate can never flip the UI
 * to "Defekt" while a fallback still answers. The reporter fires only via the
 * convenience (tests/CLI) wiring.
 */
public final class SourceHealthReporter {

    private static final Logger LOG = LoggerFactory.getLogger(SourceHealthReporter.class);

    private final ApplicationEventBus eventBus;
    private volatile boolean degraded = false;
    private volatile long degradedSinceEpochMs = 0L;

    public SourceHealthReporter(ApplicationEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /** Records the outcome of an outbound fetch, posting on transitions only. */
    public void record(boolean success) {
        boolean wasDegraded = degraded;
        if (success) {
            if (wasDegraded) {
                degraded = false;
                degradedSinceEpochMs = 0L;
                post(RedditHealthEvent.State.OK, 0L);
            }
        } else {
            if (!wasDegraded) {
                degraded = true;
                degradedSinceEpochMs = System.currentTimeMillis();
                post(RedditHealthEvent.State.DEGRADED, degradedSinceEpochMs);
            }
        }
    }

    private void post(RedditHealthEvent.State state, long sinceMs) {
        if (eventBus == null) return;
        try {
            eventBus.post(new RedditHealthEvent(state, sinceMs));
        } catch (Exception e) {
            LOG.debug("Failed to post RedditHealthEvent: {}", e.getMessage());
        }
    }
}
