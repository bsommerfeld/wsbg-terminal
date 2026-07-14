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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The permanent Fear&amp;Greed daily history (one JSONL line per trading day,
 * never deleted) — mirrored after {@link WeatherReportArchive}: loaded fully
 * into memory at startup, torn lines skipped, appends idempotent (identity =
 * the ISO date). Back-filled once from CNN's full series and topped up
 * forward; {@link #byDate} is the regime lookup the event stamper reads.
 */
@Singleton
public class FearGreedHistoryArchive {

    private static final Logger LOG = LoggerFactory.getLogger(FearGreedHistoryArchive.class);
    static final String FILE_NAME = "fear-greed-history.jsonl";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, FearGreedDayRecord> byDate = new HashMap<>();
    private String latestDate;

    @Inject
    public FearGreedHistoryArchive() {
        this(StorageUtils.getAppDataDir().resolve("archive").resolve(FILE_NAME));
    }

    /** Archive at an explicit path — for tests. */
    public FearGreedHistoryArchive(Path file) {
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
                    add(mapper.readValue(line, FearGreedDayRecord.class));
                } catch (Exception e) {
                    broken++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load Fear&Greed history archive ({}); starting empty.", e.getMessage());
        }
        if (broken > 0) LOG.warn("Fear&Greed history archive: skipped {} broken line(s).", broken);
        LOG.info("Fear&Greed history archive loaded: {} day(s).", byDate.size());
    }

    private boolean add(FearGreedDayRecord record) {
        if (record == null || record.date() == null || record.date().isBlank()) return false;
        double s = record.score();
        if (!Double.isFinite(s) || s < 0 || s > 100) return false;
        if (byDate.putIfAbsent(record.date(), record) != null) return false;
        if (latestDate == null || record.date().compareTo(latestDate) > 0) {
            latestDate = record.date();
        }
        return true;
    }

    /**
     * Appends one day's reading; a date already archived is never re-written.
     *
     * @return true when the day was new and persisted
     */
    public synchronized boolean append(FearGreedDayRecord record) {
        if (!add(record)) return false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("Failed to append Fear&Greed day {}: {}", record.date(), e.getMessage());
        }
        return true;
    }

    /** The reading of one ISO date, when the series has that trading day. */
    public synchronized Optional<FearGreedDayRecord> byDate(String date) {
        if (date == null || date.isBlank()) return Optional.empty();
        return Optional.ofNullable(byDate.get(date));
    }

    /** The newest archived ISO date — the top-up cursor. */
    public synchronized Optional<String> latestDate() {
        return Optional.ofNullable(latestDate);
    }

    public synchronized int size() {
        return byDate.size();
    }
}
