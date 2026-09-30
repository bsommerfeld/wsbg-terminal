package de.bsommerfeld.tinyrss.markup;

import java.util.Locale;
import java.util.Map;

/**
 * The vocabularies a feed is written in, recognised by namespace URI. Every
 * spelling a feed uses for the same vocabulary - {@code http} or
 * {@code https}, with or without the trailing slash, Atom 0.3 beside 1.0 -
 * lands on one constant, so the mapping asks for "Dublin Core" and not for one
 * of its URIs.
 */
public enum Namespace {

    /** No namespace: RSS 0.9x and 2.0, and every feed that forgot its declaration. */
    NONE,
    /** Atom 1.0 and 0.3. */
    ATOM,
    /** RSS 1.0 and 0.90, the RDF generation, and the RSS 2.0 namespace some feeds declare. */
    RSS,
    RDF,
    DUBLIN_CORE,
    /** {@code content:encoded}. */
    CONTENT,
    /** Media RSS: {@code media:content}, {@code media:thumbnail}, {@code media:group}. */
    MEDIA,
    ITUNES,
    /** {@code feedburner:origLink}, the publisher's own address behind FeedBurner's. */
    FEEDBURNER,
    XHTML,
    /** Anything else - a publisher's own extension. */
    OTHER;

    private static final Map<String, Namespace> BY_URI = Map.ofEntries(
            Map.entry("www.w3.org/2005/atom", ATOM),
            Map.entry("purl.org/atom/ns", ATOM),
            Map.entry("purl.org/rss/1.0", RSS),
            Map.entry("my.netscape.com/rdf/simple/0.9", RSS),
            Map.entry("backend.userland.com/rss2", RSS),
            Map.entry("blogs.law.harvard.edu/tech/rss", RSS),
            Map.entry("www.w3.org/1999/02/22-rdf-syntax-ns", RDF),
            Map.entry("purl.org/dc/elements/1.1", DUBLIN_CORE),
            Map.entry("purl.org/dc/terms", DUBLIN_CORE),
            Map.entry("purl.org/rss/1.0/modules/content", CONTENT),
            Map.entry("search.yahoo.com/mrss", MEDIA),
            Map.entry("video.search.yahoo.com/mrss", MEDIA),
            Map.entry("www.itunes.com/dtds/podcast-1.0.dtd", ITUNES),
            Map.entry("rssnamespace.org/feedburner/ext/1.0", FEEDBURNER),
            Map.entry("www.w3.org/1999/xhtml", XHTML));

    /** What a prefix nobody declared usually stands for - feeds use {@code media:} without saying so. */
    private static final Map<String, Namespace> BY_PREFIX = Map.ofEntries(
            Map.entry("atom", ATOM),
            Map.entry("a10", ATOM),
            Map.entry("rdf", RDF),
            Map.entry("dc", DUBLIN_CORE),
            Map.entry("dcterms", DUBLIN_CORE),
            Map.entry("content", CONTENT),
            Map.entry("media", MEDIA),
            Map.entry("itunes", ITUNES),
            Map.entry("feedburner", FEEDBURNER),
            Map.entry("xhtml", XHTML));

    /** The vocabulary of a declared URI; {@link #NONE} for the empty one. */
    public static Namespace ofUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return NONE;
        }
        String key = uri.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://", "");
        while (key.endsWith("/") || key.endsWith("#")) {
            key = key.substring(0, key.length() - 1);
        }
        return BY_URI.getOrDefault(key, OTHER);
    }

    /** The vocabulary an undeclared prefix most likely means. */
    public static Namespace ofUndeclaredPrefix(String prefix) {
        return BY_PREFIX.getOrDefault(prefix.toLowerCase(Locale.ROOT), OTHER);
    }
}
