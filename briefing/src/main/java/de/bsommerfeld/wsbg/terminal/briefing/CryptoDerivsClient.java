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
import java.util.Map;
import java.util.Optional;

/**
 * Crypto derivatives temperature in three keyless calls (live-verified
 * 2026-07-13 from a German IP): Binance futures' BTC funding rate + open
 * interest ({@code fapi.binance.com} — geoblocked from US/UK networks, clean
 * from DE, which is where this app runs) and Deribit's DVOL index, the "VIX
 * for Bitcoin". Together they answer "sind die Perps heißgelaufen?" — a
 * dimension the spot price alone can't show.
 */
@Singleton
public class CryptoDerivsClient {

    private static final Logger LOG = LoggerFactory.getLogger(CryptoDerivsClient.class);

    private static final String FUNDING_URL =
            "https://fapi.binance.com/fapi/v1/premiumIndex?symbol=BTCUSDT";
    private static final String OPEN_INTEREST_URL =
            "https://fapi.binance.com/fapi/v1/openInterest?symbol=BTCUSDT";
    private static final String DVOL_URL =
            "https://www.deribit.com/api/v2/public/get_index_price?index_name=btcdvol_usdc";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The BTC derivatives snapshot; NaN where a leg failed. {@code fundingRatePercent}
     * is the 8h rate in percent (0.01 = the neutral baseline).
     */
    public record DerivsSnapshot(double fundingRatePercent, double openInterestBtc,
            double dvol) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Test/default: plain direct transport. */
    public CryptoDerivsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public CryptoDerivsClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** All three legs, each best-effort; empty only when EVERY leg failed. */
    public Optional<DerivsSnapshot> snapshot() {
        double funding = parseFunding(get(FUNDING_URL));
        double oi = parseOpenInterest(get(OPEN_INTEREST_URL));
        double dvol = parseDvol(get(DVOL_URL));
        if (Double.isNaN(funding) && Double.isNaN(oi) && Double.isNaN(dvol)) {
            return Optional.empty();
        }
        return Optional.of(new DerivsSnapshot(funding, oi, dvol));
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[CryptoDerivs] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[CryptoDerivs] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** {@code lastFundingRate} (a string like "0.00003432") → percent per 8h; NaN on failure. */
    static double parseFunding(String body) {
        try {
            return Double.parseDouble(JSON.readTree(body).path("lastFundingRate").asText()) * 100.0;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** {@code openInterest} in BTC; NaN on failure. */
    static double parseOpenInterest(String body) {
        try {
            return Double.parseDouble(JSON.readTree(body).path("openInterest").asText());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** Deribit JSON-RPC reply → {@code result.index_price}; NaN on failure. */
    static double parseDvol(String body) {
        try {
            JsonNode result = JSON.readTree(body).path("result");
            double v = result.path("index_price").asDouble(Double.NaN);
            return v;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
