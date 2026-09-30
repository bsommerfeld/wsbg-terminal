package de.bsommerfeld.tinyrss.model;

import de.bsommerfeld.tinyrss.markup.HtmlText;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One item of a feed - an article, a post, an episode. What the feed does not
 * carry is empty or {@code null}, never made up.
 *
 * @param id          stable across reads, never {@code null}: the feed's own
 *                    {@code guid}/{@code id}, else the link, else a digest of
 *                    title, text and date
 * @param title       plain text, references decoded; empty when it has none
 * @param link        the article, absolute; {@code null} when the feed names none
 * @param summaryHtml the teaser as HTML - RSS {@code description}, Atom {@code summary}; empty when absent
 * @param contentHtml the full text as HTML - {@code content:encoded}, Atom {@code content}; empty when absent
 * @param authors     names, without the e-mail address RSS puts around them
 * @param published   when it first appeared; {@code null} when the feed does not say
 * @param updated     when it last changed; {@code null} when the feed does not say
 * @param categories  tags and sections, as the feed spells them
 * @param images      absolute picture addresses, the ones the feed declares
 *                    (Media RSS, image enclosures) before the ones in its HTML
 * @param enclosures  attached files, pictures included
 */
public record Entry(
        String id,
        String title,
        String link,
        String summaryHtml,
        String contentHtml,
        List<String> authors,
        Instant published,
        Instant updated,
        List<String> categories,
        List<String> images,
        List<Enclosure> enclosures) {

    public Entry {
        authors = List.copyOf(authors);
        categories = List.copyOf(categories);
        images = List.copyOf(images);
        enclosures = List.copyOf(enclosures);
    }

    /** The teaser as plain text on one line. */
    public String summaryText() {
        return HtmlText.text(summaryHtml);
    }

    /** The full text as plain text on one line. */
    public String contentText() {
        return HtmlText.text(contentHtml);
    }

    /** When it appeared: {@link #published}, else {@link #updated}. */
    public Optional<Instant> time() {
        return Optional.ofNullable(published != null ? published : updated);
    }
}
