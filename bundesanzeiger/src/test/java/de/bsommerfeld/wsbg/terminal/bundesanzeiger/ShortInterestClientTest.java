package de.bsommerfeld.wsbg.terminal.bundesanzeiger;

import de.bsommerfeld.wsbg.terminal.core.price.ShortInterest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free parse tests against a captured Leerverkaufsregister CSV excerpt
 * (probed live 2026-07-13). The capture quirks that matter: a BOM up front,
 * quoted fields whose holder names carry commas, a German decimal comma in the
 * position column, and multiple rows per ISIN that must be summed and sorted
 * largest-first.
 */
class ShortInterestClientTest {

    private final ShortInterestClient client = new ShortInterestClient();

    /** Live capture excerpt, 2026-07-13 (BOM included, as served). */
    private static final String REGISTER = "\uFEFF"
            + """
            "Positionsinhaber","Emittent","ISIN","Position","Datum"
            "Citadel Advisors LLC","CANCOM SE","DE0005419105","0,79","2026-07-09"
            "SIH Partners, LLLP","LPKF Laser & Electronics SE","DE0006450000","0,79","2026-07-09"
            "Squarepoint Ops LLC","DEUTZ Aktiengesellschaft","DE0006305006","0,81","2026-07-09"
            "WorldQuant, LLC","DEUTZ Aktiengesellschaft","DE0006305006","1,06","2026-07-06"
            "Capital Fund Management SA","DEUTZ Aktiengesellschaft","DE0006305006","0,99","2026-07-02"
            """;

    @Test
    void parsesRegisterRows() {
        Map<String, List<ShortInterest.ShortPosition>> table =
                client.parseRegister(REGISTER).orElseThrow();
        assertEquals(3, table.size());
        assertEquals(3, table.get("DE0006305006").size());
        ShortInterest.ShortPosition cancom = table.get("DE0005419105").get(0);
        assertEquals("Citadel Advisors LLC", cancom.holder());
        assertEquals(0.79, cancom.percent(), 1e-9); // German decimal comma
        assertEquals("2026-07-09", cancom.dateIso());
    }

    @Test
    void keepsCommasInsideQuotedHolderNames() {
        Map<String, List<ShortInterest.ShortPosition>> table =
                client.parseRegister(REGISTER).orElseThrow();
        assertEquals("SIH Partners, LLLP", table.get("DE0006450000").get(0).holder());
    }

    @Test
    void sumsAndSortsPositionsLargestFirst() {
        Map<String, List<ShortInterest.ShortPosition>> table =
                client.parseRegister(REGISTER).orElseThrow();
        ShortInterest si = client.fromRegister("DE0006305006", table, 42L);
        assertEquals("DE0006305006", si.isin());
        assertEquals(2.86, si.totalDisclosedPercent(), 1e-9);
        assertEquals(List.of(1.06, 0.99, 0.81),
                si.positions().stream().map(ShortInterest.ShortPosition::percent).toList());
        assertEquals("WorldQuant, LLC", si.positions().get(0).holder());
        assertEquals(42L, si.fetchedAtEpochSeconds());
    }

    @Test
    void coveredRegisterWithoutRowsIsPresentWithEmptyPositions() {
        Map<String, List<ShortInterest.ShortPosition>> table =
                client.parseRegister(REGISTER).orElseThrow();
        ShortInterest si = client.fromRegister("DE0007030009", table, 42L);
        assertTrue(si.positions().isEmpty()); // "niemand short" is a finding, not a miss
        assertEquals(0.0, si.totalDisclosedPercent(), 1e-9);
    }

    @Test
    void nonCsvAnswerIsUnreadable() {
        assertEquals(Optional.empty(), client.parseRegister(null));
        assertEquals(Optional.empty(), client.parseRegister(""));
        assertEquals(Optional.empty(), client.parseRegister("   "));
        assertEquals(Optional.empty(), client.parseRegister("<!DOCTYPE html><html>Zugriff verweigert</html>"));
        assertEquals(Optional.empty(), client.parseRegister("no,register,header,in,sight"));
    }

    @Test
    void skipsRowsWithUnparseablePosition() {
        String csv = """
                "Positionsinhaber","Emittent","ISIN","Position","Datum"
                "Some Fund","Some AG","DE0000000001","kaputt","2026-07-09"
                "Other Fund","Some AG","DE0000000001","0,60","2026-07-09"
                """;
        Map<String, List<ShortInterest.ShortPosition>> table =
                client.parseRegister(csv).orElseThrow();
        assertEquals(1, table.get("DE0000000001").size());
        assertEquals(0.60, table.get("DE0000000001").get(0).percent(), 1e-9);
    }

    @Test
    void parsesGermanNumbers() {
        assertEquals(0.6, ShortInterestClient.parseGermanNumber("0,60"), 1e-9);
        assertEquals(1.06, ShortInterestClient.parseGermanNumber("1,06"), 1e-9);
        assertEquals(Double.NaN, ShortInterestClient.parseGermanNumber(""));
        assertEquals(Double.NaN, ShortInterestClient.parseGermanNumber(null));
        assertEquals(Double.NaN, ShortInterestClient.parseGermanNumber("n/a"));
    }
}
