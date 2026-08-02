package de.bsommerfeld.wsbg.terminal.stocknear;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests for the Stocknear fallback leg.
 *
 * <p>The payload SHAPES in these fixtures are transcribed from Stocknear's own
 * AGPL frontend (type declarations in {@code stocks/heatmap/+page.svelte} and
 * the field reads in {@code reddit-tracker/+page.svelte}, 2026-08-02); the SSR
 * WRAPPER around them is reconstructed, because the live pages sit behind a
 * Cloudflare challenge that only the JCEF joker can pass at runtime. The tests
 * therefore prove the parser is wrapper-agnostic - which is exactly the
 * property that matters when the wrapper is the part we could not verify.
 */
class StocknearClientTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = StocknearClientTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- heatmap ----

    @Test
    void parsesSectorNodesAndEquityLeaves() throws Exception {
        List<StocknearClient.HeatmapNode> nodes =
                StocknearClient.parseHeatmap(fixture("stocknear-heatmap-ssr.html"));

        assertEquals(4, nodes.size());
        List<StocknearClient.HeatmapNode> sectors =
                nodes.stream().filter(StocknearClient.HeatmapNode::isSector).toList();
        assertEquals(2, sectors.size(), "Technology and Energy are containers");
        assertTrue(sectors.stream().allMatch(n -> n.symbol() == null));
    }

    @Test
    void leafCarriesPerformanceWeightAndReference() throws Exception {
        StocknearClient.HeatmapNode nvda =
                StocknearClient.parseHeatmap(fixture("stocknear-heatmap-ssr.html")).stream()
                        .filter(n -> "NVDA".equals(n.symbol())).findFirst().orElseThrow();

        assertEquals("Technology", nvda.parent());
        assertFalse(nvda.isSector());
        assertEquals(2.9276, nvda.performancePercent());
        assertEquals(4.512e12, nvda.weight());
        assertEquals(200.75, nvda.currentPrice());
        assertEquals(195.04, nvda.referencePrice());
        assertEquals("2026-07-31", nvda.referenceDate());
    }

    @Test
    void negativePerformanceSurvivesUnclamped() throws Exception {
        StocknearClient.HeatmapNode aapl =
                StocknearClient.parseHeatmap(fixture("stocknear-heatmap-ssr.html")).stream()
                        .filter(n -> "AAPL".equals(n.symbol())).findFirst().orElseThrow();

        assertEquals(-7.3539, aapl.performancePercent(), "a red cell must stay red");
    }

    @Test
    void heatmapGarbageYieldsEmptyList() {
        assertTrue(StocknearClient.parseHeatmap("<html>no payload here</html>").isEmpty());
        assertTrue(StocknearClient.parseHeatmap("").isEmpty());
        assertTrue(StocknearClient.parseHeatmap(null).isEmpty());
    }

    // ---- mentions ----

    @Test
    void parsesMentionCountsForTheRequestedWindow() throws Exception {
        List<StocknearClient.MentionCount> day =
                StocknearClient.parseMentions(fixture("stocknear-reddit-ssr.html"), "oneDay");

        assertEquals(2, day.size());
        StocknearClient.MentionCount top = day.get(0);
        assertEquals("NVDA", top.symbol());
        assertEquals(184, top.mentions());
        assertEquals("Bullish", top.sentiment());
        assertEquals(2.93, top.changePercent());
    }

    @Test
    void differentWindowsYieldDifferentRows() throws Exception {
        String html = fixture("stocknear-reddit-ssr.html");

        List<StocknearClient.MentionCount> week =
                StocknearClient.parseMentions(html, "oneWeek");

        assertEquals(1, week.size());
        assertEquals("SPY", week.get(0).symbol());
        assertNull(week.get(0).marketCap(), "an ETF row carries no market cap - null, not zero");
    }

    @Test
    void emptyWindowYieldsEmptyList() throws Exception {
        assertTrue(StocknearClient.parseMentions(
                fixture("stocknear-reddit-ssr.html"), "threeMonths").isEmpty());
    }

    @Test
    void mentionGarbageYieldsEmptyList() {
        assertTrue(StocknearClient.parseMentions("<html/>", "oneDay").isEmpty());
        assertTrue(StocknearClient.parseMentions(null, "oneDay").isEmpty());
        assertTrue(StocknearClient.parseMentions("<html/>", null).isEmpty());
    }

    // ---- posts ----

    @Test
    void parsesPostsAndAbsolutisesThePermalink() throws Exception {
        List<StocknearClient.MentionPost> posts =
                StocknearClient.parsePosts(fixture("stocknear-posts.json"));

        assertEquals(2, posts.size());
        StocknearClient.MentionPost first = posts.get(0);
        assertEquals("NVDA YOLO update", first.title());
        assertEquals("apefingers", first.author());
        assertTrue(first.url().startsWith("https://reddit.com/r/wallstreetbets/"));
        assertNotNull(first.createdAt());
        assertEquals(312, first.comments());
        assertEquals(7, first.tickerMentions());
        assertEquals("YOLO", first.flair());
    }

    @Test
    void postGarbageYieldsEmptyList() {
        assertTrue(StocknearClient.parsePosts("{\"not\":\"an array\"}").isEmpty());
        assertTrue(StocknearClient.parsePosts("nope").isEmpty());
        assertTrue(StocknearClient.parsePosts(null).isEmpty());
    }

    // ---- challenge detection ----

    @Test
    void recognisesTheCloudflareChallengeDespiteItsStatus() throws Exception {
        String challenge = fixture("stocknear-challenge.html");

        assertTrue(StocknearClient.looksLikeChallenge(challenge),
                "a challenge page is an HTTP 200 - it must be caught by content");
        assertFalse(StocknearClient.looksLikeChallenge(
                fixture("stocknear-heatmap-ssr.html")));
        assertFalse(StocknearClient.looksLikeChallenge(null));
    }

    @Test
    void aChallengeYieldsNoDataRatherThanARuinedMap() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(fixture("stocknear-challenge.html"), 200);
        StocknearClient client = new StocknearClient(fetcher);

        assertTrue(client.heatmap("SPY", "1D").isEmpty());
        assertTrue(client.mentions("wallstreetbets", "oneDay").isEmpty());
    }

    // ---- payload extraction is wrapper-agnostic ----

    @Test
    void findsThePayloadRegardlessOfTheSurroundingWrapper() {
        String inner = "{\"data\":[{\"id\":\"X\",\"colorValue\":1.5}]}";
        for (String wrapper : List.of(
                "<script>kit.start(app, el, {data:[null,%s]});</script>",
                "<script type=\"application/json\">%s</script>",
                "<div data-payload='%s'></div>",
                "%s")) {
            String html = String.format(wrapper, inner);
            assertFalse(StocknearClient.parseHeatmap(html).isEmpty(),
                    "payload must be found in wrapper: " + wrapper);
        }
    }

    @Test
    void bracesInsideStringsDoNotBreakExtraction() {
        String html = "<script>x={\"note\":\"a } brace [ in text\",\"data\":"
                + "[{\"id\":\"X\",\"colorValue\":2.0}]}</script>";

        assertEquals(1, StocknearClient.parseHeatmap(html).size());
    }

    // ---- input gates ----

    @Test
    void unknownIndexPeriodOrSubredditNeverHitsTheNetwork() {
        FakeFetcher fetcher = new FakeFetcher("{}", 200);
        StocknearClient client = new StocknearClient(fetcher);

        assertTrue(client.heatmap("IWM", "1D").isEmpty());
        assertTrue(client.heatmap("SPY", "5Y").isEmpty());
        assertTrue(client.mentions("wallstreetbetsGER", "oneDay").isEmpty());
        assertTrue(client.mentions("stocks", "yesterday").isEmpty());
        assertTrue(client.posts("stocks", "  ", "oneDay").isEmpty());
        assertTrue(fetcher.calls.isEmpty(), "input validation happens before any fetch");
    }

    @Test
    void ourOwnCageIsNotAmongTheCoveredSubreddits() {
        assertFalse(StocknearClient.SUBREDDITS.contains("wallstreetbetsGER"),
                "the cage is counted in-house, never bought in");
        assertTrue(StocknearClient.SUBREDDITS.contains("wallstreetbets"));
    }

    private static final class FakeFetcher implements WebFetcher {
        private final String body;
        private final int status;
        final List<String> calls = new ArrayList<>();

        FakeFetcher(String body, int status) {
            this.body = body;
            this.status = status;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            calls.add(url);
            return new WebResponse(status, body, Map.of());
        }
    }
}
