package de.bsommerfeld.wsbg.terminal.db;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Folds the permanent headline archive into daily per-subject sentiment
 * sheets — purely deterministic (bucket, count, majority, arc), never a model
 * call: statistics are never the model's job. Only COMPLETE days are folded
 * (a running day would freeze a half-truth), and the archive's idempotent
 * identity makes re-folding free.
 */
public final class SentimentDailyFolder {

    private SentimentDailyFolder() {}

    /**
     * Folds every complete day found in {@code headlines} that the archive does
     * not hold yet, and appends the results. {@code zone} decides where a day
     * ends (the app's home zone). Returns how many day-sheets were appended.
     */
    public static int foldMissing(List<HeadlineRecord> headlines,
            SubjectSentimentDailyArchive archive, ZoneId zone, LocalDate today) {
        if (headlines == null || headlines.isEmpty() || archive == null) return 0;
        int appended = 0;
        for (SubjectSentimentDayRecord sheet : fold(headlines, zone, today)) {
            if (archive.append(sheet)) appended++;
        }
        return appended;
    }

    /**
     * The pure fold: headlines → one sheet per (complete day, subject).
     * Package-visible core, exposed for tests.
     */
    public static List<SubjectSentimentDayRecord> fold(List<HeadlineRecord> headlines,
            ZoneId zone, LocalDate today) {
        // (date, subjectKey) → chronological sentiment labels + a display name.
        Map<String, List<String>> labels = new TreeMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        List<HeadlineRecord> sorted = new ArrayList<>(headlines);
        sorted.sort(java.util.Comparator.comparingLong(HeadlineRecord::createdAt));
        for (HeadlineRecord r : sorted) {
            if (r == null || r.sentiment() == null || r.subjects() == null) continue;
            LocalDate day = Instant.ofEpochSecond(r.createdAt()).atZone(zone).toLocalDate();
            if (!day.isBefore(today)) continue; // only complete days
            for (HeadlineSubject s : r.subjects()) {
                String key = subjectKey(s);
                if (key == null) continue;
                String id = day + "|" + key;
                labels.computeIfAbsent(id, k -> new ArrayList<>()).add(r.sentiment().name());
                names.putIfAbsent(id, s.name() == null || s.name().isBlank() ? key : s.name());
            }
        }
        List<SubjectSentimentDayRecord> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : labels.entrySet()) {
            int split = e.getKey().indexOf('|');
            String date = e.getKey().substring(0, split);
            String key = e.getKey().substring(split + 1);
            List<String> seq = e.getValue();
            out.add(new SubjectSentimentDayRecord(date, key, names.get(e.getKey()),
                    seq.size(), counts(seq), majority(seq), arc(seq)));
        }
        return out;
    }

    /** Ticker (UPPER) when the subject has one, else the {@code name:…} unit-key convention. */
    private static String subjectKey(HeadlineSubject s) {
        if (s == null) return null;
        if (s.ticker() != null && !s.ticker().isBlank()) {
            return s.ticker().trim().toUpperCase(Locale.ROOT);
        }
        if (s.name() == null || s.name().isBlank()) return null;
        return "name:" + s.name().trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Integer> counts(List<String> seq) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : seq) counts.merge(s, 1, Integer::sum);
        return counts;
    }

    /** Most frequent label; ties go to the alphabetically first (deterministic re-runs). */
    private static String majority(List<String> seq) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : new TreeMap<>(counts(seq)).entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best == null ? "" : best;
    }

    /** Chronological trajectory, consecutive duplicates collapsed; empty below two distinct steps. */
    private static String arc(List<String> seq) {
        List<String> steps = new ArrayList<>();
        for (String s : seq) {
            if (steps.isEmpty() || !steps.get(steps.size() - 1).equals(s)) steps.add(s);
        }
        return steps.size() < 2 ? "" : String.join(" → ", steps);
    }
}
