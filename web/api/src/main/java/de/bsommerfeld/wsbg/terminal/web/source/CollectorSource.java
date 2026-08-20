package de.bsommerfeld.wsbg.terminal.web.source;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;
import java.util.List;

/**
 * A COLLECTOR: a source with a fixed feed/endpoint that is polled on its own
 * cadence and pours into the article pool — world news, wires, boards,
 * everything that is not explicitly instrument-addressed. Consumers never
 * call a collector; they query the pool, because the pool already has
 * everything.
 *
 * <p>The scheduler drives the cadence: after every {@link #collect()} it draws
 * a random delay inside {@link #interval()} and re-schedules — no fixed beat,
 * no thundering herd, and the per-source bounds keep sensitive hosts slow.
 * Collectors therefore do NOT cache internally; "how often" is the
 * scheduler's job, not the source's.
 */
public interface CollectorSource extends WebSource {

    /**
     * One collection pass: fetch the feed(s), parse, return what is there
     * right now. De-duplication against earlier passes is the pool's job —
     * a collector returns the full current window every time.
     *
     * @return the articles currently on the feed, or an empty list — never
     *         {@code null}
     */
    List<Article> collect() throws Exception;

    /** The bounds the scheduler draws each next delay from. */
    default FetchInterval interval() {
        return FetchInterval.DEFAULT;
    }
}
