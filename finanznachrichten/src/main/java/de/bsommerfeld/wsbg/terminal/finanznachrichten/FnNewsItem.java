package de.bsommerfeld.wsbg.terminal.finanznachrichten;

/**
 * One news item parsed from a finanznachrichten.de RSS feed.
 *
 * <p>The feeds are RSS 2.0 with a custom {@code fn:} namespace. Each
 * {@code <item>} carries a {@code <title>}, {@code <description>} (a teaser,
 * often truncated with an ellipsis), {@code <link>} to the full article, a
 * {@code <pubDate>} in ISO-8601 instant form ({@code 2026-06-05T03:46:00Z}),
 * and an optional {@code <fn:isin>} naming the primary instrument. There is
 * <b>no {@code <guid>}, {@code <author>} or {@code <category>}</b>, so
 * {@link #link()} is the natural identity / de-duplication key.
 *
 * @param title        headline text
 * @param link         permalink to the full article (the dedup key; also the
 *                     "active link back" the feed's terms of use require)
 * @param description  teaser / lead text, HTML stripped (may be empty)
 * @param isin         the {@code fn:isin} of the primary instrument, or
 *                     {@code null} when the item carries none
 * @param publishedUtc {@code pubDate} as epoch seconds, or {@code 0} if absent
 *                     or unparseable
 * @param fetchedUtc   epoch seconds when this item was fetched locally
 * @param feedSlug     the {@link FnFeed#slug()} this item came from
 * @param sponsored    {@code true} when the description was tagged
 *                     "Anzeige / Werbung" (paid placement)
 */
public record FnNewsItem(
        String title,
        String link,
        String description,
        String isin,
        long publishedUtc,
        long fetchedUtc,
        String feedSlug,
        boolean sponsored) {
}
