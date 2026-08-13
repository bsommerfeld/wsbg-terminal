package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * D2 — the subject gauge of the offline test stand ({@code .script/bench.sh}):
 * how many of the published units are GARBAGE subjects (the eight failure classes
 * of the 2026-08-13 run: WKN-shaped unit ids, symbol==name, derivative/CFD product
 * names, names with markup characters, two-character names), and how many of those
 * the new gates (A2 name fallback, A4 name gate, B speakable-name predicate) now
 * catch — measured over the SAME archived data, no network.
 *
 * <p>Baseline from the 2026-08-13 04:52 run: 8 of 42 headlines = 19% garbage.
 *
 * <p>Run: {@code .script/bench.sh} — or directly:
 * {@code mvn test -pl agent -Dtest=SubjectBenchIT -Dtest.excludedGroups= -Dbench.enabled=true}
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "bench.enabled", matches = "true")
class SubjectBenchIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 2026-08-13 00:00 UTC — the documented reference run's day. */
    private static final long RUN_13_EPOCH = 1786579200L;

    /** All-caps/digit venue-symbol shapes standing where a NAME should be: 0O8X.IL, WQTM.DE, WINTER-USD, GC=F. */
    private static final Pattern SYMBOL_AS_NAME = Pattern.compile("^[A-Z0-9][A-Z0-9.\\-=^]*$");
    /** WKN/numeric identifier shapes. */
    private static final Pattern NUMERIC_ID = Pattern.compile("^\\d{4,}$");
    /** Markup/OCR characters that no real name carries. */
    private static final Pattern GARBAGE_CHARS = Pattern.compile("[{}\\[\\]<>|\\\\]");
    /** Derivative/CFD product vocabulary in a subject name. */
    private static final Pattern PRODUCT_NAME = Pattern.compile(
            "(?i)\\bCFD\\b|optionsschein|zertifikat|knock|turbo|faktor|mini future|open.?end");

    @Test
    void measure() throws Exception {
        Path archive = Path.of(System.getProperty("bench.archive",
                System.getProperty("user.home")
                        + "/Library/Application Support/wsbg-terminal/archive/headlines.jsonl"));
        if (!Files.exists(archive)) {
            System.out.println("[BENCH:D2] archive missing at " + archive + " — skipped");
            return;
        }

        // One unit = one clusterId; judged on its display name (first subject name,
        // else the unit id — the same fallback the UI performs).
        Map<String, String[]> units = new LinkedHashMap<>(); // id → [name, reason|null]
        Map<String, String[]> unitsRun13 = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(archive, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n;
                try {
                    n = JSON.readTree(line);
                } catch (Exception torn) {
                    continue;
                }
                String id = n.path("clusterId").asText("");
                if (id.isBlank()) continue;
                String name = n.path("subjects").isArray() && n.path("subjects").size() > 0
                        ? n.path("subjects").get(0).path("name").asText("") : "";
                if (name.isBlank()) name = id.startsWith("name:") ? id.substring(5) : id;
                String[] row = new String[] {name, garbageReason(id, name)};
                units.put(id, row);
                if (n.path("createdAt").asLong(0) >= RUN_13_EPOCH) unitsRun13.put(id, row);
            }
        }

        report("gesamtes Archiv", units);
        report("Lauf 2026-08-13", unitsRun13);
    }

    private static void report(String label, Map<String, String[]> units) {
        int garbage = 0;
        int blocked = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, String[]> e : units.entrySet()) {
            String reason = e.getValue()[1];
            if (reason == null) continue;
            garbage++;
            boolean gateBlocks = gatesBlock(e.getKey(), e.getValue()[0]);
            if (gateBlocks) blocked++;
            if (examples.size() < 12) {
                examples.add(String.format("    %s '%s' — %s%s", e.getKey(), e.getValue()[0],
                        reason, gateBlocks ? " [neu: blockiert]" : " [NICHT blockiert]"));
            }
        }
        if (units.isEmpty()) {
            System.out.println("[BENCH:D2] " + label + ": keine Units");
            return;
        }
        System.out.printf(Locale.ROOT,
                "[BENCH:D2] %s: %d Unit(s), %d Müll (%.1f%%), davon durch die neuen Gates"
                        + " blockiert: %d (%.1f%% des Mülls)%n",
                label, units.size(), garbage, 100.0 * garbage / units.size(),
                blocked, garbage == 0 ? 0.0 : 100.0 * blocked / garbage);
        examples.forEach(System.out::println);
    }

    /** Why this unit's display name is garbage — or null when it is a real subject. */
    static String garbageReason(String unitId, String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() <= 2) return "Zwei-Zeichen-Name";
        if (GARBAGE_CHARS.matcher(n).find()) return "Markup/OCR-Zeichen im Namen";
        if (NUMERIC_ID.matcher(n).matches()) return "WKN/numerische ID als Name";
        if (PRODUCT_NAME.matcher(n).find()) return "Derivate-/CFD-Produktname";
        String id = unitId == null ? "" : unitId.trim();
        if (n.equalsIgnoreCase(id) && SYMBOL_AS_NAME.matcher(n).matches() && !n.matches("[A-Z]{1,6}")) {
            // The unit displays its own venue symbol as its name (0O8X.IL, WQTM.DE,
            // WINTER-USD) — the symbol==name class. A bare 1-6 letter US ticker as
            // its own name is left alone: DAX, GOLD, MSFT read fine on a wire.
            return "Symbol steht als Name";
        }
        return null;
    }

    /**
     * Whether the NEW gates would keep this unit from being born with this name:
     * A4 (extraction name gate), A2 (no symbol-as-name fallback — a memory hit
     * without a corpus row now carries the room's spelling), B (a repair never
     * forces an unspeakable name into a sentence — the display symptom).
     */
    static boolean gatesBlock(String unitId, String name) {
        if (SubjectExtractor.cleanSubjectName(name).isEmpty()) return true;      // A4
        String id = unitId == null ? "" : unitId.trim();
        if (name != null && name.trim().equalsIgnoreCase(id)
                && SYMBOL_AS_NAME.matcher(name.trim()).matches()) return true;   // A2
        return !EditorialAgent.speakableName(name);                              // B
    }
}
