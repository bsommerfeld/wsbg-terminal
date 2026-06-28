package de.bsommerfeld.wsbg.terminal.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The user's persisted ticker watchlist — the symbols whose archived headline
 * history ({@link HeadlineArchive#byTicker}) the UI surfaces as a watchlist.
 * Tiny user state, kept as a plain JSON array of symbols
 * ({@code watchlist.json} in the app data dir), written through on every
 * mutation. Symbols are normalised to upper-case; insertion order is kept so
 * the list renders the way the user built it.
 */
@Singleton
public class WatchlistStore {

    private static final Logger LOG = LoggerFactory.getLogger(WatchlistStore.class);
    static final String FILE_NAME = "watchlist.json";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> tickers = new LinkedHashSet<>();

    @Inject
    public WatchlistStore() {
        this(StorageUtils.getAppDataDir().resolve(FILE_NAME));
    }

    /** Watchlist at an explicit path — for tests and (future) export/maintenance tooling. */
    public WatchlistStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String[] arr = mapper.readValue(file.toFile(), String[].class);
            for (String s : arr) {
                String sym = normalize(s);
                if (sym != null) tickers.add(sym);
            }
        } catch (Exception e) {
            LOG.warn("Watchlist unreadable ({}): {}", file, e.getMessage());
        }
    }

    /** Adds a symbol; returns {@code true} if it was new. */
    public synchronized boolean add(String symbol) {
        String sym = normalize(symbol);
        if (sym == null || !tickers.add(sym)) return false;
        persist();
        return true;
    }

    /** Removes a symbol; returns {@code true} if it was present. */
    public synchronized boolean remove(String symbol) {
        String sym = normalize(symbol);
        if (sym == null || !tickers.remove(sym)) return false;
        persist();
        return true;
    }

    public synchronized boolean contains(String symbol) {
        String sym = normalize(symbol);
        return sym != null && tickers.contains(sym);
    }

    /** The watched symbols in the order the user added them. */
    public synchronized List<String> all() {
        return new ArrayList<>(tickers);
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(new ArrayList<>(tickers)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Failed to persist watchlist: {}", e.getMessage());
        }
    }

    private static String normalize(String symbol) {
        if (symbol == null) return null;
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        return sym.isEmpty() ? null : sym;
    }
}
