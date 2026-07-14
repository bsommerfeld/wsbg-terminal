package de.bsommerfeld.wsbg.terminal.briefing;

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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 10-year Bund yield from the Bundesbank's keyless SDMX REST API
 * (live-verified 2026-07-13: daily Svensson zero-coupon curve value, e.g.
 * 2026-07-10 → 3.13 %). The reply is the Bundesbank's own CSV dialect —
 * metadata header rows followed by {@code date<sep>value} observations —
 * so parsing is a defensive line scan for date-shaped rows rather than a
 * schema commitment. Data is T+1: the evening report shows the latest
 * available fixing, dated.
 */
@Singleton
public class BundYieldClient {

    private static final Logger LOG = LoggerFactory.getLogger(BundYieldClient.class);

    /** BBSIS daily 10y listed-Bund zero-coupon yield, last observations as CSV. */
    private static final String URL =
            "https://api.statistiken.bundesbank.de/rest/data/BBSIS/"
                    + "D.I.ZST.ZI.EUR.S1311.B.A604.R10XX.R.A.A._Z._Z.A"
                    + "?lastNObservations=5&format=csv";

    /** {@code yyyy-MM-dd<sep>number} anywhere in a line — the observation rows. */
    private static final Pattern OBSERVATION =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*[;,]\\s*\"?(-?\\d+(?:[.,]\\d+)?)\"?");

    /** Latest available daily yield in percent, plus the prior observation for the delta. */
    public record YieldPoint(String dateIso, double percent, Double previousPercent) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public BundYieldClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public BundYieldClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The latest 10y Bund observation, or empty on any failure. */
    public Optional<YieldPoint> tenYearBund() {
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "text/csv, */*"),
                    requestTimeout);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[BundYield] answered status {}", resp == null ? "null" : resp.status());
                return Optional.empty();
            }
            return parse(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[BundYield] fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Package-private for tests: CSV body → the latest observation, network-free. */
    static Optional<YieldPoint> parse(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        List<String[]> observations = new ArrayList<>();
        for (String line : body.split("\\R")) {
            Matcher m = OBSERVATION.matcher(line);
            if (m.find()) observations.add(new String[]{m.group(1), m.group(2)});
        }
        if (observations.isEmpty()) return Optional.empty();
        observations.sort((a, b) -> a[0].compareTo(b[0]));
        String[] latest = observations.get(observations.size() - 1);
        Double previous = observations.size() > 1
                ? parseNumber(observations.get(observations.size() - 2)[1]) : null;
        Double value = parseNumber(latest[1]);
        if (value == null) return Optional.empty();
        return Optional.of(new YieldPoint(latest[0], value, previous));
    }

    private static Double parseNumber(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }
}
