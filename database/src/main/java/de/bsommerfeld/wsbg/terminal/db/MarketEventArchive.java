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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The permanent event register of the market memory — one JSONL line per
 * dated, classified event ({@link MarketEventRecord}), mirrored after
 * {@link WeatherReportArchive}: loaded fully into memory at startup, torn
 * lines skipped, appends idempotent on {@link MarketEventRecord#identity()}.
 *
 * <p>ONE mutation beyond append exists: {@link #enrich} replaces a record
 * with its reaction-enriched version (CARs, regime stamp, confounded flag)
 * under the SAME identity and atomically rewrites the file — the
 * {@code DeepDiveArchive.delete} precedent. Events themselves are never
 * deleted; enrichment only ever fills fields, evidence is never lost.
 */
@Singleton
public class MarketEventArchive {

    private static final Logger LOG = LoggerFactory.getLogger(MarketEventArchive.class);
    static final String FILE_NAME = "market-events.jsonl";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Identity → record, insertion-ordered (oldest first, like the other archives). */
    private final Map<String, MarketEventRecord> records = new LinkedHashMap<>();

    @Inject
    public MarketEventArchive() {
        this(StorageUtils.getAppDataDir().resolve("archive").resolve(FILE_NAME));
    }

    /** Archive at an explicit path — for tests. */
    public MarketEventArchive(Path file) {
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
                    add(mapper.readValue(line, MarketEventRecord.class));
                } catch (Exception e) {
                    broken++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load market event archive ({}); starting empty.", e.getMessage());
        }
        if (broken > 0) LOG.warn("Market event archive: skipped {} broken line(s).", broken);
        LOG.info("Market event archive loaded: {} event(s).", records.size());
    }

    private boolean add(MarketEventRecord record) {
        if (!valid(record)) return false;
        return records.putIfAbsent(record.identity(), record) == null;
    }

    private static boolean valid(MarketEventRecord record) {
        return record != null
                && record.date() != null && !record.date().isBlank()
                && record.eventClass() != null && !record.eventClass().isBlank()
                && record.source() != null && !record.source().isBlank()
                && ((record.symbol() != null && !record.symbol().isBlank())
                        || (record.isin() != null && !record.isin().isBlank()));
    }

    /**
     * Appends one event; an identity already registered is never re-written.
     *
     * @return true when the event was new and persisted
     */
    public synchronized boolean append(MarketEventRecord record) {
        if (!add(record)) return false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("Failed to append market event {}: {}", record.identity(), e.getMessage());
        }
        return true;
    }

    /**
     * Replaces the record with this identity by its enriched version and
     * atomically rewrites the file. A record that isn't registered is ignored
     * (returns false) — enrichment never creates events.
     */
    public synchronized boolean enrich(MarketEventRecord enriched) {
        if (!valid(enriched) || !records.containsKey(enriched.identity())) return false;
        records.put(enriched.identity(), enriched);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            StringBuilder sb = new StringBuilder();
            for (MarketEventRecord r : records.values()) {
                sb.append(mapper.writeValueAsString(r)).append(System.lineSeparator());
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warn("Failed to persist enrichment for {}: {}", enriched.identity(), e.getMessage());
        }
        return true;
    }

    /** Every registered event, oldest-registered first. */
    public synchronized List<MarketEventRecord> all() {
        return new ArrayList<>(records.values());
    }

    /** Every event of one class. */
    public synchronized List<MarketEventRecord> byClass(String eventClass) {
        if (eventClass == null || eventClass.isBlank()) return List.of();
        List<MarketEventRecord> out = new ArrayList<>();
        for (MarketEventRecord r : records.values()) {
            if (eventClass.equals(r.eventClass())) out.add(r);
        }
        return out;
    }

    /** Every event of one instrument (symbol OR ISIN match, case-insensitive). */
    public synchronized List<MarketEventRecord> byInstrument(String symbolOrIsin) {
        if (symbolOrIsin == null || symbolOrIsin.isBlank()) return List.of();
        String key = symbolOrIsin.trim().toUpperCase();
        List<MarketEventRecord> out = new ArrayList<>();
        for (MarketEventRecord r : records.values()) {
            if ((r.symbol() != null && key.equals(r.symbol().toUpperCase()))
                    || (r.isin() != null && key.equals(r.isin().toUpperCase()))) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Events still waiting for their reaction (no CAR yet) whose date is on or
     * before {@code settledCutoff} — the enrichment sweep's work list. Only
     * events with a symbol qualify (no price history without one), and
     * identities in {@code exclude} are skipped: an event whose reaction is
     * UNMEASURABLE (delisted symbol, series too short) must never livelock
     * the queue for everything registered after it.
     */
    public synchronized List<MarketEventRecord> pendingEnrichment(String settledCutoff, int limit,
            java.util.Set<String> exclude) {
        if (settledCutoff == null || limit <= 0) return List.of();
        List<MarketEventRecord> out = new ArrayList<>();
        for (MarketEventRecord r : records.values()) {
            if (r.carEvent() != null) continue;
            if (r.symbol() == null || r.symbol().isBlank()) continue;
            if (r.date().compareTo(settledCutoff) > 0) continue;
            if (exclude != null && exclude.contains(r.identity())) continue;
            out.add(r);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Exclusion-free variant (tests / callers without a failure memory). */
    public synchronized List<MarketEventRecord> pendingEnrichment(String settledCutoff, int limit) {
        return pendingEnrichment(settledCutoff, limit, null);
    }

    public synchronized int size() {
        return records.size();
    }
}
