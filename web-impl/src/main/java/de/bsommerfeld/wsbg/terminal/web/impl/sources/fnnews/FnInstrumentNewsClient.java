package de.bsommerfeld.wsbg.terminal.web.impl.sources.fnnews;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.impl.feed.FeedParser;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * finanznachrichten.de's per-instrument feed as the ISIN-addressed German news
 * leg: the densest per-stock German news aggregate anywhere (dpa-AFX,
 * dpa-AFX-Analyser, EQS, IT-Times, boersennews …), keyless, no bot wall
 * (probed 2026-07-13). The URL keys on the ISIN ALONE — the name slug is a
 * dummy token: {@code rss-x-aktien-<isin-lowercase>} answers 200 + RSS for a
 * covered ISIN and a 301 to the homepage for an unknown one (NEVER a 404 — a
 * followed redirect looks like a 200 HTML page, so the client gates on the
 * body actually being RSS).
 *
 * <p>ISIN-addressed only; symbol and name keys are ignored. {@code pubDate} is
 * ISO-8601, not RFC-1123 (the FN house quirk the ad-hoc feeds share), which the
 * house {@link FeedParser} reads natively. The queried ISIN is stamped onto
 * every item — the feed IS the instrument's own. Per-ISIN politeness cache
 * (the feed declares max-age=90; we stay far above), misses cached too.
 */
@Singleton
public class FnInstrumentNewsClient extends AbstractWebSource implements InstrumentSource {

    private static final Logger LOG = LoggerFactory.getLogger(FnInstrumentNewsClient.class);

    private static final String FEED_URL = "https://www.finanznachrichten.de/rss-x-aktien-";
    static final String PUBLISHER = "finanznachrichten.de";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private record CachedResult(Instant fetchedAt, List<Article> items) {
    }

    /** Per-ISIN politeness cache — a burst on the same ISIN costs one fetch. */
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    /** The feed has no wall — direct first (the old {@code @DirectFirst} seam). */
    @Inject
    public FnInstrumentNewsClient(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "fn-news";
    }

    /** German aggregator - German language, German press sphere. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("de", "DE");
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (limit <= 0 || instrument.isin().isEmpty()) return List.of();
        String isin = instrument.isin().get().value();
        String key = isinKey(isin);
        if (key == null) return List.of();

        CachedResult cached = cache.get(key);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cap(cached.items(), limit);
        }
        try {
            WebResponse resp = get(FEED_URL + key,
                    Map.of("Accept", "application/rss+xml, application/xml, text/xml"),
                    REQUEST_TIMEOUT);
            // An unknown ISIN 301s to the homepage — with redirects followed
            // that is a 200-shaped HTML page, so the RSS gate is the body.
            if (resp.status() == 200 && FeedParser.looksLikeFeed(resp.body())) {
                List<Article> items = stampIsin(
                        FeedParser.parse(resp.body(), PUBLISHER),
                        isin.toUpperCase(Locale.ROOT));
                cache.put(key, new CachedResult(Instant.now(), items));
                return cap(items, limit);
            }
            LOG.debug("fn-news feed for '{}' answered status {} (rss={})", key,
                    resp.status(), FeedParser.looksLikeFeed(resp.body()));
            cache.put(key, new CachedResult(Instant.now(), List.of()));
        } catch (Exception e) {
            LOG.debug("fn-news feed failed for '{}': {}", key, e.getMessage());
        }
        return List.of();
    }

    /** The queried ISIN stamped onto every item — the feed is the instrument's own. */
    private static List<Article> stampIsin(List<Article> items, String isin) {
        List<Article> out = new ArrayList<>(items.size());
        for (Article it : items) {
            out.add(new Article(it.uuid(), it.title(), it.publisher(), it.link(),
                    it.publishedAt(), it.relatedTickers(), isin, it.summary(),
                    it.sponsored(), it.imageUrl()));
        }
        return List.copyOf(out);
    }

    /** ISIN shape (2 letters + 10 alphanumerics), lowercased for the URL. */
    static String isinKey(String isin) {
        if (isin == null) return null;
        String s = isin.strip();
        if (s.length() != 12) return null;
        if (!Character.isLetter(s.charAt(0)) || !Character.isLetter(s.charAt(1))) return null;
        for (int i = 2; i < 12; i++) {
            if (!Character.isLetterOrDigit(s.charAt(i))) return null;
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static List<Article> cap(List<Article> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }
}
