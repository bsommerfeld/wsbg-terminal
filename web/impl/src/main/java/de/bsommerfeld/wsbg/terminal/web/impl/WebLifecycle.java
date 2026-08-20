package de.bsommerfeld.wsbg.terminal.web.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.impl.exec.WebExecutor;
import de.bsommerfeld.wsbg.terminal.web.schedule.CollectorScheduler;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Lifts the registered collectors into the scheduler and owns the web
 * world's start/stop. Called by the environment ONCE it is ready for
 * outbound traffic (after the window is up — collector first passes are
 * staggered anyway, so a BROWSER-first source simply rides its DIRECT
 * fallback until CEF is warm).
 */
@Singleton
public final class WebLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(WebLifecycle.class);

    private final CollectorScheduler scheduler;
    private final Set<CollectorSource> collectors;
    private final WebExecutor executor;

    @Inject
    public WebLifecycle(CollectorScheduler scheduler, Set<CollectorSource> collectors,
            WebExecutor executor) {
        this.scheduler = scheduler;
        this.collectors = collectors;
        this.executor = executor;
    }

    /** Registers every collector and starts the clock. Idempotent. */
    public void start() {
        for (CollectorSource c : collectors) scheduler.register(c);
        LOG.info("web world starting: {} collector(s) on the clock", collectors.size());
        scheduler.start();
    }

    /** Stops the clock and the executor; in-flight passes finish. */
    public void stop() {
        scheduler.stop();
        executor.close();
    }
}
