package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permanent ad-hoc event register: append-only JSONL, idempotent on
 * publishedAt+ISIN+title (the same feed item re-harvested across polls writes
 * once), torn lines skipped on load, ISIN join intact after a reload.
 */
class AdhocEventArchiveTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("adhoc-events.jsonl");
    }

    private static AdhocEventRecord rec(String publishedAt, String isin, String title) {
        return new AdhocEventRecord(publishedAt, isin, title, "https://fn.de/x");
    }

    @Test
    void appendsAndReloads() {
        AdhocEventArchive archive = new AdhocEventArchive(file());
        assertTrue(archive.append(rec("2026-07-14T18:02:00Z", "DE0007164600", "SAP senkt Prognose")));
        assertTrue(archive.append(rec("2026-07-14T19:30:00Z", "DE000ENER6Y0", "Siemens Energy erhält Großauftrag")));
        assertEquals(2, archive.size());

        AdhocEventArchive reloaded = new AdhocEventArchive(file());
        assertEquals(2, reloaded.size());
        List<AdhocEventRecord> recent = reloaded.recent(10);
        assertEquals("Siemens Energy erhält Großauftrag", recent.get(0).title());
        assertEquals("SAP senkt Prognose", recent.get(1).title());
        assertEquals("https://fn.de/x", recent.get(0).link());
    }

    @Test
    void reharvestedItemIsWrittenOnce() {
        AdhocEventArchive archive = new AdhocEventArchive(file());
        AdhocEventRecord r = rec("2026-07-14T18:02:00Z", "DE0007164600", "SAP senkt Prognose");
        assertTrue(archive.append(r));
        assertFalse(archive.append(r));
        assertEquals(1, archive.size());
        assertEquals(1, new AdhocEventArchive(file()).size());
    }

    @Test
    void distinctIssuersSameTitleBothSurvive() {
        AdhocEventArchive archive = new AdhocEventArchive(file());
        assertTrue(archive.append(rec("2026-07-14T18:02:00Z", "DE0007164600", "Kapitalerhöhung beschlossen")));
        assertTrue(archive.append(rec("2026-07-14T18:02:00Z", "DE000ENER6Y0", "Kapitalerhöhung beschlossen")));
        assertEquals(2, archive.size());
    }

    @Test
    void byIsinJoinsAndIsinLessItemsKeep() {
        AdhocEventArchive archive = new AdhocEventArchive(file());
        archive.append(rec("2026-07-13T08:00:00Z", "DE0007164600", "SAP Q2-Zahlen"));
        archive.append(rec("2026-07-14T18:02:00Z", "DE0007164600", "SAP senkt Prognose"));
        archive.append(rec("2026-07-14T19:00:00Z", null, "Marktbericht ohne ISIN"));
        assertEquals(3, archive.size());
        List<AdhocEventRecord> sap = archive.byIsin("DE0007164600");
        assertEquals(2, sap.size());
        assertEquals("SAP Q2-Zahlen", sap.get(0).title());
        assertTrue(archive.byIsin(null).isEmpty());
    }

    @Test
    void tornLineIsSkippedOnLoad() throws Exception {
        AdhocEventArchive archive = new AdhocEventArchive(file());
        archive.append(rec("2026-07-14T18:02:00Z", "DE0007164600", "SAP senkt Prognose"));
        Files.writeString(file(), "{\"publishedAt\":\"2026-07-14T19:", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        assertEquals(1, new AdhocEventArchive(file()).size());
    }

    @Test
    void blankFieldsAreRejected() {
        AdhocEventArchive archive = new AdhocEventArchive(file());
        assertFalse(archive.append(new AdhocEventRecord(null, "DE0007164600", "x", null)));
        assertFalse(archive.append(new AdhocEventRecord("2026-07-14T18:02:00Z", "DE0007164600", " ", null)));
        assertFalse(archive.append(null));
        assertEquals(0, archive.size());
    }
}
