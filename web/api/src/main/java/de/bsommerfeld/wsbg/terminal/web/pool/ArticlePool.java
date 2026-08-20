package de.bsommerfeld.wsbg.terminal.web.pool;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.WebSource;
import java.util.Collection;
import java.util.List;

/**
 * The SAMMELBECKEN: the one in-memory basin every pipeline pours into —
 * collectors on their cadence, search fans on demand. Consumers stopped
 * asking the outside world "what is there?"; they ask the pool, because the
 * pool already has everything. Pure logic, no persistence (deliberate,
 * 2026-08-11).
 *
 * <p>De-duplication is the pool's job (by {@link Article#uuid()}, link as
 * fallback), as is the size ceiling — the basin holds a bounded window of the
 * freshest items, never grows without bound.
 */
public interface ArticlePool {

    /**
     * Pours a batch in, attributed to the source that fetched it — the ONE
     * pour seam. The pool stamps each item's origin from the source's
     * self-description and files it press or sentiment by the source's
     * {@code socialSentiment()} nature. Duplicates of items already in the
     * basin are dropped silently.
     *
     * @return how many items were actually NEW
     */
    int add(WebSource source, Collection<Article> items);

    /**
     * Free-text query over the basin: title/summary matching, freshest first.
     * Sentiment items (from {@code socialSentiment()} sources) are excluded —
     * they answer {@link #querySentiment} only.
     */
    List<Article> query(String freetext, int limit);

    /**
     * Instrument query over the basin: matches by ISIN tag, ticker tag and
     * significant name words — the local replacement for fanning every feed
     * source per request.
     */
    List<Article> queryInstrument(ResolvedInstrument instrument, int limit);

    /** The sentiment stream for an instrument (social sources only). */
    List<Article> querySentiment(ResolvedInstrument instrument, int limit);

    /** The freshest press items, unfiltered — the world wire view. */
    List<Article> recent(int limit);

    /** Items currently in the basin. */
    int size();
}
