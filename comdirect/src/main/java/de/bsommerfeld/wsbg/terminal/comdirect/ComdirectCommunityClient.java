package de.bsommerfeld.wsbg.terminal.comdirect;

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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The comdirect community ({@code community.comdirect.de}, Khoros/Lithium) —
 * Germany's largest broker forum — as SENTIMENT EVIDENCE: what German retail
 * investors are actually asking, complaining and speculating about, by name.
 * The content is service- and tax-heavy (Depot mechanics, spin-off cost
 * bases, dividend bookings), and exactly THAT is the value: a company
 * surfacing here is a company German private investors are touching with
 * real money right now — the German {@code Anleger} echo the US-centric
 * legs cannot hear.
 *
 * <p><b>The RSS route is the ONLY way past the Cloudflare wall (pinned
 * 2026-07-16):</b> every HTML page of the community answers a CF challenge
 * (403 "Just a moment..."), but the Khoros-native RSS endpoints
 * ({@code /rss/board?board.id=<id>}) answer a bare client 200 with full
 * post HTML in the {@code description}. Do not "improve" this client onto
 * the HTML pages.
 *
 * <p><b>Boards (live-verified 2026-07-16 via the anonymous Khoros LiQL API
 * {@code /api/2.0/search?q=SELECT id FROM boards}):</b> the community has 15
 * boards; the four finance-relevant ACTIVE ones are pooled here.
 * {@code Brokerboard} exists but is dead (newest thread 2021) and is
 * deliberately excluded; the rest is Willkommen/Ideen/Off-Topic noise.
 *
 * <p><b>This source is a FIREHOSE, not a search:</b> each board's ~20-thread
 * feed is fetched at most once per TTL (10 min — the forum is low-frequency)
 * and cached as a shared pool; {@link #newsForName} filters that pool by
 * name relevance against the TITLE AND the description text — forum threads
 * often bury the instrument in the body ("Frage zu Spin-off XY" with the
 * names only in the post). Precision over recall, exactly like the other
 * firehose legs. {@link #newsFor} and {@link #newsForIsin} stay no-ops:
 * the feed tags neither tickers nor ISINs.
 *
 * <p><b>The soft-200 traps (pinned 2026-07-16):</b> a CF challenge can also
 * arrive 200-shaped HTML — never a feed, so the body is validated as RSS
 * before parsing. AND: an unknown/removed {@code board.id} answers a 200
 * with a VALID RSS skeleton whose channel is titled "Ressource nicht
 * gefunden" and carries zero items — so an empty parse is treated as a miss
 * and never cached (the stale pool survives a transient glitch).
 *
 * <p>Item fields (pinned live 2026-07-16): {@code <title>} is the thread
 * title, {@code <link>} == {@code <guid>} is the thread-message permalink,
 * {@code <description>} is the full first-post HTML (entity-ESCAPED, not
 * CDATA), {@code <pubDate>} is RFC-1123 with a {@code GMT} zone (twin
 * {@code <dc:date>} carries the same instant in ISO), {@code <dc:creator>}
 * is the forum author on every item.
 */
@Singleton
public class ComdirectCommunityClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(ComdirectCommunityClient.class);

    private static final String FEED_URL_PREFIX =
            "https://community.comdirect.de/rss/board?board.id=";

    /**
     * The finance-relevant ACTIVE boards, live-verified 2026-07-16 against
     * the full Khoros board catalog (see class Javadoc). Order is cosmetic —
     * results are merged and sorted by recency.
     */
    static final List<String> BOARDS = List.of(
            "WertpapiereAnlage",      // Wertpapiere & Anlage — the core board
            "CFD-Board",              // CFD
            "KontoKarte",             // Konto, Depot & Karte — Depot mechanics
            "VorsorgeFinanzierung");  // Vorsorge & Finanzierung — ETF/Vorsorge

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String PUBLISHER = "comdirect Community";
    private static final int SUMMARY_MAX = 500;

    /** Generic words that must never carry the relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    /** Hardened StAX factory (XXE off — this is a remote feed), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Per-board pools, each refreshed at most once per TTL. */
    private final Map<String, CachedPool> pools = new ConcurrentHashMap<>();

    private record CachedPool(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public ComdirectCommunityClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the RSS endpoints answer a
     * bare client with no wall (live-probed 2026-07-16; ONLY the HTML pages
     * sit behind Cloudflare), so this client declares "fine on plain HTTP"
     * through the policy annotation like the other keyless no-wall sources.
     */
    @Inject
    public ComdirectCommunityClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "comdirect-community";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /** No-op: the forum tags no tickers — companies only surface by name. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** No-op: the forum tags no ISINs — companies only surface by name. */
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
        for (String board : BOARDS) {
            for (RawNewsItem item : boardPool(board)) {
                if (matches(item, words)) out.add(item);
            }
        }
        out.sort(Comparator.comparing(RawNewsItem::publishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return cap(out, limit);
    }

    /**
     * One board's TTL-cached pool. Synchronized per client so a burst of
     * queries makes at most ONE request per board; a non-RSS, empty or
     * failed answer is never cached (the next call retries), and an outage
     * keeps the stale pool.
     */
    private synchronized List<RawNewsItem> boardPool(String board) {
        CachedPool cached = pools.get(board);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items();
        }
        try {
            WebResponse resp = fetcher.fetch(FEED_URL_PREFIX + board,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!looksLikeRss(body)) {
                    // The soft-200 trap: a Cloudflare challenge can arrive
                    // 200-shaped HTML — a status proves nothing, only content does.
                    LOG.debug("comdirect community board {} answered a 200 that is "
                            + "not RSS (CF challenge?) — a miss, not caching", board);
                    return stale(cached);
                }
                List<RawNewsItem> items = parse(body);
                if (items.isEmpty()) {
                    // The SECOND soft-200 trap: unknown board ids answer a VALID
                    // RSS skeleton ("Ressource nicht gefunden", zero items) —
                    // an empty parse is a miss too, never cached.
                    LOG.debug("comdirect community board {} answered RSS with no "
                            + "items (error channel?) — a miss, not caching", board);
                    return stale(cached);
                }
                pools.put(board, new CachedPool(Instant.now(), items));
                return items;
            }
            LOG.debug("comdirect community board {} answered status {}",
                    board, resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("comdirect community board {} fetch failed: {}", board, e.getMessage());
        }
        return stale(cached);
    }

    /** An outage serves the stale pool rather than an empty answer. */
    private static List<RawNewsItem> stale(CachedPool cached) {
        return cached == null ? List.of() : cached.items();
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /** A 200 is only a feed when the body is actually RSS/XML — CF challenges are HTML. */
    static boolean looksLikeRss(String body) {
        if (body == null) return false;
        String head = body.stripLeading();
        if (head.length() > 512) head = head.substring(0, 512);
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.startsWith("<?xml") || lower.startsWith("<rss");
    }

    /**
     * Khoros RSS 2.0 items → {@link RawNewsItem}s, unfiltered (the pool
     * caches the whole feed; relevance is applied per query). Garbage yields
     * empty, never throws. Package-private for tests.
     */
    static List<RawNewsItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null;
            String description = null, creator = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = description = creator = null;
                        }
                        current = inItem ? ln : null;
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (!inItem || current == null) continue;
                        String text = r.getText();
                        switch (current) {
                            case "title" -> title = append(title, text);
                            case "link" -> link = append(link, text);
                            case "guid" -> guid = append(guid, text);
                            case "pubDate" -> pubDate = append(pubDate, text);
                            case "description" -> description = append(description, text);
                            // dc:creator — the forum author.
                            case "creator" -> creator = append(creator, text);
                            default -> { /* dc:date twin etc. — ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("item".equals(r.getLocalName())) {
                            inItem = false;
                            RawNewsItem item =
                                    toItem(title, link, guid, pubDate, description, creator);
                            if (item != null) out.add(item);
                        }
                        current = inItem ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("comdirect community RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    /** One parsed {@code <item>} → a {@link RawNewsItem}, or null when incomplete. */
    private static RawNewsItem toItem(String title, String link, String guid,
                                      String pubDate, String description, String creator) {
        if (title == null || title.isBlank() || link == null || link.isBlank()) return null;
        String publisher = creator == null || creator.isBlank()
                ? PUBLISHER : PUBLISHER + " (" + creator.strip() + ")";
        String cleanLink = link.strip();
        String post = capSummary(stripHtml(description));
        return new RawNewsItem(
                guid != null && !guid.isBlank() ? guid.strip() : cleanLink,
                title.strip(),
                publisher,
                cleanLink,
                parsePubDate(pubDate),
                List.of(),
                null,
                post == null || post.isBlank() ? null : post,
                false);
    }

    /**
     * The description is the FULL first post — the summary keeps the lead
     * (~{@value #SUMMARY_MAX} chars), which carries the ask; forum posts
     * front-load the instrument and the problem.
     */
    static String capSummary(String text) {
        if (text == null || text.length() <= SUMMARY_MAX) return text;
        return text.substring(0, SUMMARY_MAX).stripTrailing() + "…";
    }

    /** The post's HTML tags stripped, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * RFC-1123 pubDate ("Thu, 16 Jul 2026 03:12:12 GMT") → {@link Instant};
     * unparseable → null. Lenient about the day-of-week token (strict
     * RFC-1123 rejects a weekday that doesn't match the date — the date wins).
     */
    static Instant parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        String s = pubDate.trim();
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception strict) {
            try {
                int comma = s.indexOf(',');
                return ZonedDateTime.parse(comma >= 0 ? s.substring(comma + 1).trim() : s,
                        RFC_1123_NO_DOW).toInstant();
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** RFC-1123 without the day-of-week prefix, English month names. */
    private static final DateTimeFormatter RFC_1123_NO_DOW =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    /**
     * True when the thread TITLE or the post body (the already-stripped
     * summary) carries a significant word of the queried name — forum
     * threads often bury the instrument in the body.
     */
    static boolean matches(RawNewsItem item, Set<String> nameWords) {
        if (textMatches(item.title(), nameWords)) return true;
        return item.summary() != null && textMatches(item.summary(), nameWords);
    }

    /** True when the text carries at least one significant word of the queried name. */
    static boolean textMatches(String text, Set<String> nameWords) {
        if (text == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (t.matches("(?s).*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        if (name == null || name.isBlank()) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
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
