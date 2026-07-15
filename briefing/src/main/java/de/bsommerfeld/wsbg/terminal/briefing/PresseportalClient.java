package de.bsommerfeld.wsbg.terminal.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dpa/OTS presseportal.de family — the civic-layer firehose (all feeds
 * keyless RSS 2.0, minutely-fresh, live-probed 2026-07-14). ONE client fronts
 * every presseportal channel through a {@link Channel} selector:
 *
 * <ul>
 *   <li>{@link Channel#BLAULICHT} — {@code /rss/polizei.rss2}: EVERY German
 *       police/fire press office (POL- / FW- prefixed titles), hundreds/day.</li>
 *   <li>the themed channels ({@link Channel#FINANZEN}, {@link Channel#POLITIK},
 *       {@link Channel#HANDEL}, {@link Channel#AUTO_VERKEHR},
 *       {@link Channel#GESUNDHEIT}, {@link Channel#NETZWELT},
 *       {@link Channel#PANORAMA}) — {@code /rss/<theme>.rss2}.</li>
 *   <li>{@link Channel#GENERAL} — {@code /rss/presseportal.rss2}: the corporate
 *       OTS firehose (carries non-listed firms + associations Yahoo/EQS miss).</li>
 *   <li>per-office by numeric id via {@link #office(String, int)} —
 *       {@code /rss/dienststelle_<id>.rss2}.</li>
 * </ul>
 *
 * <p>Every item carries the emitting office/company: Blaulicht titles are
 * prefixed {@code POL-<x>:} / {@code FW-<x>:} (the office token is parsed off
 * the title into {@link Item#office()}), and the RSS {@code <source>} element
 * carries the full office/company name where the feed ships it — the parser
 * prefers {@code <source>}, falling back to the title prefix.
 *
 * <p>Volume is high: per-channel politeness cache (10 min), parsed pool capped
 * at a generous runaway backstop. Best-effort — an outage keeps the stale pool.
 */
@Singleton
public class PresseportalClient {

    private static final Logger LOG = LoggerFactory.getLogger(PresseportalClient.class);

    private static final String BASE = "https://www.presseportal.de/rss/";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /** Runaway backstop, not curation — feeds carry up to ~150 items. */
    private static final int PER_CHANNEL_BACKSTOP = 200;

    /** A presseportal RSS channel; {@code slug} is the {@code <slug>.rss2} path token. */
    public enum Channel {
        BLAULICHT("polizei"),
        GENERAL("presseportal"),
        FINANZEN("finanzen"),
        POLITIK("politik"),
        HANDEL("handel"),
        AUTO_VERKEHR("auto-verkehr"),
        GESUNDHEIT("gesundheit-medizin"),
        NETZWELT("netzwelt"),
        PANORAMA("panorama");

        private final String slug;

        Channel(String slug) {
            this.slug = slug;
        }
    }

    /**
     * One press release: headline, teaser, link, the emitting office/company
     * ({@code office} — e.g. {@code "Polizeidirektion Neuwied/Rhein"} or the
     * bare {@code "POL-PDNR"} prefix when no {@code <source>}; null when neither
     * is present), and publish instant.
     */
    public record Item(String title, String teaser, String link, String office,
            Instant publishedAt) {
    }

    private record Cached(Instant at, List<Item> items) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public PresseportalClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — presseportal is wall-less. */
    @Inject
    public PresseportalClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The channel's current items, newest first, capped at {@code limit}. */
    public List<Item> channel(Channel channel, int limit) {
        return cap(fetch(BASE + channel.slug + ".rss2"), limit);
    }

    /** One police/fire office by its numeric presseportal id (dienststelle_&lt;id&gt;.rss2). */
    public List<Item> office(int officeId, int limit) {
        return cap(fetch(BASE + "dienststelle_" + officeId + ".rss2"), limit);
    }

    private List<Item> fetch(String url) {
        Cached hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.items();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Item> items = parse(resp.body());
                if (!items.isEmpty()) {
                    cache.put(url, new Cached(Instant.now(), items));
                }
                return items;
            }
            LOG.debug("[Presseportal] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Presseportal] {} failed: {}", url, e.getMessage());
        }
        return hit != null ? hit.items() : List.of();
    }

    /** Package-private for tests: presseportal RSS → items, network-free. */
    static List<Item> parse(String xml) {
        List<Item> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(xml)) {
            if (item.title() == null || item.title().isBlank()) continue;
            String title = item.title().strip();
            // <source> carries the full office/company; Rss doesn't read it, so
            // fall back to the POL-*/FW-* office token in the title prefix.
            String office = officePrefix(title);
            String teaser = item.description();
            if (teaser != null && teaser.length() > 300) teaser = teaser.substring(0, 300) + "…";
            out.add(new Item(title,
                    teaser == null || teaser.isBlank() ? null : teaser.strip(),
                    item.link() == null || item.link().isBlank() ? null : item.link().strip(),
                    office,
                    item.publishedAt()));
            if (out.size() >= PER_CHANNEL_BACKSTOP) {
                LOG.warn("[Presseportal] hit the {}-item runaway backstop", PER_CHANNEL_BACKSTOP);
                break;
            }
        }
        return out;
    }

    /**
     * The emitting office token from a Blaulicht/OTS title prefix
     * ({@code "POL-KA: …"} → {@code "POL-KA"}), or null when the title carries
     * no {@code <PREFIX>:} head.
     */
    static String officePrefix(String title) {
        int colon = title.indexOf(':');
        if (colon <= 0 || colon > 24) return null;
        String head = title.substring(0, colon).strip();
        // A real office prefix is a dashed token (POL-KA, FW-Schermbeck, POL-PDNR);
        // a plain "Firma: …" head has no dash and is not an office code.
        if (head.isEmpty() || head.indexOf('-') < 0) return null;
        return head;
    }

    private static List<Item> cap(List<Item> list, int limit) {
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }
}
