package de.bsommerfeld.wsbg.terminal.tradegate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tradegate price client — the daytime fallback below Lang &amp; Schwarz. Keyed on
 * ISIN (which L&amp;S hands down). Single live quote, EUR, no sparkline and no
 * after-hours (Tradegate closes 22:00). German ISINs answer on {@code tradegate.de};
 * US ISINs 301-redirect to {@code tradegatebsx.com}, so both hosts are tried.
 *
 * <p>{@code refresh.php?isin=…} returns {@code {"bid","ask","last","high","low","umsatz",…}}
 * (German number formatting tolerated). Returns {@link Optional#empty()} on any failure.
 */
@Singleton
public class TradegateClient {

    private static final Logger LOG = LoggerFactory.getLogger(TradegateClient.class);

    private static final String[] HOSTS = {
            "https://www.tradegate.de/refresh.php?isin=",
            "https://www.tradegatebsx.com/refresh.php?isin=",
    };

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Test/default: plain direct transport. */
    public TradegateClient() {
        this(new DirectWebFetcher());
    }

    /** Production: rides the shared browser-joker {@link WebFetcher} chain. */
    @Inject
    public TradegateClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Live EUR snapshot for an ISIN, or empty. Tries both Tradegate hosts. */
    public Optional<MarketSnapshot> fetchSnapshot(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        for (String host : HOSTS) {
            try {
                WebResponse resp = fetcher.fetch(host + isin.trim(),
                        Map.of("User-Agent", userAgent, "Accept", "application/json"),
                        requestTimeout);
                if (resp.status() != 200 || resp.body() == null || resp.body().isBlank()) continue;
                Optional<MarketSnapshot> snap = parse(resp.body(), isin.trim());
                if (snap.isPresent()) return snap;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.debug("Tradegate {} failed for {}: {}", host, isin, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Parses a refresh.php body into an EUR snapshot. Package-private, network-free. */
    Optional<MarketSnapshot> parse(String body, String isin) {
        try {
            JsonNode n = JSON.readTree(body);
            double last = num(n, "last");
            if (!Double.isFinite(last)) return Optional.empty();
            double high = num(n, "high");
            double low = num(n, "low");
            long volume = (long) num(n, "umsatz");
            // Tradegate carries no previous close → day move unknown (NaN).
            return Optional.of(new MarketSnapshot(
                    isin, last, Double.NaN, Double.NaN, high, low,
                    volume < 0 ? -1 : volume, Double.NaN, Double.NaN,
                    "EUR", "Tradegate", Instant.now().getEpochSecond(), List.of()));
        } catch (Exception e) {
            LOG.debug("Tradegate parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Reads a numeric field tolerating German formatting ("1.234,56" / "12,5"). */
    private static double num(JsonNode parent, String field) {
        JsonNode v = parent.path(field);
        if (v.isNumber()) return v.asDouble();
        String s = v.asText("").trim();
        if (s.isEmpty()) return Double.NaN;
        // German format only when a comma decimal is present: "1.234,56" → "1234.56".
        // A plain "12.5" (no comma) is already dot-decimal — leave it.
        if (s.indexOf(',') >= 0) s = s.replace(".", "").replace(',', '.');
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
