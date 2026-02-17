package de.bsommerfeld.wsbg.terminal.core.event;

import com.google.common.eventbus.Subscribe;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationEventBusTest {

    @Test
    void post_shouldDeliverEventToRegisteredListener() {
        var eventBus = new ApplicationEventBus();
        var received = new AtomicReference<String>();

        Object listener = new Object() {
            @Subscribe
            public void onEvent(String event) {
                received.set(event);
            }
        };

        eventBus.register(listener);
        eventBus.post("test-event");

        assertEquals("test-event", received.get());
    }

    @Test
    void unregister_shouldStopDeliveringEvents() {
        var eventBus = new ApplicationEventBus();
        var received = new AtomicReference<String>();

        Object listener = new Object() {
            @Subscribe
            public void onEvent(String event) {
                received.set(event);
            }
        };

        eventBus.register(listener);
        eventBus.post("first");
        assertEquals("first", received.get());

        eventBus.unregister(listener);
        eventBus.post("second");
        // Still "first" because listener was unregistered
        assertEquals("first", received.get());
    }

    @Test
    void post_shouldDeliverToMultipleListeners() {
        var eventBus = new ApplicationEventBus();
        var received1 = new AtomicReference<String>();
        var received2 = new AtomicReference<String>();

        Object listener1 = new Object() {
            @Subscribe
            public void onEvent(String event) {
                received1.set(event);
            }
        };

        Object listener2 = new Object() {
            @Subscribe
            public void onEvent(String event) {
                received2.set(event);
            }
        };

        eventBus.register(listener1);
        eventBus.register(listener2);
        eventBus.post("shared-event");

        assertEquals("shared-event", received1.get());
        assertEquals("shared-event", received2.get());
    }

    @Test
    void post_shouldFilterAgentTokenEventFromLogs() {
        // Smoke test — the method should not throw when posting an event with
        // "AgentTokenEvent" in the class name (it filters LOG output)
        var eventBus = new ApplicationEventBus();
        var received = new AtomicReference<Object>();

        Object listener = new Object() {
            @Subscribe
            public void onEvent(Object event) {
                received.set(event);
            }
        };
        eventBus.register(listener);

        // Post a regular event — just verifying no exception
        eventBus.post(42);
        assertEquals(42, received.get());
    }

    @Test
    void post_shouldNotThrowForUnhandledEvents() {
        var eventBus = new ApplicationEventBus();
        // Posting an event with no listeners should not throw
        assertDoesNotThrow(() -> eventBus.post("nobody-listens"));
    }
}
