package de.bsommerfeld.tinyrss.model;

import java.time.Instant;
import java.util.List;

/**
 * A feed as it reads now.
 *
 * @param format      how it was written
 * @param title       plain text; empty when it has none
 * @param link        the site the feed belongs to, absolute; {@code null} when unstated
 * @param description plain text; empty when it has none
 * @param language    as stated, e.g. {@code de-DE}; empty when unstated
 * @param updated     when the feed says it last changed; {@code null} when it does not say
 * @param entries     in the feed's own order - usually, not always, newest first; each id once
 */
public record Feed(
        FeedFormat format,
        String title,
        String link,
        String description,
        String language,
        Instant updated,
        List<Entry> entries) {

    public Feed {
        entries = List.copyOf(entries);
    }
}
