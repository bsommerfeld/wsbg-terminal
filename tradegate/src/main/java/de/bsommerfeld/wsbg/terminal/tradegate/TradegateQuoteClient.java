package de.bsommerfeld.wsbg.terminal.tradegate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.VenueStats;
import de.bsommerfeld.wsbg.terminal.core.price.VenueStatsSource;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tradegate Exchange quote client — the one German retail venue that publishes
 * order-book and turnover statistics keylessly, by ISIN: bid/ask WITH sizes, the
 * day's traded shares ({@code stueck}), EUR turnover ({@code umsatz}), the number
 * of executions, day high/low and the previous close. Exactly the depth data
 * L&amp;S's chart endpoint does not carry (probed 2026-07-10: no volume series,
 * bid/ask/trade quote types return the identical mid line), which is why the
 * watchlist's "Marktdaten" panel is fed from here while the price/spark stays L&amp;S.
 *
 * <p>Endpoint: {@code https://www.tradegate.de/refresh.php?isin=<ISIN>} (301s to
 * {@code tradegatebsx.com}; the transport follows). One flat JSON object; number
 * fields arrive mixed as raw numbers or German-formatted strings ({@code "992,10"},
 * {@code "1 019,80"}) depending on magnitude — {@link #parseNumber} handles both.
 * An ISIN Tradegate doesn't list returns HTTP 200 with an EMPTY body (probed),
 * so an empty/unparseable reply is a plain miss, not an error.
 */
@Singleton
public class TradegateQuoteClient implements VenueStatsSource {

    private static final Logger LOG = LoggerFactory.getLogger(TradegateQuoteClient.class);

    private static final String QUOTE_URL = "https://www.tradegate.de/refresh.php?isin=";
    private static final String VENUE_LABEL = "Tradegate";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Test/default: plain direct transport. */
    public TradegateQuoteClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public TradegateQuoteClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Optional<VenueStats> statsByIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        try {
            WebResponse resp = fetcher.fetch(
                    QUOTE_URL + URLEncoder.encode(isin.trim(), StandardCharsets.UTF_8),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("[Tradegate] HTTP {} for isin={}", resp.status(), isin);
                return Optional.empty();
            }
            Optional<VenueStats> stats = parse(resp.body());
            if (stats.isPresent()) {
                VenueStats s = stats.get();
                LOG.info("[Tradegate] {} → last={} bid={}/{} ask={}/{} vol={} trades={}",
                        isin, fmt(s.last()), fmt(s.bid()), s.bidSize(), fmt(s.ask()), s.askSize(),
                        s.volumeShares(), s.executions());
            } else {
                LOG.info("[Tradegate] {} → not listed / empty reply", isin);
            }
            return stats;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[Tradegate] fetch for isin={} failed: {}", isin, e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses the flat refresh.php object into {@link VenueStats}, or empty. Network-free. */
    Optional<VenueStats> parse(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode n = JSON.readTree(body);
            if (!n.isObject() || n.isEmpty()) return Optional.empty();
            double last = parseNumber(n.get("last"));
            double bid = parseNumber(n.get("bid"));
            double ask = parseNumber(n.get("ask"));
            // A reply without any price figure is a placeholder, not a quote.
            if (!Double.isFinite(last) && !Double.isFinite(bid)) return Optional.empty();
            return Optional.of(new VenueStats(
                    VENUE_LABEL,
                    bid, ask,
                    parseCount(n.get("bidsize")), parseCount(n.get("asksize")),
                    last,
                    parseNumber(n.get("delta")),
                    parseNumber(n.get("high")), parseNumber(n.get("low")),
                    parseNumber(n.get("close")),
                    parseCount(n.get("stueck")), parseCount(n.get("umsatz")),
                    parseCount(n.get("executions")),
                    System.currentTimeMillis() / 1000));
        } catch (Exception e) {
            LOG.warn("[Tradegate] parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Tradegate mixes raw JSON numbers with German-formatted strings in the same
     * field across instruments ({@code 183.42} vs {@code "992,10"} vs
     * {@code "1 019,80"}): numbers pass through, strings are de-localised
     * (spaces/dots as thousands separators stripped, decimal comma → dot).
     */
    static double parseNumber(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return Double.NaN;
        if (node.isNumber()) return node.asDouble();
        String s = node.asText("").strip();
        if (s.isEmpty()) return Double.NaN;
        s = s.replaceAll("[\\s\\u00A0\\u202F]", "");
        if (s.contains(",")) s = s.replace(".", "").replace(',', '.');
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Whole-number counts (sizes, shares, EUR turnover, executions); -1 unknown. */
    static long parseCount(JsonNode node) {
        double d = parseNumber(node);
        return Double.isFinite(d) && d >= 0 ? Math.round(d) : -1;
    }

    private static String fmt(double d) {
        return Double.isFinite(d) ? String.format(Locale.ROOT, "%.2f", d) : "n/a";
    }
}
