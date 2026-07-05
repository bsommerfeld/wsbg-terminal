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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The JSONL-backed persistence for the instrument corpus — the single home of
 * the {@link InstrumentEntry}↔JSON mapping (extracted from
 * {@code InstrumentCorpus}, 2026-07-04, so persist and load can no longer drift
 * apart). One record per line; {@link #save} writes atomically via a tmp file +
 * atomic move; {@link #load} skips torn lines from a crash.
 */
final class CorpusStore {

    private static final Logger LOG = LoggerFactory.getLogger(CorpusStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    CorpusStore(Path file) {
        this.file = file;
    }

    /** Last-modified instant of the backing file, or empty if it's absent/unreadable. */
    Optional<Instant> lastModified() {
        try {
            if (Files.exists(file)) return Optional.of(Files.getLastModifiedTime(file).toInstant());
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    /** Reads the persisted corpus (empty when the file is absent or unreadable). */
    List<InstrumentEntry> load() {
        List<InstrumentEntry> entries = new ArrayList<>();
        try {
            if (!Files.exists(file)) return entries;
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
        } catch (Exception e) {
            LOG.warn("[CORPUS] load failed: {}", e.getMessage());
        }
        return entries;
    }

    /** Persists the corpus atomically (tmp file + atomic move). Never throws. */
    void save(List<InstrumentEntry> entries) {
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
}
