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
import java.util.Locale;
import java.util.Map;

/**
 * The permanent per-subject fact dossier ("Steckbrief") — one JSONL line per
 * {@link DossierFact}, maintained across ALL sessions: loaded fully into memory
 * at startup, torn lines skipped, appends idempotent on
 * {@link DossierFact#identity()}. News facts only; room sentiment never lands
 * here.
 *
 * <p>Identity continuity is a property of the READ, not of bookkeeping:
 * {@link #bySubject} unions facts whose subject key OR ISIN matches, so the
 * ISIN-based ticker merges of the live registry need no forwarding table —
 * both keys' facts are one dossier the moment they share the stamped ISIN.
 *
 * <p>ONE mutation beyond append exists: {@link #rewriteSubject} replaces a
 * subject's facts with their consolidated version (several old lines folded
 * into summary lines) and atomically rewrites the file — the dossier stays a
 * dense Steckbrief instead of growing forever. Facts of other subjects are
 * untouched; the told story is additionally held forever by the headline
 * archive.
 */
@Singleton
public class SubjectDossierArchive {

    private static final Logger LOG = LoggerFactory.getLogger(SubjectDossierArchive.class);
    static final String FILE_NAME = "subject-dossiers.jsonl";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Identity → fact, insertion-ordered (oldest first, like the other archives). */
    private final Map<String, DossierFact> facts = new LinkedHashMap<>();

    @Inject
    public SubjectDossierArchive() {
        this(StorageUtils.getAppDataDir().resolve("archive").resolve(FILE_NAME));
    }

    /** Archive at an explicit path — for tests. */
    public SubjectDossierArchive(Path file) {
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
                    add(mapper.readValue(line, DossierFact.class));
                } catch (Exception e) {
                    broken++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load subject dossier archive ({}); starting empty.", e.getMessage());
        }
        if (broken > 0) LOG.warn("Subject dossier archive: skipped {} broken line(s).", broken);
        LOG.info("Subject dossier archive loaded: {} fact(s).", facts.size());
    }

    private boolean add(DossierFact fact) {
        if (!valid(fact)) return false;
        return facts.putIfAbsent(fact.identity(), fact) == null;
    }

    private static boolean valid(DossierFact fact) {
        return fact != null
                && fact.subjectKey() != null && !fact.subjectKey().isBlank()
                && fact.text() != null && !fact.text().isBlank();
    }

    /**
     * Appends one fact; a (subject, article) pair already archived is never
     * re-written — re-offering the same digest on every compose is free.
     *
     * @return true when the fact was new and persisted
     */
    public synchronized boolean append(DossierFact fact) {
        if (!add(fact)) return false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(fact) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("Failed to append dossier fact {}: {}", fact.identity(), e.getMessage());
        }
        return true;
    }

    /**
     * The dossier of one subject: every fact whose subject key OR ISIN matches
     * {@code keyOrIsin} (case-insensitive), oldest first. The union IS the
     * identity continuity — see the class doc.
     */
    public synchronized List<DossierFact> bySubject(String keyOrIsin) {
        return bySubject(keyOrIsin, null);
    }

    /** Two-axis lookup: matches {@code key} (ticker) OR {@code isin} on either fact axis. */
    public synchronized List<DossierFact> bySubject(String key, String isin) {
        String k = norm(key);
        String i = norm(isin);
        if (k == null && i == null) return List.of();
        List<DossierFact> out = new ArrayList<>();
        for (DossierFact f : facts.values()) {
            String fk = norm(f.subjectKey());
            String fi = norm(f.isin());
            if ((k != null && (k.equals(fk) || k.equals(fi)))
                    || (i != null && (i.equals(fk) || i.equals(fi)))) {
                out.add(f);
            }
        }
        return out;
    }

    /** How many facts one subject key holds (exact key, no ISIN union) — the consolidation trigger. */
    public synchronized int factCount(String subjectKey) {
        String k = norm(subjectKey);
        if (k == null) return 0;
        int n = 0;
        for (DossierFact f : facts.values()) {
            if (k.equals(norm(f.subjectKey()))) n++;
        }
        return n;
    }

    /**
     * Replaces ALL facts of {@code subjectKey} (exact key) with
     * {@code replacement} and atomically rewrites the file — the consolidation
     * pass's single mutation. Facts of other subjects keep their order; the
     * replacement lands where the subject's facts were, oldest-first as given.
     * An empty replacement is rejected: consolidation compresses, it never
     * erases a dossier.
     */
    public synchronized boolean rewriteSubject(String subjectKey, List<DossierFact> replacement) {
        String k = norm(subjectKey);
        if (k == null || replacement == null || replacement.isEmpty()) return false;
        Map<String, DossierFact> next = new LinkedHashMap<>();
        boolean inserted = false;
        for (DossierFact f : facts.values()) {
            if (k.equals(norm(f.subjectKey()))) {
                if (!inserted) {
                    inserted = true;
                    for (DossierFact r : replacement) {
                        if (valid(r)) next.putIfAbsent(r.identity(), r);
                    }
                }
                continue;
            }
            next.putIfAbsent(f.identity(), f);
        }
        if (!inserted) { // subject had no facts yet — treat as plain appends
            for (DossierFact r : replacement) {
                if (valid(r)) next.putIfAbsent(r.identity(), r);
            }
        }
        facts.clear();
        facts.putAll(next);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder();
            for (DossierFact f : facts.values()) {
                sb.append(mapper.writeValueAsString(f)).append(System.lineSeparator());
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warn("Failed to persist dossier consolidation for {}: {}", subjectKey, e.getMessage());
        }
        return true;
    }

    /** Wipes the archive — the user's "Daten löschen" includes the dossiers. */
    public synchronized void clear() {
        facts.clear();
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            LOG.warn("Failed to delete subject dossier archive: {}", e.getMessage());
        }
    }

    public synchronized int size() {
        return facts.size();
    }

    private static String norm(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim().toUpperCase(Locale.ROOT);
    }
}
