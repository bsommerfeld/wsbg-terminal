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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * finanznachrichten.de RSS — two feeds the evening briefing leans on
 * (live-verified 2026-07-13, keyless, no bot wall for plain GETs):
 *
 * <ul>
 *   <li>{@code rss-aktien-adhoc} — the day's EQS ad-hoc disclosures (the hard
 *       German catalysts: guidance changes, capital measures, M&amp;A). The ISIN
 *       is extracted by shape from title/description/category — FN embeds it
 *       inconsistently, the 12-char ISIN pattern is the robust join key.</li>
 *   <li>{@code rss-marktberichte} — finished dpa-AFX market reports (XETRA
 *       close, Wall Street wrap, outlook): a professionally edited EOD
 *       narrative, consumed as ATTRIBUTED background for the day report.</li>
 *   <li>{@code rss-aktien-analysen} — the day's analyst actions, house and
 *       rating right in the title ("JPMORGAN stuft BMW auf 'Overweight'").</li>
 * </ul>
 *
 * <p>Format pinned by live probe (2026-07-13): items carry ISO {@code pubDate}s
 * and a dedicated {@code <fn:isin>} element (empty on venue-less pieces); the
 * ISIN regex over title/description/category is only the fallback.
 */
@Singleton
public class FnRssClient {

    private static final Logger LOG = LoggerFactory.getLogger(FnRssClient.class);

    private static final String ADHOC_URL = "https://www.finanznachrichten.de/rss-aktien-adhoc";
    private static final String MARKET_REPORTS_URL = "https://www.finanznachrichten.de/rss-marktberichte";
    private static final String ANALYST_URL = "https://www.finanznachrichten.de/rss-aktien-analysen";

    private static final Pattern ISIN = Pattern.compile("\\b([A-Z]{2}[A-Z0-9]{9}[0-9])\\b");

    /** One EQS ad-hoc disclosure; {@code isin} null when the feed didn't carry one. */
    public record AdhocItem(String title, String isin, Instant publishedAt, String link) {
    }

    /** One dpa-AFX market report: headline plus the teaser lead. */
    public record PressItem(String title, String teaser, Instant publishedAt) {
    }

    /** One analyst action of the day — the title carries house, name and rating. */
    public record AnalystAction(String title, Instant publishedAt) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public FnRssClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public FnRssClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The day's ad-hoc disclosures, newest first as the feed ships them. Empty on any failure. */
    public List<AdhocItem> adhocs(int limit) {
        String body = get(ADHOC_URL);
        List<AdhocItem> out = parseAdhocs(body);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** The latest dpa-AFX market reports (title + teaser). Empty on any failure. */
    public List<PressItem> marketReports(int limit) {
        String body = get(MARKET_REPORTS_URL);
        List<PressItem> out = parseMarketReports(body);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** The latest analyst actions, newest first as the feed ships them. Empty on any failure. */
    public List<AnalystAction> analystActions(int limit) {
        String body = get(ANALYST_URL);
        List<AnalystAction> out = parseAnalystActions(body);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[FN] {} answered status {}", url, resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[FN] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Package-private for tests: feed XML → ad-hoc items, network-free. */
    static List<AdhocItem> parseAdhocs(String xml) {
        List<AdhocItem> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(xml)) {
            String isin = item.isin() != null && !item.isin().isBlank()
                    ? item.isin().trim()
                    : findIsin(item.title() + " " + item.description() + " " + item.category());
            out.add(new AdhocItem(cleanAdhocTitle(item.title()), isin,
                    item.publishedAt(), item.link()));
        }
        return out;
    }

    /** Package-private for tests: feed XML → press items, network-free. */
    static List<PressItem> parseMarketReports(String xml) {
        List<PressItem> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(xml)) {
            out.add(new PressItem(item.title(), item.description(), item.publishedAt()));
        }
        return out;
    }

    /** Package-private for tests: feed XML → analyst actions, network-free. */
    static List<AnalystAction> parseAnalystActions(String xml) {
        List<AnalystAction> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(xml)) {
            out.add(new AnalystAction(item.title(), item.publishedAt()));
        }
        return out;
    }

    /** Drops the boilerplate service prefix ("EQS-Adhoc: " and friends) off a disclosure title. */
    static String cleanAdhocTitle(String title) {
        return title.replaceFirst("^(EQS-Adhoc|EQS-Ad-hoc|DGAP-Adhoc|EQS-News)\\s*:\\s*", "").strip();
    }

    /** First ISIN-shaped token in the text, or null. */
    static String findIsin(String text) {
        if (text == null) return null;
        Matcher m = ISIN.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
