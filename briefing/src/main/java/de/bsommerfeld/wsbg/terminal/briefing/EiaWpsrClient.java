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
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EIA Weekly Petroleum Status Report, table 1 (US petroleum stocks) as
 * keyless CSV (live-probed 2026-07-14): {@code ir.eia.gov/wpsr/table1.csv}
 * 302s to a CloudFront-signed URL — the JDK transport follows
 * ({@code Redirect.NORMAL}), and a one-hop {@code Location} fallback covers a
 * transport that doesn't. The DOE inventory number is a scheduled market
 * event (Wednesdays 16:30 CEST); levels are in <b>million barrels</b>.
 * CSV quirks pinned by fixture: every cell double-quoted, thousands commas
 * INSIDE quoted numbers ("1,517.075"), the summary stock section ends where a
 * second {@code STUB_1} header row starts the supply/disposition section
 * (whose row labels live in a leading category column — ignored). Sibling
 * tables ({@code table9.csv}, …) exist under the same path scheme;
 * {@code psw01.csv} is 403 — table1 is the open one.
 */
@Singleton
public class EiaWpsrClient {

    private static final Logger LOG = LoggerFactory.getLogger(EiaWpsrClient.class);

    private static final String URL = "https://ir.eia.gov/wpsr/table1.csv";
    /** Header dates arrive US-short: "7/3/26". */
    private static final DateTimeFormatter US_SHORT =
            DateTimeFormatter.ofPattern("M/d/yy", Locale.US);
    private static final Pattern QUOTED_CELL = Pattern.compile("\"([^\"]*)\"");
    /** Weekly Wednesday release — 3h politeness cache. */
    private static final Duration CACHE_TTL = Duration.ofHours(3);

    /**
     * The report's headline stock levels, all in million barrels; deltas are
     * week-over-week (current minus prior week). NaN where a row was missing.
     * {@code weekEnding} is the stock date the levels describe (a Friday).
     */
    public record WpsrSummary(LocalDate weekEnding,
            double commercialCrudeMb, double commercialCrudeDeltaMb,
            double sprMb, double sprDeltaMb,
            double gasolineMb, double gasolineDeltaMb,
            double distillateMb, double distillateDeltaMb) {
    }

    private record Cached(Instant at, WpsrSummary summary) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private volatile Cached cached;

    /** Test/default: plain direct transport. */
    public EiaWpsrClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public EiaWpsrClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The latest report's summary; empty on any failure (stale kept over an outage). */
    public Optional<WpsrSummary> latest() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return Optional.of(hit.summary());
        }
        try {
            WebResponse resp = fetchFollowingOnce(URL);
            if (resp != null && resp.status() == 200) {
                Optional<WpsrSummary> parsed = parse(resp.body());
                parsed.ifPresent(s -> cached = new Cached(Instant.now(), s));
                if (parsed.isPresent()) return parsed;
            } else {
                LOG.debug("[WPSR] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[WPSR] fetch failed: {}", e.getMessage());
        }
        // An outage keeps the stale report instead of going empty.
        return hit != null ? Optional.of(hit.summary()) : Optional.empty();
    }

    /** Follows one explicit redirect hop for a transport that answers the raw 302. */
    private WebResponse fetchFollowingOnce(String url) throws Exception {
        Map<String, String> headers = Map.of("User-Agent", userAgent, "Accept", "text/csv,*/*");
        WebResponse resp = fetcher.fetch(url, headers, requestTimeout);
        if (resp != null && resp.status() >= 300 && resp.status() < 400) {
            Optional<String> location = resp.header("Location");
            if (location.isPresent()) {
                return fetcher.fetch(location.get(), headers, requestTimeout);
            }
        }
        return resp;
    }

    /** Package-private for tests: table1 CSV → summary, network-free. */
    static Optional<WpsrSummary> parse(String csv) {
        if (csv == null || csv.isBlank()) return Optional.empty();
        LocalDate weekEnding = null;
        double crude = Double.NaN, crudeD = Double.NaN, spr = Double.NaN, sprD = Double.NaN,
                gas = Double.NaN, gasD = Double.NaN, dist = Double.NaN, distD = Double.NaN;
        int headerCount = 0;
        for (String line : csv.split("\r?\n")) {
            List<String> cells = cells(line);
            if (cells.size() < 4) continue;
            String label = cells.get(0).strip();
            if ("STUB_1".equals(label)) {
                headerCount++;
                if (headerCount > 1) break; // supply/disposition section — not ours
                weekEnding = parseUsShortDate(cells.get(1));
                continue;
            }
            if (headerCount != 1) continue;
            switch (label) {
                case "Commercial (Excluding SPR)" -> {
                    crude = number(cells.get(1));
                    crudeD = number(cells.get(3));
                }
                case "Strategic Petroleum Reserve (SPR)" -> {
                    spr = number(cells.get(1));
                    sprD = number(cells.get(3));
                }
                case "Total Motor Gasoline" -> {
                    gas = number(cells.get(1));
                    gasD = number(cells.get(3));
                }
                case "Distillate Fuel Oil" -> {
                    dist = number(cells.get(1));
                    distD = number(cells.get(3));
                }
                default -> { /* the long tail of product rows — not the briefing's business */ }
            }
        }
        if (weekEnding == null
                || (Double.isNaN(crude) && Double.isNaN(spr) && Double.isNaN(gas) && Double.isNaN(dist))) {
            return Optional.empty();
        }
        return Optional.of(new WpsrSummary(weekEnding, crude, crudeD, spr, sprD,
                gas, gasD, dist, distD));
    }

    /** Every cell is double-quoted; numbers carry thousands commas inside the quotes. */
    private static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        Matcher m = QUOTED_CELL.matcher(line);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.replace(",", "").strip());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static LocalDate parseUsShortDate(String s) {
        try {
            return LocalDate.parse(s.strip(), US_SHORT);
        } catch (Exception e) {
            return null;
        }
    }
}
