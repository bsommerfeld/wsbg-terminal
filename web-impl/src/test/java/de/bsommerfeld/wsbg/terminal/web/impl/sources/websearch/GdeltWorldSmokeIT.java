package de.bsommerfeld.wsbg.terminal.web.impl.sources.websearch;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world index, live: real requests against GDELT DOC 2.0 (the host's rate
 * gate applies), asserting what only the real index can answer - that the
 * OR-clause reaches several languages in a SINGLE request, and that every
 * article arrives stamped with the language and source country GDELT itself
 * reports for it.
 *
 * <pre>GDELT_SMOKE=true mvn test -pl web-impl -Dtest=GdeltWorldSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "GDELT_SMOKE", matches = "true")
class GdeltWorldSmokeIT {

    @Test
    void oneRequestReachesSeveralLanguagesAndStampsEachArticle() throws Exception {
        GdeltWorldSource source =
                new GdeltWorldSource(new HouseFetcher(Set.of(new DirectTransport())));
        List<Article> items = source.newsFor(ResolvedInstrument.ofName("Apple"), 40);
        assertFalse(items.isEmpty(), "the world index answered nothing");

        Map<String, Integer> perSphere = new LinkedHashMap<>();
        Map<String, Integer> perLanguage = new LinkedHashMap<>();
        for (Article item : items) {
            perSphere.merge(item.origin().sphere(), 1, Integer::sum);
            perLanguage.merge(item.origin().language(), 1, Integer::sum);
        }
        System.out.println("[gdelt-world] spheres:   " + perSphere);
        System.out.println("[gdelt-world] languages: " + perLanguage);
        for (Article item : items) {
            System.out.println("  " + item.origin().marke() + "  " + item.publisher()
                    + "  " + item.title());
        }
        assertTrue(perLanguage.size() >= 3,
                "one request reached only " + perLanguage.size() + " language(s)");
        assertTrue(perSphere.size() >= 3,
                "one request reached only " + perSphere.size() + " sphere(s)");
    }
}
