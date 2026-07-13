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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FINRA's daily consolidated short sale volume file (live-verified 2026-07-13,
 * keyless): {@code cdn.finra.org/equity/regsho/daily/CNMSshvolYYYYMMDD.txt},
 * pipe-delimited {@code Date|Symbol|ShortVolume|ShortExemptVolume|TotalVolume|Market}.
 * The briefing joins it against the cage's top US tickers: "62 % of today's
 * volume ran over the short side". Interpretation caveat carried into the
 * prompt: short VOLUME is not short INTEREST — a high ratio is often market-
 * maker hedging, so the line reports, never concludes. The file appears after
 * US close; the client walks back to the last session.
 */
@Singleton
public class FinraShortVolumeClient {

    private static final Logger LOG = LoggerFactory.getLogger(FinraShortVolumeClient.class);

    private static final String BASE = "https://cdn.finra.org/equity/regsho/daily/CNMSshvol";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_LOOKBACK_DAYS = 5;

    /** Short-volume ratio of one symbol on one day, in percent of total volume. */
    public record ShortVolume(String symbol, double shortPercent, long totalVolume,
            String dateIso) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);

    /** Test/default: plain direct transport. */
    public FinraShortVolumeClient() {
        this(new DirectWebFetcher());
    }

    /** Production: direct-first chain (a public CDN, no wall). */
    @Inject
    public FinraShortVolumeClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Ratios for the requested symbols from the latest available file, walking
     * back from {@code today} (the file lands after US close — for a European
     * evening that is usually the prior session). ONE file fetch per call;
     * symbols the file doesn't carry are simply absent from the map.
     */
    public Map<String, ShortVolume> ratiosFor(Set<String> symbols, LocalDate today) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        for (int back = 0; back <= MAX_LOOKBACK_DAYS; back++) {
            LocalDate day = today.minusDays(back);
            try {
                WebResponse resp = fetcher.fetch(BASE + FILE_DATE.format(day) + ".txt",
                        Map.of("User-Agent", userAgent, "Accept", "text/plain"),
                        requestTimeout);
                if (resp != null && resp.status() == 200 && resp.body() != null
                        && !resp.body().isBlank()) {
                    Map<String, ShortVolume> parsed = parse(resp.body(), symbols, day.toString());
                    if (!parsed.isEmpty()) return parsed;
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return Map.of();
                }
                LOG.debug("[FINRA] fetch {} failed: {}", day, e.getMessage());
            }
        }
        return Map.of();
    }

    /**
     * Package-private for tests: file body → ratios for the wanted symbols,
     * network-free. Volumes carry fractional-share decimals — parsed as double,
     * reported as rounded shares.
     */
    static Map<String, ShortVolume> parse(String body, Set<String> symbols, String dateIso) {
        Map<String, ShortVolume> out = new LinkedHashMap<>();
        Set<String> wanted = symbols.stream()
                .map(s -> s.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        for (String line : body.split("\\R")) {
            int p1 = line.indexOf('|');
            int p2 = line.indexOf('|', p1 + 1);
            if (p1 < 0 || p2 < 0) continue;
            String symbol = line.substring(p1 + 1, p2).trim().toUpperCase(Locale.ROOT);
            if (!wanted.contains(symbol)) continue;
            String[] cols = line.split("\\|");
            if (cols.length < 5) continue;
            try {
                double shortVol = Double.parseDouble(cols[2]);
                double totalVol = Double.parseDouble(cols[4]);
                if (totalVol <= 0) continue;
                out.put(symbol, new ShortVolume(symbol, shortVol / totalVol * 100.0,
                        Math.round(totalVol), dateIso));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
