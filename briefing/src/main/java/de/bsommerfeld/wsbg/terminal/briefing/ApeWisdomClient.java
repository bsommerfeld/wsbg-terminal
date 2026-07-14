package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ApeWisdom — the social pulse OUTSIDE the own cage (live-verified 2026-07-13,
 * keyless): mention/upvote ranking across the big US retail boards (WSB,
 * r/stocks, …) with yesterday's rank beside today's. The rank JUMP is the
 * signal this house cares about (a no-name shooting from rank 750 to 22 is
 * exactly the pennystock-radar pattern, just measured on the neighbour cage).
 * Unofficial endpoint, no SLA — strictly best-effort.
 */
@Singleton
public class ApeWisdomClient {

    private static final Logger LOG = LoggerFactory.getLogger(ApeWisdomClient.class);

    private static final String URL = "https://apewisdom.io/api/v1.0/filter/all-stocks/page/1";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One ranked ticker; {@code rank24hAgo}/{@code mentions24hAgo} -1 when absent. */
    public record SocialTicker(String ticker, String name, int mentions, int upvotes,
            int rank, int rank24hAgo, int mentions24hAgo) {

        /** Positive = climbed that many ranks in 24 h; 0 when yesterday is unknown. */
        public int rankClimb() {
            return rank24hAgo > 0 && rank > 0 ? rank24hAgo - rank : 0;
        }
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public ApeWisdomClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public ApeWisdomClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The first ranking page (~100 tickers, rank ascending). Empty on any failure. */
    public List<SocialTicker> topTickers() {
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return parse(resp.body());
            LOG.debug("[ApeWisdom] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[ApeWisdom] fetch failed: {}", e.getMessage());
        }
        return List.of();
    }

    /** Package-private for tests: reply JSON → tickers, network-free, garbage-tolerant. */
    static List<SocialTicker> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<SocialTicker> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) return List.of();
            for (JsonNode n : results) {
                String ticker = text(n, "ticker");
                if (ticker.isEmpty()) continue;
                // Names arrive HTML-escaped ("SPDR S&amp;P 500 ETF Trust" —
                // live 14.07 report leak) — decoded here so no consumer
                // ever prints an entity.
                out.add(new SocialTicker(ticker, decodeEntities(text(n, "name")),
                        intOf(n, "mentions"), intOf(n, "upvotes"),
                        intOf(n, "rank"), intOf(n, "rank_24h_ago"), intOf(n, "mentions_24h_ago")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** The handful of entities ApeWisdom actually emits; double-escapes unwind too. */
    static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        String prev;
        do {
            prev = s;
            s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        } while (!s.equals(prev) && s.indexOf('&') >= 0);
        return s;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    /** Numbers arrive mixed as JSON numbers and strings; absent/garbage → -1. */
    private static int intOf(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return -1;
        if (v.isNumber()) return v.asInt(-1);
        try {
            return Integer.parseInt(v.asText("").trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
