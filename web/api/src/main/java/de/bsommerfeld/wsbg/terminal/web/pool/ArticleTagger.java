package de.bsommerfeld.wsbg.terminal.web.pool;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import java.util.Collection;
import java.util.Set;

/**
 * The article→instrument judgment seam, next to {@link ArticlePool}: the pool
 * stores articles, the tagger decides WHICH instruments each article is about
 * and remembers the verdict, so an instrument query becomes a set lookup
 * instead of a per-query word guess.
 *
 * <p>Deliberately an interface in {@code web/api}: the pool implementation must
 * not know the judging machinery (corpus, learned aliases, model arbiter live
 * behind the {@code agent} module boundary — the same direction
 * {@link de.bsommerfeld.wsbg.terminal.web.facts.InstrumentLookup} and
 * {@link de.bsommerfeld.wsbg.terminal.web.facts.PriceSource} already run).
 *
 * <p>Contract for the pool side: {@link #ingest} and {@link #forget} are cheap
 * hand-offs (never block, never throw), safe to call while the pool holds its
 * own lock. {@link #articlesFor} may do real work (index queries, cached
 * judgments) and must therefore be called OUTSIDE the pool's lock; it never
 * calls back into the pool.
 */
public interface ArticleTagger {

    /**
     * Hands freshly pooled articles over for judgment. Fire-and-forget:
     * indexing and judging happen on the tagger's own thread/laziness, never
     * on the pour path.
     */
    void ingest(Collection<Article> articles);

    /**
     * Tells the tagger these articles left the basin (TTL or ceiling), so its
     * index and verdicts can follow. Identities as {@link Article#identity()}.
     */
    void forget(Collection<String> identities);

    /**
     * The identities of every ingested article judged to be ABOUT this
     * instrument (subject role — a mere mention is not an answer). Never
     * {@code null}; unknown instruments yield the judgments a retro pass over
     * the standing index can establish, so a universe change needs no replay.
     */
    Set<String> articlesFor(ResolvedInstrument instrument);
}
