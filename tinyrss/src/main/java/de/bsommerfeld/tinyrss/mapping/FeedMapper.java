package de.bsommerfeld.tinyrss.mapping;

import de.bsommerfeld.tinyrss.markup.Element;
import de.bsommerfeld.tinyrss.markup.HtmlText;
import de.bsommerfeld.tinyrss.markup.Namespace;
import de.bsommerfeld.tinyrss.markup.Urls;
import de.bsommerfeld.tinyrss.model.Enclosure;
import de.bsommerfeld.tinyrss.model.Entry;
import de.bsommerfeld.tinyrss.model.Feed;
import de.bsommerfeld.tinyrss.model.FeedFormat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed document to a {@link Feed}: RSS 0.9x-2.0, RSS 1.0 and Atom 0.3/1.0,
 * with the extensions news feeds use - Dublin Core, {@code content:encoded},
 * Media RSS, iTunes, FeedBurner.
 *
 * <h2>Where a field comes from</h2>
 * Feeds carry the same field twice: {@code pubDate} beside {@code dc:date},
 * {@code description} beside {@code content:encoded}, {@code guid} beside a
 * publisher's own {@code metadata:id}. Each field has an order of sources;
 * the first one that holds something usable wins and the rest are ignored,
 * so two representations are never welded into one. Elements are matched by
 * vocabulary, not by prefix, so the publisher's own {@code id} is never taken
 * for the {@code guid}.
 *
 * <h2>Where the feed is</h2>
 * The first {@code rss}, {@code RDF} or {@code feed} element anywhere in the
 * document - behind a PHP warning, before a script a CDN appended - and every
 * {@code item} or {@code entry} under it, however deep broken nesting put
 * it. An entry a feed lists twice under one id is kept once, where it first
 * appears (measured 2026-09-30: SWR, NDR and Dow Jones repeat articles).
 */
public final class FeedMapper {

    private static final Set<Namespace> RSS_CORE = EnumSet.of(Namespace.NONE, Namespace.RSS);
    private static final Set<Namespace> ATOM_CORE = EnumSet.of(Namespace.ATOM, Namespace.NONE);
    private static final Set<Namespace> ATOM_ONLY = EnumSet.of(Namespace.ATOM);

    private static final Pattern AUTHOR_IN_PARENTHESES = Pattern.compile("^\\S+@\\S+\\s*\\((.+)\\)$");
    private static final Pattern AUTHOR_BEFORE_ADDRESS = Pattern.compile("^(.+?)\\s*<\\S+@\\S+>$");
    private static final Pattern IMAGE_PATH = Pattern.compile("(?i).*\\.(jpe?g|png|gif|webp|avif)$");

    private FeedMapper() {
    }

    /**
     * @param document what {@code MarkupParser} made of the answer
     * @return {@code null} when the document holds no feed
     */
    public static Feed map(Element document) {
        Element root = feedRoot(document);
        if (root == null) {
            return null;
        }
        String name = root.localName().toLowerCase(Locale.ROOT);
        if (name.equals("feed")) {
            return atom(root);
        }
        return rss(root, name.equals("rdf") ? FeedFormat.RDF : FeedFormat.RSS);
    }

    // ---- RSS ------------------------------------------------------------------

    private static Feed rss(Element root, FeedFormat format) {
        Element channel = root.localName().equalsIgnoreCase("channel") ? root : first(root, RSS_CORE, "channel");
        if (channel == null) {
            channel = root;
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Element item : all(root, RSS_CORE, "item")) {
            Entry entry = rssEntry(item);
            if (entry != null) {
                entries.putIfAbsent(entry.id(), entry);
            }
        }
        return new Feed(format,
                orEmpty(firstOf(plain(first(channel, RSS_CORE, "title")),
                        plain(first(channel, Namespace.DUBLIN_CORE, "title")))),
                firstOf(link(first(channel, RSS_CORE, "link")),
                        alternateLink(children(channel, ATOM_ONLY, "link"))),
                orEmpty(firstOf(plain(first(channel, RSS_CORE, "description")),
                        plain(first(channel, Namespace.DUBLIN_CORE, "description")))),
                orEmpty(firstOf(text(first(channel, RSS_CORE, "language")),
                        text(first(channel, Namespace.DUBLIN_CORE, "language")), root.attribute("xml:lang"))),
                firstDate(first(channel, RSS_CORE, "lastBuildDate"), first(channel, RSS_CORE, "pubDate"),
                        first(channel, Namespace.DUBLIN_CORE, "date"), first(channel, ATOM_ONLY, "updated")),
                List.copyOf(entries.values()));
    }

