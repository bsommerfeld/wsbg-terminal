package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the detail-string scrapers, in particular the verbatim
 * "downloaded / total" extraction feeding the byte readout beside the
 * progress bar.
 */
class SetupDetailParserTest {

    @Test
    void byteFiguresAreExtractedVerbatim() {
        assertEquals("739 MB / 3.3 GB",
                SetupDetailParser.parseByteFigures("45% — 739 MB / 3.3 GB"));
        assertEquals("1.2 GB / 19 GB",
                SetupDetailParser.parseByteFigures("6% — 1.2 GB / 19 GB"));
    }

    @Test
    void detailsWithoutFiguresYieldNoReadout() {
        assertNull(SetupDetailParser.parseByteFigures(null));
        assertNull(SetupDetailParser.parseByteFigures("45%"));
        assertNull(SetupDetailParser.parseByteFigures("verifying"));
        // A lone figure without a total reads as a size, not progress.
        assertNull(SetupDetailParser.parseByteFigures("45% — 739 MB"));
    }
}
