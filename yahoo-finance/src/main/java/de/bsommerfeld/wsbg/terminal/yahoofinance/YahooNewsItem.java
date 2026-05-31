package de.bsommerfeld.wsbg.terminal.yahoofinance;

import java.time.Instant;
import java.util.List;

/**
 * One news article returned by Yahoo Finance's search endpoint.
 *
 * <p>
 * The {@code link} points to a {@code finance.yahoo.com/m/{uuid}}
 * redirect that resolves to the publisher's original article — we
 * surface that URL as-is so the user can click through from the UI.
 * {@code publishedAt} is parsed from Yahoo's {@code providerPublishTime}
 * Unix-seconds field.
 *
 * <p>
 * {@code relatedTickers} can be empty — Yahoo only fills it for the
 * better-tagged big-cap stories.
 */
public record YahooNewsItem(
        String uuid,
        String title,
        String publisher,
        String link,
        Instant publishedAt,
        List<String> relatedTickers) {
}