    private static Entry rssEntry(Element item) {
        String title = orEmpty(firstOf(plain(first(item, RSS_CORE, "title")),
                plain(first(item, Namespace.DUBLIN_CORE, "title"))));

        Element guid = first(item, RSS_CORE, "guid");
        String guidText = text(guid);
        boolean guidIsLink = guid != null && !"false".equalsIgnoreCase(trimToNull(guid.attribute("isPermaLink")));
        String about = trimToNull(item.attribute("rdf:about"));
        String link = firstWeb(item,
                text(first(item, Namespace.FEEDBURNER, "origLink")),
                link(first(item, RSS_CORE, "link")),
                alternateLink(children(item, ATOM_ONLY, "link")),
                guidIsLink ? guidText : null,
                about);

        String summary = orEmpty(firstOf(html(first(item, RSS_CORE, "description")),
                html(first(item, Namespace.DUBLIN_CORE, "description")),
                textConstruct(first(item, ATOM_ONLY, "summary")),
                escaped(text(first(item, Namespace.ITUNES, "summary"))),
                escaped(mediaDescription(item))));
        String content = orEmpty(firstOf(html(first(item, Namespace.CONTENT, "encoded")),
                textConstruct(first(item, ATOM_ONLY, "content"))));

        List<String> authors = names(children(item, RSS_CORE, "author"), FeedMapper::author);
        if (authors.isEmpty()) {
            authors = names(children(item, Namespace.DUBLIN_CORE, "creator"), FeedMapper::plain);
        }
        if (authors.isEmpty()) {
            authors = names(children(item, Namespace.ITUNES, "author"), FeedMapper::plain);
        }
        if (authors.isEmpty()) {
            authors = atomAuthors(item);
        }

        Instant published = firstDate(first(item, RSS_CORE, "pubDate"), first(item, Namespace.DUBLIN_CORE, "date"),
                first(item, ATOM_ONLY, "published"), first(item, Namespace.DUBLIN_CORE, "issued"),
                first(item, Namespace.DUBLIN_CORE, "created"));
        Instant updated = firstDate(first(item, ATOM_ONLY, "updated"), first(item, Namespace.DUBLIN_CORE, "modified"));

        List<String> categories = names(children(item, RSS_CORE, "category"), FeedMapper::plain);
        categories = union(categories, names(children(item, Namespace.DUBLIN_CORE, "subject"), FeedMapper::plain));

        List<Enclosure> enclosures = new ArrayList<>();
        for (Element enclosure : children(item, RSS_CORE, "enclosure")) {
            addEnclosure(enclosures, enclosure, "url");
        }

        if (title.isEmpty() && link == null && summary.isEmpty() && content.isEmpty() && enclosures.isEmpty()) {
            return null;
        }
        String id = firstOf(guidText, about, link);
        return new Entry(id != null ? id : digest(title, summary, published),
                title, link, summary, content, authors, published, updated, categories,
                images(item, enclosures, content, summary), enclosures);
    }

    // ---- Atom -----------------------------------------------------------------

