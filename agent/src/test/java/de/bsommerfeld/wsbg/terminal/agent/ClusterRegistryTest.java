package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClusterRegistryTest {

    private ClusterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ClusterRegistry();
    }

    private InvestigationCluster cluster(String id) {
        long now = System.currentTimeMillis() / 1000;
        RedditThread t = new RedditThread(id, "wsb", "Title " + id, "author", "body",
                now, "/perma", 10, 0.9, 5, now, null);
        return new InvestigationCluster(t);
    }

    @Test
    void add_makesClusterRetrievable() {
        InvestigationCluster c = cluster("t3_a");
        registry.add(c);
        assertSame(c, registry.getCluster("t3_a"));
        assertTrue(registry.contains(c));
        assertEquals(1, registry.size());
    }

    @Test
    void add_markedDirtyAndNotifiesSubscribers() {
        AtomicInteger calls = new AtomicInteger();
        registry.subscribeToChanges(ids -> calls.incrementAndGet());
        registry.add(cluster("t3_a"));
        assertEquals(1, calls.get());
        assertTrue(registry.peekDirty().contains("t3_a"));
    }

    @Test
    void drainDirty_clearsTheSet() {
        registry.add(cluster("t3_a"));
        Set<String> drained = registry.drainDirty();
        assertEquals(Set.of("t3_a"), drained);
        assertTrue(registry.peekDirty().isEmpty());
    }

    @Test
    void remove_dropsClusterAndDirtyEntry() {
        InvestigationCluster c = cluster("t3_a");
        registry.add(c);
        registry.remove("t3_a");
        assertNull(registry.getCluster("t3_a"));
        assertFalse(registry.contains(c));
        assertTrue(registry.peekDirty().isEmpty());
    }

    @Test
    void view_returnsImmutableSnapshot() {
        InvestigationCluster c = cluster("t3_a");
        registry.add(c);

        ClusterRegistry.ClusterView v = registry.view("t3_a");
        assertNotNull(v);
        assertEquals("t3_a", v.id());
        assertEquals("Title t3_a", v.initialTitle());
        assertThrows(UnsupportedOperationException.class,
                () -> v.activeThreadIds().add("evil"));
    }

    @Test
    void allViews_reflectsCurrentState() {
        registry.add(cluster("t3_a"));
        registry.add(cluster("t3_b"));
        assertEquals(2, registry.allViews().size());
    }

    @Test
    void notifyChange_isThreadSafe() throws InterruptedException {
        // Hammer the registry from multiple threads and verify all notifications
        // land in the dirty set without loss.
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(10 * 100);

        for (int t = 0; t < 10; t++) {
            int threadIdx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        registry.notifyChange("c_" + threadIdx + "_" + i);
                        done.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        Set<String> drained = registry.drainDirty();
        assertEquals(1000, drained.size());
    }

    @Test
    void subscribers_doNotBreakRegistryOnException() {
        registry.subscribeToChanges(ids -> {
            throw new RuntimeException("subscriber bug");
        });
        AtomicInteger goodCalls = new AtomicInteger();
        registry.subscribeToChanges(ids -> goodCalls.incrementAndGet());

        // The throwing subscriber must not kill the second one.
        registry.add(cluster("t3_a"));
        assertEquals(1, goodCalls.get());
    }
}
