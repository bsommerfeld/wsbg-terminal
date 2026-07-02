package de.bsommerfeld.wsbg.terminal.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JitteredSchedulerTest {

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jitter-test");
                t.setDaemon(true);
                return t;
            });

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void jitteredDelay_staysInsideTheJitterBounds() {
        Random rnd = new Random(42);
        long base = 60_000;
        double fraction = JitteredScheduler.jitterFraction(20);
        for (int i = 0; i < 10_000; i++) {
            long delay = JitteredScheduler.jitteredDelayMillis(base, fraction, rnd);
            assertTrue(delay >= 48_000, "delay below base*(1-j): " + delay);
            assertTrue(delay <= 72_000, "delay above base*(1+j): " + delay);
        }
    }

    @Test
    void jitteredDelay_atZeroJitterIsExactlyTheBase() {
        Random rnd = new Random(1);
        for (int i = 0; i < 100; i++) {
            assertEquals(30_000, JitteredScheduler.jitteredDelayMillis(30_000, 0.0, rnd));
        }
    }

    @Test
    void phaseOffset_isInRangeZeroToBase() {
        Random rnd = new Random(7);
        long base = 60_000;
        double fraction = JitteredScheduler.jitterFraction(20);
        for (int i = 0; i < 10_000; i++) {
            long offset = JitteredScheduler.phaseOffsetMillis(base, fraction, rnd);
            assertTrue(offset >= 0, "offset negative: " + offset);
            assertTrue(offset < base, "offset >= base: " + offset);
        }
    }

    @Test
    void phaseOffset_isZeroWhenJitterDisabled() {
        Random rnd = new Random(3);
        assertEquals(0, JitteredScheduler.phaseOffsetMillis(60_000, 0.0, rnd));
    }

    @Test
    void jitterFraction_clampsToSaneRange() {
        assertEquals(0.0, JitteredScheduler.jitterFraction(-5));
        assertEquals(0.2, JitteredScheduler.jitterFraction(20), 1e-9);
        assertEquals(0.9, JitteredScheduler.jitterFraction(500));
    }

    @Test
    void schedule_runsRepeatedlyAndCancelStopsTheLoop() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch threeRuns = new CountDownLatch(3);
        JitteredScheduler.Handle handle = JitteredScheduler.schedule(executor, () -> {
            runs.incrementAndGet();
            threeRuns.countDown();
        }, 0, 10, TimeUnit.MILLISECONDS, 20);

        assertTrue(threeRuns.await(5, TimeUnit.SECONDS), "loop never reached 3 runs");
        handle.cancel();
        // Let any already-scheduled run drain, then confirm the count is stable.
        Thread.sleep(100);
        int after = runs.get();
        Thread.sleep(150);
        assertEquals(after, runs.get(), "loop kept running after cancel");
    }

    @Test
    void schedule_survivesAThrowingTask() throws Exception {
        CountDownLatch twoRuns = new CountDownLatch(2);
        JitteredScheduler.Handle handle = JitteredScheduler.schedule(executor, () -> {
            twoRuns.countDown();
            throw new IllegalStateException("boom");
        }, 0, 10, TimeUnit.MILLISECONDS, 0);

        assertTrue(twoRuns.await(5, TimeUnit.SECONDS), "a throwing task killed the loop");
        handle.cancel();
    }
}
