package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommodityCatalogTest {

    @Test
    void roomCommodityNamesBindToTheirYahooFuture() {
        assertEquals("GC=F", CommodityCatalog.lookup("Gold").symbol());
        assertEquals("GC=F", CommodityCatalog.lookup("Goldpreis").symbol());
        assertEquals("SI=F", CommodityCatalog.lookup("Silber").symbol());
        assertEquals("CL=F", CommodityCatalog.lookup("Öl").symbol(), "WTI for plain oil");
        assertEquals("CL=F", CommodityCatalog.lookup("Rohöl").symbol());
        assertEquals("BZ=F", CommodityCatalog.lookup("Brent").symbol());
        assertEquals("NG=F", CommodityCatalog.lookup("Erdgas").symbol());
    }

    @Test
    void aMiningStockNameIsNotTheCommodity() {
        // Full-name match only — „Barrick Gold" / „Gold.com Inc." must NOT bind to GC=F,
        // they stay normal equity resolution (the whole point of the no-substring rule).
        assertNull(CommodityCatalog.lookup("Barrick Gold"));
        assertNull(CommodityCatalog.lookup("Gold.com Inc."));
        assertNull(CommodityCatalog.lookup("Harmony Gold"));
    }

    @Test
    void commoditySymbolsAreRecognised() {
        assertTrue(CommodityCatalog.isCommoditySymbol("GC=F"));
        assertTrue(CommodityCatalog.isCommoditySymbol("CL=F"));
        assertNull(CommodityCatalog.lookup("AAPL"));
        assertNotNull(CommodityCatalog.lookup("WTI"));
    }
}
