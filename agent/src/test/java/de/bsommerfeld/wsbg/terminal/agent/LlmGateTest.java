package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-lane gate contract (2026-07-14): the interactive lane (on-demand
 * KI-DD) overtakes queued background work at the next free permit, and the
 * anti-starvation guarantee hands every {@link LlmGate#BACKGROUND_GUARANTEE}th
 * grant to the background lane — the wire keeps publishing while a DD runs.
 */
class LlmGateTest {

    @Test
    @Timeout(10)
    void interactiveOvertakesButBackgroundNeverStarves() throws Exception {
        LlmGate gate = new LlmGate();
        int permits = gate.availablePermits();
        assertTrue(permits >= 1, "gate must expose at least one permit");

        // Occupy every permit so all later callers queue up.
        CountDownLatch holdersIn = new CountDownLatch(permits);
        BlockingQueue<Runnable> releases = new ArrayBlockingQueue<>(permits + 8);
        for (int i = 0; i < permits; i++) {
            Thread holder = new Thread(() -> {
                gate.acquire();
                holdersIn.countDown();
            });
            holder.start();
            releases.add(gate::release);
        }
        assertTrue(holdersIn.await(5, TimeUnit.SECONDS));

        // Queue 4 interactive + 2 background waiters; each records its class
        // when granted and keeps holding (so OUR releases serialize grants).
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch granted = new CountDownLatch(6);
        for (int i = 0; i < 4; i++) {
            new Thread(() -> {
                gate.acquireInteractive();
                order.add("I");
                releases.add(gate::release);
                granted.countDown();
            }).start();
        }
        Thread.sleep(150); // interactive waiters enqueue first
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                gate.acquire();
                order.add("B");
                releases.add(gate::release);
                granted.countDown();
            }).start();
        }
        Thread.sleep(200); // all six are parked in the gate

        // Free one permit at a time; each release admits exactly one waiter.
        for (int i = 0; i < 6; i++) {
            releases.take().run();
            long deadline = System.currentTimeMillis() + 2000;
            while (order.size() < i + 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(i + 1, order.size(), "grant " + (i + 1) + " never happened: " + order);
        }
        assertTrue(granted.await(5, TimeUnit.SECONDS));

        // With BACKGROUND_GUARANTEE=3: two interactive grants, then the
        // guaranteed background slot, twice over.
        assertEquals(List.of("I", "I", "B", "I", "I", "B"), order);
    }
}
