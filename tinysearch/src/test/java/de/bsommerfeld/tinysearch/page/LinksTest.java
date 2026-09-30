package de.bsommerfeld.tinysearch.page;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinksTest {

    @Test
    void bingCarriesTheTargetAsBase64() {
        URI link = URI.create("https://www.bing.com/ck/a?!&&p=0558&ptn=3&ver=2&hsh=4"
                + "&u=a1aHR0cHM6Ly93d3cuZmluYW56ZW4ubmV0L2FrdGllbi9zYXAtYWt0aWU&ntb=1");

        assertEquals(URI.create("https://www.finanzen.net/aktien/sap-aktie"), Links.bing(link));
        assertNull(Links.bing(URI.create("https://www.bing.com/ck/a?p=1&u=b2xyz")), "not the a1 scheme");
        assertEquals(URI.create("https://example.org/"), Links.bing(URI.create("https://example.org/")));
    }

    @Test
    void duckDuckGoCarriesTheTargetEncoded() {
        URI link = URI.create("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.boerse.de%2Faktien%2FSAP%2DAktie"
                + "&rut=710f99a5");

        assertEquals(URI.create("https://www.boerse.de/aktien/SAP-Aktie"), Links.duckDuckGo(link));
        assertNull(Links.duckDuckGo(URI.create("https://duckduckgo.com/l/?rut=1")));
    }

    @Test
    void yahooCarriesTheTargetInThePath() {
        URI link = URI.create("https://r.search.yahoo.com/_ylt=A2RSqfjC;_ylu=Y29sbw/RV=2/RE=1791979458/RO=10"
                + "/RU=https%3a%2f%2fwww.finanzen.net%2faktien%2fsap-aktie/RK=2/RS=cJA_7Uk-");

        assertEquals(URI.create("https://www.finanzen.net/aktien/sap-aktie"), Links.yahoo(link));
        assertNull(Links.yahoo(URI.create("https://r.search.yahoo.com/_ylt=A2RS/RV=2")));
    }

    @Test
    void rawCharactersAreEncodedAndOnlyWebAddressesPass() {
        assertEquals(URI.create("https://example.org/a%20b%7Cc?x=%7B1%7D"),
                Links.parse("https://example.org/a b|c?x={1}"));
        assertEquals("https://de.wikipedia.org/wiki/Börse", Links.parse("https://de.wikipedia.org/wiki/Börse").toString());
        assertNull(Links.parse("javascript:void(0)"));
        assertNull(Links.parse("/relative/path"));
        assertNull(Links.parse(""));
    }
}
