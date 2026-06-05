package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The related-budget distribution: a shared pool of related-instrument lookups
 * spread evenly across all subjects (round-robin), capped per subject.
 */
class EditorialAgentTest {

    @Test
    void sixSubjectsEachGetFour() { // 6×4 = 24, the old behaviour
        assertArrayEquals(new int[]{4, 4, 4, 4, 4, 4},
                EditorialAgent.distributeRelated(6, 24, 4));
    }

    @Test
    void twentyFourSubjectsEachGetOne() {
        int[] a = EditorialAgent.distributeRelated(24, 24, 4);
        assertEquals(24, a.length);
        assertEquals(24, Arrays.stream(a).sum());
        for (int v : a) assertEquals(1, v);
    }

    @Test
    void twentyFifthSubjectGetsNone() { // user's exact example
        int[] a = EditorialAgent.distributeRelated(25, 24, 4);
        assertEquals(24, Arrays.stream(a).sum());
        for (int i = 0; i < 24; i++) assertEquals(1, a[i]);
        assertEquals(0, a[24]);
    }

    @Test
    void fewSubjectsAreCappedPerSubject() { // 3 subjects can't soak up all 24
        assertArrayEquals(new int[]{4, 4, 4}, EditorialAgent.distributeRelated(3, 24, 4));
    }

    @Test
    void twelveSubjectsEachGetTwo() {
        int[] a = EditorialAgent.distributeRelated(12, 24, 4);
        assertEquals(24, Arrays.stream(a).sum());
        for (int v : a) assertEquals(2, v);
    }

    @Test
    void zeroSubjectsIsEmpty() {
        assertEquals(0, EditorialAgent.distributeRelated(0, 24, 4).length);
    }

    // ---- salvageSubjectNames: a broken/truncated subjects array isn't total loss ----

    @Test
    void salvagesNamesFromTruncatedArray() {
        // Reply cut off mid-array: no closing ] and a dangling, unclosed final name.
        String broken = "{\"subjects\": [\"Alphabet\", \"Apple\", \"Münchener Rück\", \"SAN";
        assertEquals(List.of("Alphabet", "Apple", "Münchener Rück"),
                EditorialAgent.salvageSubjectNames(broken));
    }

    @Test
    void salvagesNamesEvenWithTrailingGarbage() {
        String broken = "ok here you go {\"subjects\": [\"Nvidia\", \"SAP\"] } blah blah";
        assertEquals(List.of("Nvidia", "SAP"), EditorialAgent.salvageSubjectNames(broken));
    }

    @Test
    void salvageReturnsEmptyWhenNoSubjectsKey() {
        assertTrue(EditorialAgent.salvageSubjectNames("totally unrelated prose").isEmpty());
        assertTrue(EditorialAgent.salvageSubjectNames(null).isEmpty());
    }
}
