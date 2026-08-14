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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The permanent daily sentiment sheet — one JSONL line per (day, subject)
 * ({@link SubjectSentimentDayRecord}): loaded fully into memory at startup,
 * torn lines skipped, appends idempotent on the {@code date|subjectKey}
 * identity. Folded deterministically from the headline archive by
 * {@link SentimentDailyFolder}; grows with subjects × days — a few hundred
 * subjects over a year is ~100k lines, still fine for the house's full-load
 * JSONL pattern, but it is the first structure that grows superlinearly with
 * runtime (named on purpose).
 */
@Singleton
public class SubjectSentimentDailyArchive {

    private static final Logger LOG = LoggerFactory.getLogger(SubjectSentimentDailyArchive.class);
    static final String FILE_NAME = "subject-sentiment-daily.jsonl";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Identity → record, insertion-ordered (oldest first, like the other archives). */
    private final Map<String, SubjectSentimentDayRecord> records = new LinkedHashMap<>();
    private String latestDate;

    @Inject
    public SubjectSentimentDailyArchive() {
        this(StorageUtils.getAppDataDir().resolve("archive").resolve(FILE_NAME));
    }

    /** Archive at an explicit path — for tests. */
    public SubjectSentimentDailyArchive(Path file) {
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
                    add(mapper.readValue(line, SubjectSentimentDayRecord.class));
                } catch (Exception e) {
                    broken++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load sentiment daily archive ({}); starting empty.", e.getMessage());
        }
        if (broken > 0) LOG.warn("Sentiment daily archive: skipped {} broken line(s).", broken);
        LOG.info("Sentiment daily archive loaded: {} day-sheet(s).", records.size());
    }

    private boolean add(SubjectSentimentDayRecord record) {
        if (record == null || record.date() == null || record.date().isBlank()
                || record.subjectKey() == null || record.subjectKey().isBlank()
                || record.headlineCount() <= 0) {
            return false;
        }
        if (records.putIfAbsent(record.identity(), record) != null) return false;
        if (latestDate == null || record.date().compareTo(latestDate) > 0) {
            latestDate = record.date();
        }
        return true;
    }

    /**
     * Appends one day-sheet; a (day, subject) already archived is never
     * re-written — the fold is idempotent by construction.
     *
     * @return true when the sheet was new and persisted
     */
    public synchronized boolean append(SubjectSentimentDayRecord record) {
        if (!add(record)) return false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("Failed to append sentiment day-sheet {}: {}", record.identity(), e.getMessage());
        }
        return true;
    }

    /** One subject's day-sheets, oldest first (case-insensitive key match). */
    public synchronized List<SubjectSentimentDayRecord> bySubject(String subjectKey) {
        if (subjectKey == null || subjectKey.isBlank()) return List.of();
        String key = subjectKey.trim().toUpperCase(Locale.ROOT);
        List<SubjectSentimentDayRecord> out = new ArrayList<>();
        for (SubjectSentimentDayRecord r : records.values()) {
            if (key.equals(r.subjectKey().trim().toUpperCase(Locale.ROOT))) out.add(r);
        }
        out.sort(java.util.Comparator.comparing(SubjectSentimentDayRecord::date));
        return out;
    }

    /** Whether this (day, subject) is already folded. */
    public synchronized boolean has(String date, String subjectKey) {
        return records.containsKey(date + "|" + subjectKey);
    }

    /** The newest archived ISO date — the fold's top-up cursor. */
    public synchronized Optional<String> latestDate() {
        return Optional.ofNullable(latestDate);
    }

    /** Wipes the archive — the user's "Daten löschen" includes the day-sheets. */
    public synchronized void clear() {
        records.clear();
        latestDate = null;
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            LOG.warn("Failed to delete sentiment daily archive: {}", e.getMessage());
        }
    }

    public synchronized int size() {
        return records.size();
    }
}
