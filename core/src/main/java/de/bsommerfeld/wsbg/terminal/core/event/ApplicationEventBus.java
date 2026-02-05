package de.bsommerfeld.wsbg.terminal.core.event;

import com.google.common.eventbus.EventBus;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple wrapper around Guava's EventBus to decouple components.
 * This acts as the central nervous system for the "Fast Path" -> "Slow Path"
 * communication.
 */
@Singleton
public class ApplicationEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationEventBus.class);
    private final EventBus eventBus;

    public ApplicationEventBus() {
        this.eventBus = new EventBus("WsbgTerminal-EventBus");
    }

    public void post(Object event) {
        // Filter out high-frequency spam from logs
        if (!event.getClass().getSimpleName().contains("AgentTokenEvent")) {
            LOG.debug("Posting event: {}", event);
        }
        eventBus.post(event);
    }

    public void register(Object listener) {
        LOG.trace("Registering listener: {}", listener.getClass().getName());
        eventBus.register(listener);
    }

    public void unregister(Object listener) {
        LOG.trace("Unregistering listener: {}", listener.getClass().getName());
        eventBus.unregister(listener);
    }
}
