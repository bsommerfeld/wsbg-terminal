package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The persisted user watchlist: normalised symbols, write-through, reload. */
class WatchlistStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("watchlist.json");
    }

    @Test
    void addNormalisesAndPersistsAcrossReload() {
        WatchlistStore w = new WatchlistStore(file());
        assertTrue(w.add(" nvda "));
        assertFalse(w.add("NVDA"), "duplicate after normalisation");
        assertTrue(w.add("GC=F"));

        WatchlistStore reloaded = new WatchlistStore(file());
        assertEquals(List.of("NVDA", "GC=F"), reloaded.all(), "insertion order kept");
        assertTrue(reloaded.contains("nvda"));
    }

    @Test
    void removePersistsAndBlanksAreRejected() {
        WatchlistStore w = new WatchlistStore(file());
        w.add("NVDA");
        assertTrue(w.remove("nvda"));
        assertFalse(w.remove("NVDA"), "already gone");
        assertFalse(w.add("  "), "blank symbol rejected");
        assertFalse(w.add(null));

        assertEquals(List.of(), new WatchlistStore(file()).all());
    }
}
