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
import java.util.Optional;
import java.util.Set;

/**
 * The permanent, append-only Wetterbericht history — one JSONL line per day,
 * never deleted, mirrored after {@link HeadlineArchive}: loaded fully into
 * memory at startup, torn lines from a crash are skipped, appends are
 * idempotent (identity = the report's local date, one report per day).
 */
@Singleton
public class WeatherReportArchive {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherReportArchive.class);
    static final String FILE_NAME = "weather-reports.jsonl";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Oldest first, like the headline archive's index. */
    private final List<WeatherReportRecord> records = new ArrayList<>();
    private final Set<String> dates = new HashSet<>();

    @Inject
    public WeatherReportArchive() {
        this(StorageUtils.getAppDataDir().resolve("archive").resolve(FILE_NAME));
    }

    /** Archive at an explicit path — for tests. */
    public WeatherReportArchive(Path file) {
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
                    add(mapper.readValue(line, WeatherReportRecord.class));
                } catch (Exception e) {
                    broken++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load weather report archive ({}); starting empty.", e.getMessage());
        }
        if (broken > 0) LOG.warn("Weather report archive: skipped {} broken line(s).", broken);
        LOG.info("Weather report archive loaded: {} report(s).", records.size());
    }

    private boolean add(WeatherReportRecord record) {
        if (record == null || record.date() == null || record.date().isBlank()
                || record.text() == null || record.text().isBlank()) return false;
        if (!dates.add(record.date())) return false;
        records.add(record);
        return true;
    }

    /** Appends one day's report; a date already archived is never re-written. */
    public synchronized void append(WeatherReportRecord record) {
        if (!add(record)) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("Failed to append weather report for {}: {}", record.date(), e.getMessage());
        }
    }

    public synchronized Optional<WeatherReportRecord> byDate(String date) {
        if (date == null || date.isBlank()) return Optional.empty();
        return records.stream().filter(r -> date.equals(r.date())).findFirst();
    }

    /** Up to {@code limit} reports, newest first. */
    public synchronized List<WeatherReportRecord> recent(int limit) {
        if (limit <= 0) return List.of();
        List<WeatherReportRecord> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(records.get(i));
        }
        return out;
    }

    public synchronized int size() {
        return records.size();
    }
}
