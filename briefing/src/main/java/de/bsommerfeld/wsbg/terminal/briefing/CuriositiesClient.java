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
 * The evening report's colour: two tiny keyless one-liners that no market
 * data vendor sells. (a) The US national debt to the cent from the Treasury's
 * FiscalData API — the standing gag with a real number behind it. (b) The
 * actual weather at the Frankfurt exchange via BrightSky/DWD — the one report
 * called Wetterbericht should know whether the sun shone over the Börse.
 * Both live-verified 2026-07-13.
 */
@Singleton
public class CuriositiesClient {

    private static final Logger LOG = LoggerFactory.getLogger(CuriositiesClient.class);

    private static final String DEBT_URL =
            "https://api.fiscaldata.treasury.gov/services/api/fiscal_service/v2/accounting/od/"
                    + "debt_to_penny?sort=-record_date&page%5Bsize%5D=1";
    /** BrightSky current weather at the Frankfurt exchange's coordinates. */
    private static final String FRANKFURT_WEATHER_URL =
            "https://api.brightsky.dev/current_weather?lat=50.11&lon=8.68";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The total US public debt outstanding, to the cent, as of {@code dateIso}. */
    public record UsDebt(double totalUsd, String dateIso) {
    }

    /** Current Frankfurt weather: temperature plus BrightSky's icon token. */
    public record ExchangeWeather(double temperatureCelsius, String icon, int cloudCoverPercent) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Test/default: plain direct transport. */
    public CuriositiesClient() {
        this(new DirectWebFetcher());
    }

    /** Production: direct-first chain. */
    @Inject
    public CuriositiesClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The latest debt figure, or empty on any failure. */
    public Optional<UsDebt> usDebt() {
        return parseDebt(get(DEBT_URL));
    }

    /** The current Frankfurt weather, or empty on any failure. */
    public Optional<ExchangeWeather> frankfurtWeather() {
        return parseWeather(get(FRANKFURT_WEATHER_URL));
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[Curiosities] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Curiosities] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Package-private for tests: FiscalData reply → the latest figure. */
    static Optional<UsDebt> parseDebt(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode data = JSON.readTree(body).path("data");
            if (!data.isArray() || data.isEmpty()) return Optional.empty();
            JsonNode row = data.get(0);
            double total = Double.parseDouble(row.path("tot_pub_debt_out_amt").asText());
            return Optional.of(new UsDebt(total, row.path("record_date").asText("")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Package-private for tests: BrightSky reply → the current reading. */
    static Optional<ExchangeWeather> parseWeather(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode w = JSON.readTree(body).path("weather");
            double temp = w.path("temperature").asDouble(Double.NaN);
            if (Double.isNaN(temp)) return Optional.empty();
            return Optional.of(new ExchangeWeather(temp, w.path("icon").asText(""),
                    w.path("cloud_cover").asInt(-1)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
