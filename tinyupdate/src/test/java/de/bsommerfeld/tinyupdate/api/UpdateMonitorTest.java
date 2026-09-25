package de.bsommerfeld.tinyupdate.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UpdateMonitorTest {

    @Test
    void reportsOnce_afterFailedAndNegativeChecks_thenStops() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        CountDownLatch reported = new CountDownLatch(1);

        UpdateMonitor.Probe probe = () -> switch (checks.incrementAndGet()) {
            case 1 -> throw new IOException("offline");
            case 2 -> false;
            default -> true;
        };
        try (UpdateMonitor monitor = new UpdateMonitor(probe, Duration.ofMillis(10), () -> {
            reports.incrementAndGet();
            reported.countDown();
        })) {
            monitor.start();
            assertTrue(reported.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);
        }

        assertEquals(1, reports.get());
        assertEquals(3, checks.get());
    }

    @Test
    void rejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> new UpdateMonitor(() -> false, Duration.ZERO, () -> { }));
    }
}
