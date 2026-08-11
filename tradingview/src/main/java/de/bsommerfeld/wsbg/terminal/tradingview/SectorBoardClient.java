package de.bsommerfeld.wsbg.terminal.tradingview;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The BRANCH BOARD - what an issuer's own industry did, computed in the house
 * from the screener's own rows.
 *
 * <p>The dossier could say "US sector proxy Tech: -2.1 % today" and nothing
 * else. Three things were wrong with that. It named a SECTOR where the
 * question is about an industry - a business software house and a chip
 * foundry share the label "Tech" and share nothing else. It knew only TODAY,
 * so "the branch fell all quarter and the share fell twice as far" could not
 * be said. And it read the sector off a hand-written keyword table mapping
 * German industry words onto eleven US sector ETFs - the kind of maintained
 * fix-list the house does not keep.
 *
 * <p>All three fall away here: the screener labels every issuer with its own
 * sector AND industry, so the subject's branch is READ, never guessed, and
 * the branch's performance is the MEDIAN over its listed members - house
 * arithmetic over a named population, computed the same way for every branch,
 * with the member count printed beside it so a four-issuer branch cannot pass
 * as a market fact.
 *
 * <p>The median, not the average: one takeover candidate at +40 % would move
 * a mean and tells nothing about the branch. And the RANK matters as much as
 * the number - "-2.1 %" reads differently when it is the worst of 122
 * branches than when the whole market is down.
 *
 * <p>ONE request per universe, filtered to the PRIMARY venue (XETR / NYSE +
 * NASDAQ). Without that filter the same issuer arrives once per German venue -
 * five rows for one company, which would weight it fivefold in its own branch
 * (measured 2026-08-11: 35.206 rows against 1.399 issuers).
 */
@Singleton
public class SectorBoardClient {

    private static final Logger LOG = LoggerFactory.getLogger(SectorBoardClient.class);

    /** Cached per universe - a branch board moves with the tape, not with the second. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private static final String COL_INDUSTRY = "industry";
    private static final String COL_WEEK = "Perf.W";
    private static final String COL_MONTH = "Perf.1M";
    private static final String COL_QUARTER = "Perf.3M";

    private static final List<String> COLUMNS = List.of(
            TradingViewScanner.COL_NAME, TradingViewScanner.COL_SECTOR, COL_INDUSTRY,
            TradingViewScanner.COL_CHANGE, COL_WEEK, COL_MONTH, COL_QUARTER,
            TradingViewScanner.COL_MARKET_CAP);

    /** The primary venue per universe - see the class note on venue duplicates. */
    private static final Map<String, TradingViewScanner.Filter> VENUE = Map.of(
            TradingViewScanner.MARKET_GERMANY,
            new TradingViewScanner.Filter("exchange", "equal", "XETR"),
            TradingViewScanner.MARKET_AMERICA,
            new TradingViewScanner.Filter("exchange", "in_range", List.of("NASDAQ", "NYSE")));

    /** Runaway backstop; both universes measured well below it (1.399 / 5.449). */
    private static final int MAX_ROWS = 20_000;

    /**
     * One branch of the board.
     *
     * @param branche the screener's industry label
     * @param sektor  the broader sector the branch sits in
     * @param titel   how many listed issuers carry the branch - the population
     *                the medians rest on, printed wherever they are
     * @param tag     median day change of its members, in percent
     * @param woche   median week performance, or NaN where too few report one
     * @param monat   median month performance, or NaN
     * @param quartal median quarter performance, or NaN
     * @param rang    place among the day's branches, 1 = strongest
     */
    public record Branche(String branche, String sektor, int titel, double tag,
            double woche, double monat, double quartal, int rang) {
    }

    /**
     * One universe's board.
     *
     * @param markt    the screener universe ({@code germany} / {@code america})
     * @param branchen every branch, strongest day first
     * @param stand    when the board was computed
     */
    public record Tafel(String markt, List<Branche> branchen, Instant stand) {

