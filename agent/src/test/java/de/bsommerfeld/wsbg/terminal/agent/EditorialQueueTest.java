package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link EditorialQueue} add-only / de-dup contract — the core of #3's "no
 * drain-and-refill, no duplicate jobs" invariant. No Ollama needed.
 */
class EditorialQueueTest {

    @Test
    void offer_isAddOnly_dropsDuplicateIdsWhilePending() {
        EditorialQueue q = new EditorialQueue();

        assertTrue(q.offer(new ComposeJob.SubjectJob("NVDA")), "first offer enqueues");
        assertFalse(q.offer(new ComposeJob.SubjectJob("NVDA")), "same id while pending is dropped");
        assertEquals(1, q.size(), "the duplicate did not enqueue");
        assertTrue(q.isPending("unit:NVDA"));
    }

    @Test
    void distinctIds_allEnqueue() {
        EditorialQueue q = new EditorialQueue();

        assertTrue(q.offer(new ComposeJob.SubjectJob("NVDA")));
        assertTrue(q.offer(new ComposeJob.SubjectJob("AMD")));
        // A theme job for the same underlying id is a DIFFERENT job id ("theme:" vs "unit:").
        assertTrue(q.offer(new ComposeJob.ThemeJob("NVDA", List.of())));
        assertEquals(3, q.size());
    }

    @Test
    void doneClearsId_soTheSameSubjectCanRequeue() throws InterruptedException {
        EditorialQueue q = new EditorialQueue();

        assertTrue(q.offer(new ComposeJob.SubjectJob("NVDA")));
        ComposeJob taken = q.take();                 // worker picks it up — still in-flight
        assertEquals("unit:NVDA", taken.id());
        assertTrue(q.isPending("unit:NVDA"), "in-flight until done()");
        assertFalse(q.offer(new ComposeJob.SubjectJob("NVDA")), "no second copy while in-flight");

        q.done(taken);                               // worker finished
        assertFalse(q.isPending("unit:NVDA"));
        assertTrue(q.offer(new ComposeJob.SubjectJob("NVDA")), "fresh evidence may now re-queue");
        assertEquals(1, q.size());
    }

    @Test
    void take_dispatchesByStrength_strongestFirst_acrossJobKinds() throws InterruptedException {
        EditorialQueue q = new EditorialQueue();
        q.offer(new ComposeJob.SubjectJob("A"));        // strength 1
        q.offer(new ComposeJob.ThemeJob("B", List.of())); // strength 9 (a ThemeJob ranked on the same scale)
        q.offer(new ComposeJob.SubjectJob("C"));        // strength 5

        Map<String, Integer> strength = new HashMap<>();
        strength.put("unit:A", 1);
        strength.put("theme:B", 9);
        strength.put("unit:C", 5);
        ToIntFunction<ComposeJob> fn = j -> strength.getOrDefault(j.id(), 0);

        // Highest strength first, regardless of insertion order or job kind.
        assertEquals("theme:B", q.take(fn).id());
        assertEquals("unit:C", q.take(fn).id());
        assertEquals("unit:A", q.take(fn).id());
    }

    @Test
    void take_evaluatesStrengthLive_atDispatchTime() throws InterruptedException {
        EditorialQueue q = new EditorialQueue();
        q.offer(new ComposeJob.SubjectJob("A"));
        q.offer(new ComposeJob.SubjectJob("B"));

        // B is weak now; A wins.
        Map<String, Integer> strength = new HashMap<>();
        strength.put("unit:A", 3);
        strength.put("unit:B", 1);
        ToIntFunction<ComposeJob> fn = j -> strength.getOrDefault(j.id(), 0);
        assertEquals("unit:A", q.take(fn).id(), "A is stronger at this take");

        // B accumulated evidence while it waited (no re-insertion) → it now leads on its own.
        strength.put("unit:B", 7);
        assertEquals("unit:B", q.take(fn).id(), "strength re-read live at the next take");
    }

    @Test
    void take_equalStrength_fallsBackToInsertionOrder() throws InterruptedException {
        EditorialQueue q = new EditorialQueue();
        q.offer(new ComposeJob.SubjectJob("A"));
        q.offer(new ComposeJob.ThemeJob("B", List.of()));
        q.offer(new ComposeJob.SubjectJob("C"));

        // All equal → earliest-inserted dispatched first (FIFO tie-break, no starvation).
        assertEquals("unit:A", q.take().id());
        assertEquals("theme:B", q.take().id());
        assertEquals("unit:C", q.take().id());
    }
}
