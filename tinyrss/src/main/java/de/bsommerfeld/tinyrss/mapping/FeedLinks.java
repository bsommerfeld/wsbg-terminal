package de.bsommerfeld.tinyrss.mapping;

import de.bsommerfeld.tinyrss.markup.Element;
import de.bsommerfeld.tinyrss.markup.Urls;
import de.bsommerfeld.tinyrss.model.FeedLink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Autodiscovery: the feeds an HTML page names in its
 * {@code <link rel="alternate" type="application/rss+xml" href="...">}
 * elements, resolved against the page's {@code <base>} or its own address.
 */
public final class FeedLinks {

    private static final Set<String> FEED_TYPES = Set.of(
            "application/rss+xml", "application/atom+xml", "application/rdf+xml", "application/xml", "text/xml");

    private FeedLinks() {
    }

    public static List<FeedLink> find(Element document, String pageUrl) {
        List<Element> links = new ArrayList<>();
        List<Element> bases = new ArrayList<>();
        collect(document, links, bases);
        String base = pageUrl;
        if (!bases.isEmpty()) {
            String declared = Urls.resolve(pageUrl, bases.getFirst().attribute("href"));
            base = declared != null ? declared : pageUrl;
        }
        Map<String, FeedLink> found = new LinkedHashMap<>();
        for (Element link : links) {
            String url = Urls.resolve(base, link.attribute("href"));
            if (Urls.isWeb(url)) {
                String title = link.attribute("title");
                found.putIfAbsent(url, new FeedLink(url, title == null ? "" : title.trim(), type(link)));
            }
        }
        return new ArrayList<>(found.values());
    }

    private static void collect(Element parent, List<Element> links, List<Element> bases) {
        for (Element element : parent.elements()) {
            String name = element.localName().toLowerCase(Locale.ROOT);
            if (name.equals("base") && element.attribute("href") != null) {
                bases.add(element);
            } else if (name.equals("link") && isFeed(element)) {
                links.add(element);
            }
            collect(element, links, bases);
        }
    }

    /** {@code rel="alternate"} with a feed's type, or the {@code rel="feed"} HTML 5 once proposed. */
    private static boolean isFeed(Element link) {
        String rel = link.attribute("rel");
        if (rel == null || link.attribute("href") == null) {
            return false;
        }
        List<String> rels = List.of(rel.toLowerCase(Locale.ROOT).trim().split("\\s+"));
        return rels.contains("feed") || rels.contains("alternate") && FEED_TYPES.contains(type(link));
    }

    private static String type(Element link) {
        String type = link.attribute("type");
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }
}
