package de.bsommerfeld.wsbg.terminal.wallstreetonline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Append-only on-disk memory of name/ticker → resolved WSO instrument
 * ({@code wso-isin.jsonl}, one JSON line per key). An ISIN never changes, so the file
 * is loaded once at startup and written through, never rewritten. Torn lines (from a
 * crashed append) are skipped on load, and poisoned entries (a derivative anchored under
 * the company name, written before the class-filter existed) are dropped via the injected
 * {@code poisonFilter} so they self-heal on the next resolve.
 *
 * <p>The on-disk format is byte-compatible with {@code terminal}'s reader of the same file —
 * do not change the {@link CacheLine} shape or the line separator.
 */
final class WsoIsinStore {

    private static final Logger LOG = LoggerFactory.getLogger(WsoIsinStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One persisted name→ISIN line. */
    private record CacheLine(String q, String isin, String wkn, String name) {}

    /** Append-only file, or {@code null} in tests (in-memory only, no disk). */
    private final Path file;
    /** Rejects a poisoned (derivative) cached name on load, so it self-heals via re-query. */
    private final Predicate<String> poisonFilter;

    WsoIsinStore(Path file, Predicate<String> poisonFilter) {
        this.file = file;
        this.poisonFilter = poisonFilter;
    }

    /** Loads the persisted name→instrument memory (skipping torn/poisoned lines), or empty. */
    Map<String, WsoInstrument> load() {
        Map<String, WsoInstrument> out = new HashMap<>();
        if (file == null || !Files.exists(file)) return out;
        int n = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    CacheLine c = JSON.readValue(line, CacheLine.class);
                    // Skip a poisoned entry written before the class-filter (a derivative
                    // anchored under the company name) — it self-heals: the next resolve
                    // re-queries (class-filtered) and appends a clean line.
                    if (c.q() != null && c.isin() != null && !poisonFilter.test(c.name())) {
                        out.put(c.q(), new WsoInstrument(c.isin(), c.wkn(), c.name()));
                        n++;
                    }
                } catch (Exception ignore) { /* skip a torn line */ }
            }
            LOG.info("[WSO] loaded {} cached ISIN(s) from {}", n, file.getFileName());
        } catch (Exception e) {
            LOG.debug("WSO cache load failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Appends a resolved name→instrument to the on-disk memory. Idempotency is the caller's
     * responsibility (it gates on an in-memory {@code putIfAbsent} before appending), so this
     * is a pure write.
     */
    void append(String key, WsoInstrument w) {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    JSON.writeValueAsString(new CacheLine(key, w.isin(), w.wkn(), w.name()))
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.debug("WSO cache append failed: {}", e.getMessage());
        }
    }
}
