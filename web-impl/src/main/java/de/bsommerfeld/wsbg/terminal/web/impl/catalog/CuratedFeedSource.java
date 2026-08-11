package de.bsommerfeld.wsbg.terminal.web.impl.catalog;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.impl.feed.FeedParser;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The generic feed collector behind every curated-list row: fetch each of the
 * row's URLs over the row's declared transport order, gate on
 * {@link FeedParser#looksLikeFeed} (a 200 that is an HTML shell is a miss,
 * not a feed), parse, de-duplicate across the row's URLs, return. No internal
 * TTL cache — cadence belongs to the scheduler, which is the whole point.
 */
public final class CuratedFeedSource implements CollectorSource {

    private static final Logger LOG = LoggerFactory.getLogger(CuratedFeedSource.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final CatalogRow row;
    private final WebFetcher fetcher;
    private final FetchUtil[] modes;

    public CuratedFeedSource(CatalogRow row, WebFetcher fetcher) {
        this.row = row;
        this.fetcher = fetcher;
        this.modes = row.modes().toArray(FetchUtil[]::new);
    }

    @Override
    public String sourceName() {
        return row.name();
    }

    @Override
    public FetchUtil[] mode() {
        return modes.clone();
    }

    @Override
    public SourceOrigin origin() {
        return row.origin();
    }

    @Override
    public boolean socialSentiment() {
        return row.social();
    }

    @Override
    public FetchInterval interval() {
        return row.interval();
    }

    @Override
    public List<Article> collect() {
        List<Article> union = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String url : row.urls()) {
            if (fetcher.hostCoolingDown(url)) continue;
            try {
                WebResponse resp = fetcher.fetch(url,
                        Map.of("Accept", row.accept()), TIMEOUT, modes);
                if (resp.status() != 200) {
                    LOG.debug("'{}' feed {} answered {}", row.name(), url, resp.status());
                    continue;
                }
                if (!FeedParser.looksLikeFeed(resp.body())) {
                    LOG.debug("'{}' feed {} answered a 200 that is not a feed", row.name(), url);
                    continue;
                }
                for (Article a : FeedParser.parse(resp.body(), row.publisher())) {
                    if (seen.add(a.uuid())) union.add(a);
                }
            } catch (Exception e) {
                LOG.debug("'{}' feed {} failed: {}", row.name(), url, e.toString());
            }
        }
        return union;
    }
}
