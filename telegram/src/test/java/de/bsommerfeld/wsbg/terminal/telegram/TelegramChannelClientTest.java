package de.bsommerfeld.wsbg.terminal.telegram;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telegram web preview — fixtures are live response excerpts (2026-07-16):
 * {@code telegram-channel.html} is {@code t.me/s/finanzen_net} trimmed to 3
 * of its 20 message blocks (markup verbatim), {@code telegram-profile-card.html}
 * is the FULL answer a preview-disabled channel redirects to
 * ({@code t.me/s/boerse_online} → 302 → profile card, {@code tgme_page}
 * markup, zero {@code tgme_widget_message} blocks).
 */
class TelegramChannelClientTest {

    private static String resource(String name) {
        try (InputStream in = TelegramChannelClientTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    private static String channelFixture() {
        return resource("/telegram-channel.html");
    }

    private static String profileCardFixture() {
        return resource("/telegram-profile-card.html");
    }

    /** Fake transport answering per-URL response sequences, counting fetches per URL. */
    private static final class FakeFetcher implements WebFetcher {
        final Map<String, AtomicInteger> fetches = new HashMap<>();
        private final Map<String, Deque<WebResponse>> replies = new HashMap<>();

        FakeFetcher reply(String channel, WebResponse... responses) {
            Deque<WebResponse> queue = replies.computeIfAbsent(
                    "https://t.me/s/" + channel, k -> new ArrayDeque<>());
            for (WebResponse r : responses) queue.add(r);
            return this;
        }

        int fetchCount(String channel) {
            AtomicInteger n = fetches.get("https://t.me/s/" + channel);
            return n == null ? 0 : n.get();
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            fetches.computeIfAbsent(url, k -> new AtomicInteger()).incrementAndGet();
            Deque<WebResponse> queue = replies.get(url);
            if (queue == null) throw new IllegalStateException("unexpected URL " + url);
            WebResponse next = queue.poll();
            if (next == null) throw new IllegalStateException("no reply left for " + url);
            if (queue.isEmpty()) queue.add(next); // last reply repeats
            return next;
        }
    }

    private static WebResponse ok(String body) {
        return new WebResponse(200, body, Map.of());
    }

    @Test
    void parseMapsTheLivePreviewFields() {
        List<RawNewsItem> items =
                TelegramChannelClient.parse("finanzen_net", channelFixture());
        assertEquals(3, items.size(), "all text-bearing message blocks parse");

        RawNewsItem infineon = items.get(0);
        assertEquals("finanzen_net/522339", infineon.uuid(),
                "uuid is the data-post value verbatim");
        assertEquals("https://t.me/finanzen_net/522339", infineon.link());
        assertEquals("Telegram (@finanzen_net)", infineon.publisher());
        assertEquals(Instant.parse("2026-07-16T12:12:28Z"), infineon.publishedAt(),
                "<time datetime> is ISO-8601 with a +00:00 offset");
        assertNull(infineon.isin());

        // Summary = the FULL text: tags stripped (emoji survive as text),
        // whitespace collapsed, the trailing channel self-link included.
        String summary = infineon.summary();
        assertTrue(summary.startsWith("🗞 Infineon-Aktie fällt trotz starker TSMC-Zahlen"),
                "emoji and umlauts survive the tag strip: " + summary);
        assertTrue(summary.contains("https://www.finanzen.net/nachricht/aktien/"),
                "the linked article URL stays in the text");
        assertFalse(summary.contains("<"), "HTML tags are stripped");

        // Title = the first ~120 chars of the text, cut at a word boundary.
        assertTrue(infineon.title().endsWith("…"),
                "a long message is truncated to a headline: " + infineon.title());
        assertTrue(infineon.title().length() <= 121);
        assertTrue(infineon.title().startsWith("🗞 Infineon-Aktie"));

        // A short message keeps its full text as the title, no ellipsis.
        RawNewsItem nutrien = items.get(1);
        assertEquals("finanzen_net/522340", nutrien.uuid());
        assertTrue(nutrien.summary().contains("Nutrien (Ex Potash Agrium) Hold"));

        assertEquals("finanzen_net/522358", items.get(2).uuid());
        assertEquals(Instant.parse("2026-07-16T12:47:34Z"), items.get(2).publishedAt());
    }

    @Test
    void parseToleratesGarbageAndAlienAnswers() {
        assertTrue(TelegramChannelClient.parse("x", "<html><body>404</body></html>").isEmpty());
        assertTrue(TelegramChannelClient.parse("x", "not html { \"json\": true }").isEmpty());
        assertTrue(TelegramChannelClient.parse("x", "").isEmpty());
        assertTrue(TelegramChannelClient.parse("x", null).isEmpty());
        // The profile card (preview-off answer) parses to nothing, never throws.
        assertTrue(TelegramChannelClient.parse("x", profileCardFixture()).isEmpty());
        // Torn mid-block (a truncated response) — parses the complete blocks.
        String fixture = channelFixture();
        String torn = fixture.substring(0,
                fixture.indexOf("finanzen_net/522358") + 30);
        List<RawNewsItem> items = TelegramChannelClient.parse("finanzen_net", torn);
        assertEquals(2, items.size(), "the two complete blocks survive the tear");
    }

    @Test
    void previewOffChannelIsMarkedDeadAndNeverRefetched() {
        FakeFetcher fetcher = new FakeFetcher()
                .reply("finanzen_net", ok(channelFixture()))
                .reply("boerse_online", ok(profileCardFixture()));
        // TTL zero: every query is a fresh fetch round — only the dead-set
        // may prevent a refetch.
        TelegramChannelClient client = new TelegramChannelClient(
                fetcher, List.of("finanzen_net", "boerse_online"), Duration.ZERO);

        assertEquals(3, client.newsForName("Infineon Nutrien Acadia", 10).size());
        client.newsForName("Infineon", 10);
        client.newsForName("Acadia", 10);

        assertEquals(3, fetcher.fetchCount("finanzen_net"),
                "a live channel is fetched every round at TTL zero");
        assertEquals(1, fetcher.fetchCount("boerse_online"),
                "the profile-card answer (preview off) marks the channel dead "
                        + "for the session — never fetched again");
    }

    @Test
    void transportFailureStaysTransientNotDead() {
        FakeFetcher fetcher = new FakeFetcher()
                .reply("finanzen_net", new WebResponse(503, "", Map.of()), ok(channelFixture()));
        TelegramChannelClient client = new TelegramChannelClient(
                fetcher, List.of("finanzen_net"), Duration.ZERO);

        assertTrue(client.newsForName("Infineon", 10).isEmpty(), "the 503 round yields nothing");
        assertEquals(1, client.newsForName("Infineon", 10).size(),
                "a failed channel is retried, not marked dead");
        assertEquals(2, fetcher.fetchCount("finanzen_net"));
    }

    @Test
    void poolIsCachedOneFetchPerChannelPerTtl() {
        FakeFetcher fetcher = new FakeFetcher()
                .reply("finanzen_net", ok(channelFixture()));
        TelegramChannelClient client = new TelegramChannelClient(
                fetcher, List.of("finanzen_net")); // default 5-min TTL

        client.newsForName("Infineon", 10);
        client.newsForName("Acadia", 10);
        client.newsForName("Nutrien", 10);

        assertEquals(1, fetcher.fetchCount("finanzen_net"),
                "a query burst makes exactly ONE fetch per channel per TTL");
    }

    @Test
    void precisionFilterMatchesTheMessageTextNotJustTheHeadline() {
        FakeFetcher fetcher = new FakeFetcher()
                .reply("finanzen_net", ok(channelFixture()));
        TelegramChannelClient client =
                new TelegramChannelClient(fetcher, List.of("finanzen_net"));

        List<RawNewsItem> hits = client.newsForName("Infineon Technologies", 10);
        assertEquals(1, hits.size(), "significant-word match, generic words ignored");
        assertEquals("finanzen_net/522339", hits.get(0).uuid());

        // "KeyBanc" only occurs inside the hyphenated token "KeyBanc-Analyst" —
        // the word-boundary match must see through the hyphen.
        List<RawNewsItem> hyphen = client.newsForName("KeyBanc", 10);
        assertEquals(1, hyphen.size());
        assertEquals("finanzen_net/522358", hyphen.get(0).uuid());

        // "Fokus" only occurs in the Infineon post's URL slug, PAST the
        // 120-char headline cut — the filter runs against the FULL message
        // text, never just the derived title.
        List<RawNewsItem> deep = client.newsForName("Fokus", 10);
        assertEquals(1, deep.size());
        assertEquals("finanzen_net/522339", deep.get(0).uuid());
        assertFalse(deep.get(0).title().toLowerCase().contains("fokus"),
                "the match word is not in the title — proof the text was filtered");

        assertTrue(client.newsForName("Rheinmetall", 10).isEmpty(),
                "an unmentioned company matches nothing");
        assertTrue(client.newsForName("AG Holding Group", 10).isEmpty(),
                "a name of only generic words never floods the pool");
        assertEquals(0, fetcher.fetchCount("finanzen_net") - 1,
                "all queries shared the one cached pool");
    }

    @Test
    void instrumentAddressedQueriesAreNoOps() {
        TelegramChannelClient client = new TelegramChannelClient(
                new FakeFetcher(), List.of("finanzen_net"));
        assertTrue(client.newsFor("IFX.DE", 10).isEmpty());
        assertTrue(client.newsForIsin("DE0006231004", 10).isEmpty());
        assertEquals("telegram", client.sourceName());
    }
}
