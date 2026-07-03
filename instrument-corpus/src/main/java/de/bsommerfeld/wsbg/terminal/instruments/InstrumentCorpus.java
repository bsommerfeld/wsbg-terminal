package de.bsommerfeld.wsbg.terminal.instruments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The auto-updating local instrument list — the GROUND the resolver's identity
 * judge stands on instead of training-time memory: every US listing (SEC, daily
 * official) plus every German instrument the room ever surfaced (the learned
 * wallstreet-online ISIN memory). Persisted to
 * {@code <app-data>/instruments/instruments.jsonl}; refreshed asynchronously
 * when older than {@link #REFRESH_AFTER} (a failed refresh keeps the previous
 * snapshot — stale ground truth beats none).
 *
 * <p><b>Retrieval is deliberately LEXICAL, not embedding-based</b>: the judge
 * must never see the whole corpus, so {@link #search} returns the top-K entries
 * by token/prefix overlap with the query — deterministic, model-free, and
 * sufficient for name lookup over ~10-15k entries. (An embedding retrieval
 * layer stays a possible upgrade for cross-language recall — as an INDEX only;
 * the judgment itself stays with the LLM judge.)
 */
public final class InstrumentCorpus {

    private static final Logger LOG = LoggerFactory.getLogger(InstrumentCorpus.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Corpus age at which a background refresh is kicked off. */
    static final Duration REFRESH_AFTER = Duration.ofDays(7);

    private final Path file;
    private final List<CorpusSource> sources;
    private final AtomicReference<Index> index = new AtomicReference<>(Index.EMPTY);

    public InstrumentCorpus(Path instrumentsFile, List<CorpusSource> sources) {
        this.file = instrumentsFile;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * Loads the persisted corpus (if any) and kicks an async refresh when it's
     * stale or absent. Never blocks the caller on the network.
     */
    public void start() {
        loadFromDisk();
        boolean stale = true;
        try {
            if (Files.exists(file)) {
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                stale = Instant.now().isAfter(modified.plus(REFRESH_AFTER));
            }
        } catch (Exception ignored) {
        }
        if (stale && !sources.isEmpty()) {
            Thread t = new Thread(this::refresh, "instrument-corpus-refresh");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Entries currently indexed (0 until loaded/refreshed). */
    public int size() {
        return index.get().entries.size();
    }

    /**
     * Top-{@code k} entries whose NAME or SYMBOL lexically matches the query:
     * shared significant tokens rank first (more shared = higher), a token
     * PREFIX hit (query token is a prefix of a name token or vice versa) counts
     * half. Deterministic; returns fewer than {@code k} when the corpus has no
     * plausible candidates at all.
     */
    public List<InstrumentEntry> search(String query, int k) {
        Index idx = index.get();
        if (query == null || query.isBlank() || idx.entries.isEmpty() || k <= 0) return List.of();
        Set<String> qTokens = tokens(query);
        if (qTokens.isEmpty()) return List.of();

        Map<Integer, Double> scores = new HashMap<>();
        for (String qt : qTokens) {
            List<Integer> exact = idx.byToken.get(qt);
            if (exact != null) {
                for (int i : exact) scores.merge(i, 1.0, Double::sum);
            }
            // Prefix pass over the token dictionary (bounded: only tokens sharing
            // the first 2 chars are candidates).
            if (qt.length() >= 3) {
                List<String> bucket = idx.tokensByPrefix.get(qt.substring(0, 2));
                if (bucket != null) {
                    for (String t : bucket) {
                        if (t.equals(qt)) continue;
                        if (t.startsWith(qt) || qt.startsWith(t)) {
                            for (int i : idx.byToken.get(t)) scores.merge(i, 0.5, Double::sum);
                        }
                    }
                }
            }
        }
        return scores.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Double.compare(b.getValue(), a.getValue());
                    if (c != 0) return c;
                    // tie-break: shorter name = more specific match
                    return Integer.compare(idx.entries.get(a.getKey()).name().length(),
                            idx.entries.get(b.getKey()).name().length());
                })
                .limit(k)
                .map(e -> idx.entries.get(e.getKey()))
                .toList();
    }

    // -- refresh + persistence --

    /** Pulls every source, merges (ISIN/symbol-deduped), persists, re-indexes. Synchronous. */
    public void refresh() {
        List<InstrumentEntry> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int failed = 0;
        for (CorpusSource s : sources) {
            try {
                List<InstrumentEntry> got = s.fetch();
                int kept = 0;
                for (InstrumentEntry e : got) {
                    String key = (e.isin() != null && !e.isin().isBlank())
                            ? "isin:" + e.isin()
                            : "sym:" + e.symbol().toUpperCase(Locale.ROOT);
                    if (seen.add(key)) {
                        merged.add(e);
                        kept++;
                    }
                }
                LOG.info("[CORPUS] source '{}' → {} instruments ({} new)", s.name(), got.size(), kept);
            } catch (Exception e) {
                failed++;
                LOG.warn("[CORPUS] source '{}' failed: {} — keeping previous snapshot for it",
                        s.name(), e.getMessage());
            }
        }
        if (merged.isEmpty()) {
            LOG.warn("[CORPUS] refresh yielded nothing ({} source(s) failed) — keeping previous corpus", failed);
            return;
        }
        persist(merged);
        index.set(Index.build(merged));
        LOG.info("[CORPUS] refreshed: {} instruments indexed", merged.size());
    }

    private void persist(List<InstrumentEntry> entries) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder(entries.size() * 96);
            for (InstrumentEntry e : entries) {
                ObjectNode n = JSON.createObjectNode();
                n.put("symbol", e.symbol());
                n.put("name", e.name());
                if (e.isin() != null) n.put("isin", e.isin());
                if (e.exchange() != null) n.put("exchange", e.exchange());
                if (e.type() != null) n.put("type", e.type());
                n.put("source", e.source());
                sb.append(n.toString()).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warn("[CORPUS] persist failed: {}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        try {
            if (!Files.exists(file)) return;
            List<InstrumentEntry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode n = JSON.readTree(line);
                    String symbol = n.path("symbol").asText("").trim();
                    String name = n.path("name").asText("").trim();
                    if (symbol.isEmpty() || name.isEmpty()) continue;
                    entries.add(new InstrumentEntry(symbol, name,
                            n.hasNonNull("isin") ? n.path("isin").asText() : null,
                            n.hasNonNull("exchange") ? n.path("exchange").asText() : null,
                            n.hasNonNull("type") ? n.path("type").asText() : null,
                            n.path("source").asText("disk")));
                } catch (Exception ignored) {
                    // torn line — skip
                }
            }
            if (!entries.isEmpty()) {
                index.set(Index.build(entries));
                LOG.info("[CORPUS] loaded {} instruments from disk", entries.size());
            }
        } catch (Exception e) {
            LOG.warn("[CORPUS] load failed: {}", e.getMessage());
        }
    }

    // -- lexical index --

    static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9äöüß]+")) {
            if (w.length() >= 2 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** Legal-form noise that would connect unrelated companies. */
    private static final Set<String> STOP = Set.of(
            "inc", "corp", "co", "ltd", "plc", "sa", "nv", "se", "ag", "kgaa", "gmbh",
            "the", "and", "of", "group", "holdings", "holding", "company", "corporation",
            "incorporated", "limited", "class", "common", "stock", "shares", "adr", "etf");

    private record Index(List<InstrumentEntry> entries, Map<String, List<Integer>> byToken,
                         Map<String, List<String>> tokensByPrefix) {

        static final Index EMPTY = new Index(List.of(), Map.of(), Map.of());

        static Index build(List<InstrumentEntry> entries) {
            Map<String, List<Integer>> byToken = new HashMap<>();
            for (int i = 0; i < entries.size(); i++) {
                InstrumentEntry e = entries.get(i);
                Set<String> ts = tokens(e.name());
                ts.addAll(tokens(e.symbol()));
                for (String t : ts) byToken.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
            }
            Map<String, List<String>> byPrefix = new HashMap<>();
            for (String t : byToken.keySet()) {
                if (t.length() >= 2) {
                    byPrefix.computeIfAbsent(t.substring(0, 2), k -> new ArrayList<>()).add(t);
                }
            }
            return new Index(List.copyOf(entries), byToken, byPrefix);
        }
    }
}
