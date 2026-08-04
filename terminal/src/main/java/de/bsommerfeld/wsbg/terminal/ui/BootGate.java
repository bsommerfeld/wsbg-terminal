package de.bsommerfeld.wsbg.terminal.ui;

import de.bsommerfeld.wsbg.terminal.core.util.BackgroundThreads;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the app's background workload back until the startup intro is off the
 * screen.
 *
 * <p><b>Why.</b> Everything heavy used to fire inside {@code
 * Guice.createInjector} — the Ollama server spawn (the {@code AgentBrain}
 * constructor), the Reddit scan loop, the editorial worker/merge/prune loops —
 * i.e. BEFORE the window was even opened. The intro then animated against a
 * machine that was busy spawning a subprocess, loading a multi-GB model and
 * opening a few dozen HTTP polls, and it stuttered for exactly as long as that
 * took. The intro is ~2.9 s; the wire's first cluster lands minutes in, so
 * holding the workload for those seconds costs nothing measurable and buys a
 * clean first impression.
 *
 * <p><b>The gate opens on whichever comes first:</b>
 * <ul>
 *   <li>{@link #READY_TYPE} from the page — sent when the intro plate leaves the
 *       DOM, which covers both the natural end and the user's skip;</li>
 *   <li>the {@link #FAILSAFE_MS} timer.</li>
 * </ul>
 *
 * <p>The failsafe is not optional. A single JS init error can leave the page
 * without its intro module, and a gate that only ever opens on a page signal
 * would turn that into an app that looks fine and never starts working. When
 * the timer is what opens the gate, that is logged as a WARNing — the page
 * failing to report is a defect, and it must not hide behind a working app.
 *
 * <p>Opening runs the workload on the gate's own background thread: the signal
 * arrives on a WebSocket thread, which must not be occupied for the length of
 * an Ollama startup.
 */
final class BootGate {

    private static final Logger LOG = LoggerFactory.getLogger(BootGate.class);

    /** Inbound page message that opens the gate. */
    static final String READY_TYPE = "boot-ready";

    /**
     * Failsafe deadline for a page that connected but never reported, measured
     * from the WebSocket handshake — NOT from {@link #arm}, because between the
     * two lies the page load, and a slow one would let the timer fire while the
     * intro was still playing (the exact stutter this class exists to prevent,
     * plus a false alarm in the log). Comfortably past the intro's ~2.9 s.
     */
    private static final long PAGE_FAILSAFE_MS = 6_000;

    /**
     * Backstop for a page that never connects at all — a dead asset server, a
     * navigation that failed, a JS bundle that never parsed. Long, because it
     * must never be the timer that beats a merely slow page load, and its only
     * job is to keep a broken UI from also meaning a silent, idle backend.
     */
    private static final long CONNECT_FAILSAFE_MS = 25_000;

    private final AtomicBoolean opened = new AtomicBoolean(false);
    private final AtomicBoolean deadlineArmed = new AtomicBoolean(false);
    private final ScheduledExecutorService gate =
            Executors.newSingleThreadScheduledExecutor(BackgroundThreads.single("boot-gate"));
    private final Runnable workload;

    BootGate(Runnable workload) {
        this.workload = workload;
    }

    /**
     * Subscribes to the page signal and starts the failsafe countdown. Call once,
     * right after the window is open — before that there is no page to hear from,
     * and the deadline would be spent on CEF's native init.
     */
    void arm(PushHub pushHub) {
        pushHub.on(READY_TYPE, payload -> open(Opener.PAGE));
        // Only once: a reconnect (JCEF host restart) must not restart the
        // deadline, and after the gate is open it is moot anyway.
        pushHub.onClientOpen(() -> {
            if (deadlineArmed.compareAndSet(false, true)) {
                gate.schedule(() -> open(Opener.PAGE_SILENT),
                        PAGE_FAILSAFE_MS, TimeUnit.MILLISECONDS);
            }
        });
        gate.schedule(() -> open(Opener.NO_PAGE), CONNECT_FAILSAFE_MS, TimeUnit.MILLISECONDS);
    }

    /** Which of the three paths opened the gate — each means something different. */
    private enum Opener { PAGE, PAGE_SILENT, NO_PAGE }

    private void open(Opener by) {
        if (!opened.compareAndSet(false, true)) return;
        switch (by) {
            case PAGE -> LOG.info("Boot gate opened — intro is off screen, starting the workload.");
            case PAGE_SILENT -> LOG.warn(
                    "Boot gate opened by the {} ms failsafe — the page connected but never "
                    + "reported '{}'. Starting the workload anyway; check the web console "
                    + "for an init error.", PAGE_FAILSAFE_MS, READY_TYPE);
            case NO_PAGE -> LOG.warn(
                    "Boot gate opened by the {} ms backstop — no page ever connected to the "
                    + "push hub. The UI is likely dead; starting the workload so the backend "
                    + "is at least alive.", CONNECT_FAILSAFE_MS);
        }
        gate.execute(() -> {
            try {
                workload.run();
            } catch (Throwable t) {
                LOG.error("Deferred startup failed — the app is up but idle.", t);
            }
        });
    }
}
