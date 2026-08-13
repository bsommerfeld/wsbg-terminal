package de.bsommerfeld.wsbg.terminal.web.article;

import java.time.Instant;
import java.util.List;

/**
 * One transport-neutral article, the single domain object every web source
 * emits — regardless of whether it came from a search endpoint, a newswire RSS
 * feed, a scraped page or the collector pool. Downstream (pool, gateway,
 * agent) consumes only this type, so a new source never leaks a
 * vendor-specific record up the stack.
 *
 * <p>Genuinely source-internal bookkeeping (which RSS feed an item came from,
 * the local fetch timestamp) and pure presentation derivations deliberately do
 * <b>not</b> live here — only what is meaningful across sources.
 *
 * @param uuid           stable id / de-duplication key — a source's own id where
 *                       it has one, otherwise the {@code link}. Never blank.
 * @param title          headline text
 * @param publisher      the originating outlet / source name (may be blank)
 * @param link           permalink to the full article (also a fallback identity)
 * @param publishedAt    publication instant, or {@code null} if the source gave none
 * @param relatedTickers ticker symbols this item references; empty when the
 *                       source doesn't tag instruments
 * @param isin           the primary instrument's ISIN, or {@code null}
 * @param summary        teaser / lead text (HTML stripped), or {@code null}/empty
 * @param sponsored      {@code true} for paid placement / advertising the source
 *                       flagged, so downstream can drop or de-rank it
 * @param imageUrl       URL of an image embedded with the article, or {@code null}
 * @param origin         language and press sphere the item came out of. Sources
 *                       never fill this in by hand: the pipeline stamps it from
 *                       {@code WebSource#origin()} on the way through, so every
 *                       construction below leaves it {@link SourceOrigin#UNKNOWN}
 *                       and the seam stays in one place.
 */
public record Article(
        String uuid,
        String title,
        String publisher,
        String link,
        Instant publishedAt,
        List<String> relatedTickers,
        String isin,
        String summary,
        boolean sponsored,
        String imageUrl,
        SourceOrigin origin) {

    public Article {
        if (origin == null) origin = SourceOrigin.UNKNOWN;
    }

    /**
     * The construction every source uses: origin unstamped. The pipeline puts
     * it on afterwards ({@link #withOrigin}), which is the only place that
     * knows WHICH source answered.
     */
    public Article(
            String uuid,
            String title,
            String publisher,
            String link,
            Instant publishedAt,
            List<String> relatedTickers,
            String isin,
            String summary,
            boolean sponsored,
            String imageUrl) {
        this(uuid, title, publisher, link, publishedAt, relatedTickers, isin, summary,
                sponsored, imageUrl, SourceOrigin.UNKNOWN);
    }

    /**
     * The same article with its origin stamped. Never overwrites an origin an
     * item already carries - a source that DID name its own item's origin
     * (a multi-edition aggregator that knows more than its instance does)
     * keeps it.
     */
    public Article withOrigin(SourceOrigin stamp) {
        if (stamp == null || !stamp.known() || origin.known()) return this;
        return new Article(uuid, title, publisher, link, publishedAt, relatedTickers,
                isin, summary, sponsored, imageUrl, stamp);
    }

    /**
     * The same article under a different byline. The one caller is the story
     * clusterer: when several outlets carry one agency report, a single item
     * stands for the cluster and takes a byline naming the others - how WIDELY
     * a story is carried is itself signal, and losing it would make three
     * outlets look like one.
     */
    public Article withPublisher(String byline) {
        if (byline == null || byline.isBlank() || byline.equals(publisher)) return this;
        return new Article(uuid, title, byline, link, publishedAt, relatedTickers,
                isin, summary, sponsored, imageUrl, origin);
    }

    /**
     * The basin identity every consumer keys this article by: the source's own
     * stable id, the permalink as fallback, empty when neither exists. Lives on
     * the record so the pool and the tagger can never disagree about who an
     * article is.
     */
    public String identity() {
        if (uuid != null && !uuid.isBlank()) return uuid;
        return link == null ? "" : link;
    }

    /**
     * Convenience constructor for sources that carry no ISIN / teaser /
     * sponsored flag / image (e.g. plain search results).
     */
    public Article(
            String uuid,
            String title,
            String publisher,
            String link,
            Instant publishedAt,
            List<String> relatedTickers) {
        this(uuid, title, publisher, link, publishedAt, relatedTickers, null, null, false);
    }

    /**
     * Convenience constructor for sources that tag instruments / teasers / ads
     * but carry no image. Delegates with {@code imageUrl} null.
     */
    public Article(
            String uuid,
            String title,
            String publisher,
            String link,
            Instant publishedAt,
            List<String> relatedTickers,
            String isin,
            String summary,
            boolean sponsored) {
        this(uuid, title, publisher, link, publishedAt, relatedTickers, isin, summary, sponsored, null);
    }
}
