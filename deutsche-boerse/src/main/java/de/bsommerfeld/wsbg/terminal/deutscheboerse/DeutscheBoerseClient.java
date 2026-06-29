package de.bsommerfeld.wsbg.terminal.deutscheboerse;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deutsche Börse price client (the official Xetra / Frankfurt venue), keyed on
 * ISIN — the EUR price the target audience actually sees. Replaces the old
 * Tradegate fallback below Lang &amp; Schwarz: same ISIN-keyed, EUR, no sparkline,
 * but it carries the official day-change and an honest last-trade timestamp.
 *
 * <p>Source is the keyless JSON endpoint behind {@code live.deutsche-boerse.com}:
 * {@code api.boerse-frankfurt.de/v1/data/quote_box/single?isin=…&mic=…} →
 * {@code {lastPrice, changeToPrevDayAbsolute, changeToPrevDayInPercent,
 * timestampLastPrice, …}}. Tried on Xetra ({@code XETR}, 09:00–17:30) first, then
 * Frankfurt floor ({@code XFRA}, longer hours) so an off-Xetra quote still resolves.
 * (The richer chart/52-week endpoints sit behind a request-signature wall and are
 * deferred to the browser joker; L&amp;S remains the sparkline provider.)
 *
 * <p>Returns {@link Optional#empty()} on any failure; the price chain falls through.
 */
@Singleton
public class DeutscheBoerseClient {

    private static final Logger LOG = LoggerFactory.getLogger(DeutscheBoerseClient.class);

    private static final String QUOTE_URL =
            "https://api.boerse-frankfurt.de/v1/data/quote_box/single?isin=";

    /** Venues to try, most-liquid first: Xetra, then the longer-hours Frankfurt floor. */
    private static final String[][] VENUES = {
            {"XETR", "Xetra"},
            {"XFRA", "Börse Frankfurt"},
    };

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Test/default: plain direct transport. */
    public DeutscheBoerseClient() {
        this(new DirectWebFetcher());
    }

    /** Production: rides the shared browser-joker {@link WebFetcher} chain. */
    @Inject
    public DeutscheBoerseClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Live EUR snapshot for an ISIN, or empty. Tries Xetra then Frankfurt. */
    public Optional<MarketSnapshot> fetchSnapshot(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String id = isin.trim();
        for (String[] venue : VENUES) {
            try {
                WebResponse resp = fetcher.fetch(QUOTE_URL + id + "&mic=" + venue[0],
                        Map.of("User-Agent", userAgent, "Accept", "application/json",
                                "Origin", "https://live.deutsche-boerse.com"),
                        requestTimeout);
                if (resp.status() != 200 || resp.body() == null || resp.body().isBlank()) continue;
                Optional<MarketSnapshot> snap = parse(resp.body(), id, venue[1]);
                if (snap.isPresent()) return snap;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.debug("Deutsche Börse {} failed for {}: {}", venue[0], isin, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Parses a quote_box body into an EUR snapshot. Package-private, network-free. */
    Optional<MarketSnapshot> parse(String body, String isin, String venueName) {
        try {
            JsonNode n = JSON.readTree(body);
            double last = n.path("lastPrice").asDouble(Double.NaN);
            if (!Double.isFinite(last) || last <= 0) return Optional.empty();
            double changeAbs = n.path("changeToPrevDayAbsolute").asDouble(Double.NaN);
            double changePct = n.path("changeToPrevDayInPercent").asDouble(Double.NaN);
            double prevClose = Double.isFinite(changeAbs) ? last - changeAbs : Double.NaN;
            long marketTime = parseTimestamp(n.path("timestampLastPrice").asText(null));
            // quote_box carries no day high/low, volume or 52-week range → NaN/-1;
            // Xetra/Frankfurt trade in EUR, so the figure needs no FX conversion.
            return Optional.of(new MarketSnapshot(
                    isin, last, prevClose, changePct, Double.NaN, Double.NaN, -1,
                    Double.NaN, Double.NaN, "EUR", venueName, marketTime, List.of()));
        } catch (Exception e) {
            LOG.debug("Deutsche Börse parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** ISO-8601-with-offset → epoch seconds, or 0 when absent/unparseable. */
    private static long parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return OffsetDateTime.parse(iso).toEpochSecond();
        } catch (Exception e) {
            return 0;
        }
    }
}
