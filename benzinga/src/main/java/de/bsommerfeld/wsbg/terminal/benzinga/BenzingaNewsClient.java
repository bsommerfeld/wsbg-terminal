package de.bsommerfeld.wsbg.terminal.benzinga;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Benzinga newsdesk headlines via the keyless WordPress category feeds
 * {@code /news/feed} and {@code /markets/feed} plus the topic feed
 * {@code /topic/why-is-it-moving/feed} (all live-probed 2026-07-16, plain
 * client 200) — the fast US retail-facing wire: movers, analyst chatter,
 * pre-market radar lists, syndicated GlobeNewswire press releases, and the
 * "Why Is It Moving?" desk: short causal notes on sudden movers ("SNBR
 * crashes after hours: why"), heavy on exactly the small caps the cage
 * trades, each with the ticker in the headline. Deliberately NOT the bare
 * {@code /feed}: that carries the evergreen "price prediction" money-blog,
 * not the newsdesk; and NOT {@code /topic/wiim/feed}, which answers a VALID
 * but permanently empty feed (both probed 2026-07-16).
 *
 * <p><b>This source is a FIREHOSE, not a search</b> — but a ticker-tagged
 * one: article bodies embed machine-readable instrument links
 * ({@code data-ticker="INTC"} attributes and {@code benzinga.com/quote/…}
 * anchors), which are harvested into {@link RawNewsItem#relatedTickers()}.
 * So {@link #newsFor} answers by EXACT tag match (never fuzzy text), and
 * {@link #newsForName} applies the house precision filter against title +
 * the FULL delivered article text (the display teaser is capped, the search
 * text is not — a mention deep in the body still counts; mandate
 * 2026-07-16). Each feed (~15 items) is fetched, parsed and cached as a per-feed
 * POOL shared across all queries (10-min politeness TTL); a story syndicated
 * into both categories is deduplicated by its WordPress guid.
 * {@link #newsForIsin} stays a no-op: a US wire doesn't print ISINs.
 *
 * <p>Item fields (pinned live 2026-07-16): {@code <guid>} is the stable
 * WordPress id ("53295522 at https://www.benzinga.com"), {@code <pubDate>}
 * RFC-1123 with numeric offset, {@code <dc:creator>} the author (or "Globe
 * Newswire" on syndicated PRs — still Benzinga's wire, so the publisher
 * stays "Benzinga"), {@code <description>} is entity-encoded article HTML
 * (decoded once by the XML layer) with the ticker anchors inside; stripped
 * and capped into the teaser.
 */
@Singleton
public class BenzingaNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(BenzingaNewsClient.class);

    /** The newsdesk category feeds, live-probed 2026-07-16 (see class doc). */
    private static final List<String> FEEDS = List.of(
            "https://www.benzinga.com/news/feed",
            "https://www.benzinga.com/markets/feed",
            "https://www.benzinga.com/topic/why-is-it-moving/feed");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String PUBLISHER = "Benzinga";
    private static final int SUMMARY_CHARS = 600;

    /** Generic words that must never carry the relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    /** The machine-readable instrument tags inside the article HTML. */
    private static final Pattern DATA_TICKER =
            Pattern.compile("data-ticker=\"([A-Za-z0-9.-]{1,12})\"");
    private static final Pattern QUOTE_LINK =
            Pattern.compile("benzinga\\.com/quote/([A-Za-z0-9.-]{1,12})");

    /** Hardened StAX factory (XXE off — this is a remote feed), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** The shared pools: one parsed feed per category, refreshed at most once per TTL. */
    private final Map<String, CachedPool> pools = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedPool(Instant fetchedAt, List<WireItem> items) {}

    /** One story: the emitted item plus the searchable text (title + teaser). */
    record WireItem(RawNewsItem item, String searchText) {}

    /** Test/default: plain direct transport. */
    public BenzingaNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the category feeds answer a
     * bare client with no wall (live-probed 2026-07-16), so this client
     * declares "fine on plain HTTP" through the policy annotation like the
     * other keyless no-wall sources.
     */
    @Inject
    public BenzingaNewsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "benzinga";
    }

    /**
     * EXACT match against the harvested instrument tags — never fuzzy text
     * (a symbol like "AI" or "EV" in prose would false-positive constantly).
     * Exchange suffixes are cut ("BRK.B" queries as "BRK" would be wrong —
     * only the venue suffix after the first dot falls, matching the tags'
     * own US shapes).
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        String bare = bareSymbol(symbol);
        if (bare.isEmpty() || limit <= 0) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        for (WireItem w : currentPool()) {
            for (String t : w.item().relatedTickers()) {
                if (t.equalsIgnoreCase(bare)) {
                    out.add(w.item());
                    break;
                }
            }
        }
        return cap(out, limit);
    }

    /** No-op: a US wire doesn't print ISINs. */
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
        for (WireItem w : currentPool()) {
            if (textMatches(w.searchText(), words)) out.add(w.item());
        }
        return cap(out, limit);
    }

    /**
     * The union of both category pools, each TTL-cached, deduplicated by
     * WordPress guid (stories syndicate into both categories). Synchronized
     * so a burst of queries makes at most ONE request per feed; a non-RSS or
     * failed answer is never cached (the next call retries), and an outage
     * keeps serving that feed's stale pool without touching the other.
     */
    private synchronized List<WireItem> currentPool() {
        List<WireItem> union = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String feed : FEEDS) {
            for (WireItem w : feedPool(feed)) {
                if (seen.add(w.item().uuid())) union.add(w);
            }
        }
        return union;
    }

    private List<WireItem> feedPool(String url) {
        CachedPool cached = pools.get(url);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!looksLikeRss(body)) {
                    LOG.debug("Benzinga feed {} answered a 200 that is not RSS — "
                            + "treating as a miss, not caching", url);
                    return stale(cached);
                }
                List<WireItem> items = parse(body);
                pools.put(url, new CachedPool(Instant.now(), items));
                return items;
            }
            LOG.debug("Benzinga feed {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Benzinga feed {} fetch failed: {}", url, e.getMessage());
        }
        return stale(cached);
    }

    /** An outage serves the stale pool rather than an empty answer. */
    private static List<WireItem> stale(CachedPool cached) {
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
     * RSS 2.0 items → {@link WireItem}s, unfiltered (the pool caches the
     * whole feed; relevance is applied per query). Garbage yields empty,
     * never throws. Package-private for tests.
     */
    static List<WireItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        // Cloudflare appends an email-decode <script> AFTER </rss> (live
        // 2026-07-16) — a strict XML reader chokes on the trailer, so the
        // document ends where the feed ends.
        int end = xml.lastIndexOf("</rss>");
        if (end >= 0) xml = xml.substring(0, end + "</rss>".length());
        List<WireItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null, description = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = description = null;
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
                            default -> { /* dc:creator, category, content:encoded — ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("item".equals(r.getLocalName())) {
                            inItem = false;
                            WireItem item = toItem(title, link, guid, pubDate, description);
                            if (item != null) out.add(item);
                        }
                        current = inItem ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("Benzinga RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    /** One parsed {@code <item>} → a {@link WireItem}, or null when incomplete. */
    private static WireItem toItem(String title, String link, String guid,
                                   String pubDate, String description) {
        if (title == null || title.isBlank() || link == null || link.isBlank()) return null;
        String cleanTitle = decodeEntities(title).replaceAll("\\s+", " ").strip();
        // The description is the article HTML (the XML layer decoded the
        // entity armour once) — harvest the instrument tags BEFORE stripping.
        List<String> tickers = harvestTickers(description);
        String fullText = stripHtml(description);
        String teaser = truncate(fullText, SUMMARY_CHARS);
        String cleanLink = link.strip();
        RawNewsItem item = new RawNewsItem(
                guid != null && !guid.isBlank() ? guid.strip() : cleanLink,
                cleanTitle,
                PUBLISHER,
                cleanLink,
                parsePubDate(pubDate),
                tickers,
                null,
                teaser == null || teaser.isBlank() ? null : teaser,
                false);
        // The name matcher scans the FULL article text the feed delivers —
        // the capped teaser alone would lose a mention deep in the body
        // (mandate 2026-07-16: scan everything a source hands over).
        String searchText = fullText == null || fullText.isBlank()
                ? cleanTitle : cleanTitle + " " + fullText;
        return new WireItem(item, searchText);
    }

    /**
     * The machine-readable instrument tags of an article body:
     * {@code data-ticker} attributes plus {@code /quote/…} anchors, upper-cased,
     * first-seen order. Both patterns because either can appear alone.
     */
    static List<String> harvestTickers(String html) {
        if (html == null || html.isBlank()) return List.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Pattern p : List.of(DATA_TICKER, QUOTE_LINK)) {
            Matcher m = p.matcher(html);
            while (m.find()) out.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    /** The exchange suffix cut: the symbol part before the first dot, trimmed. */
    static String bareSymbol(String symbol) {
        if (symbol == null) return "";
        String s = symbol.strip();
        int dot = s.indexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        return s.strip();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max).stripTrailing() + "…";
    }

    /** Article HTML → plain teaser text: tags dropped, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        String s = html.replaceAll("<[^>]+>", " ");
        return decodeEntities(s).replaceAll("\\s+", " ").strip();
    }

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /** The entity set seen live (2026-07-16) plus generic numeric references. */
    static String decodeEntities(String s) {
        if (s == null) return null;
        String out = s
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#039;", "'")
                .replace("&nbsp;", " ");
        Matcher m = NUMERIC_ENTITY.matcher(out);
        if (m.find()) {
            StringBuilder sb = new StringBuilder();
            m.reset();
            while (m.find()) {
                try {
                    int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            new String(Character.toChars(cp))));
                } catch (Exception e) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                }
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out.replace("&amp;", "&"); // last, so "&amp;#8216;" decoded above stays right
    }

    /**
     * RFC-1123 pubDate ("Fri, 19 Jun 2026 02:22:20 +0000") → {@link Instant};
     * unparseable → null. Lenient about the day-of-week token (strict
     * RFC-1123 rejects a weekday that doesn't match the date — the date wins).
     */
    static Instant parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        String s = pubDate.trim();
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception strict) {
            int comma = s.indexOf(',');
            String noDow = comma >= 0 ? s.substring(comma + 1).trim() : s;
            for (DateTimeFormatter f : List.of(RFC_1123_NO_DOW, RFC_1123_NO_DOW_OFFSET)) {
                try {
                    return ZonedDateTime.parse(noDow, f).toInstant();
                } catch (Exception ignored) {
                    // try the next shape
                }
            }
            return null;
        }
    }

    /** RFC-1123 without the day-of-week prefix, English month names. */
    private static final DateTimeFormatter RFC_1123_NO_DOW =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    /** The same, with the numeric offset this feed actually emits ("+0000"). */
    private static final DateTimeFormatter RFC_1123_NO_DOW_OFFSET =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    /** True when the text carries at least one significant word of the queried name. */
    static boolean textMatches(String text, Set<String> nameWords) {
        if (nameWords.isEmpty()) return false;
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
