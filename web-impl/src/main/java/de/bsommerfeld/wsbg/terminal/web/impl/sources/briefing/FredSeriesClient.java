package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * FRED - the St. Louis Fed's data warehouse, read through its KEYLESS chart
 * export ({@code fred.stlouisfed.org/graph/fredgraph.csv?id=<series>},
 * live-probed 2026-08-11). The documented JSON API wants a registered key;
 * the CSV the chart page downloads does not, and it answers the same numbers.
 *
 * <p>What it closes: the house held central-bank pages, statistics offices and
 * a calendar - sources that say what was PUBLISHED - but no addressable
 * TIME SERIES. So a macro quantity could be quoted, never plotted; the
 * repricing bench could measure a share against its index and never against
 * the rate curve, the credit spread or the oil price that moved it. One series
 * id is one series, dated, from the front of the archive to today.
 *
 * <p>The spine below is a vocabulary of SERIES IDS - the names FRED gives
 * these quantities - and any other id can be asked for directly through
 * {@link #series(String, int)}: the client is a reader of FRED, not a curator
 * of which macro matters.
 *
 * <p><b>The host has a bot wall</b> (measured 2026-08-11): a cold client is
 * answered, and a handful of requests in a row from the same address stop
 * being answered at all - not a status code, no reply. That is precisely what
 * the browser joker exists for, so the transport order keeps the browser as
 * the rescue behind the direct path. The six-hour cache is part of the same
 * answer: a macro series that moves once a day must not be fetched once a
 * dossier.
 */
@Singleton
public class FredSeriesClient {

    private static final Logger LOG = LoggerFactory.getLogger(FredSeriesClient.class);

    private static final String CSV_URL = "https://fred.stlouisfed.org/graph/fredgraph.csv?id=%s";
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;
    /** How many spine series are asked for at once - see {@link #spineSeries}. */
    private static final int SPINE_FAN_WIDTH = 4;

    /**
     * The macro spine a dossier reads a single share against: the price of
     * money, its shape over time, what credit costs above it, what the
     * currency and the barrel do, and what the real economy printed. Ordered
     * as a reader meets them, not by importance.
     */
    public static final Map<String, String> SPINE = spine();

    private static Map<String, String> spine() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("DFF", "US-Leitzins (effective federal funds rate)");
        m.put("DGS2", "US-Staatsanleihe 2 Jahre");
        m.put("DGS10", "US-Staatsanleihe 10 Jahre");
        m.put("T10Y2Y", "Zinsstrukturkurve 10J minus 2J");
        m.put("T10YIE", "Inflationserwartung 10 Jahre (breakeven)");
        m.put("BAMLH0A0HYM2", "Risikoaufschlag High Yield");
        m.put("DTWEXBGS", "US-Dollar handelsgewichtet");
        m.put("DCOILWTICO", "Rohöl WTI");
        m.put("VIXCLS", "VIX");
        m.put("CPIAUCSL", "US-Verbraucherpreise");
        m.put("UNRATE", "US-Arbeitslosenquote");
        m.put("PAYEMS", "US-Beschäftigte außerhalb der Landwirtschaft");
        m.put("INDPRO", "US-Industrieproduktion");
        m.put("UMCSENT", "US-Verbrauchervertrauen");
        return Map.copyOf(m);
    }

    /** One dated observation. */
    public record Punkt(LocalDate datum, double wert) {}

    /**
     * One series as FRED carries it.
     *
     * @param id     the FRED series id
     * @param titel  the house's name for it, or the id when it was asked for
     *               directly
     * @param punkte observations, OLDEST first - a series is read forwards
     */
    public record Reihe(String id, String titel, List<Punkt> punkte) {

        public boolean isEmpty() {
            return punkte.isEmpty();
        }

        /** The most recent observation, or null for an empty series. */
        public Punkt letzter() {
            return punkte.isEmpty() ? null : punkte.get(punkte.size() - 1);
        }
    }

    /** Direct-first with the browser joker as the rescue behind the bot wall. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);

    private record CacheEntry(Reihe reihe, long atMs) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Inject
    public FredSeriesClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * One series, its {@code maxPoints} most recent observations, oldest
     * first. An empty series is returned for an unknown id or a failed
     * request - never null, and never cached on a network failure.
     */
    public Reihe series(String id, int maxPoints) {
        if (id == null || id.isBlank() || maxPoints <= 0) return leer(id);
        String key = id.strip().toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(key);
        if (hit != null && now - hit.atMs() < CACHE_TTL_MS) return trim(hit.reihe(), maxPoints);
        try {
            WebResponse resp = fetcher.fetch(String.format(CSV_URL, key),
                    Map.of("Accept", "text/csv,text/plain"),
                    requestTimeout, MODES);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.debug("[fred] {} answered status {}", key,
                        resp == null ? "null" : resp.status());
                return leer(key);
            }
            Reihe reihe = parse(key, SPINE.getOrDefault(key, key), resp.body());
            if (reihe.isEmpty()) {
                LOG.warn("[fred] {} answered 200 with no observation row - unknown series "
                        + "id or a changed export format", key);
                return reihe;
            }
            cache.put(key, new CacheEntry(reihe, now));
            LOG.info("[fred] {} → {} observation(s), latest {}", key, reihe.punkte().size(),
                    reihe.letzter().datum());
            return trim(reihe, maxPoints);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[fred] {} failed: {}", key, e.getMessage());
            return leer(key);
        }
    }

    /**
     * The whole macro spine, {@code maxPoints} observations each. A series
     * that fails is absent from the answer rather than empty in it - a
     * dossier that prints a missing quantity as a gap and one that prints it
     * as an empty chart are not the same report.
     */
    public Map<String, Reihe> spineSeries(int maxPoints) {
        List<String> ids = List.copyOf(SPINE.keySet());
        Map<String, Reihe> geholt = new ConcurrentHashMap<>();
        // Fourteen independent series, fetched side by side (2026-08-11). One
        // after another this leg took 4 min 26 s of a dossier's collection -
        // and the host was never the reason: each series answered in a few
        // hundred milliseconds once it was asked. The width is deliberately
        // narrow: FRED's wall reacts to BURSTS from one address, so the leg
        // asks with a handful of hands, not with fourteen.
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(SPINE_FAN_WIDTH, ids.size()), r -> {
                    Thread t = new Thread(r, "fred-spine");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<Future<?>> futures = new ArrayList<>(ids.size());
            for (String id : ids) {
                futures.add(pool.submit(() -> {
                    Reihe reihe = series(id, maxPoints);
                    if (!reihe.isEmpty()) geholt.put(id, reihe);
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    // series() already swallows its own failures; anything that
                    // still lands here costs its series, never the spine.
                    LOG.debug("[fred] a spine series failed in the fan: {}", e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // The spine is a VOCABULARY in a reader's order (see SPINE) - the fan
        // must not shuffle it, so the answer is assembled in the declared one.
        Map<String, Reihe> out = new LinkedHashMap<>();
        for (String id : ids) {
            Reihe reihe = geholt.get(id);
            if (reihe != null) out.put(id, reihe);
        }
        return out;
    }

    /**
     * FRED's export is {@code observation_date,<SERIES_ID>} followed by dated
     * rows; a period with no observation carries a lone dot, which is a REAL
     * statement (the series does not print on that day) and is skipped rather
     * than read as zero. Package-private for tests.
     */
    static Reihe parse(String id, String titel, String csv) {
        List<Punkt> punkte = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            int comma = line.indexOf(',');
            if (comma <= 0) continue;
            String datum = line.substring(0, comma).strip();
            String wert = line.substring(comma + 1).strip();
            if (datum.length() != 10 || datum.charAt(4) != '-') continue;
            try {
                punkte.add(new Punkt(LocalDate.parse(datum), Double.parseDouble(wert)));
            } catch (Exception notAnObservation) {
                // Header row or a dot for "no value" - both are simply not points.
            }
        }
        return new Reihe(id, titel, List.copyOf(punkte));
    }

    private static Reihe trim(Reihe reihe, int maxPoints) {
        if (reihe.punkte().size() <= maxPoints) return reihe;
        return new Reihe(reihe.id(), reihe.titel(), List.copyOf(reihe.punkte()
                .subList(reihe.punkte().size() - maxPoints, reihe.punkte().size())));
    }

    private static Reihe leer(String id) {
        String key = id == null ? "" : id.strip().toUpperCase(Locale.ROOT);
        return new Reihe(key, SPINE.getOrDefault(key, key), List.of());
    }
}
