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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The general market press review — a CATALOG of keyless market/economy RSS
 * feeds across the Atlantic (all live-probed 2026-07-14, plain client 200 +
 * fresh same-day items), fetched as one pooled sweep. This is the
 * Wetterbericht's "why did the tape move" layer: a timed press headline
 * ("Consumer prices rose 3.5% annually in June…", 15:45) lands in the day-part
 * window it broke in, so the model can attach a sector move to its reported
 * cause instead of speculating — and it makes the report viable on a day the
 * cage is silent.
 *
 * <p>Feeds (each best-effort on its own; one failing never empties the sweep):
 * CNBC Markets/Economy/Technology (fresh to the minute), MarketWatch Top
 * Stories (the MarketPulse sibling feed is STALE — items months old under a
 * current lastBuildDate, probed 2026-07-14 — and deliberately absent), WSJ
 * Markets (headlines keyless, articles paywalled — the title is the value),
 * Investing.com stock-market news (Reuters syndication; zone-less pubDates,
 * handled by {@link Rss#parseDate}), the flagship wires (probed 2026-07-16):
 * Bloomberg Markets/Economics vertical RSS, Reuters via its Arc news-sitemap
 * (the one keyless door — classic RSS is dead; parsed by {@link NewsSitemap},
 * the desk read from the article URL's path), FT international homepage RSS,
 * and the German desk: n-tv Wirtschaft, Spiegel Wirtschaft, Handelsblatt and
 * WiWo Schlagzeilen (both 301 onto their feeds.cms.* hosts — the transport
 * follows).
 *
 * <p>Whole-sweep politeness cache (10 min); an outage keeps the stale pool.
 * Items carry the FEED's category tag (US_MARKETS/US_ECONOMY/US_TECH/DE) so
 * the material formatter can label the desk a headline came from.
 */
@Singleton
public class MarketPressClient {

    private static final Logger LOG = LoggerFactory.getLogger(MarketPressClient.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /**
     * Pure runaway backstop, NOT a curation cap (user mandate 2026-07-14:
     * nothing is lost to fixed caps — the model decides relevance item by
     * item). The catalogued feeds carry ~20-100 items each; a feed answering
     * more than this is malfunctioning.
     */
    private static final int PER_FEED_BACKSTOP = 100;

    /** One catalogued feed. Category groups feeds by desk for the material label. */
    record Feed(String source, String category, String url) {}

    static final List<Feed> CATALOG = List.of(
            new Feed("CNBC", "US_MARKETS",
                    "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10000664"),
            new Feed("CNBC", "US_ECONOMY",
                    "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258"),
            new Feed("CNBC", "US_TECH",
                    "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=19854910"),
            new Feed("MarketWatch", "US_MARKETS",
                    "https://feeds.content.dowjones.io/public/rss/mw_topstories"),
            new Feed("WSJ", "US_MARKETS",
                    "https://feeds.content.dowjones.io/public/rss/RSSMarketsMain"),
            new Feed("Investing.com", "US_MARKETS",
                    "https://www.investing.com/rss/news_25.rss"),
            new Feed("Bloomberg", "US_MARKETS",
                    "https://feeds.bloomberg.com/markets/news.rss"),
            new Feed("Bloomberg", "US_ECONOMY",
                    "https://feeds.bloomberg.com/economics/news.rss"),
            // The catalog category is the FALLBACK desk: the sitemap branch
            // reads the actual desk from each article URL's path.
            new Feed("Reuters", "WORLD",
                    "https://www.reuters.com/arc/outboundfeeds/news-sitemap/?outputType=xml"),
            new Feed("FT", "WORLD",
                    "https://www.ft.com/rss/home"),
            new Feed("n-tv", "DE",
                    "https://www.n-tv.de/wirtschaft/rss"),
            new Feed("Spiegel", "DE",
                    "https://www.spiegel.de/wirtschaft/index.rss"),
            new Feed("Handelsblatt", "DE",
                    "https://www.handelsblatt.com/contentexport/feed/schlagzeilen"),
            new Feed("WiWo", "DE",
                    "https://www.wiwo.de/contentexport/feed/rss/schlagzeilen"));

    /**
     * One press headline: title (the figure/cause usually sits right in it) +
     * teaser head; {@code link} is the article URL for the re-knock full-text
     * read (null when the feed carried none).
     */
    public record PressHeadline(String title, String teaser, String source, String category,
            Instant publishedAt, String link) {}

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    private volatile CachedSweep sweep;

    private record CachedSweep(Instant fetchedAt, List<PressHeadline> items) {}

    /** Test/default: plain direct transport. */
    public MarketPressClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — every catalogued feed is wall-less. */
    @Inject
    public MarketPressClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Headlines published at or after {@code since}, all feeds merged, newest
     * first, title-deduplicated across feeds (syndication repeats the same
     * story), capped. Timestamp-less items are dropped — a press review lives
     * on WHEN a story broke.
     */
    public List<PressHeadline> headlinesSince(Instant since, int limit) {
        List<PressHeadline> all = currentSweep();
        List<PressHeadline> out = new ArrayList<>();
        Set<String> seenTitles = new HashSet<>();
        for (PressHeadline h : all) {
            if (h.publishedAt() == null || h.publishedAt().isBefore(since)) continue;
            if (!seenTitles.add(normalizeTitle(h.title()))) continue;
            out.add(h);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** The pooled sweep, refreshed at most once per TTL; an outage keeps the stale pool. */
    private synchronized List<PressHeadline> currentSweep() {
        CachedSweep cached = sweep;
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items();
        }
        List<PressHeadline> all = new ArrayList<>();
        int feedsAnswered = 0;
        for (Feed feed : CATALOG) {
            List<PressHeadline> items = parseFeed(feed, get(feed.url()));
            if (!items.isEmpty()) feedsAnswered++;
            all.addAll(items);
        }
        if (all.isEmpty()) {
            LOG.debug("[MarketPress] sweep answered nothing — keeping the stale pool");
            return cached == null ? List.of() : cached.items();
        }
        all.sort((a, b) -> {
            Instant ia = a.publishedAt() == null ? Instant.EPOCH : a.publishedAt();
            Instant ib = b.publishedAt() == null ? Instant.EPOCH : b.publishedAt();
            return ib.compareTo(ia);
        });
        LOG.debug("[MarketPress] sweep: {} items from {}/{} feeds",
                all.size(), feedsAnswered, CATALOG.size());
        sweep = new CachedSweep(Instant.now(), all);
        return all;
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[MarketPress] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[MarketPress] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Package-private for tests: one feed's XML → capped, tagged headlines. */
    static List<PressHeadline> parseFeed(Feed feed, String xml) {
        if (xml != null && xml.contains("<urlset")) return parseSitemapFeed(feed, xml);
        List<PressHeadline> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(xml)) {
            String teaser = item.description();
            if (teaser != null && teaser.length() > 200) teaser = teaser.substring(0, 200) + "…";
            out.add(new PressHeadline(item.title(), teaser == null || teaser.isBlank() ? null : teaser,
                    feed.source(), feed.category(), item.publishedAt(),
                    item.link() == null || item.link().isBlank() ? null : item.link().strip()));
            if (out.size() >= PER_FEED_BACKSTOP) {
                LOG.warn("[MarketPress] feed {} hit the {}-item runaway backstop",
                        feed.url(), PER_FEED_BACKSTOP);
                break;
            }
        }
        return out;
    }

    /**
     * The Google-News-sitemap branch (Reuters Arc): headline + instant, no
     * teaser, the desk derived from the article URL's path — /business/ and
     * /markets/ report the tape, /technology/ the tech desk, everything else
     * (world, sports, the non-English desks) keeps the feed's fallback
     * category. Ingestion stays wide (house principle) — the model judges
     * relevance, the desk label only tells it where a headline broke.
     */
    private static List<PressHeadline> parseSitemapFeed(Feed feed, String xml) {
        List<PressHeadline> out = new ArrayList<>();
        for (NewsSitemap.Item item : NewsSitemap.parse(xml)) {
            out.add(new PressHeadline(item.title(), null,
                    feed.source(), sitemapCategory(item.loc(), feed.category()),
                    item.publishedAt(),
                    item.loc().isBlank() ? null : item.loc()));
            if (out.size() >= PER_FEED_BACKSTOP) {
                LOG.warn("[MarketPress] feed {} hit the {}-item runaway backstop",
                        feed.url(), PER_FEED_BACKSTOP);
                break;
            }
        }
        return out;
    }

    /** The desk from a wire article URL's first path segment. */
    static String sitemapCategory(String loc, String fallback) {
        String l = loc == null ? "" : loc.toLowerCase(Locale.ROOT);
        if (l.contains("/business/") || l.contains("/markets/")) return "US_MARKETS";
        if (l.contains("/technology/")) return "US_TECH";
        return fallback;
    }

    /** Cross-feed dedupe key: lowercased, punctuation-less title. */
    static String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
