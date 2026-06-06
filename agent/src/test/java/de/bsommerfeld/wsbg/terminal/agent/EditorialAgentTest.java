package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---- cleanSubjectName: strip a transcribed price tail, keep numeric names ----

    @Test
    void cleanSubjectNameStripsScreenshotPriceTail() {
        assertEquals("Micron Technology", EditorialAgent.cleanSubjectName("Micron Technology 772,30 € ▼ 9,23 %"));
        assertEquals("Oracle", EditorialAgent.cleanSubjectName("Oracle 185,00 € ▼ 8,48 %"));
        assertEquals("Take-Two Interactive", EditorialAgent.cleanSubjectName("Take-Two Interactive 49,57 €"));
    }

    @Test
    void cleanSubjectNameKeepsLegitimateNumericNames() {
        assertEquals("S&P 500", EditorialAgent.cleanSubjectName("S&P 500"));
        assertEquals("3M", EditorialAgent.cleanSubjectName("3M"));
        assertEquals("Nvidia", EditorialAgent.cleanSubjectName("Nvidia"));
        assertEquals("Berkshire Hathaway", EditorialAgent.cleanSubjectName("Berkshire Hathaway"));
    }

    // ---- salvageDraftByRegex: recover a headline from stray-quote-broken JSON ----

    @Test
    void salvageDraftByRegexRecoversHeadlineFromStrayQuoteJson() {
        // The exact live failure: `"ticker": null"` breaks the object, but the
        // headline + scalars must still be recovered.
        String broken = "{\"headline\": \"NVIDIA-Update: Die Apes sehen einen Rückgang von -4,97% bei NVIDIA\", "
                + "\"mode\": \"UPDATE\", \"sentiment\": \"CAPITULATION\", \"highlight\": \"NORMAL\", "
                + "\"tickerSymbol\": null, \"subjects\": [{\"name\": \"NVIDIA\", \"ticker\": null\"}], "
                + "\"priceMovePercent\": -4.97}";
        var d = EditorialAgent.salvageDraftByRegex(broken);
        assertEquals("NVIDIA-Update: Die Apes sehen einen Rückgang von -4,97% bei NVIDIA", d.headline());
        assertEquals("CAPITULATION", d.sentiment());
        assertEquals("NORMAL", d.highlight());
        assertEquals(-4.97, d.priceMovePercent());
    }

    // ---- headlineHasPriceNumber: detect a user-posted price for the "unverified" flag ----

    @Test
    void detectsPriceShapedNumbersForUnverifiedFlag() {
        assertTrue(EditorialAgent.headlineHasPriceNumber("NVIDIA −4,97% im Depot"));
        assertTrue(EditorialAgent.headlineHasPriceNumber("Ceres Power fällt auf 13,11%"));
        assertTrue(EditorialAgent.headlineHasPriceNumber("TSLA 175.00 $ im Screenshot"));
    }

    @Test
    void ignoresNonPriceNumbersAndPlainText() {
        assertFalse(EditorialAgent.headlineHasPriceNumber("S&P 500 im Fokus der Apes"));
        assertFalse(EditorialAgent.headlineHasPriceNumber("SAP taucht als Wette auf"));
        assertFalse(EditorialAgent.headlineHasPriceNumber(null));
    }

    @Test
    void salvageDraftByRegexFieldsAndNumber() {
        assertEquals("Oracle (ORCL) +2%", EditorialAgent.regexStringField(
                "{\"headline\": \"Oracle (ORCL) +2%\", \"x\": 1}", "headline"));
        assertEquals(6.5, EditorialAgent.regexNumberField("{\"priceMovePercent\": 6.5}", "priceMovePercent"));
        assertNull(EditorialAgent.regexStringField("no json here", "headline"));
    }
}
