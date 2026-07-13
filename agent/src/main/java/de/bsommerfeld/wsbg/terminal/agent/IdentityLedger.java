package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The identity desk's persistent verdict book: subject name → the stamped identity
 * the judge decided ({@code identity-ledger.jsonl}, one JSON line per verdict,
 * append-only). Identity is stable, so a verdict is decided ONCE and replayed on
 * every later resolve and across restarts — the desk's model call amortises to
 * ~one per unique subject name ever.
 *
 * <p>Two lessons from {@code wso-isin.jsonl} are built in rather than patched later:
 * <ul>
 *   <li><b>Supersede, don't edit:</b> a re-decided verdict is simply appended; the
 *       LAST line per key wins on load. Nothing is ever rewritten or deleted.</li>
 *   <li><b>No eternal mistakes:</b> a verdict older than {@link #MAX_AGE_DAYS} is
 *       ignored on load (not deleted), so the subject is re-judged with fresh venue
 *       facts and the corrected verdict supersedes the stale line naturally.</li>
 * </ul>
 *
 * <p>Torn lines from a crashed append are skipped on load. {@code null} path =
 * in-memory only (tests).
 */
final class IdentityLedger {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityLedger.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Verdicts older than this are re-judged instead of replayed — bounded error lifetime. */
    private static final long MAX_AGE_DAYS = 30;

    /**
     * The verdict semantics generation. Bumped when the desk's decision RULES
     * change, so every verdict decided under the old rules is re-judged instead of
     * replayed (and superseded by append on the next decide) — a semantics fix must
     * not wait out {@link #MAX_AGE_DAYS} on a wrong stamp. History: 2 = the kind
     * gate + crypto-wrapper veto (2026-07-13: "trump"→DJT, "bitcoin"→ETP stamp).
     */
    static final int SCHEMA_VERSION = 2;

    /**
     * One stamped verdict. {@code symbol} is the unit/display identifier (a Yahoo
     * ticker, or the venue WKN for a listing Yahoo doesn't carry); {@code venueId} +
     * {@code isin} + {@code category} are the exact venue instrument the price chain
     * executes without any re-resolution. {@code venueRuledOut} persists the desk's
     * considered "no venue listing" so a replay keeps the price chain's fuzzy name
     * search shut across restarts (absent on pre-flag lines → {@code false}).
     * {@code v} is the {@link #SCHEMA_VERSION} the verdict was decided under
     * (absent on pre-version lines → {@code 0} → re-judged on load).
     */
    record Entry(String q, String symbol, String canonical, String isin,
            long venueId, String category, boolean venueRuledOut, long decidedAt, int v) {

        /** Pre-version shape (call sites/tests): stamped with the current semantics. */
        Entry(String q, String symbol, String canonical, String isin,
                long venueId, String category, boolean venueRuledOut, long decidedAt) {
            this(q, symbol, canonical, isin, venueId, category, venueRuledOut, decidedAt,
                    SCHEMA_VERSION);
        }

        /** Pre-flag shape (older call sites/tests): no considered venue verdict. */
        Entry(String q, String symbol, String canonical, String isin,
                long venueId, String category, long decidedAt) {
            this(q, symbol, canonical, isin, venueId, category, false, decidedAt);
        }
    }

    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    IdentityLedger(Path file) {
        this.file = file;
        load();
    }

    /** The replayable verdict for a normalised subject name, or null. */
    Entry get(String key) {
        return key == null ? null : entries.get(normalize(key));
    }

    /** Records a fresh verdict: in-memory immediately, appended to disk (supersede-by-append). */
    void put(String key, Entry entry) {
        if (key == null || entry == null) return;
        String k = normalize(key);
        entries.put(k, entry);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.debug("identity ledger append failed: {}", e.getMessage());
        }
    }

    int size() {
        return entries.size();
    }

    static String normalize(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        long cutoff = Instant.now().minusSeconds(MAX_AGE_DAYS * 24 * 3600).getEpochSecond();
        Map<String, Entry> loaded = new HashMap<>();
        int stale = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    Entry e = JSON.readValue(line, Entry.class);
                    if (e.q() == null || e.q().isBlank()) continue;
                    if (e.decidedAt() < cutoff || e.v() < SCHEMA_VERSION) {
                        // Stale or pre-semantics-fix verdict: drop from the replay map (a
                        // NEWER line for the same key may still re-add it — last-wins runs
                        // over the whole file). An old-rules verdict is re-judged fresh.
                        loaded.remove(normalize(e.q()));
                        stale++;
                        continue;
                    }
                    loaded.put(normalize(e.q()), e);
                } catch (Exception ignore) { /* torn line from a crashed append */ }
            }
            entries.putAll(loaded);
            LOG.info("[IDENTITY] ledger loaded: {} verdict(s) replayable{} ({})", loaded.size(),
                    stale > 0 ? ", " + stale + " expired (will re-judge)" : "", file.getFileName());
        } catch (Exception e) {
            LOG.debug("identity ledger load failed: {}", e.getMessage());
        }
    }
}
