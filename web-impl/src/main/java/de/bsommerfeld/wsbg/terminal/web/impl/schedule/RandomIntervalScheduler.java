package de.bsommerfeld.wsbg.terminal.web.impl.schedule;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.exec.WebExecutor;
import de.bsommerfeld.wsbg.terminal.web.pool.ArticlePool;
import de.bsommerfeld.wsbg.terminal.web.schedule.CollectorScheduler;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The self-re-scheduling collector clock. Each registered collector is one
 * chain of appointments: run a pass on a worker, pour the yield into the
 * basin, then draw the next delay from the source's own {@link FetchInterval}
 * ({@code ThreadLocalRandom.nextInt(a, b+1)} minutes) and book again. Because
 * the draw happens AFTER the pass, the fetch duration is naturally part of
 * the spacing, and because every source draws independently, the collectors
 * drift apart instead of thundering in step.
 *
 * <p>Failures never kill a chain: a pass that throws is logged as that
 * source's miss and the next appointment is booked anyway — a collector may
 * lose a pass, never its function.
 */
@Singleton
public final class RandomIntervalScheduler implements CollectorScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RandomIntervalScheduler.class);

    /** First-pass stagger: spread the cold start instead of bursting it. */
    private static final int FIRST_PASS_MAX_DELAY_S = 90;

    private final WebExecutor executor;
    private final ArticlePool pool;
    private final Queue<CollectorSource> registered = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Inject
    public RandomIntervalScheduler(WebExecutor executor, ArticlePool pool) {
        this.executor = executor;
        this.pool = pool;
    }

    @Override
    public void register(CollectorSource source) {
        registered.add(source);
        if (running.get()) bookFirst(source);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        LOG.info("collector clock starting: {} collector(s)", registered.size());
        for (CollectorSource source : registered) bookFirst(source);
    }

    @Override
    public void stop() {
        running.set(false);
    }

    private void bookFirst(CollectorSource source) {
        int delayS = ThreadLocalRandom.current().nextInt(5, FIRST_PASS_MAX_DELAY_S + 1);
        // Debug tap (dev-only, JIT-removed when off): the booked appointment,
        // so the clock view can show when a collector is DUE, not just when
        // it last ran. Recording only — the schedule itself is unchanged.
        if (de.bsommerfeld.wsbg.terminal.core.debug.Debug.ENABLED) {
            de.bsommerfeld.wsbg.terminal.core.debug.CollectorClock.get()
                    .recordBooked(source.sourceName(),
                            System.currentTimeMillis() + delayS * 1_000L);
        }
        executor.schedule("collect:" + source.sourceName(),
                () -> pass(source), delayS, TimeUnit.SECONDS);
    }

    private void pass(CollectorSource source) {
        if (!running.get()) return;
        long t0 = de.bsommerfeld.wsbg.terminal.core.debug.Debug.ENABLED ? System.nanoTime() : 0L;
        try {
            List<Article> yield = source.collect();
            int fresh = pool.add(source, yield == null ? List.of() : yield);
            if (de.bsommerfeld.wsbg.terminal.core.debug.Debug.ENABLED) {
                de.bsommerfeld.wsbg.terminal.core.debug.CollectorClock.get()
                        .recordPass(source.sourceName(), (System.nanoTime() - t0) / 1_000_000,
                                yield == null ? 0 : yield.size(), fresh, null);
            }
            if (fresh > 0) {
                LOG.debug("collector '{}' poured {} fresh item(s)", source.sourceName(), fresh);
            }
        } catch (Exception e) {
            if (de.bsommerfeld.wsbg.terminal.core.debug.Debug.ENABLED) {
                de.bsommerfeld.wsbg.terminal.core.debug.CollectorClock.get()
                        .recordPass(source.sourceName(), (System.nanoTime() - t0) / 1_000_000,
                                0, 0, e.toString());
            }
            LOG.info("collector '{}' missed a pass: {}", source.sourceName(), e.toString());
        } finally {
            bookNext(source);
        }
    }

    private void bookNext(CollectorSource source) {
        if (!running.get()) return;
        FetchInterval interval = source.interval();
        int minutes = ThreadLocalRandom.current()
                .nextInt(interval.minMinutes(), interval.maxMinutes() + 1);
        if (de.bsommerfeld.wsbg.terminal.core.debug.Debug.ENABLED) {
            de.bsommerfeld.wsbg.terminal.core.debug.CollectorClock.get()
                    .recordBooked(source.sourceName(),
                            System.currentTimeMillis() + minutes * 60_000L);
        }
        executor.schedule("collect:" + source.sourceName(),
                () -> pass(source), minutes, TimeUnit.MINUTES);
    }
}
