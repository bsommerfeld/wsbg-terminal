package de.bsommerfeld.wsbg.terminal.web.schedule;

import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;

/**
 * Drives the collectors: one self-re-scheduling task per source. After a
 * source's {@link CollectorSource#collect()} finishes (the fetch duration is
 * thereby naturally part of the spacing), the scheduler draws the next delay
 * from the source's {@link FetchInterval} and books the next pass. A host the
 * fetcher reports as cooling down pushes the next pass out instead of burning
 * a request.
 *
 * <p>Collected articles go straight into the pool; the scheduler owns cadence
 * and nothing else.
 */
public interface CollectorScheduler {

    /** Registers a collector; it is first fired shortly after {@link #start()}. */
    void register(CollectorSource source);

    /** Starts driving all registered collectors. Idempotent. */
    void start();

    /** Stops all scheduling; in-flight passes finish. Idempotent. */
    void stop();
}
