package de.bsommerfeld.wsbg.terminal.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * White House Presidential Actions (live-probed 2026-07-14):
 * {@code whitehouse.gov/presidential-actions/feed/} — a keyless WordPress RSS
 * carrying the FULL text of every executive order / proclamation / memorandum
 * (~580 KB for 30 items, hours faster than the Federal Register print).
 *
 * <p>Shape pinned by probe: each item's {@code content:encoded} opens with
 * ~9 KB of WP page chrome (topper + nav) and the actual prose starts right
 * after the LAST {@code </nav>}; the trailing "The post … appeared first on …"
 * paragraph is WP boilerplate. The {@code description} is a ~55-word excerpt
 * ending in "[…]" plus the same trailer — used as the fallback when an item
 * carries no encoded body. Parse is capped: an over-long body is cut at the
 * last complete {@code </item>} so StAX still sees well-formed XML.
 */
@Singleton
public class WhiteHouseActionsClient {

    private static final Logger LOG = LoggerFactory.getLogger(WhiteHouseActionsClient.class);

    private static final String FEED_URL = "https://www.whitehouse.gov/presidential-actions/feed/";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    /** The feed runs ~580 KB for 30 items; 2 MB is the runaway guard. */
    static final int MAX_PARSE_CHARS = 2_000_000;
    /** "first ~400 chars of text" — the excerpt cap. */
    static final int EXCERPT_CHARS = 400;

    private static final Pattern ITEM_BLOCK = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern ITEM_LINK = Pattern.compile("<link>\\s*(.*?)\\s*</link>");
    private static final Pattern ENCODED =
            Pattern.compile("<content:encoded><!\\[CDATA\\[(.*?)]]></content:encoded>", Pattern.DOTALL);
    private static final Pattern POST_TRAILER =
            Pattern.compile("The post .{0,300}? appeared first on .{0,80}?\\.\\s*$", Pattern.DOTALL);
    private static final Pattern NUMERIC_ENTITY =
            Pattern.compile("&#(?:(\\d{1,7})|[xX]([0-9a-fA-F]{1,6}));");

    /** One presidential action; {@code excerpt} = first ~400 chars of the action's own prose. */
    public record Action(String title, String link, Instant publishedAt, String excerpt) {
    }

    private record Cached(Instant at, List<Action> actions) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cache;

    /** Test/default: plain direct transport. */
    public WhiteHouseActionsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public WhiteHouseActionsClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The latest presidential actions, feed order (newest first). Empty on any failure. */
    public List<Action> actions(int limit) {
        Cached hit = cache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return cap(hit.actions(), limit);
        }
        try {
            WebResponse resp = fetcher.fetch(FEED_URL,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Action> actions = parse(resp.body());
                if (!actions.isEmpty()) {
                    cache = new Cached(Instant.now(), actions);
                }
                return cap(actions, limit);
            }
            LOG.debug("[WhiteHouse] feed answered status {}",
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[WhiteHouse] fetch failed: {}", e.getMessage());
        }
        // An outage keeps the stale pool instead of caching empty.
        return hit != null ? cap(hit.actions(), limit) : List.of();
    }

    /** Package-private for tests: feed XML → actions, network-free. */
    static List<Action> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        String capped = capFeed(xml);
        // link → full-text excerpt, extracted by regex (the shared Rss reader
        // deliberately ignores content:encoded).
        Map<String, String> excerpts = new HashMap<>();
        Matcher item = ITEM_BLOCK.matcher(capped);
        while (item.find()) {
            String block = item.group(1);
            Matcher link = ITEM_LINK.matcher(block);
            Matcher enc = ENCODED.matcher(block);
            if (link.find() && enc.find()) {
                String excerpt = extractExcerpt(enc.group(1));
                if (excerpt != null) excerpts.put(link.group(1), excerpt);
            }
        }
        List<Action> out = new ArrayList<>();
        for (Rss.Item it : Rss.parse(capped)) {
            String excerpt = excerpts.get(it.link());
            if (excerpt == null) {
                excerpt = stripTrailer(decodeNumericEntities(it.description()));
            }
            out.add(new Action(it.title(), it.link(), it.publishedAt(), excerpt));
        }
        return out;
    }

    /**
     * The action's own prose out of the WP-chromed {@code content:encoded}:
     * everything after the LAST {@code </nav>} (probe-pinned: exactly one per
     * item, prose right behind it), HTML-stripped, trailer-stripped, capped.
     */
    static String extractExcerpt(String encoded) {
        if (encoded == null) return null;
        int nav = encoded.lastIndexOf("</nav>");
        if (nav < 0) return null;
        // The excerpt needs ~400 text chars; 6 KB of markup is ample headroom.
        int end = Math.min(encoded.length(), nav + 6 + 6_000);
        String text = stripTrailer(
                decodeNumericEntities(Rss.stripHtml(encoded.substring(nav + 6, end))));
        if (text.isBlank()) return null;
        return text.length() > EXCERPT_CHARS ? text.substring(0, EXCERPT_CHARS).strip() : text;
    }

    /** WP full text is thick with numeric entities (&amp;#8217; curly quotes, &amp;#8230; ellipsis). */
    static String decodeNumericEntities(String s) {
        if (s == null || s.indexOf("&#") < 0) return s;
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            try {
                int cp = m.group(1) != null
                        ? Integer.parseInt(m.group(1))
                        : Integer.parseInt(m.group(2), 16);
                m.appendReplacement(out, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Exception e) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Drops WordPress's "The post … appeared first on …" boilerplate paragraph. */
    static String stripTrailer(String text) {
        if (text == null) return null;
        return POST_TRAILER.matcher(text).replaceFirst("").strip();
    }

    /** Cuts an over-long body at the last complete item so StAX still parses. */
    static String capFeed(String xml) {
        if (xml.length() <= MAX_PARSE_CHARS) return xml;
        int cut = xml.lastIndexOf("</item>", MAX_PARSE_CHARS);
        if (cut < 0) return xml.substring(0, MAX_PARSE_CHARS);
        return xml.substring(0, cut + "</item>".length()) + "</channel></rss>";
    }

    private static List<Action> cap(List<Action> list, int limit) {
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }
}
