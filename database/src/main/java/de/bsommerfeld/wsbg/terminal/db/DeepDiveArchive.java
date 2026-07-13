package de.bsommerfeld.wsbg.terminal.db;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The permanent KI-DD report history — one JSONL line per generated report
 * ({@code <app-data>/archive/deepdive-reports.jsonl}). Same discipline as the
 * headline/weather archives: loaded + indexed in memory at startup, torn lines
 * from a crash are skipped, appends are idempotent by report id. A report is a
 * FIXED snapshot — it is never revised, a new run appends a new record. The
 * ONE mutation beyond append is an explicit user {@link #delete} (UI trash
 * button, 2026-07-13) — the file is atomically rewritten without the record;
 * the system itself never deletes.
 */
@Singleton
public class DeepDiveArchive {

    private static final Logger LOG = LoggerFactory.getLogger(DeepDiveArchive.class);

    static final String FILE_NAME = "deepdive-reports.jsonl";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Oldest first, like the other archives' indexes. */
    private final List<DeepDiveRecord> records = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();

    @Inject
    public DeepDiveArchive() {
        this(StorageUtils.getAppDataDir().resolve("archive").resolve(FILE_NAME));
    }

    /** Archive at an explicit path — for tests. */
    public DeepDiveArchive(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        int broken = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    add(mapper.readValue(line, DeepDiveRecord.class));
                } catch (Exception e) {
                    broken++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load deep-dive archive ({}); starting empty.", e.getMessage());
        }
        if (broken > 0) LOG.warn("Deep-dive archive: skipped {} broken line(s).", broken);
        LOG.info("Deep-dive archive loaded: {} report(s).", records.size());
    }

    private boolean add(DeepDiveRecord record) {
        if (record == null || record.id() == null || record.id().isBlank()
                || record.report() == null || record.report().isBlank()) return false;
        if (!ids.add(record.id())) return false;
        records.add(record);
        return true;
    }

    /** Appends one report; an id already archived is never re-written. */
    public synchronized void append(DeepDiveRecord record) {
        if (!add(record)) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Failed to append deep-dive report {}: {}", record.id(), e.getMessage());
        }
    }

    /**
     * Removes one report from the index AND the file — an explicit user action,
     * never system-initiated. The JSONL is rewritten to a temp file and moved
     * into place (atomic where the filesystem supports it), so a crash mid-way
     * never tears the archive.
     *
     * @return true when the id existed and is gone
     */
    public synchronized boolean delete(String id) {
        if (id == null || !ids.remove(id)) return false;
        records.removeIf(r -> id.equals(r.id()));
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (DeepDiveRecord r : records) {
                sb.append(mapper.writeValueAsString(r)).append(System.lineSeparator());
            }
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Deep-dive report {} deleted ({} remain).", id, records.size());
        } catch (IOException e) {
            LOG.warn("Failed to rewrite deep-dive archive after deleting {}: {}",
                    id, e.getMessage());
        }
        return true;
    }

    public synchronized Optional<DeepDiveRecord> byId(String id) {
        if (id == null) return Optional.empty();
        for (DeepDiveRecord r : records) {
            if (id.equals(r.id())) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** The most recent reports, NEWEST first. */
    public synchronized List<DeepDiveRecord> recent(int limit) {
        List<DeepDiveRecord> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(records.get(i));
        }
        return out;
    }

    public synchronized int size() {
        return records.size();
    }
}
