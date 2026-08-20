package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GDELT 2.0 event stream (researched + live-probed 2026-07-17, keyless): the
 * machine-coded world event file that lands every 15 minutes under
 * {@code data.gdeltproject.org/gdeltv2/}. This is the layer that answers the
 * standing user ask - attacks at their ACTUAL location instead of a country
 * centroid: GDELT carries the geocoded place of the action itself, down to the
 * city, which the editorial conflict wire (country-level by construction)
 * never can.
 *
 * <p>Only the violent CAMEO families ride: root {@value #ROOT_ASSAULT}
 * (assault), {@value #ROOT_FIGHT} (fight) and {@value #ROOT_MASS_VIOLENCE}
 * (unconventional mass violence). Country-precision rows are dropped on
 * purpose - a centroid is exactly what this layer exists to replace, and the
 * conflict layer already draws those honestly.
 *
 * <p>The coding is machine-made, and the map says so: GDELT reads the world's
 * wire, it does not verify it. The two mechanical levers that separate a real
 * event from an incidental mention are the ones used here - the event must be
 * what its article is ABOUT, and it must be geocoded finer than a country.
 * No keyword list decides anything (house rule: no curated fix-lists).
 *
 * <p>THREE products come out of the one file. The violent point events are
 * the attacks layer. The actor pair - GDELT geocodes WHO acted and WHOM they
 * acted upon, separately from where it happened - becomes the relation arcs:
 * who is currently doing something to whom, across the planet, cooperation
 * and conflict in the same grammar. And the running volume per place, kept
 * across windows in this client, is what makes a story visibly ESCALATE.
 *
 * <p>Both language streams ride: the English file plus the TRANSLINGUAL one
 * ({@code lastupdate-translation.txt}), which is where the German, Russian,
 * Arabic, Portuguese and Ukrainian press lands. Reading only the English file
 * would mean seeing the world exclusively through anglophone eyes.
 *
 * <p>Transport: the update pointer is plain text; the event file itself is a
 * ZIP, so its bytes take the house fetcher's BINARY path ({@code fetchBinary})
 * - a text decode would ruin the archive.
 */
@Singleton
public class GdeltConflictClient {

    private static final Logger LOG = LoggerFactory.getLogger(GdeltConflictClient.class);

    private static final String LAST_UPDATE = "http://data.gdeltproject.org/gdeltv2/lastupdate.txt";
    /** The non-English press - German, Russian, Arabic, Portuguese, Ukrainian. */
    private static final String LAST_UPDATE_TRANSLATION =
            "http://data.gdeltproject.org/gdeltv2/lastupdate-translation.txt";
    /** One fetch per source window, so the escalation history counts real windows. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final int MAX_EVENTS = 220;
    private static final int MAX_RELATIONS = 90;
    private static final int MAX_HOTSPOTS = 25;

    /** How many windows of volume history a place keeps (~4 h of running). */
    private static final int TREND_WINDOWS = 16;
    /** Before this many windows nothing is called escalating - there is no baseline yet. */
    private static final int TREND_MIN_WINDOWS = 4;
    /** A story has to more than TRIPLE its usual coverage to count as escalating. */
    private static final double TREND_FACTOR = 3.0;
    /** ...and be loud in absolute terms, or every quiet village spikes. */
    private static final int TREND_MIN_ARTICLES = 10;

    private static final String ROOT_ASSAULT = "18";
    private static final String ROOT_FIGHT = "19";
    private static final String ROOT_MASS_VIOLENCE = "20";

    // Column layout of the 61-field export table (pinned against a live file).
    private static final int COL_IS_ROOT_EVENT = 25;
    private static final int COL_ROOT_CODE = 28;
    private static final int COL_GOLDSTEIN = 30;
    private static final int COL_ARTICLES = 33;
    private static final int COL_QUAD_CLASS = 29;
    private static final int COL_A1_GEO_TYPE = 35;
    private static final int COL_A1_GEO_NAME = 36;
    private static final int COL_A1_GEO_LAT = 40;
    private static final int COL_A1_GEO_LON = 41;
    private static final int COL_A2_GEO_TYPE = 43;
    private static final int COL_A2_GEO_NAME = 44;
    private static final int COL_A2_GEO_LAT = 48;
    private static final int COL_A2_GEO_LON = 49;
    private static final int COL_GEO_TYPE = 51;
    private static final int COL_GEO_NAME = 52;
    private static final int COL_GEO_LAT = 56;
    private static final int COL_GEO_LON = 57;
    private static final int COL_SOURCE_URL = 60;
    private static final int COLUMNS = 61;

    /** Geo precision below city level is a centroid - the very thing this layer replaces. */
    private static final int GEO_TYPE_COUNTRY = 1;

    /**
     * One geocoded violent event: {@code kind} is the CAMEO family token
     * (ASSAULT/FIGHT/MASS_VIOLENCE), {@code place} GDELT's own place name,
     * {@code articles} how loudly the world reported it, {@code goldstein} the
     * event's conflict-intensity score (negative = destructive).
     */
    public record Attack(String kind, String place, double lat, double lon,
            int articles, double goldstein, String sourceUrl) {
    }

    /**
     * One directed relation between two places: {@code stance} is CAMEO's own
     * quad class collapsed to a token (COOPERATION / VERBAL_CONFLICT /
     * MATERIAL_CONFLICT), the arc runs from the actor to the acted-upon.
     */
    public record Relation(String stance, String fromPlace, double fromLat, double fromLon,
            String toPlace, double toLat, double toLon, int articles, String sourceUrl) {
    }

    /**
     * A place whose coverage is climbing hard against its own recent normal -
     * the "this keeps escalating" signal. {@code factor} is how many times its
     * own baseline the current window is running at.
     */
    public record Hotspot(String place, double lat, double lon, int articles,
            int baseline, double factor, String sourceUrl) {
    }

    private record Cached(Instant at, List<Attack> attacks, List<Relation> relations,
            List<Hotspot> hotspots) {
    }

    /** Direct-first (joker mandate 2026-07-14); the browser is the rescue. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private final StoryTrend trend = new StoryTrend();
    private volatile Cached cached;

    @Inject
    public GdeltConflictClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The newest window's attacks; stale kept on failure. */
    public List<Attack> attacks() {
        return snapshot().attacks();
    }

    /** Who is doing something to whom, as directed place-to-place arcs. */
    public List<Relation> relations() {
        return snapshot().relations();
    }

    /** Places whose coverage is climbing hard against their own recent normal. */
    public List<Hotspot> hotspots() {
        return snapshot().hotspots();
    }

    /** One fetch per source window feeds all three products. */
    private Cached snapshot() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit;
        }
        try {
            String table = fetchWindow();
            if (!table.isBlank()) {
                List<String[]> rows = rowsOf(table);
                if (!rows.isEmpty()) {
                    Cached fresh = new Cached(Instant.now(), parseAttacks(rows),
                            parseRelations(rows), trend.observe(volumesOf(rows)));
                    cached = fresh;
                    return fresh;
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[GDELT] fetch failed: {}", e.getMessage());
        }
        return hit == null ? new Cached(Instant.now(), List.of(), List.of(), List.of()) : hit;
    }

    /**
     * Both language streams of the current window, concatenated. A failing
     * stream costs its own coverage, never the whole window - the English
     * file alone is still a world, and so is the translingual one.
     */
    private String fetchWindow() {
        StringBuilder table = new StringBuilder();
        for (String pointer : List.of(LAST_UPDATE, LAST_UPDATE_TRANSLATION)) {
            try {
                String url = exportUrlAt(pointer);
                if (url == null) continue;
                String part = fetchTable(url);
                if (!part.isBlank()) table.append(part).append('\n');
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.debug("[GDELT] stream {} failed: {}", pointer, e.getMessage());
            }
        }
        return table.toString();
    }

    /** The pointer file names the current window's three products; we want the events. */
    private String exportUrlAt(String pointerUrl) throws Exception {
        WebResponse resp = fetcher.fetch(pointerUrl,
                Map.of("Accept", "text/plain"), requestTimeout, MODES);
        if (resp == null || resp.status() != 200) {
            LOG.debug("[GDELT] pointer answered status {}", resp == null ? "null" : resp.status());
            return null;
        }
        return exportUrlIn(resp.body());
    }

    /** Package-visible for the fixture test. Lines are "size hash url". */
    static String exportUrlIn(String pointer) {
        if (pointer == null) return null;
        for (String line : pointer.split("\n")) {
            int space = line.lastIndexOf(' ');
            if (space < 0) continue;
            String url = line.substring(space + 1).strip();
            if (url.endsWith(".export.CSV.zip")) return url;
        }
        return null;
    }

    /** The event file is a ZIP - its bytes take the fetcher's binary path, never the text one. */
    private String fetchTable(String url) throws Exception {
        WebResponse resp = fetcher.fetchBinary(url,
                Map.of("Accept", "*/*"), requestTimeout, MODES);
        if (resp.status() != 200 || !resp.isBinary()) {
            LOG.debug("[GDELT] table answered status {}", resp.status());
            return "";
        }
        return unzipFirstEntry(resp.raw());
    }

    /** Package-visible for the fixture test. The archive carries exactly one table. */
    static String unzipFirstEntry(byte[] archive) throws Exception {
        if (archive == null || archive.length == 0) return "";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null) return "";
            return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Package-visible for the fixture test. Tab-separated, no header row. */
    List<String[]> rowsOf(String table) {
        if (table == null || table.isBlank()) return List.of();
        List<String[]> rows = new ArrayList<>();
        for (String line : table.split("\n")) {
            String[] c = line.split("\t", -1);
            if (c.length >= COLUMNS) rows.add(c);
        }
        return rows;
    }

    /** Convenience for the fixture tests: parse a raw table straight to attacks. */
    List<Attack> parse(String table) {
        return parseAttacks(rowsOf(table));
    }

    /**
     * The violent point events. The loudest report of a place wins, so a swarm
     * of wire copies about the same strike lands as ONE marker.
     */
    List<Attack> parseAttacks(List<String[]> rows) {
        Map<String, Attack> strongest = new HashMap<>();
        for (String[] c : rows) {
            String kind = kindOf(c[COL_ROOT_CODE]);
            if (kind == null) continue;
            // Only the event the article is ABOUT. GDELT codes every violent
            // mention it finds, so an opinion column that references a killing
            // in passing would otherwise plant a marker on that city
            // (live-measured 2026-08-02: this drops ~40 % of the rows, and it
            // is the incidental ones that go).
            if (!"1".equals(c[COL_IS_ROOT_EVENT].strip())) continue;
            if (intOr(c[COL_GEO_TYPE], GEO_TYPE_COUNTRY) <= GEO_TYPE_COUNTRY) continue;
            Double lat = doubleOrNull(c[COL_GEO_LAT]);
            Double lon = doubleOrNull(c[COL_GEO_LON]);
            if (lat == null || lon == null) continue;
            Attack attack = new Attack(kind, c[COL_GEO_NAME].strip(), lat, lon,
                    intOr(c[COL_ARTICLES], 1), doubleOr(c[COL_GOLDSTEIN], 0),
                    c[COL_SOURCE_URL].strip());
            // One marker per place and family: same spot, same kind = same story.
            String key = kind + '@' + cellKey(lat, lon);
            Attack seen = strongest.get(key);
            if (seen == null || attack.articles() > seen.articles()) strongest.put(key, attack);
        }
        List<Attack> out = new ArrayList<>(strongest.values());
        out.sort((a, b) -> Integer.compare(b.articles(), a.articles()));
        return out.size() > MAX_EVENTS ? List.copyOf(out.subList(0, MAX_EVENTS)) : List.copyOf(out);
    }

    /**
     * Package-visible for the fixture test. The directed arcs: an event whose
     * actor and target sit in DIFFERENT places is a relation between those
     * places - which is what "geopolitics" looks like from orbit. Both ends
     * must be geocoded finer than nothing; country-level ends are allowed
     * here on purpose (a state acting on a state IS a country-level fact,
     * unlike a single attack, which happens somewhere precise).
     */
    List<Relation> parseRelations(List<String[]> rows) {
        Map<String, Relation> strongest = new HashMap<>();
        for (String[] c : rows) {
            if (intOr(c[COL_A1_GEO_TYPE], 0) <= 0 || intOr(c[COL_A2_GEO_TYPE], 0) <= 0) continue;
            Double fromLat = doubleOrNull(c[COL_A1_GEO_LAT]);
            Double fromLon = doubleOrNull(c[COL_A1_GEO_LON]);
            Double toLat = doubleOrNull(c[COL_A2_GEO_LAT]);
            Double toLon = doubleOrNull(c[COL_A2_GEO_LON]);
            if (fromLat == null || fromLon == null || toLat == null || toLon == null) continue;
            // Same place acting on itself is domestic news, not a relation.
            if (cellKey(fromLat, fromLon).equals(cellKey(toLat, toLon))) continue;
            String stance = stanceOf(c[COL_QUAD_CLASS]);
            if (stance == null) continue;
            Relation relation = new Relation(stance,
                    c[COL_A1_GEO_NAME].strip(), fromLat, fromLon,
                    c[COL_A2_GEO_NAME].strip(), toLat, toLon,
                    intOr(c[COL_ARTICLES], 1), c[COL_SOURCE_URL].strip());
            String key = stance + '@' + cellKey(fromLat, fromLon) + '>' + cellKey(toLat, toLon);
            Relation seen = strongest.get(key);
            if (seen == null || relation.articles() > seen.articles()) {
                strongest.put(key, relation);
            }
        }
        List<Relation> out = new ArrayList<>(strongest.values());
        out.sort((a, b) -> Integer.compare(b.articles(), a.articles()));
        return out.size() > MAX_RELATIONS
                ? List.copyOf(out.subList(0, MAX_RELATIONS)) : List.copyOf(out);
    }

    /**
     * Package-visible for the fixture test. The window's coverage volume per
     * place, over EVERY topic - a story that escalates does not have to be a
     * war; it can be a court case, a protest or a scandal.
     */
    Map<String, Volume> volumesOf(List<String[]> rows) {
        Map<String, Volume> out = new LinkedHashMap<>();
        for (String[] c : rows) {
            if (intOr(c[COL_GEO_TYPE], GEO_TYPE_COUNTRY) <= GEO_TYPE_COUNTRY) continue;
            Double lat = doubleOrNull(c[COL_GEO_LAT]);
            Double lon = doubleOrNull(c[COL_GEO_LON]);
            if (lat == null || lon == null) continue;
            String key = cellKey(lat, lon);
            Volume seen = out.get(key);
            int articles = intOr(c[COL_ARTICLES], 1);
            if (seen == null) {
                out.put(key, new Volume(c[COL_GEO_NAME].strip(), lat, lon, articles,
                        c[COL_SOURCE_URL].strip()));
            } else {
                out.put(key, new Volume(seen.place(), seen.lat(), seen.lon(),
                        seen.articles() + articles, seen.sourceUrl()));
            }
        }
        return out;
    }

    /** One place's coverage inside a single window. */
    record Volume(String place, double lat, double lon, int articles, String sourceUrl) {
    }

    /**
     * The escalation memory: how loud each place has been over the last
     * {@value #TREND_WINDOWS} windows. A place becomes a hotspot when the
     * current window runs at more than {@value #TREND_FACTOR} times its own
     * median - measured in code, never judged by a model (house rule: the
     * statistics stay with the house).
     *
     * <p>It is deliberately EMPTY for the first hour of running: without a
     * baseline, "this is escalating" would be a guess.
     */
    static final class StoryTrend {

        private final Map<String, Deque<Integer>> history = new LinkedHashMap<>();

        synchronized List<Hotspot> observe(Map<String, Volume> window) {
            List<Hotspot> rising = new ArrayList<>();
            for (Map.Entry<String, Volume> e : window.entrySet()) {
                Volume now = e.getValue();
                Deque<Integer> past = history.computeIfAbsent(e.getKey(), k -> new ArrayDeque<>());
                if (past.size() >= TREND_MIN_WINDOWS && now.articles() >= TREND_MIN_ARTICLES) {
                    int baseline = median(past);
                    double factor = baseline <= 0 ? now.articles() : (double) now.articles() / baseline;
                    if (factor >= TREND_FACTOR) {
                        rising.add(new Hotspot(now.place(), now.lat(), now.lon(),
                                now.articles(), baseline, factor, now.sourceUrl()));
                    }
                }
                past.addLast(now.articles());
                while (past.size() > TREND_WINDOWS) past.removeFirst();
            }
            // A place that went quiet still ages out, or the map would grow forever.
            for (Map.Entry<String, Deque<Integer>> e : history.entrySet()) {
                if (window.containsKey(e.getKey())) continue;
                e.getValue().addLast(0);
                while (e.getValue().size() > TREND_WINDOWS) e.getValue().removeFirst();
            }
            history.entrySet().removeIf(e -> e.getValue().stream().allMatch(v -> v == 0));
            rising.sort((a, b) -> Double.compare(b.factor(), a.factor()));
            return rising.size() > MAX_HOTSPOTS
                    ? List.copyOf(rising.subList(0, MAX_HOTSPOTS)) : List.copyOf(rising);
        }

        private static int median(Deque<Integer> values) {
            List<Integer> sorted = new ArrayList<>(values);
            sorted.sort(Integer::compare);
            return sorted.get(sorted.size() / 2);
        }
    }

    /** ~1 km cells: the same strike reported with slightly different coordinates is one spot. */
    private static String cellKey(double lat, double lon) {
        return Math.round(lat * 100) + "," + Math.round(lon * 100);
    }

    /** CAMEO quad class 1..4 collapsed to the three stances an arc can show. */
    private static String stanceOf(String quadClass) {
        return switch (quadClass == null ? "" : quadClass.strip()) {
            case "1", "2" -> "COOPERATION";
            case "3" -> "VERBAL_CONFLICT";
            case "4" -> "MATERIAL_CONFLICT";
            default -> null;
        };
    }

    private static String kindOf(String rootCode) {
        return switch (rootCode == null ? "" : rootCode.strip()) {
            case ROOT_ASSAULT -> "ASSAULT";
            case ROOT_FIGHT -> "FIGHT";
            case ROOT_MASS_VIOLENCE -> "MASS_VIOLENCE";
            default -> null;
        };
    }

    private static Double doubleOrNull(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Double.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double doubleOr(String raw, double fallback) {
        Double v = doubleOrNull(raw);
        return v == null ? fallback : v;
    }

    private static int intOr(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
