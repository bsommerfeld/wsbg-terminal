package de.bsommerfeld.wsbg.terminal.tradingview;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branch board, live against the real screener - both universes, plus the
 * seat of one issuer in each. What only a live probe can answer: whether the
 * industry labels still come through, whether the venue filter still leaves
 * one row per issuer, and whether an issuer can be found on its own board.
 *
 * <pre>SECTOR_SMOKE=true mvn test -pl tradingview -Dtest=SectorBoardSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "SECTOR_SMOKE", matches = "true")
class SectorBoardSmokeIT {

    @Test
    void bothUniversesCarryTheirBranchesAndSeatTheirIssuers() {
        SectorBoardClient client = new SectorBoardClient();
        for (String markt : new String[] {
                TradingViewScanner.MARKET_GERMANY, TradingViewScanner.MARKET_AMERICA }) {
            SectorBoardClient.Tafel tafel = client.tafel(markt);
            assertFalse(tafel.isEmpty(), markt + ": the screener answered no branch");
            System.out.println("\n[sector] " + markt + " — " + tafel.branchen().size()
                    + " Branchen");
            System.out.println("[sector]   stärkste:");
            for (SectorBoardClient.Branche b : tafel.staerkste(3, 5)) print(b);
            System.out.println("[sector]   schwächste:");
            for (SectorBoardClient.Branche b : tafel.schwaechste(3, 5)) print(b);
            // Every branch must carry members and a rank - a branch of zero
            // issuers is a parse artefact, not a branch.
            for (SectorBoardClient.Branche b : tafel.branchen()) {
                assertTrue(b.titel() > 0, "branch without members: " + b.branche());
                assertTrue(b.rang() >= 1 && b.rang() <= tafel.branchen().size(),
                        "branch out of rank: " + b.branche());
            }
        }

        SectorBoardClient.Sitz sap =
                client.sitz(TradingViewScanner.MARKET_GERMANY, "SAP");
        assertNotNull(sap, "SAP has no seat on the German board");
        System.out.println("\n[sector] SAP sitzt in: " + sap.branche().branche()
                + " (" + sap.branche().sektor() + "), " + sap.branche().titel() + " Titel"
                + ", Branche heute " + sap.branche().tag() + " %"
                + ", SAP " + sap.titelTag() + " %"
                + ", Vorsprung " + sap.vorsprung() + " pp"
                + ", Rang " + sap.branche().rang());

        SectorBoardClient.Sitz nvda =
                client.sitz(TradingViewScanner.MARKET_AMERICA, "NVDA");
        assertNotNull(nvda, "NVDA has no seat on the US board");
        System.out.println("[sector] NVDA sitzt in: " + nvda.branche().branche()
                + ", Branche Quartal " + nvda.branche().quartal() + " %");
    }

    private static void print(SectorBoardClient.Branche b) {
        System.out.printf("[sector]     #%-3d %-38s n=%-4d Tag %+6.2f%%  Woche %+7.2f%%  "
                        + "Monat %+7.2f%%  Quartal %+7.2f%%%n",
                b.rang(), b.branche(), b.titel(), b.tag(), b.woche(), b.monat(), b.quartal());
    }
}
