package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eurostat - the European statistics office, through its keyless
 * dissemination API (JSON-stat, live-probed 2026-08-11).
 *
 * <p>It is the European counterpart to {@code FredSeriesClient}, and it closes
 * the same hole on this side of the Atlantic: the house held central-bank
 * pages, national statistics offices and a release calendar - sources that say
 * what was PUBLISHED - but for Europe no addressable TIME SERIES at all. A
 * European macro quantity could be quoted from a press release; its course
 * could not be read, plotted or computed against a share.
 *
 * <p>Two properties of the answer shape the client. Eurostat serves JSON-stat:
 * the observations arrive as a SPARSE map from a flat index into the time
 * dimension, so a period the office has not published yet is simply absent -
 * and that absence is the honest state of the statistic, never a zero. And the
 * periods are not always dates: a quarterly series is labelled {@code 2026-Q2}.
 * They are kept as the office writes them.
 *
 * <p>The spine below is a set of DATASET CODES with the dimension filters that
 * actually carry data - every one of them verified live, because a filter
 * combination that names a valid dimension value with no observations behind
 * it answers 200 with an empty series (measured on four of them). Any other
 * dataset can be asked for directly through {@link #series}.
 */
@Singleton
public class EurostatClient {

    private static final Logger LOG = LoggerFactory.getLogger(EurostatClient.class);

    private static final String API =
            "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/%s"
                    + "?format=JSON&lang=EN&lastTimePeriod=%d%s";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    /** Direct-first: the dissemination API carries no wall; the browser joker stays the fallback. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    /** One catalogued series: dataset, its filter, and the house's name for it. */
    public record Kennzahl(String dataset, String filter, String titel) {}

    /**
     * The European macro spine. Ordered as a reader meets it: what things
     * cost, what money costs, what is produced, what is bought, who works.
     */
    public static final List<Kennzahl> SPINE = List.of(
            new Kennzahl("prc_hicp_manr", "coicop=CP00&geo=EA&unit=RCH_A",
                    "Euroraum-Inflation (HVPI, Jahresrate)"),
            new Kennzahl("irt_st_m", "geo=EA&int_rt=IRT_M3",
                    "Euro-Geldmarktzins 3 Monate"),
            new Kennzahl("sts_inpr_m", "geo=EA20&nace_r2=B-D&s_adj=SCA&unit=I21",
                    "Euroraum-Industrieproduktion (Index)"),
            new Kennzahl("sts_trtu_m",
                    "geo=EA20&nace_r2=G47&s_adj=SCA&unit=I21&indic_bt=NETTUR",
                    "Euroraum-Einzelhandelsumsatz (Index)"),
            new Kennzahl("une_rt_m", "geo=EU27_2020&s_adj=SA&age=TOTAL&sex=T&unit=PC_ACT",
                    "EU-Arbeitslosenquote"),
            new Kennzahl("namq_10_gdp",
                    "geo=EA20&s_adj=SCA&unit=CLV_PCH_PRE&na_item=B1GQ",
                    "Euroraum-BIP (Veränderung zum Vorquartal)"));

    /**
     * One observation. {@code periode} is Eurostat's own label - {@code
     * "2026-06"} for a month, {@code "2026-Q2"} for a quarter - kept verbatim
     * because rewriting a quarter as a date would invent a day the office
     * never published.
     */
    public record Punkt(String periode, double wert) {}

    /** One series as Eurostat carries it, oldest observation first. */
    public record Reihe(String dataset, String titel, List<Punkt> punkte) {

        public boolean isEmpty() {
            return punkte.isEmpty();
        }

        public Punkt letzter() {
            return punkte.isEmpty() ? null : punkte.get(punkte.size() - 1);
        }
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(25);

    private record CacheEntry(Reihe reihe, long atMs) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Inject
    public EurostatClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * One series, its {@code lastPeriods} most recent periods, oldest first.
     * Periods the office has not published are absent, not zero. Empty on any
     * failure (never cached).
     *
     * @param filter the dimension filter as query parameters, or empty
     */
    public Reihe series(String dataset, String filter, String titel, int lastPeriods) {
        if (dataset == null || dataset.isBlank() || lastPeriods <= 0) {
            return new Reihe("", titel == null ? "" : titel, List.of());
        }
        String key = dataset + "|" + filter + "|" + lastPeriods;
        CacheEntry hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.atMs() < CACHE_TTL_MS) {
            return hit.reihe();
        }
        try {
            String url = String.format(Locale.ROOT, API, dataset.strip(), lastPeriods,
                    filter == null || filter.isBlank() ? "" : "&" + filter);
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.debug("[eurostat] {} answered status {}", dataset,
                        resp == null ? "null" : resp.status());
                return new Reihe(dataset, titel == null ? "" : titel, List.of());
            }
            Reihe reihe = parse(dataset, titel, resp.body());
            if (reihe.isEmpty()) {
                // A valid dimension value with no observations behind it is a
                // 200 with an empty series - the failure mode that looks like
                // success, so it is said out loud.
                LOG.warn("[eurostat] {} answered 200 with no observation - the filter names "
                        + "a cell the office does not fill: {}", dataset, filter);
                return reihe;
            }
            cache.put(key, new CacheEntry(reihe, System.currentTimeMillis()));
            LOG.info("[eurostat] {} → {} observation(s), latest {} = {}", dataset,
                    reihe.punkte().size(), reihe.letzter().periode(), reihe.letzter().wert());
            return reihe;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[eurostat] {} failed: {}", dataset, e.getMessage());
            return new Reihe(dataset, titel == null ? "" : titel, List.of());
        }
    }

    /**
     * The whole spine, {@code lastPeriods} periods each. A series that answers
     * nothing stays OUT of the answer rather than sitting in it empty - a
     * dossier that prints a missing quantity as a gap and one that prints it
     * as an empty chart are not the same report.
     */
    public Map<String, Reihe> spineSeries(int lastPeriods) {
        Map<String, Reihe> out = new LinkedHashMap<>();
        for (Kennzahl k : SPINE) {
            Reihe reihe = series(k.dataset(), k.filter(), k.titel(), lastPeriods);
            if (!reihe.isEmpty()) out.put(k.dataset(), reihe);
        }
        return out;
    }

    /**
     * JSON-stat → the series. {@code value} is a SPARSE map from the flat
     * index into the time dimension, so the period labels come from
     * {@code dimension.time.category.index} and a missing index is a period
     * the office has not published. Package-private for tests.
     */
    static Reihe parse(String dataset, String titel, String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        String label = titel == null || titel.isBlank()
                ? root.path("label").asText(dataset) : titel;
        JsonNode index = root.path("dimension").path("time").path("category").path("index");
        JsonNode values = root.path("value");
        if (!index.isObject() || !values.isObject()) {
            return new Reihe(dataset, label, List.of());
        }
        Map<Integer, String> byPosition = new LinkedHashMap<>();
        index.fields().forEachRemaining(e -> byPosition.put(e.getValue().asInt(), e.getKey()));
        List<Punkt> punkte = new ArrayList<>();
        for (int i = 0; i < byPosition.size(); i++) {
            JsonNode v = values.get(String.valueOf(i));
            if (v == null || v.isNull() || !v.isNumber()) continue;
            String periode = byPosition.get(i);
            if (periode == null) continue;
            punkte.add(new Punkt(periode, v.asDouble()));
        }
        return new Reihe(dataset, label, List.copyOf(punkte));
    }
}
