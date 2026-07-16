package de.bsommerfeld.wsbg.terminal.wallstreetonline;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * German retail-investor FORUM SENTIMENT via wallstreet-online's keyless
 * board RSS feeds ({@code /rss/board-<slug>.xml}) — the board-talk leg for
 * Germany's LARGEST finance community: what German retail is discussing
 * about a stock RIGHT NOW, at minute cadence during trading hours
 * (live-probed 2026-07-16, plain client 200; the most active board turned
 * its whole 20-item window over in under three hours). WSO's own /rss page
 * advertises the feeds for exactly this embed use, so headline/author RSS
 * consumption is squarely inside the house's re-express-never-reproduce
 * line; post bodies stay out.
 *
 * <p><b>This source is a FIREHOSE over a small fixed set of boards, not a
 * search:</b> WSO offers ~78 per-board feeds but no per-instrument feed, so
 * the handful of broad, high-churn EQUITY boards (hot stocks, German stocks
 * in focus, German small caps, US hot stocks — the boards where company
 * threads actually live, picked from the live /rss index 2026-07-16) are
 * each fetched at most once per TTL and united into ONE pool shared across
 * all queries. {@link #newsForName} filters that pool by title relevance
 * exactly like GoogleNewsClient (significant words, umlaut-tolerant,
 * precision over recall) — viable here because WSO items are THREAD titles,
 * not post bodies, and thread titles name the company ("OHB Technology AG:
 * Kaufempfehlungen!"). {@link #newsFor} and {@link #newsForIsin} stay
 * no-ops: the items tag neither tickers nor ISINs.
 *
 * <p><b>These are forum threads, not articles</b> — user opinion, not
 * verified fact (house rule: user numbers are sentiment). The title is the
 * THREAD title (so a match means "German retail is talking about X in this
 * thread right now", the freshest post merely bumped it), the author of the
 * newest post rides in the publisher ("wallstreet-online Forum (name)") so
 * the model sees attribution without mistaking the venue for a newsroom.
 *
 * <p>Item fields (pinned live 2026-07-16): everything is CDATA-wrapped;
 * {@code <title>} = thread title, {@code <description>} = literally
 * {@code "Autor: <username>"} (NO post body — nothing to summarise),
 * {@code <link>} = thread deep link with a {@code #beitrag_<postId>} anchor
 * (unique per post — the uuid, so the same thread re-surfaces as new posts
 * land), {@code <dc:date>} = ISO-8601 with offset
 * ({@code 2026-07-16T14:48:34+02:00}) — NOT RFC-1123, there is no
 * {@code <pubDate>} at all.
 *
 * <p><b>The soft-200 trap, WSO flavour (pinned 2026-07-16):</b> an UNKNOWN
 * {@code /rss/board-*.xml} slug answers 200 with a perfectly VALID RSS feed
 * of some default board's items — so even "200 + parses as RSS" proves
 * nothing about a slug. Defense: the slugs here are pinned verbatim from
 * the live {@code /rss} index, never guessed; and genuinely broken answers
 * (HTML error shells) are still content-validated and treated as
 * never-cached misses.
 */
@Singleton
public class WsoBoardRssClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(WsoBoardRssClient.class);

    /**
     * The broad, high-churn equity boards, pinned verbatim from the live
     * {@code /rss} index (2026-07-16) — never guess a slug, unknown slugs
     * soft-200 into a default board's feed. Package-private for tests.
     */
    static final List<String> BOARD_FEEDS = List.of(
            "https://www.wallstreet-online.de/rss/board-hot-stocks.xml",
            "https://www.wallstreet-online.de/rss/board-deutsche-aktien-im-fokus.xml",
            "https://www.wallstreet-online.de/rss/board-nebenwerte-deutschland.xml",
            "https://www.wallstreet-online.de/rss/board-us-hotstocks.xml");

    /** Each feed holds only the 20 newest posts and churns fast — short TTL. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String PUBLISHER_PREFIX = "wallstreet-online Forum";

    /** The feed's whole description is the author marker: {@code "Autor: <name>"}. */
    private static final Pattern AUTHOR_MARKER =
            Pattern.compile("^\\s*Autor:\\s*(.+?)\\s*$", Pattern.DOTALL);

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    /** Hardened StAX factory (XXE off — these are remote feeds), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Per-feed caches: each board is fetched at most once per TTL, independently. */
    private final Map<String, CachedFeed> feedCache = new HashMap<>();

    private record CachedFeed(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public WsoBoardRssClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the board feeds answer a
     * bare client with no wall (live-probed 2026-07-16), so this client
     * declares "fine on plain HTTP" like the other keyless no-wall sources.
     */
    @Inject
    public WsoBoardRssClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "wso-board-rss";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /** No-op: the feeds tag no tickers — companies only surface by thread title. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** No-op: the feeds tag no ISINs — companies only surface by thread title. */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        for (RawNewsItem item : currentPool()) {
            if (titleMatches(item.title(), words)) out.add(item);
        }
        return cap(out, limit);
    }

    /**
     * The united pool over all board feeds, each refreshed at most once per
     * TTL. Synchronized so a burst of queries makes at most ONE request per
     * feed; a non-RSS or failed answer is never cached (the next call
     * retries that feed), and an outage on one board keeps its stale items
     * without touching the others. The union is de-duplicated by uuid (the
     * post anchor) and sorted newest-first so a limit caps the freshest
     * chatter, not one board's tail.
     */
    private synchronized List<RawNewsItem> currentPool() {
        List<RawNewsItem> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String feedUrl : BOARD_FEEDS) {
            for (RawNewsItem item : feedItems(feedUrl)) {
                if (seen.add(item.uuid())) merged.add(item);
            }
        }
        merged.sort(Comparator.comparing(RawNewsItem::publishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return merged;
    }

    /** One board's items, from cache when fresh, refetched when the TTL lapsed. */
    private List<RawNewsItem> feedItems(String feedUrl) {
        CachedFeed cached = feedCache.get(feedUrl);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items();
        }
        try {
            WebResponse resp = fetcher.fetch(feedUrl,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!looksLikeRss(body)) {
                    // The soft-200 trap: error shells answer 200-shaped HTML —
                    // a status proves nothing, only content does.
                    LOG.debug("WSO board feed {} answered a 200 that is not RSS "
                            + "(soft-200 trap) — treating as a miss, not caching", feedUrl);
                    return stale(cached);
                }
                List<RawNewsItem> items = parse(body);
                feedCache.put(feedUrl, new CachedFeed(Instant.now(), items));
                return items;
            }
            LOG.debug("WSO board feed {} answered status {}", feedUrl,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("WSO board feed {} fetch failed: {}", feedUrl, e.getMessage());
        }
        return stale(cached);
    }

    /** An outage serves the feed's stale items rather than an empty answer. */
    private static List<RawNewsItem> stale(CachedFeed cached) {
        return cached == null ? List.of() : cached.items();
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
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
     * RSS 2.0 items → {@link RawNewsItem}s, unfiltered (the pool caches whole
     * feeds; relevance is applied per query). Every field arrives CDATA-wrapped
     * (pinned live 2026-07-16). Garbage yields empty, never throws.
     * Package-private for tests.
     */
    static List<RawNewsItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, date = null, description = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = date = description = null;
                        }
                        current = inItem ? ln : null;
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (!inItem || current == null) continue;
                        String text = r.getText();
                        switch (current) {
                            case "title" -> title = append(title, text);
                            case "link" -> link = append(link, text);
                            // dc:date — the only timestamp this feed carries.
                            case "date" -> date = append(date, text);
                            case "description" -> description = append(description, text);
                            default -> { /* ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("item".equals(r.getLocalName())) {
                            inItem = false;
                            RawNewsItem item = toItem(title, link, date, description);
                            if (item != null) out.add(item);
                        }
                        current = inItem ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("WSO board RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    /** One parsed {@code <item>} → a {@link RawNewsItem}, or null when incomplete. */
    private static RawNewsItem toItem(String title, String link, String date,
                                      String description) {
        if (title == null || title.isBlank() || link == null || link.isBlank()) return null;
        String author = extractAuthor(description);
        String publisher = author == null
                ? PUBLISHER_PREFIX : PUBLISHER_PREFIX + " (" + author + ")";
        String cleanLink = link.strip();
        return new RawNewsItem(
                cleanLink,
                title.strip(),
                publisher,
                cleanLink,
                parseDate(date),
                List.of(),
                null,
                // The description is ONLY the author marker — no body, no summary.
                null,
                false);
    }

    /** The author from the feed's {@code "Autor: <name>"} description, or null. */
    static String extractAuthor(String description) {
        if (description == null) return null;
        Matcher m = AUTHOR_MARKER.matcher(description);
        if (!m.find()) return null;
        String author = m.group(1).strip();
        return author.isEmpty() ? null : author;
    }

    /**
     * {@code dc:date} ("2026-07-16T14:48:34+02:00" — ISO-8601 with offset,
     * pinned live 2026-07-16; this feed has NO {@code <pubDate>}) →
     * {@link Instant}; unparseable → null, never a guessed timestamp.
     */
    static Instant parseDate(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            return OffsetDateTime.parse(date.trim()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the title carries at least one significant word of the queried name. */
    static boolean titleMatches(String title, Set<String> nameWords) {
        if (nameWords.isEmpty()) return false;
        String t = normalize(title);
        for (String w : nameWords) {
            if (t.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        if (name == null || name.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
