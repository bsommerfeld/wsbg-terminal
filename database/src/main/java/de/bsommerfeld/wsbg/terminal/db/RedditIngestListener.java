package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;

/**
 * The one seam every piece of scraped room text passes through on its way into
 * the {@link RedditRepository} — no matter which path answered (OAuth, the
 * anonymous JSON, or RSS).
 *
 * <p>It exists so the mention counter can read the stream at ingest without the
 * three scrapers having to know about it, and without this module having to
 * know about the counter. Implementations are called on the scraper's thread
 * and must be cheap and silent: the repository swallows whatever they throw,
 * because a counting mistake must never cost a thread.
 *
 * <p>Both callbacks fire on EVERY save, including a re-save of something
 * already seen (a bumped score, a re-scrape). Deciding what is new is the
 * listener's job.
 */
public interface RedditIngestListener {

    /** A thread was stored — new, or saved again with fresher metadata. */
    void onThread(RedditThread thread);

    /** A comment was stored — new, or saved again with a fresher score/edit. */
    void onComment(RedditComment comment);
}
