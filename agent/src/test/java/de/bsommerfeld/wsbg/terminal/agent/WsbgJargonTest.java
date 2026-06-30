package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WsbgJargonTest {

    @Test
    void canonicalizeMapsSlangDeterministically() {
        // The slash-listed aliases are split into individual whole-name keys, case-insensitive.
        assertEquals("Rheinmetall", WsbgJargon.canonicalize("Rheiner"));
        assertEquals("Rheinmetall", WsbgJargon.canonicalize("rheini"));
        assertEquals("Rheinmetall", WsbgJargon.canonicalize("  RHEIN  "));
        assertEquals("Palantir", WsbgJargon.canonicalize("Pala"));
        assertEquals("NVIDIA", WsbgJargon.canonicalize("Lederjacken-Mann"));
        assertEquals("Mercedes-Benz Group", WsbgJargon.canonicalize("Benz"));
    }

    @Test
    void canonicalizeLeavesUnknownAndRealNamesUntouched() {
        assertEquals("SpaceX", WsbgJargon.canonicalize("SpaceX"));
        assertEquals("Rheinmetall AG", WsbgJargon.canonicalize("Rheinmetall AG"), "not a bare alias → unchanged");
        // A parenthetical hint-only entry ("Bumsbude") must NOT become a subject rename.
        assertEquals("Bumsbude", WsbgJargon.canonicalize("Bumsbude"));
    }
}
