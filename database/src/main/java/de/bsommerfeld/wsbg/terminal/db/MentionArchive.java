package de.bsommerfeld.wsbg.terminal.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The permanent day-by-day record of what the cage talked about — append-only,
 * never deleted, the second store (after {@link HeadlineArchive}) that
 * deliberately breaks the "everything is in-memory" rule.
 *
 * <p>It may: unlike a mirror of Reddit state, a count is our own measurement of
 * a moment that has passed. It cannot go stale and it cannot resurrect a ghost
 * cluster — and a counter without history is not a counter.
 *
 * <p><b>Format:</b> one JSONL file per calendar day
 * ({@code mentions/YYYY-MM-DD.jsonl} in the app data dir), one scanned item per
 * line: {@code {"id":"t1_abc","p":{"$tsla":2,"telekom":1}}}. A file per day
 * keeps a "show me last Tuesday" read down to a single small file while a
 * multi-year range still reads only the days it needs. Torn lines from a crash
 * are skipped on load.
 *
 * <p><b>What is stored is the SPELLING, not the symbol.</b> Resolution happens
 * on read, so a name the resolver learns tomorrow folds into its ticker
 * throughout the history stored today.
 *
 * <p><b>Idempotency:</b> every line carries the Reddit id it came from and an
 * id is counted at most once per day. That is not de-duplication of mentions —
 * if the room spams a ticker twenty times, twenty is the count and twenty is
 * the truth. It only stops the same comment being counted twice because a
 * re-scrape or a restart handed it to us again.
 */
@Singleton
public class MentionArchive {

    private static final Logger LOG = LoggerFactory.getLogger(MentionArchive.class);
    static final String DIR_NAME = "mentions";
    private static final String SUFFIX = ".jsonl";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;
    private final ZoneId zone;
    /** Lazily loaded days; a loaded day is authoritative for both counts and ids. */
    private final Map<LocalDate, Tally> days = new HashMap<>();

    @Inject
    public MentionArchive() {
        this(StorageUtils.getAppDataDir().resolve(DIR_NAME), ZoneId.systemDefault());
    }

    /** Archive at an explicit location — for tests and maintenance tooling. */
    public MentionArchive(Path dir, ZoneId zone) {
        this.dir = dir;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
    }

    /**
     * Books one scanned post or comment. Returns {@code false} when this id was
     * already counted for that day (a re-scrape or a restart) or when it
     * carried no mention at all.
     */
    public synchronized boolean record(Instant when, String itemId, Map<String, Integer> phrases) {
        if (itemId == null || itemId.isBlank() || phrases == null || phrases.isEmpty()) return false;
        LocalDate day = (when == null ? Instant.now() : when).atZone(zone).toLocalDate();
        Tally tally = tally(day);
        if (!tally.ids.add(itemId)) return false;
        for (Map.Entry<String, Integer> e : phrases.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue() <= 0) continue;
            tally.counts.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        append(day, itemId, phrases);
        return true;
    }

    /** One day exactly as it was counted (empty when nothing was booked). */
    public synchronized MentionDay day(LocalDate day) {
        if (day == null) return new MentionDay(LocalDate.now(zone), Map.of(), 0);
        Tally t = tally(day);
        return new MentionDay(day, t.counts, t.ids.size());
    }

    /** Every day in {@code [from, to]} that carries something, oldest first. */
    public synchronized List<MentionDay> range(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) return List.of();
        List<MentionDay> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            MentionDay md = day(d);
            if (!md.isEmpty()) out.add(md);
        }
        return out;
    }

    /**
     * The whole window folded into one view — the slider's wide setting, where
     * a month or a year is read as a single picture instead of day by day.
     */
    public synchronized MentionDay fold(LocalDate from, LocalDate to) {
        Map<String, Integer> merged = new HashMap<>();
        int items = 0;
        for (MentionDay d : range(from, to)) {
            for (Map.Entry<String, Integer> e : d.phrases().entrySet()) {
                merged.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            items += d.items();
        }
        return new MentionDay(from, merged, items);
    }

    /** The oldest day on disk — the left edge of what the slider can reach. */
    public synchronized Optional<LocalDate> earliestDay() {
        List<LocalDate> stored = storedDays();
        return stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(0));
    }

    /** Every day the archive holds, oldest first. */
    public synchronized List<LocalDate> storedDays() {
        Map<LocalDate, Boolean> found = new TreeMap<>();
        for (LocalDate d : days.keySet()) {
            if (!days.get(d).isEmpty()) found.put(d, true);
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).forEach(p -> {
                String name = p.getFileName().toString();
                try {
                    found.put(LocalDate.parse(name.substring(0, name.length() - SUFFIX.length())), true);
                } catch (Exception ignored) {
                    // not a day file — ignore
                }
            });
        } catch (Exception ignored) {
            // no directory yet
        }
        return new ArrayList<>(found.keySet());
    }

    /** The day boundary this archive counts in. */
    public ZoneId zone() {
        return zone;
    }

    // -- persistence --

    private Tally tally(LocalDate day) {
        return days.computeIfAbsent(day, this::load);
    }

    private Tally load(LocalDate day) {
        Tally t = new Tally();
        Path file = fileFor(day);
        if (!Files.exists(file)) return t;
        int broken = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode n = JSON.readTree(line);
                    String id = n.path("id").asText("");
                    if (id.isEmpty() || !t.ids.add(id)) continue;
                    JsonNode p = n.path("p");
                    p.fieldNames().forEachRemaining(phrase -> {
                        int c = p.path(phrase).asInt(0);
                        if (c > 0) t.counts.merge(phrase, c, Integer::sum);
                    });
                } catch (Exception e) {
                    broken++;
                }
            }
        } catch (Exception e) {
            LOG.warn("[MENTIONS] load of {} failed: {}", day, e.getMessage());
            return t;
        }
        if (broken > 0) LOG.warn("[MENTIONS] {}: {} broken line(s) skipped", day, broken);
        return t;
    }

    private void append(LocalDate day, String itemId, Map<String, Integer> phrases) {
        try {
            Files.createDirectories(dir);
            ObjectNode line = JSON.createObjectNode();
            line.put("id", itemId);
            ObjectNode p = line.putObject("p");
            for (Map.Entry<String, Integer> e : phrases.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue() <= 0) continue;
                p.put(e.getKey(), e.getValue());
            }
            Files.writeString(fileFor(day), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("[MENTIONS] append for {} failed: {}", day, e.getMessage());
        }
    }

    private Path fileFor(LocalDate day) {
        return dir.resolve(day + SUFFIX);
    }

    /** One day in memory: what was counted and which items already contributed. */
    private static final class Tally {
        final Map<String, Integer> counts = new HashMap<>();
        final Set<String> ids = new HashSet<>();

        boolean isEmpty() {
            return counts.isEmpty();
        }
    }
}
