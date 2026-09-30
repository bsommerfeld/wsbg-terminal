package de.bsommerfeld.tinyrss.model;

/**
 * A file an entry carries: a podcast episode, a PDF, a picture - RSS
 * {@code <enclosure>}, Atom {@code <link rel="enclosure">}.
 *
 * @param url    absolute address
 * @param type   media type as the feed states it, e.g. {@code audio/mpeg}; empty when unstated
 * @param length size in bytes; {@code 0} when unstated
 */
public record Enclosure(String url, String type, long length) {
}
