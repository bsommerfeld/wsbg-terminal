package de.bsommerfeld.wsbg.terminal.web.impl.sources.telegram;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
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
 * markup, zero {@code tgme_widget_message} blocks). Ported from the module
 * world; the collector delivers every message unfiltered — the pool TTL and
 * name matching left with the old NewsSource fan, the dead-channel logic for
 * the preview opt-out stays 1:1.
 */
class TelegramChannelSourceTest {

    private static String resource(String name) {
        try (InputStream in = TelegramChannelSourceTest.class.getResourceAsStream(name)) {
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
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            fetches.computeIfAbsent(url, k -> new AtomicInteger()).incrementAndGet();
            Deque<WebResponse> queue = replies.get(url);
            if (queue == null) throw new IllegalStateException("unexpected URL " + url);
            WebResponse next = queue.poll();
            if (next == null) throw new IllegalStateException("no reply left for " + url);
            if (queue.isEmpty()) queue.add(next); // last reply repeats
            return next;
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

    private static WebResponse ok(String body) {
        return WebResponse.text(200, body, Map.of());
    }

    @Test
    void parseMapsTheLivePreviewFields() {
        List<Article> items =
                TelegramChannelSource.parse("finanzen_net", channelFixture());
        assertEquals(3, items.size(), "all text-bearing message blocks parse");

        Article infineon = items.get(0);
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
        Article nutrien = items.get(1);
        assertEquals("finanzen_net/522340", nutrien.uuid());
        assertTrue(nutrien.summary().contains("Nutrien (Ex Potash Agrium) Hold"));

        assertEquals("finanzen_net/522358", items.get(2).uuid());
        assertEquals(Instant.parse("2026-07-16T12:47:34Z"), items.get(2).publishedAt());
    }

    @Test
    void parseToleratesGarbageAndAlienAnswers() {
        assertTrue(TelegramChannelSource.parse("x", "<html><body>404</body></html>").isEmpty());
        assertTrue(TelegramChannelSource.parse("x", "not html { \"json\": true }").isEmpty());
        assertTrue(TelegramChannelSource.parse("x", "").isEmpty());
        assertTrue(TelegramChannelSource.parse("x", null).isEmpty());
        // The profile card (preview-off answer) parses to nothing, never throws.
        assertTrue(TelegramChannelSource.parse("x", profileCardFixture()).isEmpty());
        // Torn mid-block (a truncated response) — parses the complete blocks.
        String fixture = channelFixture();
        String torn = fixture.substring(0,
                fixture.indexOf("finanzen_net/522358") + 30);
        List<Article> items = TelegramChannelSource.parse("finanzen_net", torn);
        assertEquals(2, items.size(), "the two complete blocks survive the tear");
    }

    @Test
    void previewOffChannelIsMarkedDeadAndNeverRefetched() {
        FakeFetcher fetcher = new FakeFetcher()
                .reply("finanzen_net", ok(channelFixture()))
                .reply("boerse_online", ok(profileCardFixture()));
        TelegramChannelSource source = new TelegramChannelSource(
                fetcher, List.of("finanzen_net", "boerse_online"));

        assertEquals(3, source.collect().size(),
                "the live channel's messages arrive, the opted-out channel adds none");
        source.collect();
        source.collect();

        assertEquals(3, fetcher.fetchCount("finanzen_net"),
                "a live channel is fetched on every collection pass — no internal TTL, "
                        + "cadence is the scheduler's job");
        assertEquals(1, fetcher.fetchCount("boerse_online"),
                "the profile-card answer (preview off) marks the channel dead "
                        + "for the session — never fetched again");
    }

    @Test
    void transportFailureStaysTransientNotDead() {
        FakeFetcher fetcher = new FakeFetcher()
                .reply("finanzen_net", WebResponse.text(503, "", Map.of()), ok(channelFixture()));
        TelegramChannelSource source = new TelegramChannelSource(
                fetcher, List.of("finanzen_net"));

        assertTrue(source.collect().isEmpty(), "the 503 pass yields nothing");
        assertEquals(3, source.collect().size(),
                "a failed channel is retried, not marked dead");
        assertEquals(2, fetcher.fetchCount("finanzen_net"));
    }

    @Test
    void sourceSelfDescription() {
        TelegramChannelSource source = new TelegramChannelSource(
                new FakeFetcher(), List.of("finanzen_net"));
        assertEquals("telegram", source.sourceName());
        assertTrue(source.socialSentiment(),
                "room opinion rides the sentiment fan, never the press loom");
        assertEquals(FetchUtil.DIRECT, source.mode()[0], "direct-first transport");
    }
}
