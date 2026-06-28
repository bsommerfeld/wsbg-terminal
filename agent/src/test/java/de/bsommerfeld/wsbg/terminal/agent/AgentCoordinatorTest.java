package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCoordinatorTest {

    private ClusterRegistry registry;
    private EditorialPipeline pipeline;
    private AgentCoordinator coordinator;

    @BeforeEach
    void setUp() {
        registry = new ClusterRegistry();
        pipeline = mock(EditorialPipeline.class);
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null)
            coordinator.shutdown();
    }

    private InvestigationCluster cluster(String id) {
        long now = System.currentTimeMillis() / 1000;
        RedditThread t = new RedditThread(id, "wsb", "T " + id, "u", "b",
                now, "/p", 1, 0.9, 0, now, null);
        return new InvestigationCluster(t, Embedding.from(new float[] { 0.1f, 0.2f }));
    }

    @Test
    void singleNotify_triggersOneAgentRunAfterDebounce() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            runs.incrementAndGet();
            latch.countDown();
            return null;
        }).when(pipeline).submitClusters(anySet());

        coordinator = new AgentCoordinator(registry, pipeline, 100L);
        registry.add(cluster("t3_a"));

        assertTrue(latch.await(8, TimeUnit.SECONDS), "agent should run after debounce");
        assertEquals(1, runs.get());
    }

    @Test
    void burstOfNotifies_coalescesIntoSingleRun() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstRun = new CountDownLatch(1);
        doAnswer(inv -> {
            runs.incrementAndGet();
            firstRun.countDown();
            return null;
        }).when(pipeline).submitClusters(anySet());

        coordinator = new AgentCoordinator(registry, pipeline, 100L);
        for (int i = 0; i < 10; i++) {
            registry.notifyChange("t3_" + i);
        }

        assertTrue(firstRun.await(8, TimeUnit.SECONDS));
        // Give the system a moment to confirm no extra runs queued.
        Thread.sleep(300);
        assertEquals(1, runs.get());
    }

    @Test
    void notifyDuringRun_triggersFollowUpAfterRunCompletes() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstStart = new CountDownLatch(1);
        CountDownLatch secondRun = new CountDownLatch(1);

        doAnswer(inv -> {
            int n = runs.incrementAndGet();
            if (n == 1) {
                firstStart.countDown();
                // Simulate slow agent so a notification arrives during the run.
                Thread.sleep(200);
            } else if (n == 2) {
                secondRun.countDown();
            }
            return null;
        }).when(pipeline).submitClusters(anySet());

        coordinator = new AgentCoordinator(registry, pipeline, 100L);
        registry.notifyChange("t3_a");

        assertTrue(firstStart.await(8, TimeUnit.SECONDS), "first run must start");
        // Add a cluster while the first run is still executing.
        registry.notifyChange("t3_b");

        assertTrue(secondRun.await(10, TimeUnit.SECONDS), "second run should fire after first finishes");
        assertEquals(2, runs.get());
    }

    @Test
    void emptyDirtySet_doesNotInvokeAgent() throws InterruptedException {
        coordinator = new AgentCoordinator(registry, pipeline, 100L);
        // No notifyChange — no run should happen.
        Thread.sleep(500);
        verify(pipeline, never()).submitClusters(any());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> anySet() {
        return any(Set.class);
    }
}