        public static final Tafel LEER = new Tafel("", List.of(), null);

        public boolean isEmpty() {
            return branchen.isEmpty();
        }

        /** The branch by its label, or {@code null}. */
        public Branche branche(String label) {
            if (label == null || label.isBlank()) return null;
            for (Branche b : branchen) {
                if (b.branche().equalsIgnoreCase(label.strip())) return b;
            }
            return null;
        }

        /**
         * The strongest {@code n} branches of the day carried by at least
         * {@code minTitel} issuers. The floor is not a cap on the data - every
         * branch keeps its row and its rank - but a one-issuer branch is one
         * COMPANY wearing a branch's name, and printed as "the day's strongest
         * branch" it would be a false statement about a market (measured
         * 2026-08-11: a single printing house led the German board).
         */
        public List<Branche> staerkste(int n, int minTitel) {
            List<Branche> out = new ArrayList<>(n);
            for (Branche b : branchen) {
                if (out.size() >= n) break;
                if (b.titel() >= minTitel && !Double.isNaN(b.tag())) out.add(b);
            }
            return out;
        }

        /** The weakest {@code n} qualifying branches of the day, weakest first. */
        public List<Branche> schwaechste(int n, int minTitel) {
            List<Branche> out = new ArrayList<>(n);
            for (int i = branchen.size() - 1; i >= 0 && out.size() < n; i--) {
                Branche b = branchen.get(i);
                if (b.titel() >= minTitel && !Double.isNaN(b.tag())) out.add(b);
            }
            return out;
        }
    }

    /**
     * A single issuer's seat on the board: which branch the screener files it
     * under, and how it moved against that branch today.
     *
     * @param branche  the branch row itself, never null
     * @param titelTag the issuer's own day change, or NaN
     */
    public record Sitz(Branche branche, double titelTag) {

        /** Percentage points the issuer moved ahead of (positive) its branch. */
        public double vorsprung() {
            return Double.isNaN(titelTag) ? Double.NaN : titelTag - branche.tag();
        }
    }

    private final TradingViewScanner scanner;

    private record CacheEntry(Tafel tafel, Map<String, Double> tagJeTitel,
            Map<String, String> brancheJeTitel, long atMs) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Test/default: the scanner's own default transport. */
    public SectorBoardClient() {
        this(new TradingViewScanner());
    }

    @Inject
    public SectorBoardClient(TradingViewScanner scanner) {
        this.scanner = scanner;
    }

    /**
     * The whole board of one universe, strongest branch first. Empty when the
     * screener could not be reached (never cached).
     */
    public Tafel tafel(String markt) {
        CacheEntry entry = board(markt);
        return entry == null ? Tafel.LEER : entry.tafel();
    }

    /**
     * Where {@code ticker} sits on its universe's board - its branch, the
     * branch's course, and the gap between the two today. {@code null} when
     * the screener does not carry the ticker or could not be reached.
     */
    public Sitz sitz(String markt, String ticker) {
        if (ticker == null || ticker.isBlank()) return null;
        CacheEntry entry = board(markt);
        if (entry == null) return null;
        String key = ticker.strip().toUpperCase(Locale.ROOT);
        // A venue suffix means nothing to the screener's ticker column.
        int dot = key.indexOf('.');
        if (dot > 0) key = key.substring(0, dot);
        String label = entry.brancheJeTitel().get(key);
        if (label == null) return null;
        Branche branche = entry.tafel().branche(label);
        if (branche == null) return null;
        Double tag = entry.tagJeTitel().get(key);
        return new Sitz(branche, tag == null ? Double.NaN : tag);
    }

