package de.bsommerfeld.wsbg.terminal.lemmy;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * {@code body} is the EMPTY STRING (including two SpaceX titles).
 */
class LemmyClientTest {

    private static final String FEDDIT_URL =
            "https://feddit.org/api/v3/post/list?community_name=finanzen&sort=New&limit=50";
    private static final String WORLD_URL =
            "https://lemmy.world/api/v3/post/list?community_name=stocks&sort=New&limit=50";

    private static final LemmyClient.Community FINANZEN =
            new LemmyClient.Community("feddit.org", "finanzen");

    private static String fixture(String name) {
        try (InputStream in = LemmyClientTest.class.getResourceAsStream("/" + name)) {
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
                    .answer(FEDDIT_URL, new WebResponse(200, feddit(), Map.of()))
                    .answer(WORLD_URL, new WebResponse(200, world(), Map.of()));
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            List<WebResponse> seq = replies.get(url);
            assertTrue(seq != null, "unexpected URL fetched: " + url);
            int n = fetches.computeIfAbsent(url, u -> new AtomicInteger()).getAndIncrement();
            return seq.get(Math.min(n, seq.size() - 1));
        }

        int total() {
            return fetches.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    @Test
    void parseMapsTheLiveFieldsIncludingMissingAndEmptyBodies() {
        List<LemmyClient.PooledPost> posts = LemmyClient.parse(feddit(), FINANZEN);
        assertEquals(4, posts.size(), "the pool is unfiltered — all listed posts parse");

        RawNewsItem ibm = posts.get(0).item();
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

        RawNewsItem bodyless = posts.get(1).item();
        assertEquals("https://reddthat.com/post/69350987", bodyless.link(),
                "a federated post's ap_id points at a FOREIGN instance — still the permalink");
        assertEquals("(0 Kommentare)", bodyless.summary(),
                "a MISSING body key yields the bare comment echo, never null");

        // lemmy.world link posts carry body as the EMPTY STRING instead.
        List<LemmyClient.PooledPost> stocks = LemmyClient.parse(world(),
                new LemmyClient.Community("lemmy.world", "stocks"));
        assertEquals(4, stocks.size());
        assertEquals("(1 Kommentar)", stocks.get(0).item().summary(),
                "empty-string body + singular comment count");
        assertEquals("Lemmy (!stocks@lemmy.world)", stocks.get(0).item().publisher());
    }

    @Test
    void precisionFilterMatchesTitleAndBody() {
        LemmyClient client = new LemmyClient(FakeFetcher.live());

        List<RawNewsItem> ibm = client.newsForName("IBM", 10);
        assertEquals(1, ibm.size(), "title match");
        assertEquals("IBM-CEO schockiert Aktionäre, Aktie kracht", ibm.get(0).title());

        // "Neobroker" appears ONLY in a post's body ("Günstiges Depot+Tagesgeld
        // für Oma") — community posts often name the instrument in prose only.
        List<RawNewsItem> body = client.newsForName("Neobroker", 10);
        assertEquals(1, body.size(), "BODY match — the filter reads title AND body");
        assertEquals("Günstiges Depot+Tagesgeld für Oma", body.get(0).title());

        assertEquals(2, client.newsForName("SpaceX", 10).size(),
                "both communities feed ONE unioned pool");
        assertEquals(1, client.newsForName("SpaceX", 1).size(), "limit caps the answer");

        assertTrue(client.newsForName("Volkswagen", 10).isEmpty(),
                "an instrument the pool doesn't carry yields nothing");
        assertTrue(client.newsForName("Micron Technology, Inc.", 10).size() >= 1,
                "legal-suffix noise doesn't block the significant word");
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name of only generic/short words never matches the firehose");
    }

    @Test
    void poolCacheAnswersABurstWithOneFetchPerCommunity() {
        FakeFetcher fetcher = FakeFetcher.live();
        LemmyClient client = new LemmyClient(fetcher);

        assertEquals(1, client.newsForName("IBM", 10).size());
        assertEquals(2, client.newsForName("SpaceX", 10).size());
        assertEquals(1, client.newsForName("Micron", 10).size());
        assertEquals(1, fetcher.fetches.get(FEDDIT_URL).get(),
                "the POOL is cached — a burst makes ONE request per community");
        assertEquals(1, fetcher.fetches.get(WORLD_URL).get());
    }

    @Test
    void parseToleratesGarbageAndNonLemmyAnswers() {
        assertNull(LemmyClient.parse("<html><body>404</body></html>", FINANZEN));
        assertNull(LemmyClient.parse("not json at all", FINANZEN));
        assertNull(LemmyClient.parse("", FINANZEN));
        assertNull(LemmyClient.parse(null, FINANZEN));
        assertNull(LemmyClient.parse("{\"error\":\"couldnt_find_community\"}", FINANZEN),
                "JSON without a posts array is not a post list");
        // Torn mid-stream (a truncated response) — invalid JSON, a miss.
        String torn = feddit().substring(0, feddit().length() / 2);
        assertNull(LemmyClient.parse(torn, FINANZEN));
        // Garbage ENTRIES inside a valid list are skipped, not fatal.
        List<LemmyClient.PooledPost> mixed = LemmyClient.parse(
                "{\"posts\":[{\"post\":{\"name\":\"\",\"ap_id\":\"\"}},"
                        + "{\"nonsense\":true},"
                        + "{\"post\":{\"name\":\"Titel\",\"ap_id\":\"https://x/post/1\","
                        + "\"published\":\"gestern\"}}]}", FINANZEN);
        assertEquals(1, mixed.size(), "incomplete entries are skipped, the rest survives");
        assertNull(mixed.get(0).item().publishedAt(),
                "an unparseable published yields null, never a guessed timestamp");
    }

    @Test
    void softTwoHundredGarbageIsAMissAndNeverPoisonsThePool() {
        FakeFetcher fetcher = new FakeFetcher()
                .answer(FEDDIT_URL,
                        new WebResponse(200, "<!doctype html><html>wall</html>", Map.of()),
                        new WebResponse(200, feddit(), Map.of()))
                .answer(WORLD_URL, new WebResponse(500, "", Map.of()));
        LemmyClient client = new LemmyClient(fetcher);

        assertTrue(client.newsForName("IBM", 10).isEmpty(),
                "an HTML 200 is a miss, a 500 leg stays empty — no crash");
        assertEquals(1, client.newsForName("IBM", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.fetches.get(FEDDIT_URL).get());
    }

    @Test
    void symbolAndIsinLegsAreNoOps() {
        FakeFetcher fetcher = FakeFetcher.live();
        LemmyClient client = new LemmyClient(fetcher);
        assertTrue(client.newsFor("IBM", 10).isEmpty(), "symbol leg is a no-op");
        assertTrue(client.newsForIsin("US4592001014", 10).isEmpty(), "ISIN leg is a no-op");
        assertTrue(client.newsForName("", 10).isEmpty());
        assertTrue(client.newsForName(null, 10).isEmpty());
        assertTrue(client.newsForName("IBM", 0).isEmpty());
        assertEquals(0, fetcher.total(),
                "no-op legs and blank/zero-limit queries never fetch");
    }

    @Test
    void summaryStripsMarkdownCapsAtFiveHundredAndAppendsTheEcho() {
        assertEquals("Text mit Link (3 Kommentare)",
                LemmyClient.summary("**Text** mit [Link](https://x.example)", 3));
        assertEquals("(0 Kommentare)", LemmyClient.summary(null, 0));
        assertEquals("(1 Kommentar)", LemmyClient.summary("", 1));
        String longBody = "wort ".repeat(200); // 1000 chars
        String capped = LemmyClient.summary(longBody, 2);
        assertTrue(capped.length() <= 500 + " … (2 Kommentare)".length(),
                "body capped near 500 chars: " + capped.length());
        assertTrue(capped.contains("…") && capped.endsWith("(2 Kommentare)"));
    }

    @Test
    void publishedParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-14T20:24:11.097863Z"),
                LemmyClient.parsePublished("2026-07-14T20:24:11.097863Z"));
        assertEquals(Instant.parse("2026-07-14T20:24:11.097863Z"),
                LemmyClient.parsePublished("2026-07-14T20:24:11.097863"),
                "a zone-less timestamp (older Lemmy versions) is UTC");
        assertNull(LemmyClient.parsePublished("gestern"));
        assertNull(LemmyClient.parsePublished(""));
        assertNull(LemmyClient.parsePublished(null));
    }

    @Test
    void matchTextIsUmlautNormalisedBothWays() {
        LemmyClient client = new LemmyClient(FakeFetcher.live());
        assertEquals(1, client.newsForName("Guenstiges Depot", 10).size(),
                "ae/oe/ue query matches an umlaut title");
        List<RawNewsItem> viaUmlaut = client.newsForName("Günstiges Depot", 10);
        assertEquals(1, viaUmlaut.size(), "umlaut query matches too");
    }
}
