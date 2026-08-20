package de.bsommerfeld.wsbg.terminal.web.impl.sources.googlenews;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world editions, live: real HTTP against news.google.com, one request
 * per edition. Isolated verification is the live probe, never a synthetic
 * scraper - so this smoke asserts what only the real index can answer: that
 * the foreign editions ANSWER at all, that they answer with items the home
 * edition does not carry, and that every item arrives stamped with the sphere
 * of the index that produced it.
 *
 * <pre>GNEWS_SMOKE=true mvn test -pl web/impl -Dtest=GoogleNewsWorldSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "GNEWS_SMOKE", matches = "true")
class GoogleNewsWorldSmokeIT {

    @Test
    void everyEditionAnswersAndStampsItsOwnSphere() throws Exception {
        WebFetcher fetcher = new HouseFetcher(Set.of(new DirectTransport()));
        GoogleNewsWorldSource world = new GoogleNewsWorldSource(fetcher);
        ResolvedInstrument apple = ResolvedInstrument.ofName("Apple");
        List<Article> items = world.newsFor(apple, 20 * GoogleNewsWorldSource.EDITIONS.size());
        assertFalse(items.isEmpty(), "no world edition answered at all");

        Map<String, Integer> perSphere = new LinkedHashMap<>();
        for (Article item : items) {
            assertTrue(item.origin().known(),
                    "an item arrived without the sphere of its edition: " + item.title());
            perSphere.merge(item.origin().sphere(), 1, Integer::sum);
        }
        System.out.println("[gnews-world] items per sphere: " + perSphere);

        // The whole point of the fan: more than one gatekeeping answered.
        assertTrue(perSphere.size() >= 3,
                "only " + perSphere.size() + " sphere(s) answered: " + perSphere);

        // And the foreign indexes carry houses the home index never surfaces.
        Set<String> homeDomains = domains(new GoogleNewsSource(fetcher).newsFor(apple, 40));
        Set<String> worldDomains = domains(items);
        worldDomains.removeAll(homeDomains);
        System.out.println("[gnews-world] domains the home edition never carried: "
                + worldDomains);
        assertFalse(worldDomains.isEmpty(),
                "the world fan found no publisher the German edition misses");
    }

    private static Set<String> domains(List<Article> items) {
        Set<String> out = new LinkedHashSet<>();
        for (Article item : items) {
            if (item.publisher() != null && !item.publisher().isBlank()) {
                out.add(item.publisher().strip());
            }
        }
        return out;
    }
}
