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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * German macro ACTUALS as press headlines — Destatis' and ifo's own RSS feeds
 * (live-verified 2026-07-13, keyless): the released figure usually sits right
 * in the title ("Inflationsrate im Juni 2026 bei +2,3 %"), which is ideal
 * material for a 4B model. Destatis mixes non-economic releases (tourism,
 * social statistics) into the same feed, so its items pass a keyword filter;
 * ifo's feed is economics through and through and passes as-is.
 */
@Singleton
public class MacroPressClient {

    private static final Logger LOG = LoggerFactory.getLogger(MacroPressClient.class);

    private static final String DESTATIS_URL =
            "https://www.destatis.de/SiteGlobals/Functions/RSSFeed/DE/RSSNewsfeed/Aktuell.xml";
    private static final String IFO_URL = "https://www.ifo.de/rss";

    /** Market-relevant Destatis release families; everything else in the feed is noise here. */
    private static final Pattern DESTATIS_RELEVANT = Pattern.compile(
            "inflation|verbraucherpreis|erzeugerpreis|grosshandelspreis|großhandelspreis"
                    + "|import|export|aussenhandel|außenhandel|produktion|auftragseingang"
                    + "|bip|bruttoinlandsprodukt|erwerbstätig|arbeitslos|umsatz|einzelhandel"
                    + "|baugenehmigung|insolvenz|löhne|loehne|verdienste|zins",
            Pattern.CASE_INSENSITIVE);

    /** One macro press release: the headline carries the figure. */
    public record MacroActual(String title, String source, Instant publishedAt) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public MacroPressClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public MacroPressClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Market-relevant releases published at or after {@code since}, both feeds
     * merged, newest first. Each feed is best-effort on its own — one failing
     * never empties the other.
     */
    public List<MacroActual> actualsSince(Instant since, int limit) {
        List<MacroActual> out = new ArrayList<>();
        out.addAll(filterSince(parseDestatis(get(DESTATIS_URL)), since));
        out.addAll(filterSince(parseIfo(get(IFO_URL)), since));
        out.sort((a, b) -> {
            Instant ia = a.publishedAt() == null ? Instant.EPOCH : a.publishedAt();
            Instant ib = b.publishedAt() == null ? Instant.EPOCH : b.publishedAt();
            return ib.compareTo(ia);
        });
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static List<MacroActual> filterSince(List<MacroActual> items, Instant since) {
        return items.stream()
                .filter(a -> a.publishedAt() != null && !a.publishedAt().isBefore(since))
                .toList();
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[MacroPress] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[MacroPress] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Package-private for tests: Destatis feed XML → keyword-filtered actuals. */
    static List<MacroActual> parseDestatis(String xml) {
        List<MacroActual> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(xml)) {
            if (!DESTATIS_RELEVANT.matcher(item.title().toLowerCase(Locale.ROOT)).find()) continue;
            out.add(new MacroActual(item.title(), "Destatis", item.publishedAt()));
        }
        return out;
    }

    /** Package-private for tests: ifo feed XML → actuals (no filter — it's all economics). */
    static List<MacroActual> parseIfo(String xml) {
        List<MacroActual> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(xml)) {
            out.add(new MacroActual(item.title(), "ifo", item.publishedAt()));
        }
        return out;
    }
}
