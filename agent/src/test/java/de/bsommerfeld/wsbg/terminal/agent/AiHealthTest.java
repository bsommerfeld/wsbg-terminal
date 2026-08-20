package de.bsommerfeld.wsbg.terminal.agent;

import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.AiHealthEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.AiHealthEvent.State;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The breaker's two jobs: decide when {@link ChatGateway} may stop paying for
 * the retry ladder, and tell the UI - once per transition, not per call.
 */
class AiHealthTest {

    private final List<AiHealthEvent> seen = new ArrayList<>();

    @Subscribe
    public void onEvent(AiHealthEvent e) {
        seen.add(e);
    }

    private AiHealth health() {
        ApplicationEventBus bus = new ApplicationEventBus();
        bus.register(this);
        return new AiHealth(bus);
    }

    @Test
    void startsHealthyAndSilent() {
        AiHealth h = health();
        assertFalse(h.tripped());
        assertEquals(State.OK, h.snapshot().state());
        assertTrue(seen.isEmpty(), "a healthy start is not news");
    }

    @Test
    void tripsOnUnreachableAndClearsOnTheNextSuccess() {
        AiHealth h = health();

        h.noteUnreachable("http://box.local:11434", false, "Connection refused");
        assertTrue(h.tripped(), "the ladder must be skipped while it is down");
        assertEquals(1, seen.size());
        assertEquals(State.UNREACHABLE, seen.get(0).state());
        assertEquals("http://box.local:11434", seen.get(0).endpoint());

        // Recovery needs no polling thread: the next call that gets through IS
        // the probe.
        h.noteOk("http://box.local:11434", false);
        assertFalse(h.tripped());
        assertEquals(2, seen.size());
        assertEquals(State.OK, seen.get(1).state());
    }

    @Test
    void repeatsAreNotBroadcast() {
        AiHealth h = health();
        for (int i = 0; i < 5; i++) {
            h.noteUnreachable("http://box.local:11434", false, "Connection refused");
        }
        assertEquals(1, seen.size(), "every failing call reports; the UI hears it once");
    }

    @Test
    void aChangedEndpointIsNews_evenWhileStillDown() {
        // Swapping the address in the settings while the old one is down must
        // reach the UI, or the panel keeps naming a host nobody calls any more.
        AiHealth h = health();
        h.noteUnreachable("http://old.local:11434", false, "Connection refused");
        h.noteUnreachable("http://new.local:11434", false, "Connection refused");

        assertEquals(2, seen.size());
        assertEquals("http://new.local:11434", seen.get(1).endpoint());
    }

    @Test
    void aChangedReasonIsNews() {
        // "unreachable" → "model not found" is a different problem with a
        // different fix, even though both are red.
        AiHealth h = health();
        h.noteRejected("http://box.local:11434", false, "model 'qwen3:32b' not found");
        h.noteRejected("http://box.local:11434", false, "unauthorized");

        assertEquals(2, seen.size());
        assertEquals("unauthorized", seen.get(1).reason());
    }

    @Test
    void aRejectingServerDoesNotTripTheBreaker() {
        // The breaker exists to stop paying connect timeouts. A server that
        // ANSWERS costs none - and the next request may well be a valid one
        // (a different lane, a different prompt), so nothing is saved by
        // short-circuiting it.
        AiHealth h = health();
        h.noteRejected("http://box.local:11434", false, "model not found");

        assertFalse(h.tripped());
        assertEquals(State.REJECTED, h.snapshot().state());
    }
}
