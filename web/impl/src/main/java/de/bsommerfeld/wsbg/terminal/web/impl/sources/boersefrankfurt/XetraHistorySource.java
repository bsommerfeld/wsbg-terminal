package de.bsommerfeld.wsbg.terminal.web.impl.sources.boersefrankfurt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Börse Frankfurt's OWN TradingView bridge
 * ({@code api.boerse-frankfurt.de/v1/tradingview/history?symbol=XETR:<ISIN>},
 * probed 2026-08-05): ten years of daily Xetra OHLC bars in one keyless call -
 * no salt, no tracing headers, none of the ceremony the deeper
 * {@code /v1/data} endpoints demand. The venue's own tape for every ISIN
 * Xetra lists, US names included (AAPL answered 2,530 bars).
 *
 * <p>The {@code v} column is EUR TURNOVER, not shares - verified against
 * onvista's Xetra share counts (3.23M shares × 169.26 EUR ≈ the 547M the
 * column carried). Callers must label it as money, never as units.
 *
 * <p>Only the {@code XETR:} prefix works; other MICs answer 500 - the live
 * venue snapshot for those rides the {@code price_information} SSE stream,
 * not this bridge.
 */
@Singleton
public class XetraHistorySource {

    private static final Logger LOG = LoggerFactory.getLogger(XetraHistorySource.class);

    static final String HISTORY_URL = "https://api.boerse-frankfurt.de/v1/tradingview/"
            + "history?symbol=XETR%%3A%s&resolution=D&from=%d&to=%d";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ISIN_SHAPE = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final ZoneId FRANKFURT = ZoneId.of("Europe/Berlin");

    /** Browser-first (old unannotated seam binding). */
    private static final FetchUtil[] MODES = {FetchUtil.BROWSER, FetchUtil.DIRECT};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);

    @Inject
    public XetraHistorySource(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** One daily Xetra bar. {@code turnoverEur} is MONEY traded, not shares. */
    public record XetraBar(LocalDate date, double open, double high, double low,
            double close, double turnoverEur) {}

    /** The venue's daily history, oldest first. */
    public record XetraHistory(String isin, List<XetraBar> bars) {

        public boolean isEmpty() {
            return bars.isEmpty();
        }
    }

    /**
     * Daily Xetra bars for one ISIN from {@code from} to today.
     *
     * @return the history, or empty when the ISIN is malformed, unlisted or
     *         the endpoint failed - never {@code null}
     */
    public Optional<XetraHistory> history(String isin, LocalDate from) {
        if (isin == null || from == null) return Optional.empty();
        String v = isin.trim().toUpperCase(Locale.ROOT);
        if (!ISIN_SHAPE.matcher(v).matches()) return Optional.empty();
        try {
            long fromEpoch = from.atStartOfDay(FRANKFURT).toEpochSecond();
            long toEpoch = Instant.now().getEpochSecond();
            WebResponse resp = fetcher.fetch(
                    String.format(Locale.ROOT, HISTORY_URL, v, fromEpoch, toEpoch),
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[BF-Xetra] status {} for {}",
                        resp == null ? "null" : resp.status(), v);
                return Optional.empty();
            }
            Optional<XetraHistory> history = parse(v, resp.body());
            history.ifPresent(h -> LOG.info("[BF-Xetra] {} → {} daily bar(s), {} to {}",
                    v, h.bars().size(),
                    h.bars().isEmpty() ? "-" : h.bars().get(0).date(),
                    h.bars().isEmpty() ? "-" : h.bars().get(h.bars().size() - 1).date()));
            return history;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[BF-Xetra] fetch for {} failed: {}", v, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses the columnar TradingView reply ({@code s/t/o/h/l/c/v} parallel
     * arrays) - a short column truncates rather than corrupts. Package-private
     * for tests.
     */
    static Optional<XetraHistory> parse(String isin, String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!"ok".equalsIgnoreCase(root.path("s").asText())) return Optional.empty();
            JsonNode t = root.path("t");
            JsonNode o = root.path("o");
            JsonNode h = root.path("h");
            JsonNode l = root.path("l");
            JsonNode c = root.path("c");
            JsonNode v = root.path("v");
            int n = Math.min(t.size(), c.size());
            List<XetraBar> bars = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double close = c.path(i).asDouble(Double.NaN);
                if (!Double.isFinite(close) || close <= 0) continue;
                LocalDate date = Instant.ofEpochSecond(t.path(i).asLong())
                        .atZone(FRANKFURT).toLocalDate();
                bars.add(new XetraBar(date,
                        num(o, i), num(h, i), num(l, i), close, num(v, i)));
            }
            if (bars.isEmpty()) return Optional.empty();
            bars.sort((a, b) -> a.date().compareTo(b.date()));
            return Optional.of(new XetraHistory(isin, List.copyOf(bars)));
        } catch (Exception e) {
            LOG.warn("[BF-Xetra] unparseable payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static double num(JsonNode column, int i) {
        JsonNode v = column.path(i);
        return v.isNumber() ? v.asDouble() : Double.NaN;
    }
}
