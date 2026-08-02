package de.bsommerfeld.wsbg.terminal.boersenmedien;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The cheap instrument resolver behind Börsenmedien AG - <b>not</b> a
 * {@code NewsSource}: it answers identity and price, never headlines.
 *
 * <p>Two keyless JSON endpoints, both probed live 2026-08-02:
 *
 * <ul>
 *   <li>{@code deraktionaer.de/api/remote/searchStocks?q=<Name|ISIN|WKN>} -
 *       name → instrument. Ten hits of {@code isin, wkn, name, linkUrl},
 *       relevance-ordered, no key, no cookie. Der Aktionär's
 *       {@code robots.txt} disallows {@code /suchen*}, not {@code /api/},
 *       so this is the sanctioned door.</li>
 *   <li>{@code deraktionaer.de/api/remote/symbols/?s=<ISIN>} - ISIN → name,
 *       ticker, US ticker, WKN <b>and a Tradegate EUR quote</b> (last, bid,
 *       ask, open/high/low, previous close plus a ready-made performance block
 *       from 1 day to several years).</li>
 * </ul>
 *
 * <p><b>One backend, two brands.</b> {@code boerse-online.de/symbol/remote?s=}
 * returns the exact same bytes as the Der Aktionär quote endpoint - verified by
 * comparing both responses for Siemens. The Börse Online host is therefore
 * wired as a straight fallback rather than as a second opinion. The name search
 * exists only under {@code deraktionaer.de/api/}; the matching Börse Online
 * path 404s (its own search is the HTML page {@code /suchen?q=}, which
 * {@link BoersenmedienNewsClient} uses for articles).
 *
 * <p><b>GOTCHA - {@code symbol/remote?s=} takes ISINs only.</b> Passing a name
 * answers a valid, EMPTY array ({@code []}) rather than an error, so a caller
 * that forgets to resolve first gets silence instead of a hint. Use
 * {@link #search} for names.
 *
 * <p>The EUR quote makes this a possible second leg beside the Lang &amp;
 * Schwarz / Tradegate chain: same venue, same currency, one keyless GET, no
 * crumb. No caller today - it is exposed so the DeepDive pipeline can pick it
 * up without reopening this source.
 */
@Singleton
public class BoersenmedienResolver {

    private static final Logger LOG = LoggerFactory.getLogger(BoersenmedienResolver.class);

    static final String URL_SEARCH_STOCKS =
            BoersenmedienNewsClient.HOST_DA + "/api/remote/searchStocks?q=%s";
    static final String URL_SYMBOL_DA =
            BoersenmedienNewsClient.HOST_DA + "/api/remote/symbols/?s=%s";
    /** Byte-identical twin of {@link #URL_SYMBOL_DA} - used only when the first fails. */
    static final String URL_SYMBOL_BO =
            BoersenmedienNewsClient.HOST_BO + "/symbol/remote?s=%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One instrument as the house knows it.
     *
     * @param isin     the join key
     * @param wkn      German security number
     * @param name     display name
     * @param ticker   home-venue ticker, or {@code null}
     * @param usTicker US OTC/ADR ticker, or {@code null}
     * @param linkUrl  the house's own instrument page (relative), or {@code null}
     */
    public record Instrument(String isin, String wkn, String name,
                             String ticker, String usTicker, String linkUrl) {}

    /**
     * A Tradegate EUR snapshot.
     *
     * @param isin          the instrument
     * @param name          display name at the time of the quote
     * @param currency      always {@code EUR} on the Tradegate venue
     * @param last          last traded price, or {@code null}
     * @param changePercent day change in percent, or {@code null}
     * @param previousClose previous close, or {@code null}
     * @param venue         the quoting venue as named by the house
     * @param asOf          quote timestamp, or {@code null}
     */
    public record Quote(String isin, String name, String currency, Double last,
                        Double changePercent, Double previousClose, String venue, Instant asOf) {}

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Test/default: plain direct transport. */
    public BoersenmedienResolver() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - neither host walls. */
    @Inject
    public BoersenmedienResolver(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Name, ISIN or WKN → candidate instruments, house relevance order
     * preserved. Never throws; an unavailable endpoint answers empty.
     */
    public List<Instrument> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String url = String.format(URL_SEARCH_STOCKS,
                URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        return parseSearch(get(url));
    }

    /** ISIN → the Tradegate EUR snapshot, or empty when the house has none. */
    public Optional<Quote> quote(String isin) {
        String key = BoersenmedienNewsClient.normalizeIsin(isin);
        if (key == null) return Optional.empty();
        String body = get(String.format(URL_SYMBOL_DA, key));
        if (body == null || parseQuote(body).isEmpty()) {
            body = get(String.format(URL_SYMBOL_BO, key));
        }
        return body == null ? Optional.empty() : parseQuote(body);
    }

    /** ISIN → the instrument's identity block from the quote endpoint. */
    public Optional<Instrument> byIsin(String isin) {
        String key = BoersenmedienNewsClient.normalizeIsin(isin);
        if (key == null) return Optional.empty();
        String body = get(String.format(URL_SYMBOL_DA, key));
        return body == null ? Optional.empty() : parseInstrument(body);
    }

    // ---- parsers, package-private for tests ----

    /** {@code searchStocks} answers a bare array of instruments. */
    static List<Instrument> parseSearch(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Instrument> out = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) return List.of();
            for (JsonNode n : root) {
                String isin = text(n, "isin");
                if (isin == null) continue;
                out.add(new Instrument(isin.toUpperCase(Locale.ROOT), text(n, "wkn"),
                        text(n, "name"), text(n, "ticker"), text(n, "usTicker"),
                        text(n, "linkUrl")));
            }
        } catch (Exception e) {
            LOG.debug("Unparseable Börsenmedien stock search: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /** {@code symbols/?s=} answers an array with one {@code instrument}/{@code quote} pair. */
    static Optional<Instrument> parseInstrument(String json) {
        JsonNode entry = firstEntry(json);
        if (entry == null) return Optional.empty();
        JsonNode i = entry.path("instrument");
        String isin = text(i, "isin");
        if (isin == null) return Optional.empty();
        return Optional.of(new Instrument(isin.toUpperCase(Locale.ROOT), text(i, "wkn"),
                text(i, "name"), text(i, "ticker"), text(i, "usTicker"), null));
    }

    static Optional<Quote> parseQuote(String json) {
        JsonNode entry = firstEntry(json);
        if (entry == null) return Optional.empty();
        JsonNode i = entry.path("instrument");
        JsonNode q = entry.path("quote");
        String isin = text(i, "isin");
        if (isin == null || q.isMissingNode() || q.isNull()) return Optional.empty();
        return Optional.of(new Quote(
                isin.toUpperCase(Locale.ROOT),
                text(i, "name"),
                text(q, "currency"),
                number(q, "last"),
                number(q, "changePercent"),
                number(q, "previousPrice"),
                text(i, "tradingVenueName"),
                instant(text(q, "date"))));
    }

    private static JsonNode firstEntry(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.isArray()) return root.isEmpty() ? null : root.get(0);
            return root.isObject() ? root : null;
        } catch (Exception e) {
            LOG.debug("Unparseable Börsenmedien symbol payload: {}", e.getMessage());
            return null;
        }
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("Börsenmedien {} answered status {}", url, resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Börsenmedien {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static Double number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    /** {@code 2026-07-31T20:02:55+00:00} → {@link Instant}; unparseable → null. */
    static Instant instant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim()).toInstant();
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(s.trim())
                        .toInstant(java.time.ZoneOffset.UTC);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