    private CacheEntry board(String markt) {
        if (markt == null || markt.isBlank()) return null;
        String key = markt.strip().toLowerCase(Locale.ROOT);
        CacheEntry hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.atMs() < CACHE_TTL.toMillis()) {
            return hit;
        }
        List<TradingViewScanner.Filter> filters = new ArrayList<>();
        filters.add(new TradingViewScanner.Filter("type", "equal", "stock"));
        TradingViewScanner.Filter venue = VENUE.get(key);
        if (venue != null) filters.add(venue);
        TradingViewScanner.ScanResult result =
                scanner.scan(key, COLUMNS, filters, null, 0, MAX_ROWS);
        if (result == null || result.isEmpty()) {
            LOG.debug("[sector-board] {} answered no rows", key);
            return null;
        }
        Map<String, List<TradingViewScanner.ScanRow>> perBranche = new LinkedHashMap<>();
        Map<String, Double> tagJeTitel = new LinkedHashMap<>();
        Map<String, String> brancheJeTicker = new LinkedHashMap<>();
        for (TradingViewScanner.ScanRow row : result.rows()) {
            String branche = row.text(COL_INDUSTRY);
            if (branche == null || branche.isBlank()) continue;
            perBranche.computeIfAbsent(branche, b -> new ArrayList<>()).add(row);
            String name = row.text(TradingViewScanner.COL_NAME);
            if (name != null && !name.isBlank()) {
                String ticker = name.strip().toUpperCase(Locale.ROOT);
                brancheJeTicker.putIfAbsent(ticker, branche);
                OptionalDouble tag = row.number(TradingViewScanner.COL_CHANGE);
                if (tag.isPresent()) tagJeTitel.putIfAbsent(ticker, tag.getAsDouble());
            }
        }
        List<Branche> branchen = new ArrayList<>(perBranche.size());
        for (var e : perBranche.entrySet()) {
            List<TradingViewScanner.ScanRow> rows = e.getValue();
            branchen.add(new Branche(e.getKey(),
                    rows.get(0).text(TradingViewScanner.COL_SECTOR),
                    rows.size(),
                    median(rows, TradingViewScanner.COL_CHANGE),
                    median(rows, COL_WEEK), median(rows, COL_MONTH), median(rows, COL_QUARTER),
                    0));
        }
        // NaN LAST in both directions: a branch whose members report no day
        // change is not the strongest branch of the day, and Java's natural
        // double order would seat it exactly there (it sorts NaN above every
        // number, so the reversed order leads with it - measured 2026-08-11).
        branchen.sort(Comparator.comparingDouble(
                (Branche b) -> Double.isNaN(b.tag()) ? Double.NEGATIVE_INFINITY : b.tag())
                .reversed());
        List<Branche> ranked = new ArrayList<>(branchen.size());
        for (int i = 0; i < branchen.size(); i++) {
            Branche b = branchen.get(i);
            ranked.add(new Branche(b.branche(), b.sektor(), b.titel(), b.tag(), b.woche(),
                    b.monat(), b.quartal(), i + 1));
        }
        Tafel tafel = new Tafel(key, List.copyOf(ranked), Instant.now());
        CacheEntry entry = new CacheEntry(tafel, Map.copyOf(tagJeTitel),
                Map.copyOf(brancheJeTicker), System.currentTimeMillis());
        cache.put(key, entry);
        LOG.info("[sector-board] {} → {} branch(es) over {} issuers", key, ranked.size(),
                result.rows().size());
        return entry;
    }

    /** The median of one column over a branch's members; NaN when none report it. */
    private static double median(List<TradingViewScanner.ScanRow> rows, String column) {
        List<Double> values = new ArrayList<>(rows.size());
        for (TradingViewScanner.ScanRow row : rows) {
            OptionalDouble v = row.number(column);
            if (v.isPresent() && Double.isFinite(v.getAsDouble())) values.add(v.getAsDouble());
        }
        if (values.isEmpty()) return Double.NaN;
        java.util.Collections.sort(values);
        int n = values.size();
        return n % 2 == 1 ? values.get(n / 2)
                : (values.get(n / 2 - 1) + values.get(n / 2)) / 2.0;
    }
}
