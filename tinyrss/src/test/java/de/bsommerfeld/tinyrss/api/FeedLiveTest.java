package de.bsommerfeld.tinyrss.api;

import de.bsommerfeld.tinyfetch.api.BrowserEngine;
import de.bsommerfeld.tinyfetch.api.TinyFetch;
import de.bsommerfeld.tinyrss.model.Entry;
import de.bsommerfeld.tinyrss.model.Feed;
import de.bsommerfeld.tinyrss.model.FeedFormat;
import de.bsommerfeld.tinyrss.model.FeedLink;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real feeds of six houses through the real browser engine - opt-in:
 * {@code mvn package -pl tinybrowser -am -DskipTests}, then
 * {@code mvn test -pl tinyrss -Dtest=FeedLiveTest -Dtest.excludedGroups=visual}.
 * One feed per shape that breaks naive readers: RSS 2.0, Atom, RSS 1.0,
 * tag soup (ariva's forum), a byte-order mark (Anadolu), a strict rate limit
 * (FinancialJuice) - and the autodiscovery of a front page. Run it once, not
 * in a loop: FinancialJuice counts.
 */
@Tag("live")
class FeedLiveTest {

    private static final Map<String, FeedFormat> FEEDS = Map.of(
            "https://www.tagesschau.de/wirtschaft/index~rss2.xml", FeedFormat.RSS,
            "https://www.heise.de/rss/heise-atom.xml", FeedFormat.ATOM,
            "https://www.ndr.de/home/index-rss.xml", FeedFormat.RDF,
            "https://www.ariva.de/forum/rss", FeedFormat.RSS,
            "https://www.aa.com.tr/en/rss/default?cat=economy", FeedFormat.RSS,
            // Cloudflare bans for a minute after a few requests in a row - one FETCH passes
            "https://www.financialjuice.com/feed.ashx?xy=rss", FeedFormat.RSS);

    @Test
    void readsEveryShapeAndDiscoversAFrontPagesFeeds() throws Exception {
        Path target = Path.of(System.getProperty("tinybrowser.target", "../tinybrowser/target")).toAbsolutePath();
        assumeTrue(Files.isDirectory(target.resolve("engine")), "TinyBrowser not packaged - mvn package -pl tinybrowser -am");
        BrowserEngine engine = BrowserEngine.of(List.of(target.resolve("classes"), target.resolve("engine").resolve("*")),
                target.resolve("chromium"), target.resolve("rss-profile"));

        try (TinyFetch fetch = TinyFetch.builder().engine(engine).build()) {
            FeedReader feeds = new FeedReader(fetch);

            for (Map.Entry<String, FeedFormat> expected : FEEDS.entrySet()) {
                Feed feed = feeds.read(expected.getKey());
                System.out.println(feed.format() + " " + feed.title() + ": " + feed.entries().size() + " entries");
                feed.entries().stream().limit(2).forEach(entry ->
                        System.out.println("  " + entry.time().orElse(null) + " | " + entry.title() + " | " + entry.link()));

                assertEquals(expected.getValue(), feed.format(), expected.getKey());
                assertFalse(feed.entries().isEmpty(), expected.getKey());
                for (Entry entry : feed.entries()) {
                    assertFalse(entry.title().isEmpty() && entry.link() == null, entry.id());
                }
            }

            // heise's front page announces no feed at all (measured 2026-09-30); tagesschau's announces three
            List<FeedLink> found = feeds.discover("https://www.tagesschau.de/");
            found.forEach(link -> System.out.println("discovered " + link));
            assertTrue(found.stream().anyMatch(link -> link.url().equals("https://www.tagesschau.de/index~rss2.xml")));
        }
    }
}