    private static Feed atom(Element feed) {
        List<String> feedAuthors = atomAuthors(feed);
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Element element : all(feed, ATOM_CORE, "entry")) {
            Entry entry = atomEntry(element, feedAuthors);
            if (entry != null) {
                entries.putIfAbsent(entry.id(), entry);
            }
        }
        return new Feed(FeedFormat.ATOM,
                orEmpty(atomTitle(first(feed, ATOM_CORE, "title"))),
                alternateLink(children(feed, ATOM_CORE, "link")),
                orEmpty(firstOf(atomTitle(first(feed, ATOM_CORE, "subtitle")),
                        atomTitle(first(feed, ATOM_CORE, "tagline")))),
                orEmpty(trimToNull(feed.attribute("xml:lang"))),
                firstDate(first(feed, ATOM_CORE, "updated"), first(feed, ATOM_CORE, "modified")),
                List.copyOf(entries.values()));
    }

    private static Entry atomEntry(Element entry, List<String> feedAuthors) {
        String title = orEmpty(atomTitle(first(entry, ATOM_CORE, "title")));
        String id = text(first(entry, ATOM_CORE, "id"));
        String link = firstWeb(entry,
                text(first(entry, Namespace.FEEDBURNER, "origLink")),
                alternateLink(children(entry, ATOM_CORE, "link")),
                id);
        String summary = orEmpty(firstOf(textConstruct(first(entry, ATOM_CORE, "summary")),
                escaped(mediaDescription(entry))));
        String content = orEmpty(textConstruct(first(entry, ATOM_CORE, "content")));

        List<String> authors = atomAuthors(entry);
        if (authors.isEmpty()) {
            Element source = first(entry, ATOM_CORE, "source");
            authors = source == null ? List.of() : atomAuthors(source);
        }
        if (authors.isEmpty()) {
            authors = names(children(entry, Namespace.DUBLIN_CORE, "creator"), FeedMapper::plain);
        }
        if (authors.isEmpty()) {
            authors = feedAuthors;
        }

        Instant published = firstDate(first(entry, ATOM_CORE, "published"), first(entry, ATOM_CORE, "issued"),
                first(entry, ATOM_CORE, "created"), first(entry, Namespace.DUBLIN_CORE, "date"));
        Instant updated = firstDate(first(entry, ATOM_CORE, "updated"), first(entry, ATOM_CORE, "modified"));

        List<String> categories = new ArrayList<>();
        for (Element category : children(entry, ATOM_CORE, "category")) {
            String term = firstOf(trimToNull(category.attribute("term")), trimToNull(category.attribute("label")),
                    text(category));
            if (term != null) {
                categories.add(term);
            }
        }
        categories = union(categories, names(children(entry, Namespace.DUBLIN_CORE, "subject"), FeedMapper::plain));

        List<Enclosure> enclosures = new ArrayList<>();
        for (Element candidate : children(entry, ATOM_CORE, "link")) {
            if ("enclosure".equalsIgnoreCase(trimToNull(candidate.attribute("rel")))) {
                addEnclosure(enclosures, candidate, "href");
            }
        }

        if (title.isEmpty() && link == null && summary.isEmpty() && content.isEmpty() && enclosures.isEmpty()) {
            return null;
        }
        return new Entry(id != null ? id : link != null ? link : digest(title, summary, published),
                title, link, summary, content, authors, published, updated, categories,
                images(entry, enclosures, content, summary), enclosures);
    }

    /** Media RSS's description, on the entry or inside its {@code media:group} - YouTube keeps it there. */
    private static String mediaDescription(Element entry) {
        String description = text(first(entry, Namespace.MEDIA, "description"));
        Element group = first(entry, Namespace.MEDIA, "group");
        return description != null || group == null ? description : text(first(group, Namespace.MEDIA, "description"));
    }

    private static List<String> atomAuthors(Element parent) {
        List<String> authors = new ArrayList<>();
        for (Element author : children(parent, ATOM_CORE, "author")) {
            String name = firstOf(plain(first(author, ATOM_CORE, "name")),
                    author.hasElements() ? null : plain(author));
            if (name != null && !authors.contains(name)) {
                authors.add(name);
            }
        }
        return authors;
    }

    /**
     * An Atom text construct as HTML: {@code html} as it stands, {@code xhtml}
     * written back from its elements, {@code text} escaped. Atom 0.3's
     * {@code mode} is honoured; base64 and out-of-line content ({@code src})
     * give nothing.
     */
    private static String textConstruct(Element element) {
        if (element == null) {
            return null;
        }
        String type = orEmpty(trimToNull(element.attribute("type"))).toLowerCase(Locale.ROOT);
        String mode = orEmpty(trimToNull(element.attribute("mode"))).toLowerCase(Locale.ROOT);
        if (mode.equals("base64")) {
            return null;
        }
        if (type.contains("xhtml") || mode.equals("xml")) {
            List<Element> elements = element.elements();
            Element wrapper = elements.size() == 1 && elements.getFirst().localName().equalsIgnoreCase("div")
                    ? elements.getFirst() : element;
            return trimToNull(wrapper.innerMarkup());
        }
        if (type.contains("html") || mode.equals("escaped")) {
            return html(element);
        }
        if (type.isEmpty() || type.startsWith("text")) {
            return element.hasElements() ? trimToNull(element.innerMarkup()) : escaped(text(element));
        }
        return null;
    }

    /** An Atom title or subtitle as plain text - a {@code text} one exactly as written. */
    private static String atomTitle(Element element) {
        if (element == null) {
            return null;
        }
        String type = orEmpty(trimToNull(element.attribute("type"))).toLowerCase(Locale.ROOT);
        if ((type.isEmpty() || type.equals("text") || type.equals("text/plain")) && !element.hasElements()) {
            return trimToNull(HtmlText.collapse(element.text()));
        }
        return plain(textConstruct(element));
    }

    // ---- links, pictures, files ---------------------------------------------

    /** An RSS {@code <link>}: its text, or an {@code href} where a feed wrote it Atom-style. */
    private static String link(Element element) {
        if (element == null) {
            return null;
        }
        String href = trimToNull(element.attribute("href"));
        return href != null ? Urls.resolve(element.base(), href) : Urls.resolve(element.base(), text(element));
    }

    /**
     * The Atom link to the page itself: {@code rel="alternate"} or no
     * {@code rel}, an HTML one first - never {@code self}, {@code edit},
     * {@code replies} or an enclosure.
     */
    private static String alternateLink(List<Element> links) {
        Element any = null;
        for (Element link : links) {
            String rel = orEmpty(trimToNull(link.attribute("rel"))).toLowerCase(Locale.ROOT);
            if (link.attribute("href") == null || !(rel.isEmpty() || rel.equals("alternate"))) {
                continue;
            }
            String type = orEmpty(trimToNull(link.attribute("type"))).toLowerCase(Locale.ROOT);
            if (type.isEmpty() || type.contains("html")) {
                return Urls.resolve(link.base(), link.attribute("href"));
            }
            if (any == null) {
                any = link;
            }
        }
        return any == null ? null : Urls.resolve(any.base(), any.attribute("href"));
    }

    private static void addEnclosure(List<Enclosure> enclosures, Element element, String urlAttribute) {
        String url = Urls.resolve(element.base(), element.attribute(urlAttribute));
        if (!Urls.isWeb(url)) {
            return;
        }
        long length = 0;
        String declared = trimToNull(element.attribute("length"));
        if (declared != null && declared.matches("\\d{1,18}")) {
            length = Long.parseLong(declared);
        }
        enclosures.add(new Enclosure(url, orEmpty(trimToNull(element.attribute("type"))), length));
    }

    /**
     * The pictures of an entry: the ones its feed declares - Media RSS full
     * size, then Media RSS thumbnails, image enclosures, the iTunes image -
     * then the ones in its HTML.
     */
    private static List<String> images(Element entry, List<Enclosure> enclosures, String content, String summary) {
        Set<String> full = new LinkedHashSet<>();
        Set<String> thumbnails = new LinkedHashSet<>();
        collectMedia(entry, full, thumbnails);
        Set<String> images = new LinkedHashSet<>(full);
        images.addAll(thumbnails);
        for (Enclosure enclosure : enclosures) {
            if (enclosure.type().toLowerCase(Locale.ROOT).startsWith("image/")
                    || enclosure.type().isEmpty() && looksLikeImage(enclosure.url())) {
                images.add(enclosure.url());
            }
        }
        for (Element image : children(entry, EnumSet.of(Namespace.ITUNES), "image")) {
            String url = Urls.resolve(image.base(), image.attribute("href"));
            if (Urls.isWeb(url)) {
                images.add(url);
            }
        }
        images.addAll(HtmlText.images(content, entry.base()));
        images.addAll(HtmlText.images(summary, entry.base()));
        return new ArrayList<>(images);
    }

    private static void collectMedia(Element parent, Set<String> full, Set<String> thumbnails) {
        for (Element element : parent.elements()) {
            if (element.namespace() != Namespace.MEDIA) {
                continue;
            }
            String name = element.localName().toLowerCase(Locale.ROOT);
            String url = Urls.resolve(element.base(), element.attribute("url"));
            if (name.equals("thumbnail") && Urls.isWeb(url)) {
                thumbnails.add(url);
            } else if (name.equals("content")) {
                String medium = orEmpty(trimToNull(element.attribute("medium"))).toLowerCase(Locale.ROOT);
                String type = orEmpty(trimToNull(element.attribute("type"))).toLowerCase(Locale.ROOT);
                boolean image = medium.equals("image") || type.startsWith("image/")
                        || medium.isEmpty() && type.isEmpty() && looksLikeImage(url);
                if (image && Urls.isWeb(url)) {
                    full.add(url);
                }
                collectMedia(element, full, thumbnails);
            } else if (name.equals("group")) {
                collectMedia(element, full, thumbnails);
            }
        }
    }

    private static boolean looksLikeImage(String url) {
        if (url == null) {
            return false;
        }
        int end = url.length();
        for (char stop : new char[] {'?', '#'}) {
            int index = url.indexOf(stop);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return IMAGE_PATH.matcher(url.substring(0, end)).matches();
    }

    // ---- values ---------------------------------------------------------------

    /** The element's content as HTML: written back when it holds unescaped tags, its text otherwise. */
    private static String html(Element element) {
        if (element == null) {
            return null;
        }
        return trimToNull(element.hasElements() ? element.innerMarkup() : element.text());
    }

    /**
     * The element's content as plain text, read as HTML - so a title the feed
     * escaped twice ({@code F&amp;amp;O}, 7 of 6,493 titles in the corpus)
     * comes out once. None of the corpus' titles held text that reads as a tag.
     */
    private static String plain(Element element) {
        return element == null ? null : plain(html(element));
    }

    private static String plain(String html) {
        return html == null ? null : trimToNull(HtmlText.text(html));
    }

    private static String escaped(String text) {
        return text == null ? null : HtmlText.escape(text);
    }

    private static String text(Element element) {
        return element == null ? null : trimToNull(element.text());
    }

    /** {@code jane@example.com (Jane Doe)} and {@code Jane Doe <jane@example.com>} to {@code Jane Doe}. */
    private static String author(Element element) {
        String author = plain(element);
        if (author == null) {
            return null;
        }
        Matcher parentheses = AUTHOR_IN_PARENTHESES.matcher(author);
        if (parentheses.matches()) {
            return parentheses.group(1).trim();
        }
        Matcher address = AUTHOR_BEFORE_ADDRESS.matcher(author);
        return address.matches() ? address.group(1).trim() : author;
    }

    private static Instant firstDate(Element... candidates) {
        for (Element candidate : candidates) {
            Instant instant = candidate == null ? null : Dates.parse(candidate.text());
            if (instant != null) {
                return instant;
            }
        }
        return null;
    }

    /** A stable id for an entry that names none: the same entry digests the same on every read. */
    private static String digest(String title, String summary, Instant published) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String identity = title + "\n" + HtmlText.text(summary) + "\n" + (published == null ? "" : published);
            byte[] hash = sha256.digest(identity.getBytes(StandardCharsets.UTF_8));
            return "urn:sha256:" + HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    private static List<String> names(List<Element> elements, Function<Element, String> read) {
        List<String> names = new ArrayList<>();
        for (Element element : elements) {
            String name = read.apply(element);
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static List<String> union(List<String> first, List<String> second) {
        Set<String> union = new LinkedHashSet<>(first);
        union.addAll(second);
        return new ArrayList<>(union);
    }

    // ---- tree walking -------------------------------------------------------

    /**
     * The feed's root: the first {@code rss}, {@code RDF} or Atom {@code feed}
     * in the document, else a bare {@code channel} that holds items.
     */
    private static Element feedRoot(Element document) {
        Element found = find(document, element -> {
            String name = element.localName().toLowerCase(Locale.ROOT);
            return name.equals("rss") || name.equals("rdf")
                    || name.equals("feed") && ATOM_CORE.contains(element.namespace());
        });
        if (found != null) {
            return found;
        }
        return find(document, element -> element.isIn(RSS_CORE, "channel") && !all(element, RSS_CORE, "item").isEmpty());
    }

    private static Element find(Element parent, Predicate<Element> test) {
        for (Element element : parent.elements()) {
            if (test.test(element)) {
                return element;
            }
            Element nested = find(element, test);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /** Every {@code name} element below {@code parent} at any depth, but none inside another. */
    private static List<Element> all(Element parent, Set<Namespace> namespaces, String name) {
        List<Element> found = new ArrayList<>();
        collect(parent, namespaces, name, found);
        return found;
    }

    private static void collect(Element parent, Set<Namespace> namespaces, String name, List<Element> found) {
        for (Element element : parent.elements()) {
            if (element.isIn(namespaces, name)) {
                found.add(element);
            } else {
                collect(element, namespaces, name, found);
            }
        }
    }

    private static Element first(Element parent, Set<Namespace> namespaces, String name) {
        for (Element element : parent.elements()) {
            if (element.isIn(namespaces, name)) {
                return element;
            }
        }
        return null;
    }

    private static Element first(Element parent, Namespace namespace, String name) {
        return first(parent, EnumSet.of(namespace), name);
    }

    private static List<Element> children(Element parent, Set<Namespace> namespaces, String name) {
        List<Element> children = new ArrayList<>();
        for (Element element : parent.elements()) {
            if (element.isIn(namespaces, name)) {
                children.add(element);
            }
        }
        return children;
    }

    private static List<Element> children(Element parent, Namespace namespace, String name) {
        return children(parent, EnumSet.of(namespace), name);
    }

    // ---- small helpers --------------------------------------------------------

    /** The first value that is there. */
    @SafeVarargs
    private static <T> T firstOf(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** The first value that is an absolute web address, resolved against {@code context}. */
    private static String firstWeb(Element context, String... values) {
        for (String value : values) {
            String url = Urls.resolve(context.base(), value);
            if (Urls.isWeb(url)) {
                return url;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
