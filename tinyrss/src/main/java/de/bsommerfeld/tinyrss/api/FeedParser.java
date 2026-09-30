package de.bsommerfeld.tinyrss.api;

import de.bsommerfeld.tinyrss.mapping.FeedLinks;
import de.bsommerfeld.tinyrss.mapping.FeedMapper;
import de.bsommerfeld.tinyrss.markup.Element;
import de.bsommerfeld.tinyrss.markup.MarkupParser;
import de.bsommerfeld.tinyrss.markup.TextDecoding;
import de.bsommerfeld.tinyrss.model.Feed;
import de.bsommerfeld.tinyrss.model.FeedLink;

import java.util.List;

/**
 * Reads a feed that is already at hand - {@link FeedReader} fetches and then
 * calls this. RSS 0.9x-2.0, RSS 1.0 and Atom 0.3/1.0, and whatever a real
 * feed gets wrong about them:
 * <ul>
 *   <li>the charset - a byte-order mark, UTF-8 declared as ISO-8859-1, a
 *       stray Windows-1252 byte in UTF-8;</li>
 *   <li>the XML - a bare {@code &}, undeclared HTML entities, unescaped HTML
 *       in a description, undeclared prefixes, a script after the closing
 *       root tag, a PHP warning before the prolog;</li>
 *   <li>the content - dates in any common notation, relative links, twin
 *       fields, a title escaped twice.</li>
 * </ul>
 * Nothing is ever fetched while parsing: DOCTYPEs are skipped, external
 * entities do not exist.
 */
public final class FeedParser {

    private FeedParser() {
    }

    /**
     * @param body        the answer's bytes
     * @param contentType its {@code content-type} header, for the charset; may be {@code null}
     * @param location    where it came from, for relative links; may be {@code null}
     * @throws NotAFeedException the body is an HTML page, JSON, empty, or holds no feed
     */
    public static Feed parse(byte[] body, String contentType, String location) throws NotAFeedException {
        return parse(TextDecoding.decode(body, contentType), location);
    }

    /** {@link #parse(byte[], String, String)} for text already decoded. */
    public static Feed parse(String text, String location) throws NotAFeedException {
        if (text.isBlank()) {
            throw new NotAFeedException("empty answer" + from(location));
        }
        Element document = MarkupParser.parse(text, location);
        Feed feed = FeedMapper.map(document);
        if (feed == null) {
            throw new NotAFeedException(describe(text, document) + from(location));
        }
        return feed;
    }

    /**
     * The feeds an HTML page announces ({@code <link rel="alternate">}), and
     * the page itself when it already is a feed.
     */
    public static List<FeedLink> links(byte[] body, String contentType, String location) {
        String text = TextDecoding.decode(body, contentType);
        Element document = MarkupParser.parse(text, location);
        Feed feed = FeedMapper.map(document);
        if (feed != null) {
            return List.of(new FeedLink(location, feed.title(), switch (feed.format()) {
                case RSS -> "application/rss+xml";
                case RDF -> "application/rdf+xml";
                case ATOM -> "application/atom+xml";
            }));
        }
        return FeedLinks.find(document, location);
    }

    private static String describe(String text, Element document) {
        String start = text.stripLeading();
        if (start.startsWith("{") || start.startsWith("[")) {
            return "JSON, not a feed";
        }
        List<Element> elements = document.elements();
        if (elements.isEmpty()) {
            return "no markup in it";
        }
        Element root = elements.getFirst();
        return root.localName().equalsIgnoreCase("html")
                ? "an HTML page, not a feed"
                : "no feed in it - the document is <" + root.name() + ">";
    }

    private static String from(String location) {
        return location == null ? "" : " (" + location + ")";
    }
}
