package de.bsommerfeld.wsbg.terminal.instruments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The LEARNED name→symbol memory: every spelling the room used that the resolver
 * once settled on an instrument — kept as a <b>book of postings</b>, not a table
 * of facts.
 *
 * <p>This exists because the corpus only carries a company's <b>registered</b>
 * name ("Deutsche Telekom AG") while the cage writes the short form ("Telekom"),
 * and because the room coins its own words for things. The bridge between the two
 * is deliberately NOT a curated noise list — it is <b>learned</b>: every time the
 * resolver settles a subject name on a symbol (a decision that happens anyway
 * during normal operation), the pair lands here.
 *
 * <h3>A ledger, not a table</h3>
 * Each line is a POSTING — this spelling was read as that symbol, at that time,
 * by that stage, under that headline. The state (which readings a spelling has,
 * how strong each is, which have died) is FOLDED out of the postings when the
 * file is read; nothing is overwritten and nothing is deleted. That is what makes
 * the memory correctable without being destructible: a reading that fell out of
 * use is not gone, it is merely weak, and a single new posting brings it back.
 *
 * <h3>One spelling, several readings</h3>
 * A name may carry several {@link AliasCandidate}s at once, and the store reports
 * them rather than picking. Two readings can differ because the word genuinely
 * means two things (a key figure and a Japanese printer maker) or because one
 * paper trades in two places — the first needs the identity judge's context, the
 * second is a venue preference. Neither decision belongs in a file. What DOES
 * belong here is the finding that the question is open, which the old
 * last-line-wins rule silently swallowed.
 *
 * <h3>Vitality replaces curation</h3>
 * <b>Nothing is held forever any more.</b> Every posting adds one unit of
 * strength and the total halves every {@link #HALF_LIFE_DAYS} days, so a reading
 * has to be re-earned by being used. A spelling the room keeps writing stays
 * strong for free; a one-off coinage, a dead hype ticker or a single misfire
 * fades below {@link #LIVE_THRESHOLD} and counts as unlearned — reachable in the
 * history, gone from the answers. Nobody curates this and nobody presses a
 * button: the decay is a pure function of "now minus last posting", computed on
 * read, so no timer and no maintenance job exist to forget to run.
 *
 * <h3>Format</h3>
 * JSONL ({@code instruments/aliases.jsonl} in the app data dir), append-only. A
 * posting is {@code {"name","symbol","at","tier","isin","venue","cat","ctx","conf"}};
 * {@code "none":true} instead of a symbol books the considered "this spelling is
 * no instrument". Lines written before the ledger (bare
 * {@code {"name","symbol","learnedAt"}}) read as plain postings, so the memory
 * collected so far carries over unchanged. A torn final line from a crash is
 * skipped on load. When the file outgrows {@link #COMPACT_LINES} it is rewritten
 * to one folded line per reading, carrying the accumulated weight in {@code "w"}.
 */
@Singleton
public final class AliasStore {

    private static final Logger LOG = LoggerFactory.getLogger(AliasStore.class);
    static final String FILE_NAME = "aliases.jsonl";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * How fast an unused reading fades. Seven days is deliberately short: the room
     * posts massively, so anything that matters is re-mentioned within a week, and
     * anything that is not re-mentioned within a week was noise. The price — a
     * subject the room only discusses at earnings dies between two quarters — is
     * affordable precisely because re-learning costs a single judge call.
     */
    static final double HALF_LIFE_DAYS = 7.0;

    /**
     * Below this strength a reading counts as unlearned. Set so that a reading with
     * a SINGLE posting behind it dies after three half-lives (~21 days), while one
     * the room keeps confirming survives proportionally longer.
     */
    static final double LIVE_THRESHOLD = 0.125;

    /**
     * The strength a reading needs before the resolver may take it as settled and
     * skip its own work. Roughly "confirmed more than twice, recently". One posting
     * is a data point, not a memory.
     */
    public static final double TRUSTED_STRENGTH = 2.0;

    /**
     * The time grain of the heartbeat: the same reading is booked at most once per
     * hour. Without it every re-preparation of a hot thread would append a line for
     * the same pair and the file would grow with the scan loop rather than with the
     * knowledge.
     */
    static final long GRAIN_SECONDS = 3_600;

    /** Above this many lines the ledger is folded down on load. */
    static final int COMPACT_LINES = 5_000;

    private static final double HALF_LIFE_SECONDS = HALF_LIFE_DAYS * 24 * 3_600;

    /** The in-memory fold: normalized name → reading key → accumulated cell. */
    private final Map<String, Map<String, Cell>> byName = new ConcurrentHashMap<>();
    /** Bumped on every written posting so consumers can rebuild derived structures. */
    private final AtomicInteger version = new AtomicInteger();

    private final Path file;

    @Inject
    public AliasStore() {
        this(StorageUtils.getAppDataDir().resolve("instruments").resolve(FILE_NAME));
    }

    /** Store at an explicit path — for tests and maintenance tooling. */
    public AliasStore(Path file) {
        this.file = file;
        load();
    }

    // -- writing --

    /** Records that {@code name} was read as {@code symbol}, with nothing known about why. */
    public void learn(String name, String symbol) {
        learn(name, symbol, AliasProvenance.UNKNOWN);
    }

    /**
     * Books one posting: this spelling was read as this symbol, now, for this
     * reason. Never throws — a failing write costs the memory, not the caller.
     * Within {@link #GRAIN_SECONDS} of the previous posting for the same reading
     * the call is a no-op, so a hot thread's repeated preparations do not inflate
     * either the file or the strength.
     */
    public void learn(String name, String symbol, AliasProvenance provenance) {
        String key = NameKey.normalize(name);
        if (key.isEmpty() || symbol == null || symbol.isBlank()) return;
        book(key, symbol.trim().toUpperCase(Locale.ROOT), provenance);
    }

    /**
     * Books the considered negative: this spelling was looked at with the full
     * candidate set on the table and is no instrument. Worth writing down — without
     * it every mention of a room word re-asks the model the same settled question.
     * It decays like any other reading, so a "no" from three weeks ago stops
     * blocking a fresh look.
     */
    public void learnNothing(String name, AliasProvenance provenance) {
        String key = NameKey.normalize(name);
        if (key.isEmpty()) return;
        book(key, null, provenance);
    }

    private void book(String key, String symbol, AliasProvenance provenance) {
        long now = nowSeconds();
        String readingKey = readingKey(symbol);
        Map<String, Cell> readings = byName.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Cell cell = readings.computeIfAbsent(readingKey, k -> new Cell(symbol));
        double strength;
        synchronized (cell) {
            if (now - cell.lastSeen < GRAIN_SECONDS && cell.postings > 0) return;
            cell.add(now, provenance);
            strength = strengthOf(cell, now);
        }
        version.incrementAndGet();
        append(key, symbol, now, provenance);
        LOG.info("[ALIAS] booked '{}' → {}{} (strength {})", key,
                symbol == null ? "(no instrument)" : symbol,
                provenance != null && provenance.tier() != null ? " via " + provenance.tier() : "",
                String.format(Locale.ROOT, "%.2f", strength));
    }

    // -- reading --

    /**
     * The symbol this spelling means, when the ledger is UNAMBIGUOUS about it.
     * Empty whenever the memory is contested — a second reading of comparable
     * strength, or a live "no instrument" verdict that is not clearly beaten. A
     * contested spelling is not a failure to report; it is the signal that the
     * question needs the context only the resolver has.
     */
    public Optional<String> symbolFor(String name) {
        List<AliasCandidate> live = candidatesFor(name);
        if (live.isEmpty()) return Optional.empty();
        AliasCandidate best = live.get(0);
        if (best.isNone()) return Optional.empty();
        // A runner-up within reach means the readings disagree — say so instead of
        // picking the marginally fresher one, which is what last-line-wins used to do.
        if (live.size() > 1 && live.get(1).strength() * 2 > best.strength()) return Optional.empty();
        return Optional.of(best.symbol());
    }

    /**
     * Every LIVE reading of this spelling, strongest first — the negative among
     * them. Callers that can weigh context (the identity judge) or venue (the price
     * chain) get the whole picture; callers that only want an answer use
     * {@link #symbolFor}.
     */
    public List<AliasCandidate> candidatesFor(String name) {
        return readings(name, true);
    }

    /**
     * Every reading ever booked for this spelling, strongest first, INCLUDING the
     * faded ones. The history a later re-interpretation reads: what this word was
     * taken for, how often, by which stage and under which headline.
     */
    public List<AliasCandidate> ledgerFor(String name) {
        return readings(name, false);
    }

    private List<AliasCandidate> readings(String name, boolean liveOnly) {
        Map<String, Cell> readings = byName.get(NameKey.normalize(name));
        if (readings == null || readings.isEmpty()) return List.of();
        long now = nowSeconds();
        List<AliasCandidate> out = new ArrayList<>(readings.size());
        for (Cell c : readings.values()) {
            AliasCandidate cand;
            synchronized (c) {
                cand = new AliasCandidate(c.symbol, strengthOf(c, now), c.postings, c.lastSeen,
                        c.provenance());
            }
            if (liveOnly && !cand.isLive()) continue;
            out.add(cand);
        }
        out.sort(Comparator.comparingDouble(AliasCandidate::strength).reversed());
        return List.copyOf(out);
    }

    /**
     * Whether the ledger holds a live, uncontested "this spelling is no instrument".
     * The guard that stops the same room word being re-judged on every mention.
     */
    public boolean isRuledOut(String name) {
        List<AliasCandidate> live = candidatesFor(name);
        return !live.isEmpty() && live.get(0).isNone() && live.get(0).isTrusted();
    }

    /** A snapshot of the unambiguous live pairs (normalized name → symbol). */
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : byName.keySet()) {
            symbolFor(name).ifPresent(sym -> out.put(name, sym));
        }
        return Map.copyOf(out);
    }

    /** How many spellings still carry at least one live reading. */
    public int size() {
        int n = 0;
        for (String name : byName.keySet()) {
            if (!candidatesFor(name).isEmpty()) n++;
        }
        return n;
    }

    /** Increments with every booked posting — a cheap "has the memory changed?" handle. */
    public int version() {
        return version.get();
    }

    // -- decay --

    private static double strengthOf(Cell cell, long now) {
        return decay(cell.weight, cell.lastSeen, now);
    }

    /** Halves the weight once per half-life of elapsed time. Never grows. */
    private static double decay(double weight, long from, long to) {
        if (to <= from || weight <= 0) return weight;
        return weight * Math.pow(0.5, (to - from) / HALF_LIFE_SECONDS);
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    private static String readingKey(String symbol) {
        return symbol == null ? " none" : symbol;
    }

    // -- persistence --

    /**
     * One reading's accumulated state. The weight is an exponentially-weighted
     * count: on every posting the standing weight is first decayed to that
     * posting's time, then one is added. Folding the whole file that way gives the
     * same number as summing each posting's individually-decayed contribution,
     * without having to keep the postings in memory.
     */
    private static final class Cell {
        private final String symbol;
        private double weight;
        private long lastSeen;
        private int postings;
        private String tier;
        private String isin;
        private long venueId;
        private String category;
        private String context;
        private String confidence;

        Cell(String symbol) {
            this.symbol = symbol;
        }

        void add(long at, AliasProvenance p) {
            weight = decay(weight, lastSeen, at) + 1;
            if (at > lastSeen) lastSeen = at;
            postings++;
            remember(p);
        }

        /** Seeds a compacted line: the weight is carried, not re-derived. */
        void seed(long at, double w, int n, AliasProvenance p) {
            weight = decay(weight, lastSeen, at) + w;
            if (at > lastSeen) lastSeen = at;
            postings += Math.max(1, n);
            remember(p);
        }

        /**
         * The newest posting's reasons win, but a field the newer posting left blank
         * never erases what an older one knew — an ISIN learned once stays available
         * even when a later, thinner verdict carried none.
         */
        private void remember(AliasProvenance p) {
            if (p == null) return;
            if (p.tier() != null) tier = p.tier();
            if (p.isin() != null) isin = p.isin();
            if (p.venueId() != 0) venueId = p.venueId();
            if (p.category() != null) category = p.category();
            if (p.context() != null && !p.context().isBlank()) context = p.context();
            if (p.confidence() != null) confidence = p.confidence();
        }

        AliasProvenance provenance() {
            return new AliasProvenance(tier, isin, venueId, category, context, confidence);
        }
    }

    private void load() {
        int lines = 0;
        int broken = 0;
        try {
            if (!Files.exists(file)) return;
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<String, Map<String, Cell>> loaded = new HashMap<>();
            for (String line : all) {
                if (line.isBlank()) continue;
                lines++;
                try {
                    if (!replay(loaded, line)) broken++;
                } catch (Exception e) {
                    broken++;
                }
            }
            for (Map.Entry<String, Map<String, Cell>> e : loaded.entrySet()) {
                byName.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
            }
        } catch (Exception e) {
            LOG.warn("[ALIAS] load failed: {}", e.getMessage());
            return;
        }
        LOG.info("[ALIAS] folded {} posting(s){} → {} live spelling(s) ← {}", lines,
                broken > 0 ? " (" + broken + " broken line(s) skipped)" : "", size(), file);
        if (lines > COMPACT_LINES) compact(lines);
    }

    /** Replays one ledger line into the fold. False = unusable line. */
    private boolean replay(Map<String, Map<String, Cell>> into, String line) throws IOException {
        JsonNode n = JSON.readTree(line);
        String name = NameKey.normalize(n.path("name").asText(""));
        if (name.isEmpty()) return false;
        boolean none = n.path("none").asBoolean(false);
        String symbol = n.path("symbol").asText("").trim().toUpperCase(Locale.ROOT);
        if (!none && symbol.isEmpty()) return false;
        // "at" is the ledger's stamp; "learnedAt" is what the pre-ledger lines wrote.
        long at = n.hasNonNull("at") ? n.path("at").asLong() : n.path("learnedAt").asLong(0);
        if (at <= 0) at = nowSeconds();
        AliasProvenance p = new AliasProvenance(
                text(n, "tier"), text(n, "isin"), n.path("venue").asLong(0),
                text(n, "cat"), text(n, "ctx"), text(n, "conf"));
        String symbolOrNull = none ? null : symbol;
        Cell cell = into.computeIfAbsent(name, k -> new HashMap<>())
                .computeIfAbsent(readingKey(symbolOrNull), k -> new Cell(symbolOrNull));
        if (n.hasNonNull("w")) {
            cell.seed(at, n.path("w").asDouble(1.0), n.path("n").asInt(1), p);
        } else {
            cell.add(at, p);
        }
        return true;
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText("");
        return v.isBlank() ? null : v;
    }

    private synchronized void append(String name, String symbol, long at, AliasProvenance p) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, posting(name, symbol, at, p, null, 0) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("[ALIAS] append failed for '{}': {}", name, e.getMessage());
        }
    }

    /** One ledger line. With {@code weight} set it is a folded line, not a posting. */
    private static String posting(String name, String symbol, long at, AliasProvenance p,
            Double weight, int postings) {
        ObjectNode n = JSON.createObjectNode();
        n.put("name", name);
        if (symbol == null) n.put("none", true);
        else n.put("symbol", symbol);
        n.put("at", at);
        if (p != null) {
            if (p.tier() != null) n.put("tier", p.tier());
            if (p.isin() != null) n.put("isin", p.isin());
            if (p.venueId() != 0) n.put("venue", p.venueId());
            if (p.category() != null) n.put("cat", p.category());
            if (p.context() != null && !p.context().isBlank()) n.put("ctx", clip(p.context()));
            if (p.confidence() != null) n.put("conf", p.confidence());
        }
        if (weight != null) {
            n.put("w", weight);
            n.put("n", postings);
        }
        return n.toString();
    }

    /** The room's handle is a reason, not an archive — a headline's worth is plenty. */
    private static String clip(String s) {
        String t = s.strip();
        return t.length() <= 160 ? t : t.substring(0, 160);
    }

    /**
     * Folds the ledger down to one line per reading — the accumulated weight rides
     * along in {@code "w"}, so the fold survives the rewrite. Only the DEAD readings
     * are actually lost, which is the point: they no longer say anything.
     */
    private synchronized void compact(int lines) {
        try {
            long now = nowSeconds();
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Map<String, Cell>> e : byName.entrySet()) {
                for (Cell c : e.getValue().values()) {
                    synchronized (c) {
                        double strength = strengthOf(c, now);
                        if (strength < LIVE_THRESHOLD) continue;
                        out.add(posting(e.getKey(), c.symbol, c.lastSeen, c.provenance(),
                                c.weight, c.postings));
                    }
                }
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".compact");
            Files.write(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("[ALIAS] compacted {} posting(s) → {} live reading(s)", lines, out.size());
        } catch (Exception e) {
            LOG.warn("[ALIAS] compaction failed (ledger left as it was): {}", e.getMessage());
        }
    }
}
