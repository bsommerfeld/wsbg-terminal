package de.bsommerfeld.wsbg.terminal.core.debug;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DebugRingTest {

    @Test
    void staysBoundedAndKeepsTheNewest() {
        DebugRing<Integer> ring = new DebugRing<>(3);
        for (int i = 1; i <= 5; i++) ring.add(i);
        assertEquals(3, ring.size());
        assertEquals(List.of(3, 4, 5), ring.snapshot());
        assertEquals(5, ring.totalAdded());
    }

    @Test
    void recentReturnsTheNewestSliceOldestFirst() {
        DebugRing<String> ring = new DebugRing<>(4);
        ring.add("a");
        ring.add("b");
        ring.add("c");
        assertEquals(List.of("b", "c"), ring.recent(2));
        assertEquals(List.of("a", "b", "c"), ring.recent(99));
        assertEquals(List.of(), ring.recent(0));
    }

    @Test
    void clearEmptiesEverything() {
        DebugRing<String> ring = new DebugRing<>(2);
        ring.add("x");
        ring.clear();
        assertEquals(0, ring.size());
        assertEquals(List.of(), ring.snapshot());
        assertEquals(0, ring.totalAdded());
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new DebugRing<>(0));
    }

    @Test
    void concurrentWritersNeverExceedCapacity() throws Exception {
        DebugRing<Integer> ring = new DebugRing<>(64);
        Thread[] writers = new Thread[4];
        for (int t = 0; t < writers.length; t++) {
            writers[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) ring.add(i);
            });
            writers[t].start();
        }
        for (Thread w : writers) w.join();
        assertEquals(64, ring.size());
        assertEquals(40_000, ring.totalAdded());
    }
}
