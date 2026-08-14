package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permanent subject dossier: appends idempotent per (subject, article),
 * reads union over ticker OR ISIN (the merge-free identity continuity), the
 * consolidation rewrite replaces one subject's facts in place, and everything
 * survives a reload from disk.
 */
class SubjectDossierArchiveTest {

    @TempDir
    Path dir;

    private static DossierFact fact(String key, String isin, String url, String text) {
        return new DossierFact(key, isin, "Testwerk", text, 1000,
                "Titel", "Testblatt", url, 900L, false);
    }

    @Test
    void appendIsIdempotentPerSubjectAndArticle() {
        SubjectDossierArchive a = new SubjectDossierArchive(dir.resolve("d.jsonl"));
        assertTrue(a.append(fact("TST", null, "https://x/1", "Fakt eins.")));
        assertFalse(a.append(fact("TST", null, "https://x/1", "Fakt eins nochmal.")));
        assertTrue(a.append(fact("TST", null, "https://x/2", "Fakt zwei.")));
        assertEquals(2, a.factCount("TST"));
    }

    @Test
    void bySubjectUnionsTickerAndIsin() {
        SubjectDossierArchive a = new SubjectDossierArchive(dir.resolve("d.jsonl"));
        a.append(fact("A419CG", "US1234567890", "https://x/1", "Venue-Fakt."));
        a.append(fact("RKLB", "US1234567890", "https://x/2", "Yahoo-Fakt."));
        a.append(fact("NVDA", "US0000000000", "https://x/3", "Fremder Fakt."));
        // Asked by the Yahoo key + the shared ISIN, the venue twin's fact rides along.
        List<DossierFact> dossier = a.bySubject("RKLB", "US1234567890");
        assertEquals(2, dossier.size());
        // Asked by the venue key alone, the ISIN axis still unites them.
        assertEquals(2, a.bySubject("A419CG", "US1234567890").size());
        assertEquals(1, a.bySubject("NVDA", null).size());
    }

    @Test
    void rewriteSubjectReplacesOnlyThatSubjectsFacts() {
        SubjectDossierArchive a = new SubjectDossierArchive(dir.resolve("d.jsonl"));
        a.append(fact("TST", null, "https://x/1", "Alt eins."));
        a.append(fact("TST", null, "https://x/2", "Alt zwei."));
        a.append(fact("NVDA", null, "https://x/3", "Bleibt."));
        DossierFact summary = new DossierFact("TST", null, "Testwerk",
                "Sammelstand aus zwei Fakten.", 1000, null, null, null, null, true);
        assertTrue(a.rewriteSubject("TST", List.of(summary)));
        assertEquals(1, a.factCount("TST"));
        assertEquals(1, a.bySubject("NVDA", null).size());
        assertTrue(a.bySubject("TST", null).get(0).consolidated());
        // An empty replacement must never erase a dossier.
        assertFalse(a.rewriteSubject("TST", List.of()));
    }

    @Test
    void archiveSurvivesReloadFromDisk() {
        Path file = dir.resolve("d.jsonl");
        SubjectDossierArchive a = new SubjectDossierArchive(file);
        a.append(fact("TST", "DE0000000001", "https://x/1", "Bleibender Fakt."));
        a.rewriteSubject("TST", List.of(
                new DossierFact("TST", "DE0000000001", "Testwerk", "Sammelstand.",
                        1000, null, null, null, null, true)));

        SubjectDossierArchive reloaded = new SubjectDossierArchive(file);
        assertEquals(1, reloaded.size());
        DossierFact f = reloaded.bySubject("TST", null).get(0);
        assertEquals("Sammelstand.", f.text());
        assertTrue(f.consolidated());
        assertEquals("DE0000000001", f.isin());
    }

    @Test
    void clearWipesMemoryAndFile() {
        Path file = dir.resolve("d.jsonl");
        SubjectDossierArchive a = new SubjectDossierArchive(file);
        a.append(fact("TST", null, "https://x/1", "Fakt."));
        a.clear();
        assertEquals(0, a.size());
        assertEquals(0, new SubjectDossierArchive(file).size());
    }
}
