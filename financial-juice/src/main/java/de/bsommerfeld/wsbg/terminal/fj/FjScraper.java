package de.bsommerfeld.wsbg.terminal.fj;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetches the FinancialJuice RSS feed and de-duplicates it into a delta of
 * new {@link RawNewsItem}s. The RSS/XML parsing and HTML→text cleaning live in
 * {@link FjRssParser}; this class owns fetch + cross-call GUID dedup + the
 * polling-interval surface.
 *
 * <h3>Feed format</h3>
 * FinancialJuice exposes a standard RSS 2.0 feed at
 * {@code https://www.financialjuice.com/feed.ashx?xy=rss}. Each
 * {@code <item>} contains a headline, permalink, optional HTML description,
 * author, publication date, and a numeric GUID used for deduplication.
 *
 * <h3>Rate limiting</h3>
 * RSS feeds are not API endpoints — there is no documented rate limit.
 * A 60-second polling interval is conservative and safe; most RSS
 * providers tolerate intervals down to 15 seconds without issues.
 * If FinancialJuice ever starts returning 429s, the scraper logs the
 * status code and returns an empty list without retrying.
 *
 * <h3>Deduplication</h3>
 * The scraper tracks seen GUIDs in memory across calls. Items already
 * seen in a previous fetch are not included in the returned list.
 * This allows callers to treat the return value as a "delta" of new
 * items since the last poll.
 *
 * <h3>Title normalization</h3>
 * Every item title is prefixed with {@code "FinancialJuice: "} by the
 * feed. This prefix is stripped during parsing to produce cleaner headlines
 * for downstream AI analysis.
 */
@Singleton
public class FjScraper {

    private static final Logger LOG = LoggerFactory.getLogger(FjScraper.class);

    private static final String FEED_URL = "https://www.financialjuice.com/feed.ashx?xy=rss";

    /**
     * 30s is aggressive for a typical RSS feed but well within safe territory.
     * RSS providers expect polling in the minute range; FinancialJuice
     * has no documented rate limit. Can be lowered to 15s if needed,
     * but 30s already catches most breaking news within one cycle.
     */
    private static final long POLL_INTERVAL_SECONDS = 30;

    /**
     * A random, realistic browser User-Agent chosen once per process so the feed
     * accepts us as a genuine browser without every install sharing one
     * fingerprint. See {@link BrowserUserAgent}.
     */
    private final String userAgent = BrowserUserAgent.random();

    private final HttpClient httpClient;

    private final FjRssParser parser = new FjRssParser();

    /**
     * GUIDs that have been returned in previous {@link #fetch()} calls.
     * Prevents re-processing of items already seen. Not persisted across
     * restarts, and no longer backstopped by any downstream similarity
     * dedup (the embedding stack was removed 2026-07-03) — GUID identity
     * is the sole dedup mechanism.
     */
    private final Set<String> seenGuids = new HashSet<>();

    public FjScraper() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    /**
     * Fetches the RSS feed and returns only items not seen in previous calls.
     *
     * @return new items since the last fetch, ordered newest-first
     *         (matching the feed's natural order). Returns an empty list on
     *         any fetch or parse failure.
     */
    public List<RawNewsItem> fetch() {
        LOG.debug("Fetching FinancialJuice RSS feed");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FEED_URL))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warn("FinancialJuice RSS returned HTTP {}", response.statusCode());
                return Collections.emptyList();
            }

            List<RawNewsItem> allItems = parseRss(response.body());
            List<RawNewsItem> newItems = new ArrayList<>();

            for (RawNewsItem item : allItems) {
                if (seenGuids.add(item.uuid())) {
                    newItems.add(item);
                }
            }

            if (!newItems.isEmpty()) {
                LOG.info("FinancialJuice: {} new items fetched", newItems.size());
            }

            return Collections.unmodifiableList(newItems);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("FinancialJuice fetch interrupted");
            return Collections.emptyList();
        } catch (Exception e) {
            LOG.error("Failed to fetch FinancialJuice RSS", e);
            return Collections.emptyList();
        }
    }

    /** Parses raw RSS 2.0 XML into domain objects. Delegates to {@link FjRssParser}. */
    List<RawNewsItem> parseRss(String xml) {
        return parser.parseRss(xml);
    }

    /** Returns the total number of unique items seen since startup. */
    public int seenCount() {
        return seenGuids.size();
    }

    /** Returns the configured polling interval in seconds. */
    public long pollIntervalSeconds() {
        return POLL_INTERVAL_SECONDS;
    }

    /** Clears the deduplication cache, causing the next fetch to return all feed items. */
    public void resetSeenGuids() {
        seenGuids.clear();
    }

}
