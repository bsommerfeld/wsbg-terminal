package de.bsommerfeld.wsbg.terminal.handelsblatt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Handelsblatt's keyless ISIN endpoint
 * ({@code market.www.handelsblatt.com/api/isin/<ISIN>}, probed 2026-08-02):
 * name, WKN, ticker, sector, issuer profile, last price and the full
 * performance ladder from one week to five years plus EPS and dividend - all
 * from a single call, no key, no cookie, no {@code User-Agent}.
 *
 * <p>NOT a {@code NewsSource} on purpose: this is reference data for the
 * deep-dive pipeline, addressed per instrument. It stands ready and documented;
 * nothing polls it today.
 *
 * <p>The endpoint answers an ARRAY of venue snapshots (Stuttgart first at the
 * time of probing); the first entry is taken. Its {@code charset=utf-8}
 * response is double-encoded at the source ({@code ansässige} arrives as
 * {@code ansÃ¤ssige}), so text fields are repaired on the way out.
 */
@Singleton
public class HandelsblattMarketClient {

    private static final Logger LOG = LoggerFactory.getLogger(HandelsblattMarketClient.class);

    static final String ISIN_URL = "https://market.www.handelsblatt.com/api/isin/%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ISIN_SHAPE = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    /** Telltale of the publisher's double UTF-8 encoding. */
    private static final Pattern MOJIBAKE = Pattern.compile("[ÂÃ][\\u0080-\\u00BF]");

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public HandelsblattMarketClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public HandelsblattMarketClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Master data plus snapshot for one ISIN.
     *
     * @return the instrument, or empty when the ISIN is malformed, unknown or
     *         the endpoint failed - never {@code null}
     */
    public Optional<HandelsblattInstrument> byIsin(String isin) {
        if (isin == null) return Optional.empty();
        String v = isin.trim().toUpperCase(Locale.ROOT);
        if (!ISIN_SHAPE.matcher(v).matches()) return Optional.empty();
        try {
            WebResponse resp = fetcher.fetch(String.format(ISIN_URL, v),
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) return parse(resp.body());
            LOG.debug("Handelsblatt market answered status {} for {}",
                    resp == null ? "null" : resp.status(), v);
        } catch (Exception e) {
            LOG.debug("Handelsblatt market failed for {}: {}", v, e.getMessage());
        }
        return Optional.empty();
    }

    /** Parses the endpoint's array payload. Package-private for tests. */
    static Optional<HandelsblattInstrument> parse(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode entry = root.isArray() ? (root.isEmpty() ? null : root.get(0)) : root;
            if (entry == null || !entry.isObject()) return Optional.empty();

            JsonNode instrument = entry.path("reference").path("instrument");
            JsonNode company = entry.path("reference").path("company");
            JsonNode listing = entry.path("reference").path("listing");
            JsonNode perf = entry.path("snapshot").path("performance");
            JsonNode fundamentals = entry.path("snapshot").path("fundamentals");

            String isin = text(instrument, "isin");
            if (isin == null) return Optional.empty();

            return Optional.of(new HandelsblattInstrument(
                    text(instrument, "name"),
                    text(company, "name"),
                    isin,
                    text(instrument, "wkn"),
                    text(instrument.path("identifier"), "ticker"),
                    text(instrument, "currency"),
                    text(instrument, "classification"),
                    text(company, "sector"),
                    text(company.path("country"), "name"),
                    text(listing, "marketplace"),
                    text(company, "description"),
                    number(entry.path("snapshot").path("trade"), "last"),
                    number(perf, "oneWeekPercent"),
                    number(perf, "oneMonthPercent"),
                    number(perf, "threeMonthPercent"),
                    number(perf, "sixMonthsPercent"),
                    number(perf, "oneYearPercent"),
                    number(perf, "fiveYearsPercent"),
                    number(perf, "yearToDatePercent"),
                    number(fundamentals.path("eps"), "current"),
                    number(fundamentals.path("dividend"), "current"),
                    number(fundamentals.path("dividend"), "yield")));
        } catch (Exception e) {
            LOG.warn("Unparseable Handelsblatt market payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Repairs the publisher's double UTF-8 encoding ({@code ansÃ¤ssige} →
     * {@code ansässige}) and leaves clean text untouched. Package-private for
     * tests.
     */
    static String repairEncoding(String s) {
        if (s == null || s.isEmpty() || !MOJIBAKE.matcher(s).find()) return s;
        String repaired = new String(s.getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.UTF_8);
        return repaired.indexOf('�') >= 0 ? s : repaired;
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = repairEncoding(v.asText("")).trim();
        return s.isEmpty() ? null : s;
    }

    private static Double number(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }
}
