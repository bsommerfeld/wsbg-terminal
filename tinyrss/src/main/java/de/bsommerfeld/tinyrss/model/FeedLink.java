package de.bsommerfeld.tinyrss.model;

/**
 * A feed a page points to, found by autodiscovery.
 *
 * @param url   absolute address of the feed
 * @param title what the page calls it; empty when it says nothing
 * @param type  media type the page announces, e.g. {@code application/rss+xml}
 */
public record FeedLink(String url, String title, String type) {
}
