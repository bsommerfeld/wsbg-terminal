package de.bsommerfeld.wsbg.terminal.websearch;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetchChain;

import java.util.concurrent.Callable;

/**
 * The one global pace GDELT is asked at - shared by every client that touches
 * the DOC API. GDELT wants one request every ~5 seconds and a short burst
 * earned a multi-minute IP block during probing, so the gate belongs to the
 * HOST, not to a client: two clients each keeping their own 8-second interval
 * would still hit the API twice as fast as it allows.
 *
 * <p>The lock is held ACROSS the request, not merely around the wait - GDELT
 * counts requests in flight, not requests started.
 *
 * <p><b>The interval is measured from one request's START to the next one's</b>
 * (2026-08-11). Stamping it on COMPLETION made every slow request pay twice:
 * a GDELT call whose browser leg ran into its 25 s timeout held the global door
 * for those 25 seconds and the next caller then slept the full 8 on top, though
 * the host had long since had its rest. A rate is a distance between requests,
 * not a cool-down after them.
 *
 * <p><b>A host that is not being talked to is owed no pace</b> (2026-08-11):
 * when the chain is failing fast on GDELT's rate-limit cooldown, no socket is
 * touched, and waiting in front of that is pure loss - measured in one dossier,
 * 39 of 83 GDELT requests slept 8 s each to be told there was nothing to wait
 * for. Those calls now pass straight through, keep their place in no queue, and
 * cost the collection nothing.
 */
final class GdeltGate {

    private static final Object LOCK = new Object();
    private static final long MIN_INTERVAL_MS = 8_000;
    private static long lastRequestMs;

    private GdeltGate() {
    }

    /**
     * Runs one GDELT request at the host's pace, strictly sequential - unless
     * the request would not reach the host at all, which costs neither pace
     * nor queue.
     *
     * @param url the request's target, so the gate can see whether the host is
     *            currently answering at all; {@code null} always takes the pace
     */
    static <T> T through(String url, Callable<T> request) throws Exception {
        if (url != null && WebFetchChain.hostFailingFast(url)) return request.call();
        synchronized (LOCK) {
            long wait = lastRequestMs + MIN_INTERVAL_MS - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastRequestMs = System.currentTimeMillis();
            return request.call();
        }
    }
}
