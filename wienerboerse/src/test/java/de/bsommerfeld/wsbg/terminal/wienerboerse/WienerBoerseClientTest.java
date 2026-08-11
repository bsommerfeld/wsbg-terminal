package de.bsommerfeld.wsbg.terminal.wienerboerse;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser and scrape contracts against trimmed real captures (probed 2026-08-05). */
class WienerBoerseClientTest {

    private static String fixture(String name) throws IOException {
        try (var in = WienerBoerseClientTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheGermanCsvOldestFirst() throws IOException {
        List<WienerBoerseHistory.WienerBar> bars =
                WienerBoerseClient.parseCsv(fixture("/wienerboerse-voestalpine.csv"));
        assertEquals(6, bars.size(), "header and BOM never become bars");
        var first = bars.get(0);
        var last = bars.get(bars.size() - 1);
        assertEquals(LocalDate.of(2016, 1, 4), first.date());
        assertEquals(LocalDate.of(2026, 8, 5), last.date());
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertEquals(27.955, first.open(), 1e-9, "comma decimals read as decimals");
        assertEquals(27.96, first.high(), 1e-9);
        assertEquals(26.96, first.low(), 1e-9);
        assertEquals(27.315, first.close(), 1e-9);
        assertEquals(857_140, first.volumeShares(), 1e-9, "dot thousands read as thousands");
        assertEquals(23_425_844, first.turnoverEur(), 1e-9);
        assertEquals(47.12, last.close(), 1e-9);
        assertEquals(940_326, last.volumeShares(), 1e-9);
    }

    @Test
    void scrapesPathAndModuleIdOffTheRealPageSnippet() throws IOException {
        String[] key = WienerBoerseClient.pageKey(fixture("/wienerboerse-histpage.html")).orElseThrow();
        assertEquals("/aktien-prime-market/voestalpine-ag-AT0000937503/historische-daten/", key[0]);
        assertEquals("48840", key[1]);
    }

    @Test
    void canonicalTagPlusFallbackIdCoversAPageWhoseCsvLinkMoved() {
        String[] key = WienerBoerseClient.pageKey(
                "<link rel=\"canonical\" href=\"https://www.wienerborse.at"
                        + "/aktien-prime-market/omv-ag-AT0000743059/historische-daten/\"/>")
                .orElseThrow();
        assertEquals("/aktien-prime-market/omv-ag-AT0000743059/historische-daten/", key[0]);
        assertEquals(WienerBoerseClient.FALLBACK_MODULE_ID, key[1]);
        assertTrue(WienerBoerseClient.pageKey(null).isEmpty());
        assertTrue(WienerBoerseClient.pageKey("").isEmpty());
        assertTrue(WienerBoerseClient.pageKey("<html>no links here</html>").isEmpty());
    }

    @Test
    void brokenLinesAreSkippedNotFatal() {
        var bars = WienerBoerseClient.parseCsv(
                "﻿Datum;Eröffnungspreis;Tageshoch;Tagestief;Schlusspreis;Diff.%;Geldumsatz1;Stückumsatz1;\n"
                        + "\"99.13.2020\";\"1,00\";\"1,00\";\"1,00\";\"1,00\";\"0 %\";\"1\";\"1\";\n"
                        + "\"03.01.2020\";\"kaputt\";\"2,00\";\"1,00\";\"1,50\";\"0 %\";;\"100\";\n"
                        + "not;a;row\n"
                        + "\"02.01.2020\";\"2,00\";\"2,10\";\"1,90\";\"2,05\";\"0 %\";\"4.100\";\"2.000\";\n");
        assertEquals(2, bars.size(), "the impossible date and the short row fall away");
        assertEquals(LocalDate.of(2020, 1, 2), bars.get(0).date(), "oldest first after the sort");
        assertTrue(Double.isNaN(bars.get(1).open()), "an unreadable cell is NaN, never zero");
        assertTrue(Double.isNaN(bars.get(1).turnoverEur()), "an empty cell is NaN too");
        assertEquals(100, bars.get(1).volumeShares(), 1e-9);
        assertTrue(WienerBoerseClient.parseCsv(null).isEmpty());
        assertTrue(WienerBoerseClient.parseCsv("").isEmpty());
        assertTrue(WienerBoerseClient.parseCsv("<!DOCTYPE html><html>not a csv</html>").isEmpty());
    }

    @Test
    void garbageAndNonAtShapesStayNetworkFree() {
        // The ISIN gate is network-free: foreign paper never leaves the JVM.
        var noNetwork = new de.bsommerfeld.wsbg.terminal.source.net.WebFetcher() {
            @Override
            public String name() {
                return "none";
            }

            @Override
            public de.bsommerfeld.wsbg.terminal.source.net.WebResponse fetch(
                    String url, java.util.Map<String, String> headers,
                    java.time.Duration timeout) {
                throw new AssertionError("no network expected for " + url);
            }
        };
        var client = new WienerBoerseClient(noNetwork);
        var from = LocalDate.of(2016, 1, 1);
        assertTrue(client.history("DE0007664039", from).isEmpty(), "German paper stays home");
        assertTrue(client.history("US0378331005", from).isEmpty());
        assertTrue(client.history("VOE", from).isEmpty(), "a ticker is not an ISIN");
        assertTrue(client.history("AT000093750", from).isEmpty(), "eleven chars is not an ISIN");
        assertTrue(client.history(null, from).isEmpty());
        assertTrue(client.history("AT0000937503", null).isEmpty());
    }
}
