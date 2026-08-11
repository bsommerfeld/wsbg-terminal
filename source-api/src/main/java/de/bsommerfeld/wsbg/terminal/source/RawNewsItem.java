package de.bsommerfeld.wsbg.terminal.source;

import java.time.Instant;
import java.util.List;

/**
 * One transport-neutral news article, the single news domain object every
 * {@link NewsSource} emits — regardless of whether it came from Yahoo's search
 * endpoint, a newswire RSS feed, finanznachrichten, or FinancialJuice.
 *
 * <p>The aggregator consumes only this type, so a new source is "just another
 * {@link NewsSource}" and never leaks a vendor-specific record up the stack.
 * Genuinely source-internal bookkeeping (which RSS feed an item came from, the
 * local fetch timestamp) and pure presentation derivations (FinancialJuice's
 * "red" urgency flag, its title-derived tags) deliberately do <b>not</b> live
 * here — only what is meaningful across sources and useful to the editorial
 * pipeline. Presentation flags are recomputed at render time from {@link #title()}.
 *
 * @param uuid           stable id / de-duplication key for the item — a source's
 *                       own id where it has one (Yahoo {@code uuid}, Fj {@code guid}),
 *                       otherwise the {@code link}. Never blank.
 * @param title          headline text
 * @param publisher      the originating outlet / source name (may be blank)
 * @param link           permalink to the full article (also a fallback identity)
 * @param publishedAt    publication instant, or {@code null} if the source gave none
 * @param relatedTickers ticker symbols this item references; empty when the
 *                       source doesn't tag instruments. The aggregator keys its
 *                       ticker index off these.
 * @param isin           the primary instrument's ISIN, or {@code null} — a second
 *                       instrument key for sources that tag ISINs (finanznachrichten)
 *                       rather than ticker symbols.
 * @param summary        teaser / lead text (HTML stripped), or {@code null}/empty
 *                       when the source carries none (Yahoo search gives no body).
 * @param sponsored      {@code true} for paid placement / advertising the source
 *                       flagged, so the aggregator can drop or de-rank it.
 * @param imageUrl       URL of an image embedded with the article, or {@code null}.
 *                       Cross-source (newswires/Yahoo articles carry images) and a
 *                       candidate for multimodal vision analysis.
 * @param origin         language and press sphere the item came out of, as the
 *                       source declares itself. Sources never fill this in by
 *                       hand: the fan stamps it from {@link NewsSource#origin()}
 *                       on the way through, so every construction below leaves
 *                       it {@link SourceOrigin#UNKNOWN} and the seam stays in
 *                       one place.
 */
public record RawNewsItem(
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

    public RawNewsItem {
        if (origin == null) origin = SourceOrigin.UNKNOWN;
    }

    /**
     * The construction every source uses: origin unstamped. The fan puts it on
     * afterwards ({@link #withOrigin}), which is the only place that knows
     * WHICH source answered.
     */
    public RawNewsItem(
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
     * The same item with its origin stamped. Never overwrites an origin an
     * item already carries - a source that DID name its own item's origin
     * (a multi-edition aggregator that knows more than its instance does)
     * keeps it.
     */
    public RawNewsItem withOrigin(SourceOrigin stamp) {
        if (stamp == null || !stamp.known() || origin.known()) return this;
        return new RawNewsItem(uuid, title, publisher, link, publishedAt, relatedTickers,
                isin, summary, sponsored, imageUrl, stamp);
    }

    /**
     * Convenience constructor for sources that carry no ISIN / teaser / sponsored
     * flag / image (e.g. Yahoo search). Delegates with those left empty, so such
     * call sites stay a plain six-argument construction.
     */
    public RawNewsItem(
            String uuid,
            String title,
            String publisher,
            String link,
            Instant publishedAt,
            List<String> relatedTickers) {
        this(uuid, title, publisher, link, publishedAt, relatedTickers, null, null, false);
    }

    /**
     * Convenience constructor for sources that tag instruments / teasers / ads but
     * carry no image (e.g. finanznachrichten). Delegates with {@code imageUrl} null.
     */
    public RawNewsItem(
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
