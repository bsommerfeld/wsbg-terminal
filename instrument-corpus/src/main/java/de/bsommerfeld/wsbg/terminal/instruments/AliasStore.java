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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The LEARNED name→symbol memory: every spelling the room used that the
 * resolver once settled on an instrument, kept forever.
 *
 * <p>This exists because the corpus only carries a company's <b>registered</b>
 * name ("Deutsche Telekom AG") while the cage writes the short form
 * ("Telekom"). The bridge between the two is deliberately NOT derived by
 * stripping legal forms off a curated noise list — it is <b>learned</b>: every
 * time {@code TickerResolver} settles a subject name on a symbol (Yahoo + the
 * identity judge, a decision that happens anyway during normal operation), the
 * pair lands here. Collected once, held forever, free of extra requests.
 *
 * <p><b>Cold start is by design.</b> On day one this store is empty and a short
 * form counts as itself; from the first settled verdict on it counts as the
 * symbol — and because history stores the raw spelling and resolves on READ,
 * the fold-in reaches backwards through the archive too.
 *
 * <p><b>Format:</b> JSONL ({@code instruments/aliases.jsonl} in the app data
 * dir), one {@code {"name","symbol","learnedAt"}} per line, append-only. A
 * later line for the same name wins (the resolver changed its mind); a torn
 * final line from a crash is skipped on load.
 */
@Singleton
public final class AliasStore {

    private static final Logger LOG = LoggerFactory.getLogger(AliasStore.class);
    static final String FILE_NAME = "aliases.jsonl";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    /** normalized name → symbol. */
    private final Map<String, String> aliases = new ConcurrentHashMap<>();
    /** Bumped on every learned pair so consumers can rebuild their lexicon. */
    private final AtomicInteger version = new AtomicInteger();

    @Inject
    public AliasStore() {
        this(StorageUtils.getAppDataDir().resolve("instruments").resolve(FILE_NAME));
    }

    /** Store at an explicit path — for tests and maintenance tooling. */
    public AliasStore(Path file) {
        this.file = file;
        load();
    }

    /**
     * Records that {@code name} means {@code symbol}. Idempotent: an unchanged
     * pair never touches the file. Never throws — a failing write costs the
     * memory, not the caller.
     */
    public void learn(String name, String symbol) {
        String key = NameKey.normalize(name);
        if (key.isEmpty() || symbol == null || symbol.isBlank()) return;
        String value = symbol.trim().toUpperCase(Locale.ROOT);
        String previous = aliases.put(key, value);
        if (value.equals(previous)) return;
        version.incrementAndGet();
        append(key, value);
        LOG.info("[ALIAS] learned '{}' → {}{}", key, value,
                previous == null ? "" : " (was " + previous + ")");
    }

    /** The symbol this spelling was once settled on, if any. */
    public Optional<String> symbolFor(String name) {
        String key = NameKey.normalize(name);
        return key.isEmpty() ? Optional.empty() : Optional.ofNullable(aliases.get(key));
    }

    /** A snapshot of every learned pair (normalized name → symbol). */
    public Map<String, String> all() {
        return Map.copyOf(aliases);
    }

    public int size() {
        return aliases.size();
    }

    /** Increments with every learned pair — a cheap "has the memory changed?" handle. */
    public int version() {
        return version.get();
    }

    // -- persistence --

    private void load() {
        Map<String, String> loaded = new HashMap<>();
        int broken = 0;
        try {
            if (!Files.exists(file)) return;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode n = JSON.readTree(line);
                    String name = NameKey.normalize(n.path("name").asText(""));
                    String symbol = n.path("symbol").asText("").trim().toUpperCase(Locale.ROOT);
                    if (name.isEmpty() || symbol.isEmpty()) continue;
                    loaded.put(name, symbol); // later line wins
                } catch (Exception e) {
                    broken++;
                }
            }
        } catch (Exception e) {
            LOG.warn("[ALIAS] load failed: {}", e.getMessage());
            return;
        }
        aliases.putAll(loaded);
        LOG.info("[ALIAS] loaded {} learned name(s){} ← {}", aliases.size(),
                broken > 0 ? " (" + broken + " broken line(s) skipped)" : "", file);
    }

    private synchronized void append(String name, String symbol) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            ObjectNode n = JSON.createObjectNode();
            n.put("name", name);
            n.put("symbol", symbol);
            n.put("learnedAt", System.currentTimeMillis() / 1000);
            Files.writeString(file, n.toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("[ALIAS] append failed for '{}': {}", name, e.getMessage());
        }
    }
}
