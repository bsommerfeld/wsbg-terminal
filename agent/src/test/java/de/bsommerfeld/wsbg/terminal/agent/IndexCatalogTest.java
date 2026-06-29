package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IndexCatalogTest {

    @Test
    void lookup_resolvesDaxToIndexSymbol_notTheSameNamedEtf() {
        IndexCatalog.Index dax = IndexCatalog.lookup("DAX");
        assertNotNull(dax);
        assertEquals("^GDAXI", dax.symbol());
        assertEquals("DAX", dax.displayName());
    }

    @Test
    void lookup_isCaseAndPunctuationInsensitive() {
        assertEquals("^GSPC", IndexCatalog.lookup("S&P 500").symbol());
        assertEquals("^GSPC", IndexCatalog.lookup("sp500").symbol());
        assertEquals("^GSPC", IndexCatalog.lookup("SPX").symbol());
        assertEquals("^NDX", IndexCatalog.lookup("Nasdaq 100").symbol());
        assertEquals("^IXIC", IndexCatalog.lookup("nasdaq").symbol());
        assertEquals("^STOXX50E", IndexCatalog.lookup("EuroStoxx 50").symbol());
    }

    @Test
    void lookup_resolvesBrokerNicknamesToTheIndex() {
        // CFD-broker / room nicknames the wire actually used (e.g. "US Tech 100").
        assertEquals("^NDX", IndexCatalog.lookup("US Tech 100").symbol());
        assertEquals("^NDX", IndexCatalog.lookup("Tech 100").symbol());
        assertEquals("^NDX", IndexCatalog.lookup("US 100").symbol());
        assertEquals("^GDAXI", IndexCatalog.lookup("Germany 40").symbol());
        assertEquals("^GSPC", IndexCatalog.lookup("US 500").symbol());
    }

    @Test
    void lookup_doesNotMatchEtfOrPartialNames() {
        // "DAX-ETF" is genuinely an ETF, not the index — must NOT hijack it.
        assertNull(IndexCatalog.lookup("DAX-ETF"));
        assertNull(IndexCatalog.lookup("DAX ETF"));
        assertNull(IndexCatalog.lookup("Nagarro SE"));
        assertNull(IndexCatalog.lookup(""));
        assertNull(IndexCatalog.lookup(null));
    }

    @Test
    void isIndexSymbol_recognisesCaretSymbols() {
        assertTrue(IndexCatalog.isIndexSymbol("^GDAXI"));
        assertTrue(IndexCatalog.isIndexSymbol("^IXIC"));
        assertFalse(IndexCatalog.isIndexSymbol("DAX"));
        assertFalse(IndexCatalog.isIndexSymbol("AAPL"));
        assertFalse(IndexCatalog.isIndexSymbol(null));
    }
}
