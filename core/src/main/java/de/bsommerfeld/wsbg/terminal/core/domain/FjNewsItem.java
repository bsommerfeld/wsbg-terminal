package de.bsommerfeld.wsbg.terminal.core.domain;

import java.util.List;

/**
 * Immutable snapshot of a single FinancialJuice RSS item.
 * Timestamps are Unix epoch seconds (UTC).
 *
 * @param guid         unique FinancialJuice item ID (e.g. {@code 9469537})
 * @param title        headline text with the {@code FinancialJuice: } prefix
 *                     stripped
 * @param link         full URL to the article on financialjuice.com
 * @param description  plain-text body (HTML tags stripped), empty if absent
 * @param author       author attribution from the feed
 * @param publishedUtc publication timestamp in epoch seconds
 * @param fetchedAtUtc timestamp when this item was fetched (epoch seconds)
 * @param imageUrl     URL to an embedded image if one was present in the description, or null
 * @param tags         categorization tags derived from the title content
 * @param isRed        true if the item is flagged as urgent/high-impact (e.g., geopolitics)
 */
public record FjNewsItem(
        String guid,
        String title,
        String link,
        String description,
        String author,
        long publishedUtc,
        long fetchedAtUtc,
        String imageUrl,
        List<String> tags,
        boolean isRed) {
}
