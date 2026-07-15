package de.bsommerfeld.wsbg.terminal.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RKI acute-respiratory-illness surveillance — the seasonal-sickness leg, the
 * RKI's open GitHub-raw TSV pattern (live-probed 2026-07-14): the
 * ARE-Konsultationsinzidenz weekly consultation incidence per 100k, pinned to
 * the nationwide all-ages series ({@code Bundesland=Bundesweit},
 * {@code Altersgruppe=00+}). The TSV columns are
 * {@code Saison, Kalenderwoche, Bundesland, Bundesland_ID, Altersgruppe,
 * ARE_Konsultationsinzidenz}; {@code Kalenderwoche} is ISO week
 * ({@code YYYY-Www}). Latest week(s) with their value.
 *
 * <p>Weekly cadence: cache 24h. Best-effort empty on any failure.
 */
@Singleton
public class RkiSurveillanceClient {

    private static final Logger LOG = LoggerFactory.getLogger(RkiSurveillanceClient.class);

    private static final String URL = "https://raw.githubusercontent.com/robert-koch-institut/"
            + "ARE-Konsultationsinzidenz/main/ARE-Konsultationsinzidenz.tsv";
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final String NATIONWIDE = "Bundesweit";
    private static final String ALL_AGES = "00+";

    /** One weekly reading: ISO week ({@code YYYY-Www}) and the nationwide all-ages incidence per 100k. */
    public record WeeklyIncidence(String isoWeek, double incidence) {
    }

    private record Cached(Instant at, List<WeeklyIncidence> series) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private volatile Cached cache;

    /** Test/default: plain direct transport. */
    public RkiSurveillanceClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — GitHub raw is wall-less. */
    @Inject
    public RkiSurveillanceClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The latest {@code weeks} nationwide all-ages readings, oldest→newest (so
     * the last element is the most recent week).
     */
    public synchronized List<WeeklyIncidence> latest(int weeks) {
        Cached hit = cache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return tail(hit.series(), weeks);
        }
        try {
            WebResponse resp = fetcher.fetch(URL, Map.of("Accept", "text/plain"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<WeeklyIncidence> series = parse(resp.body());
                if (!series.isEmpty()) {
                    cache = new Cached(Instant.now(), series);
                    return tail(series, weeks);
                }
            } else {
                LOG.debug("[RKI] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[RKI] failed: {}", e.getMessage());
        }
        return hit != null ? tail(hit.series(), weeks) : List.of();
    }

    /**
     * Package-private for tests: the ARE TSV → nationwide all-ages weekly
     * series, oldest→newest, network-free. Only {@code Bundesweit}/{@code 00+}
     * rows are kept.
     */
    static List<WeeklyIncidence> parse(String tsv) {
        if (tsv == null || tsv.isBlank()) return List.of();
        List<WeeklyIncidence> out = new ArrayList<>();
        String[] lines = tsv.split("\r?\n");
        for (int i = 1; i < lines.length; i++) { // skip header
            String[] c = lines[i].split("\t");
            if (c.length < 6) continue;
            if (!NATIONWIDE.equals(c[2].strip()) || !ALL_AGES.equals(c[4].strip())) continue;
            try {
                out.add(new WeeklyIncidence(c[1].strip(), Double.parseDouble(c[5].strip())));
            } catch (NumberFormatException ignored) {
                // a non-numeric value cell is skipped
            }
        }
        return out;
    }

    private static List<WeeklyIncidence> tail(List<WeeklyIncidence> list, int n) {
        if (n <= 0 || list.size() <= n) return list;
        return List.copyOf(list.subList(list.size() - n, list.size()));
    }
}
