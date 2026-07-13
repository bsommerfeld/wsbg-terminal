package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Wetterbericht's deterministic output sanitizer: wire-input markup a 4B
 * model parrots into the prose ({@code [!]}, bracketed tickers, raw caret
 * index symbols) must never reach the page, while the report's own markdown
 * dress passes through untouched.
 */
class WeatherReportServiceTest {

    @Test
    void importantFlagIsStripped() {
        assertEquals("NVIDIA zieht an.",
                WeatherReportService.stripWireMarkup("[!] NVIDIA zieht an."));
    }

    @Test
    void parenthesizedCaretSymbolResolvesToTheCataloguedIndexName() {
        assertEquals("Der Composite (Nasdaq Composite) fiel um 1,2 %.",
                WeatherReportService.stripWireMarkup("Der Composite ([^IXIC]) fiel um 1,2 %."));
    }

    @Test
    void bareCaretSymbolIsDeCaretedWhenUncatalogued() {
        assertEquals("XYZ123 lief seitwärts.",
                WeatherReportService.stripWireMarkup("^XYZ123 lief seitwärts."));
    }

    @Test
    void bracketedTickerLosesItsBrackets() {
        assertEquals("NVDA legte zu, (RHM) auch.",
                WeatherReportService.stripWireMarkup("[NVDA] legte zu, ([RHM]) auch."));
    }

    @Test
    void markdownStructureSurvives() {
        String md = "## Großwetterlage\n\n**NVIDIA** trug den Tag, *vorsichtig* gelesen.";
        assertEquals(md, WeatherReportService.stripWireMarkup(md));
    }

    @Test
    void strayWhitespaceFromRemovalsIsCollapsed() {
        assertEquals("Rheinmetall, sagt der Raum.",
                WeatherReportService.stripWireMarkup("Rheinmetall [!] , sagt der Raum."));
    }

    @Test
    void looksLikeReportNeedsLengthAndSections() {
        String sectioned = "## Großwetterlage\n" + "x".repeat(200)
                + "\n## Der Käfig\n" + "x".repeat(200)
                + "\n## Randnotizen\n" + "x".repeat(200);
        org.junit.jupiter.api.Assertions.assertTrue(
                WeatherReportService.looksLikeReport(sectioned));
        org.junit.jupiter.api.Assertions.assertFalse(
                WeatherReportService.looksLikeReport("## A\nkurz"));
        org.junit.jupiter.api.Assertions.assertFalse(
                WeatherReportService.looksLikeReport("x".repeat(800)));
        org.junit.jupiter.api.Assertions.assertFalse(
                WeatherReportService.looksLikeReport(null));
    }
}
