package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole press catalog, live: one real sweep over every catalogued feed.
 * The point is not that SOME feed answers - it is which ones DON'T, because a
 * feed that quietly went dead is invisible in production (the sweep is
 * best-effort per feed by design) and the register would keep listing it as a
 * source we have.
 *
 * <pre>PRESS_SMOKE=true mvn test -pl briefing -Dtest=MarketPressCatalogSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "PRESS_SMOKE", matches = "true")
class MarketPressCatalogSmokeIT {

    @Test
    void everyCatalogedFeedStillAnswers() {
        List<MarketPressClient.PressHeadline> items =
                new MarketPressClient().headlinesSince(
                        Instant.now().minus(Duration.ofDays(7)), 10_000);
        Map<String, Integer> perSource = new LinkedHashMap<>();
        Map<String, Integer> perCategory = new LinkedHashMap<>();
        for (MarketPressClient.PressHeadline h : items) {
            perSource.merge(h.source(), 1, Integer::sum);
            perCategory.merge(h.category(), 1, Integer::sum);
        }
        System.out.println("[press] per category: " + perCategory);
        StringBuilder silent = new StringBuilder();
        for (MarketPressClient.Feed feed : MarketPressClient.CATALOG) {
            int n = perSource.getOrDefault(feed.source(), 0);
            System.out.printf("[press] %-24s %-4s %5d item(s)%s%s%n",
                    feed.source(), feed.category(), n, feed.jokerOnly() ? "  [joker]" : "",
                    n == 0 ? "   " + diagnose(feed) : "");
            // A joker-only house is expected to refuse this transport - it is
            // reachable from the app and nowhere else, and asserting on it
            // here would only ever prove that this test has no browser.
            if (n == 0 && !feed.jokerOnly()) {
                silent.append("\n  ").append(feed.source())
                        .append(" (").append(feed.category()).append(") ").append(feed.url())
                        .append("  ").append(diagnose(feed));
            }
        }
        // Named, not swallowed: a dead feed must be a visible failure, and the
        // list of the silent ones IS the finding.
        assertTrue(silent.length() == 0, "feeds that answered nothing:" + silent);
    }

    /**
     * WHY a feed is silent - the three failures look identical from the sweep
     * and need completely different repairs: the host refused, the parser
     * found no items, or the items carry no date the freshness filter can use.
     */
    private static String diagnose(MarketPressClient.Feed feed) {
        try {
            var resp = new de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher().fetch(
                    feed.url(),
                    Map.of("User-Agent",
                            de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent.random(),
                            "Accept",
                            "application/rss+xml, application/xml, text/xml, */*"),
                    Duration.ofSeconds(15));
            if (resp == null) return "→ no response";
            if (resp.status() != 200) return "→ HTTP " + resp.status();
            List<MarketPressClient.PressHeadline> parsed =
                    MarketPressClient.parseFeed(feed, resp.body());
            long dated = parsed.stream().filter(h -> h.publishedAt() != null).count();
            if (parsed.isEmpty()) {
                return "→ 200 but the parser found no item (" + resp.body().length() + " chars)";
            }
            if (dated == 0) return "→ " + parsed.size() + " item(s), NONE dated";
            Instant newest = parsed.stream().map(MarketPressClient.PressHeadline::publishedAt)
                    .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
            return "→ " + parsed.size() + " item(s), " + dated + " dated, newest " + newest;
        } catch (Exception e) {
            return "→ " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
