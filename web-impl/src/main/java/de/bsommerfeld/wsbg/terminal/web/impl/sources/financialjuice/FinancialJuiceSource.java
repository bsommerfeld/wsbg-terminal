package de.bsommerfeld.wsbg.terminal.web.impl.sources.financialjuice;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The FinancialJuice headline wire as a collector: one pass fetches the RSS
 * feed and returns the FULL current window — de-duplication against earlier
 * passes is the pool's job (GUID identity via {@link Article#uuid()}), the
 * cadence is the scheduler's ({@link FetchInterval#FAST}: this is the house's
 * fastest breaking-headline wire). The RSS/XML parsing and HTML→text cleaning
 * live in {@link FjRssParser}.
 *
 * <h3>Feed format</h3>
 * FinancialJuice exposes a standard RSS 2.0 feed at
 * {@code https://www.financialjuice.com/feed.ashx?xy=rss}. Each
 * {@code <item>} contains a headline, permalink, optional HTML description,
 * author, publication date, and a numeric GUID used for deduplication.
 *
 * <h3>Rate limiting</h3>
 * RSS feeds are not API endpoints — there is no documented rate limit; the
 * FAST interval's 2-minute floor is well inside what RSS providers tolerate.
 * If FinancialJuice ever starts returning 429s, the source logs the status
 * code and returns an empty list without retrying (host cooldowns live in
 * the fetcher).
 *
 * <h3>Title normalization</h3>
 * Every item title is prefixed with {@code "FinancialJuice: "} by the
 * feed. This prefix is stripped during parsing to produce cleaner headlines
 * for downstream AI analysis.
 */
@Singleton
public class FinancialJuiceSource extends AbstractWebSource implements CollectorSource {

    private static final Logger LOG = LoggerFactory.getLogger(FinancialJuiceSource.class);

    private static final String FEED_URL = "https://www.financialjuice.com/feed.ashx?xy=rss";

    private final FjRssParser parser = new FjRssParser();

    @Inject
    public FinancialJuiceSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "financial-juice";
    }

    /** The feed answers a plain client; the joker stays the fallback. */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** A breaking-headline wire — the fastest cadence the house allows. */
    @Override
    public FetchInterval interval() {
        return FetchInterval.FAST;
    }

    /**
     * One collection pass: the feed's full current window, newest-first as
     * delivered. Returns an empty list on any fetch or parse failure.
     */
    @Override
    public List<Article> collect() {
        LOG.debug("Fetching FinancialJuice RSS feed");
        try {
            WebResponse resp = get(FEED_URL, Map.of(), Duration.ofSeconds(15));
            if (resp.status() != 200) {
                LOG.warn("FinancialJuice RSS returned HTTP {}", resp.status());
                return List.of();
            }
            return parseRss(resp.body());
        } catch (Exception e) {
            LOG.error("Failed to fetch FinancialJuice RSS", e);
            return List.of();
        }
    }

    /** Parses raw RSS 2.0 XML into domain objects. Delegates to {@link FjRssParser}. */
    List<Article> parseRss(String xml) {
        return parser.parseRss(xml);
    }
}
