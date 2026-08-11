package de.bsommerfeld.wsbg.terminal.briefing;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UK regulatory wire, live. Two things only a real fetch can answer: that
 * the index still renders its rows server-side (a move to client-side
 * rendering would empty this leg without an error), and that the rows are
 * FRESH - a wire whose newest announcement is a week old has stopped.
 *
 * <pre>RNS_SMOKE=true mvn test -pl briefing -Dtest=InvestegateRnsSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RNS_SMOKE", matches = "true")
class InvestegateRnsSmokeIT {

    @Test
    void theWireCarriesFreshAnnouncements() {
        InvestegateRnsClient client = new InvestegateRnsClient();
        List<RawNewsItem> latest = client.latest(12);
        assertFalse(latest.isEmpty(), "the UK wire carried no announcement");
        for (RawNewsItem item : latest) {
            System.out.printf("[rns] %s  %-46s %s%n", item.publishedAt(),
                    item.publisher(), item.title());
        }
        long dated = latest.stream().filter(i -> i.publishedAt() != null).count();
        assertTrue(dated >= latest.size() - 1,
                "only " + dated + " of " + latest.size() + " announcements carried a timestamp");
        Instant newest = latest.stream().map(RawNewsItem::publishedAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(Instant.EPOCH);
        assertTrue(newest.isAfter(Instant.now().minus(Duration.ofDays(4))),
                "the newest announcement is from " + newest + " - the wire has stopped");

        // And the per-issuer fan finds one of them by its own company cell.
        String company = latest.get(0).publisher();
        String name = company.substring(0, Math.max(1, company.indexOf(" (")));
        List<RawNewsItem> mine = client.newsForName(name, 5);
        System.out.println("[rns] Rückprobe auf \"" + name + "\": " + mine.size() + " Treffer");
        assertFalse(mine.isEmpty(), "an announcement could not be found by its own issuer name");
    }
}
