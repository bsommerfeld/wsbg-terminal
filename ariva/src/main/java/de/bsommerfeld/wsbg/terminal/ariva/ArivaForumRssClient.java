package de.bsommerfeld.wsbg.terminal.ariva;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * German retail-investor FORUM SENTIMENT via Ariva's keyless community RSS
 * ({@code www.ariva.de/forum/rss}) — the German board-talk leg the news
 * scanners can't see: what anonymous German retail is saying about a stock
 * RIGHT NOW, minutes-fresh (live-probed 2026-07-16, plain client 200, posts
 * at minute cadence during trading hours). The same forum backs the
 * finanzen.net and onvista community white-labels (identical thread ids,
 * verified 2026-07-16), so this ONE feed covers all three venues.
 *
 * <p><b>This source is a FIREHOSE, not a search:</b> the one community-wide
 * feed carries only the ~10 newest posts, so it is fetched, parsed and cached
 * as a POOL shared across all queries (short TTL — the feed itself turns over
 * in minutes). <b>Addressing is ISIN-only:</b> every item is tagged with one
 * or more {@code <isin>} elements (multi-listing threads like VW carry both
 * share classes), which is authoritative instrument identity — no fuzzy name
 * matching against post titles like "geb ich dir absolut Recht" that never
 * name the company. {@link #newsFor} and {@link #newsForName} stay no-ops.
 *
 * <p><b>These are forum posts, not articles</b> — user opinion, not verified
 * fact (house rule: user numbers are sentiment). The item title is the POST
 * title, the summary is the post body (HTML stripped), the author is pulled
 * out of Ariva's trailing {@code <b><i> -name</i></b>} marker into the
 * publisher ("Ariva-Forum (name)") so the model sees attribution without
 * mistaking the venue for a newsroom.
 *
 * <p>Item fields (pinned live 2026-07-16): {@code <title>} = post title,
 * {@code <description>} = post body HTML ending in the author marker,
 * {@code <link>} = deep link with page + jumppos anchor (unique per post —
 * the uuid), {@code <pubDate>} = "16 Jul 2026 14:36:38 +0200" (RFC-1123
 * WITHOUT the day-of-week token), {@code <isin>} repeated per instrument.
 * ToS note: Ariva's robots.txt leaves the forum open; headline/teaser use
 * is the classic RSS embed purpose. Full-thread fetching stays out.
 */
@Singleton
public class ArivaForumRssClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(ArivaForumRssClient.class);

    private static final String FEED_URL = "https://www.ariva.de/forum/rss";
    /** The feed holds only ~10 posts and turns over in minutes — short TTL. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(3);
    private static final String PUBLISHER_PREFIX = "Ariva-Forum";

    /** Ariva's trailing author marker inside the description HTML. */
    private static final Pattern AUTHOR_MARKER =
            Pattern.compile("<b><i>\\s*-(.*?)</i></b>\\s*$", Pattern.DOTALL);

    /** Hardened StAX factory (XXE off — this is a remote feed), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** The shared pool: the parsed community feed, refreshed at most once per TTL. */
    private volatile CachedPool pool;

    private record CachedPool(Instant fetchedAt, List<ForumPost> posts) {}

    /** One parsed post with ALL its ISIN tags (the emitted item carries the queried one). */
    record ForumPost(RawNewsItem item, List<String> isins) {}

    /** Test/default: plain direct transport. */
    public ArivaForumRssClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the feed answers a bare
     * client with no wall (live-probed 2026-07-16), so this client declares
     * "fine on plain HTTP" like the other keyless no-wall sources.
     */
    @Inject
    public ArivaForumRssClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "ariva-forum";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /** No-op: the feed tags ISINs, not ticker symbols. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** No-op: post titles never name the company — only the ISIN tags are trustworthy. */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        if (isin == null || isin.isBlank() || limit <= 0) return List.of();
        String wanted = isin.strip().toUpperCase(Locale.ROOT);
        List<RawNewsItem> out = new ArrayList<>();
        for (ForumPost post : currentPool()) {
            if (post.isins().contains(wanted)) {
                out.add(withIsin(post.item(), wanted));
                if (out.size() >= limit) break;
            }
        }
        return List.copyOf(out);
    }

    /** The emitted item carries the QUERIED isin (a post may tag several). */
    private static RawNewsItem withIsin(RawNewsItem item, String isin) {
        return new RawNewsItem(item.uuid(), item.title(), item.publisher(), item.link(),
                item.publishedAt(), item.relatedTickers(), isin, item.summary(),
                item.sponsored(), item.imageUrl());
    }

    /**
     * The shared, TTL-cached pool. Synchronized so a burst of queries makes
     * exactly ONE feed request; a non-RSS or failed answer is never cached
     * (the next call retries), and an outage keeps the stale pool.
     */
    private synchronized List<ForumPost> currentPool() {
        CachedPool cached = pool;
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.posts();
        }
        try {
            WebResponse resp = fetcher.fetch(FEED_URL,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!looksLikeRss(body)) {
                    LOG.debug("Ariva forum feed answered a 200 that is not RSS — "
                            + "treating as a miss, not caching");
                    return stale(cached);
                }
                List<ForumPost> posts = parse(body);
                pool = new CachedPool(Instant.now(), posts);
                return posts;
            }
            LOG.debug("Ariva forum feed answered status {}",
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Ariva forum feed fetch failed: {}", e.getMessage());
        }
        return stale(cached);
    }

    /** An outage serves the stale pool rather than an empty answer. */
    private static List<ForumPost> stale(CachedPool cached) {
        return cached == null ? List.of() : cached.posts();
    }

    /** A 200 is only a feed when the body is actually RSS/XML — error shells are HTML. */
    static boolean looksLikeRss(String body) {
        if (body == null) return false;
        String head = body.stripLeading();
        if (head.length() > 512) head = head.substring(0, 512);
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.startsWith("<?xml") || lower.startsWith("<rss");
    }

    /**
     * RSS 2.0 items → {@link ForumPost}s, unfiltered (the pool caches the
     * whole feed; the ISIN filter is applied per query). Garbage yields
     * empty, never throws. Package-private for tests.
     *
     * <p><b>Description quirk (pinned live 2026-07-16):</b> Ariva embeds the
     * post body's HTML as RAW child elements ({@code <br/>}, the
     * {@code <b><i> -author</i></b>} marker) inside {@code <description>},
     * not as escaped entities — so the description subtree is re-serialised
     * into an innerHTML string (tags + text) and the author/HTML stripping
     * runs on that, exactly as it would on an escaped feed.
     */
    static List<ForumPost> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<ForumPost> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            boolean inDescription = false;
            String title = null, link = null, pubDate = null, description = null;
            List<String> isins = new ArrayList<>();
            StringBuilder descBuf = new StringBuilder();
            StringBuilder isinBuf = new StringBuilder();
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if (inDescription) {
                            // Raw HTML child (br, b, i, …) — re-serialise it.
                            descBuf.append('<').append(ln).append('>');
                            continue;
                        }
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = pubDate = description = null;
                            isins = new ArrayList<>();
                        }
                        if (inItem && "description".equals(ln)) {
                            inDescription = true;
                            descBuf.setLength(0);
                        }
                        if (inItem && "isin".equals(ln)) isinBuf.setLength(0);
                        current = inItem ? ln : null;
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (inDescription) {
                            descBuf.append(r.getText());
                            continue;
                        }
                        if (!inItem || current == null) continue;
                        String text = r.getText();
                        switch (current) {
                            case "title" -> title = append(title, text);
                            case "link" -> link = append(link, text);
                            case "pubDate" -> pubDate = append(pubDate, text);
                            case "isin" -> isinBuf.append(text);
                            default -> { /* ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String ln = r.getLocalName();
                        if (inDescription) {
                            if ("description".equals(ln)) {
                                inDescription = false;
                                description = descBuf.toString();
                            } else {
                                descBuf.append("</").append(ln).append('>');
                            }
                            continue;
                        }
                        if ("isin".equals(ln) && inItem) {
                            String isin = isinBuf.toString().strip().toUpperCase(Locale.ROOT);
                            if (!isin.isEmpty()) isins.add(isin);
                            isinBuf.setLength(0);
                        } else if ("item".equals(ln)) {
                            inItem = false;
                            ForumPost post = toPost(title, link, pubDate, description, isins);
                            if (post != null) out.add(post);
                        }
                        current = inItem ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("Ariva forum RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    /** One parsed {@code <item>} → a {@link ForumPost}, or null when incomplete. */
    private static ForumPost toPost(String title, String link, String pubDate,
                                    String description, List<String> isins) {
        if (title == null || title.isBlank() || link == null || link.isBlank()) return null;
        List<String> validIsins = isins.stream()
                .map(String::strip)
                .filter(s -> s.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]"))
                .distinct()
                .toList();
        String author = extractAuthor(description);
        String publisher = author == null
                ? PUBLISHER_PREFIX : PUBLISHER_PREFIX + " (" + author + ")";
        String body = stripHtml(stripAuthorMarker(description));
        String cleanLink = link.strip();
        RawNewsItem item = new RawNewsItem(
                cleanLink,
                title.strip(),
                publisher,
                cleanLink,
                parsePubDate(pubDate),
                List.of(),
                validIsins.isEmpty() ? null : validIsins.get(0),
                body == null || body.isBlank() ? null : body,
                false);
        return new ForumPost(item, validIsins);
    }

    /** The author from Ariva's trailing {@code <b><i> -name</i></b>} marker, or null. */
    static String extractAuthor(String descriptionHtml) {
        if (descriptionHtml == null) return null;
        Matcher m = AUTHOR_MARKER.matcher(descriptionHtml.strip());
        if (!m.find()) return null;
        String author = m.group(1).strip();
        return author.isEmpty() ? null : author;
    }

    /** The trailing author marker removed so it doesn't echo into the summary. */
    static String stripAuthorMarker(String descriptionHtml) {
        if (descriptionHtml == null) return null;
        return AUTHOR_MARKER.matcher(descriptionHtml.strip()).replaceFirst("");
    }

    /** The post body's HTML tags stripped, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Ariva pubDate ("16 Jul 2026 14:36:38 +0200" — RFC-1123 WITHOUT the
     * day-of-week token, English month names) → {@link Instant}; a stray
     * "Wed, "-prefixed variant is tolerated; unparseable → null.
     */
    static Instant parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        String s = pubDate.trim();
        int comma = s.indexOf(',');
        if (comma >= 0) s = s.substring(comma + 1).trim();
        try {
            return ZonedDateTime.parse(s, ARIVA_DATE).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** RFC-1123 without the day-of-week prefix, numeric offset, English months. */
    private static final DateTimeFormatter ARIVA_DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
