package de.bsommerfeld.wsbg.terminal.web.impl.sources.lemmy;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lemmy community listings — fixtures are the LIVE {@code /api/v3/post/list}
 * answers of both communities (2026-07-16, limit=4, byte-identical): the
 * feddit.org finanzen page carries a text post with a Markdown body (IBM, 8
 * comments), a federated link post whose {@code body} key is MISSING entirely
 * and whose {@code ap_id} points at a FOREIGN instance (reddthat.com), and
 * two discussion posts; the lemmy.world stocks page carries link posts whose
 * {@code body} is the EMPTY STRING (including two SpaceX titles). Ported from
 * the module world; the collector delivers every post unfiltered — pool
 * caching and name matching left with the old NewsSource fan.
 */
class LemmySourceTest {

    private static final String FEDDIT_URL =
            "https://feddit.org/api/v3/post/list?community_name=finanzen&sort=New&limit=50";
    private static final String WORLD_URL =
            "https://lemmy.world/api/v3/post/list?community_name=stocks&sort=New&limit=50";

    private static final LemmySource.Community FINANZEN =
            new LemmySource.Community("feddit.org", "finanzen");

    private static String fixture(String name) {
        try (InputStream in = LemmySourceTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    private static String feddit() {
        return fixture("lemmy-feddit-finanzen.json");
    }

    private static String world() {
        return fixture("lemmy-world-stocks.json");
    }

    /** Fake transport answering per-URL reply sequences, counting fetches per URL. */
    private static final class FakeFetcher implements WebFetcher {
        final Map<String, AtomicInteger> fetches = new HashMap<>();
        private final Map<String, List<WebResponse>> replies = new HashMap<>();

        FakeFetcher answer(String url, WebResponse... responses) {
            replies.put(url, List.of(responses));
            return this;
        }

        static FakeFetcher live() {
            return new FakeFetcher()
                    .answer(FEDDIT_URL, WebResponse.text(200, feddit(), Map.of()))
                    .answer(WORLD_URL, WebResponse.text(200, world(), Map.of()));
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            List<WebResponse> seq = replies.get(url);
            assertTrue(seq != null, "unexpected URL fetched: " + url);
            int n = fetches.computeIfAbsent(url, u -> new AtomicInteger()).getAndIncrement();
            return seq.get(Math.min(n, seq.size() - 1));
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers,
                Duration timeout, FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WebResponse post(String url, Map<String, String> headers, String body,
                String contentType, Duration timeout, FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hostCoolingDown(String url) {
            return false;
        }
    }

    @Test
    void parseMapsTheLiveFieldsIncludingMissingAndEmptyBodies() {
        List<Article> posts = LemmySource.parse(feddit(), FINANZEN);
        assertEquals(4, posts.size(), "the answer is unfiltered — all listed posts parse");

        Article ibm = posts.get(0);
        assertEquals("IBM-CEO schockiert Aktionäre, Aktie kracht", ibm.title());
        assertEquals("https://feddit.org/post/32654998", ibm.link(),
                "ap_id is the permalink — link AND uuid");
        assertEquals(ibm.link(), ibm.uuid());
        assertEquals("Lemmy (!finanzen@feddit.org)", ibm.publisher());
        assertEquals(Instant.parse("2026-07-14T20:24:11.097863Z"), ibm.publishedAt(),
                "published is ISO-8601 with microseconds + Z");
        assertTrue(ibm.summary().contains("IBM-Aktien haben am Dienstag"),
                "the Markdown body becomes the summary: " + ibm.summary());
        assertFalse(ibm.summary().contains(">") || ibm.summary().contains("**"),
                "quote and emphasis markers are stripped: " + ibm.summary());
        assertTrue(ibm.summary().endsWith("(8 Kommentare)"),
                "the comment count rides the summary — it IS the discussion echo");

        Article bodyless = posts.get(1);
        assertEquals("https://reddthat.com/post/69350987", bodyless.link(),
                "a federated post's ap_id points at a FOREIGN instance — still the permalink");
        assertEquals("(0 Kommentare)", bodyless.summary(),
                "a MISSING body key yields the bare comment echo, never null");

        // lemmy.world link posts carry body as the EMPTY STRING instead.
        List<Article> stocks = LemmySource.parse(world(),
                new LemmySource.Community("lemmy.world", "stocks"));
        assertEquals(4, stocks.size());
        assertEquals("(1 Kommentar)", stocks.get(0).summary(),
                "empty-string body + singular comment count");
        assertEquals("Lemmy (!stocks@lemmy.world)", stocks.get(0).publisher());
    }

    @Test
    void collectUnitesBothCommunities() {
        FakeFetcher fetcher = FakeFetcher.live();
        LemmySource source = new LemmySource(fetcher);

        List<Article> all = source.collect();
        assertEquals(8, all.size(), "both communities feed ONE unioned pass");
        assertEquals(1, fetcher.fetches.get(FEDDIT_URL).get(),
                "ONE request per community per pass");
        assertEquals(1, fetcher.fetches.get(WORLD_URL).get());
    }

    @Test
    void parseToleratesGarbageAndNonLemmyAnswers() {
        assertNull(LemmySource.parse("<html><body>404</body></html>", FINANZEN));
        assertNull(LemmySource.parse("not json at all", FINANZEN));
        assertNull(LemmySource.parse("", FINANZEN));
        assertNull(LemmySource.parse(null, FINANZEN));
        assertNull(LemmySource.parse("{\"error\":\"couldnt_find_community\"}", FINANZEN),
                "JSON without a posts array is not a post list");
        // Torn mid-stream (a truncated response) — invalid JSON, a miss.
        String torn = feddit().substring(0, feddit().length() / 2);
        assertNull(LemmySource.parse(torn, FINANZEN));
        // Garbage ENTRIES inside a valid list are skipped, not fatal.
        List<Article> mixed = LemmySource.parse(
                "{\"posts\":[{\"post\":{\"name\":\"\",\"ap_id\":\"\"}},"
                        + "{\"nonsense\":true},"
                        + "{\"post\":{\"name\":\"Titel\",\"ap_id\":\"https://x/post/1\","
                        + "\"published\":\"gestern\"}}]}", FINANZEN);
        assertEquals(1, mixed.size(), "incomplete entries are skipped, the rest survives");
        assertNull(mixed.get(0).publishedAt(),
                "an unparseable published yields null, never a guessed timestamp");
    }

    @Test
    void softTwoHundredGarbageIsAMissNotAnEmptyCommunity() {
        FakeFetcher fetcher = new FakeFetcher()
                .answer(FEDDIT_URL,
                        WebResponse.text(200, "<!doctype html><html>wall</html>", Map.of()),
                        WebResponse.text(200, feddit(), Map.of()))
                .answer(WORLD_URL, WebResponse.text(500, "", Map.of()));
        LemmySource source = new LemmySource(fetcher);

        assertTrue(source.collect().isEmpty(),
                "an HTML 200 is a miss, a 500 leg stays empty — no crash");
        assertEquals(4, source.collect().size(),
                "nothing was cached — the next pass refetches and succeeds");
        assertEquals(2, fetcher.fetches.get(FEDDIT_URL).get());
    }

    @Test
    void sourceSelfDescription() {
        LemmySource source = new LemmySource(FakeFetcher.live());
        assertEquals("lemmy", source.sourceName());
        assertTrue(source.socialSentiment(),
                "room opinion rides the sentiment fan, never the press loom");
        assertEquals(FetchUtil.DIRECT, source.mode()[0], "direct-first transport");
    }

    @Test
    void summaryStripsMarkdownCapsAtFiveHundredAndAppendsTheEcho() {
        assertEquals("Text mit Link (3 Kommentare)",
                LemmySource.summary("**Text** mit [Link](https://x.example)", 3));
        assertEquals("(0 Kommentare)", LemmySource.summary(null, 0));
        assertEquals("(1 Kommentar)", LemmySource.summary("", 1));
        String longBody = "wort ".repeat(200); // 1000 chars
        String capped = LemmySource.summary(longBody, 2);
        assertTrue(capped.length() <= 500 + " … (2 Kommentare)".length(),
                "body capped near 500 chars: " + capped.length());
        assertTrue(capped.contains("…") && capped.endsWith("(2 Kommentare)"));
    }

    @Test
    void publishedParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-14T20:24:11.097863Z"),
                LemmySource.parsePublished("2026-07-14T20:24:11.097863Z"));
        assertEquals(Instant.parse("2026-07-14T20:24:11.097863Z"),
                LemmySource.parsePublished("2026-07-14T20:24:11.097863"),
                "a zone-less timestamp (older Lemmy versions) is UTC");
        assertNull(LemmySource.parsePublished("gestern"));
        assertNull(LemmySource.parsePublished(""));
        assertNull(LemmySource.parsePublished(null));
    }
}
