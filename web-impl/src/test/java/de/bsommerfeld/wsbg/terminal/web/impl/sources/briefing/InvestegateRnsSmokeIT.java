package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UK regulatory wire, live. Two things only a real fetch can answer: that
 * the index still renders its rows server-side (a move to client-side
 * rendering would empty this leg without an error), and that the rows are
 * FRESH - a wire whose newest announcement is a week old has stopped.
 *
 * <pre>RNS_SMOKE=true mvn test -pl web-impl -Dtest=InvestegateRnsSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RNS_SMOKE", matches = "true")
class InvestegateRnsSmokeIT {

    @Test
    void theWireCarriesFreshAnnouncements() throws Exception {
        InvestegateRnsClient client =
                new InvestegateRnsClient(new HouseFetcher(
                        Set.<de.bsommerfeld.wsbg.terminal.web.fetch.Transport>of(
                                new DirectTransport())));
        List<Article> sweep = client.collect();
        assertFalse(sweep.isEmpty(), "the UK wire carried no announcement");
        List<Article> latest = sweep.size() <= 12 ? sweep : sweep.subList(0, 12);
        for (Article item : latest) {
            System.out.printf("[rns] %s  %-46s %s%n", item.publishedAt(),
                    item.publisher(), item.title());
        }
        long dated = latest.stream().filter(i -> i.publishedAt() != null).count();
        assertTrue(dated >= latest.size() - 1,
                "only " + dated + " of " + latest.size() + " announcements carried a timestamp");
        Instant newest = latest.stream().map(Article::publishedAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(Instant.EPOCH);
        assertTrue(newest.isAfter(Instant.now().minus(Duration.ofDays(4))),
                "the newest announcement is from " + newest + " - the wire has stopped");

        // And an announcement stays findable by its own company cell - the
        // per-issuer question the pool answers in the new world.
        String company = latest.get(0).publisher();
        String name = company.substring(0, Math.max(1, company.indexOf(" (")))
                .toLowerCase(java.util.Locale.ROOT);
        long mine = sweep.stream()
                .filter(i -> i.publisher() != null
                        && i.publisher().toLowerCase(java.util.Locale.ROOT).contains(name))
                .count();
        System.out.println("[rns] Rückprobe auf \"" + name + "\": " + mine + " Treffer");
        assertTrue(mine > 0, "an announcement could not be found by its own issuer name");
    }
}
