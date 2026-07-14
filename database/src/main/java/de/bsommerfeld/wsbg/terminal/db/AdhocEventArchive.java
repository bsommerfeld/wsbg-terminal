package de.bsommerfeld.wsbg.terminal.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The permanent, append-only ad-hoc event register (one JSONL line per EQS
 * disclosure, never deleted) — mirrored after {@link WeatherReportArchive}:
 * loaded fully into memory at startup, torn lines from a crash are skipped,
 * appends are idempotent (identity = publishedAt + ISIN + title, so the same
 * feed item re-harvested across polls is written exactly once).
 *
 * <p>This is the first stone of the market-memory module: dated, dateable
 * German events accumulate here so the future event-study layer can measure
 * how prices reacted to each disclosure class.
 */
@Singleton
public class AdhocEventArchive {

    private static final Logger LOG = LoggerFactory.getLogger(AdhocEventArchive.class);
    static final String FILE_NAME = "adhoc-events.jsonl";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Oldest first, like the other archives' indices. */
    private final List<AdhocEventRecord> records = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();

    @Inject
    public AdhocEventArchive() {
        this(StorageUtils.getAppDataDir().resolve("archive").resolve(FILE_NAME));
    }

    /** Archive at an explicit path — for tests. */
    public AdhocEventArchive(Path file) {
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
                    add(mapper.readValue(line, AdhocEventRecord.class));
                } catch (Exception e) {
                    broken++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load ad-hoc event archive ({}); starting empty.", e.getMessage());
        }
        if (broken > 0) LOG.warn("Ad-hoc event archive: skipped {} broken line(s).", broken);
        LOG.info("Ad-hoc event archive loaded: {} event(s).", records.size());
    }

    private boolean add(AdhocEventRecord record) {
        if (record == null || record.publishedAt() == null || record.publishedAt().isBlank()
                || record.title() == null || record.title().isBlank()) return false;
        if (!identities.add(identityOf(record))) return false;
        records.add(record);
        return true;
    }

    private static String identityOf(AdhocEventRecord record) {
        return record.publishedAt() + "|" + (record.isin() == null ? "" : record.isin())
                + "|" + record.title();
    }

    /**
     * Appends one disclosure; an already-archived identity is never re-written.
     *
     * @return true when the record was new and persisted
     */
    public synchronized boolean append(AdhocEventRecord record) {
        if (!add(record)) return false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("Failed to append ad-hoc event '{}': {}", record.title(), e.getMessage());
        }
        return true;
    }

    /** Up to {@code limit} events, newest first. */
    public synchronized List<AdhocEventRecord> recent(int limit) {
        if (limit <= 0) return List.of();
        List<AdhocEventRecord> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(records.get(i));
        }
        return out;
    }

    /** All events of one issuer (ISIN-joined, the register's key), oldest first. */
    public synchronized List<AdhocEventRecord> byIsin(String isin) {
        if (isin == null || isin.isBlank()) return List.of();
        List<AdhocEventRecord> out = new ArrayList<>();
        for (AdhocEventRecord r : records) {
            if (isin.equalsIgnoreCase(r.isin())) out.add(r);
        }
        return out;
    }

    public synchronized int size() {
        return records.size();
    }
}
