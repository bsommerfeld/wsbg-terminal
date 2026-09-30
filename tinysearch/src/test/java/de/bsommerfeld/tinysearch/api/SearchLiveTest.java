package de.bsommerfeld.tinysearch.api;

import de.bsommerfeld.tinyfetch.api.BrowserEngine;
import de.bsommerfeld.tinyfetch.api.TinyFetch;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One real search on every engine through the real browser engine - opt-in,
 * and run sparingly: {@code mvn package -pl tinybrowser -am -DskipTests},
 * then {@code mvn test -pl tinysearch -Dtest=SearchLiveTest -Dtest.excludedGroups=visual}.
 * Chromium comes from {@code tinybrowser/target/chromium} (installed on first
 * use), the profile stays in {@code tinybrowser/target/search-profile}, so
 * the runs are one returning visitor.
 *
 * <p>Every engine has to answer with hits: one that does not has put up a
 * wall or rebuilt its page, and its reader needs looking at.
 */
@Tag("live")
class SearchLiveTest {

    @Test
    void everyEngineAnswers() throws Exception {
        Path target = Path.of(System.getProperty("tinybrowser.target", "../tinybrowser/target")).toAbsolutePath();
        assumeTrue(Files.isDirectory(target.resolve("engine")), "TinyBrowser not packaged - mvn package -pl tinybrowser -am");
        BrowserEngine engine = BrowserEngine.of(List.of(target.resolve("classes"), target.resolve("engine").resolve("*")),
                target.resolve("chromium"), target.resolve("search-profile"));

        TinyFetch.Builder http = TinyFetch.builder().engine(engine);
        TinySearch.hostPolicies().forEach(http::policy);

        try (TinyFetch fetch = http.build()) {
            SearchResults results = TinySearch.builder(fetch).build().search("SAP Aktie");

            results.reports().values().forEach(report -> System.out.println(
                    report.engine() + ": " + report.outcome() + ", " + report.hits() + " hits " + report.detail()));
            System.out.println(results.hits().size() + " pages in all, the first ten:");
            results.hits().stream().limit(10).forEach(hit ->
                    System.out.println("  " + hit.engines() + " " + hit.url() + " | " + hit.title()));

            for (EngineReport report : results.reports().values()) {
                assertEquals(EngineReport.Outcome.ANSWERED, report.outcome(), report.toString());
                assertTrue(report.hits() > 0, report.toString());
            }
        }
    }
}
