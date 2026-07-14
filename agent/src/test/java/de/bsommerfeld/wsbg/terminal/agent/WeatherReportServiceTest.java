package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerStat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Wetterbericht's deterministic typesetting: wire-input markup a 4B
 * model parrots into the prose ({@code [!]}, bracketed tickers, raw caret
 * index symbols) must never reach the page, the assembly sets OUR heading
 * literals and honest literals for whiffed sections, the formatting belt
 * enforces the bold budget and swaps raw wire tickers for display names,
 * and the examiner raster reconciles figures against mixed-locale material.
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
    void wireExtrasBracesUnwrapToParentheses() {
        assertEquals("Kakao-Longs (-4,4 %, gespalten) blieben Thema.",
                WeatherReportService.stripWireMarkup(
                        "Kakao-Longs {-4,4 %, gespalten} blieben Thema."));
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
        assertTrue(WeatherReportService.looksLikeReport(sectioned));
        assertFalse(WeatherReportService.looksLikeReport("## A\nkurz"));
        assertFalse(WeatherReportService.looksLikeReport("x".repeat(800)));
        assertFalse(WeatherReportService.looksLikeReport(null));
    }

    // --- the Redaktion's typesetting ----------------------------------------

    @Test
    void assemblySetsSystemHeadingsAndHonestLiterals() {
        String filler = " Der Raum las die Lage über die volle Sitzung hinweg bemerkenswert"
                + " gelassen und blieb bei seiner Erzählung des Tages.";
        String[] bodies = {"Der Tag gehörte **Rheinmetall**." + filler, null,
                "Mittags drehte das Band." + filler, "Abends übernahmen die Mover." + filler,
                null};
        String report = WeatherReportService.assemble(
                WeatherReportService.SECTIONS_DE, bodies, true, List.of());
        // All five headings present, in order, even where the section whiffed.
        int last = -1;
        for (String heading : WeatherReportService.SECTIONS_DE) {
            int at = report.indexOf("## " + heading);
            assertTrue(at > last, "heading missing or out of order: " + heading);
            last = at;
        }
        assertTrue(report.contains("Der Käfig blieb in diesem Fenster still."), report);
        assertTrue(report.contains("Für morgen liegt nichts auf dem Kalender."), report);
        assertTrue(WeatherReportService.looksLikeReport(report), report);
    }

    @Test
    void assemblyAddsGermanPercentSpacing() {
        String[] bodies = {"NVIDIA legte **+4,0%** zu.", "a".repeat(10), "b".repeat(10),
                "c".repeat(10), "d".repeat(10)};
        String report = WeatherReportService.assemble(
                WeatherReportService.SECTIONS_DE, bodies, true, List.of());
        assertTrue(report.contains("+4,0 %"), report);
    }

    @Test
    void boldSpansBeyondTheBudgetAreUnwrapped() {
        String body = "**Eins** und **zwei** und **drei** und **vier** und **fünf**.";
        assertEquals("**Eins** und **zwei** und **drei** und **vier** und fünf.",
                WeatherReportService.capBoldSpans(body, 4));
        assertEquals(body, WeatherReportService.capBoldSpans(body, 5));
    }

    @Test
    void rawWireTickersAreSwappedForDisplayNames() {
        List<TickerStat> tickers = List.of(
                new TickerStat("DTRUY", "Daimler Truck", 3, 0, null, null, null, null, null),
                // Symbol-headed name: "SAP" must NOT balloon into "SAP SE SE".
                new TickerStat("SAP", "SAP SE", 2, 0, null, null, null, null, null));
        String out = WeatherReportService.tickersToNames(
                "Die Diskussion um DTRUY hielt an, SAP lief seitwärts. "
                        + "Daimler Truck (DTRUY) blieb Thema.", tickers);
        assertTrue(out.contains("Diskussion um Daimler Truck"), out);
        assertTrue(out.contains("SAP lief seitwärts"), out);
        // Parenthesized ticker is legitimate typography and stays.
        assertTrue(out.contains("Daimler Truck (DTRUY)"), out);
    }

    @Test
    void nameCatalogMergesTopTickersAndAllHeadlineSubjects() {
        var top = List.of(new TickerStat("RHM", "Rheinmetall AG", 5, 1,
                null, null, null, null, null));
        var record = new de.bsommerfeld.wsbg.terminal.db.HeadlineRecord("c1", "text", null, 0,
                List.of(), List.of(), de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL,
                "KOSPI.KS",
                List.of(new de.bsommerfeld.wsbg.terminal.db.HeadlineSubject(
                        "KOSPI Composite", "KOSPI.KS")),
                null, List.of(), null, null, null, false, List.of());
        var catalog = WeatherReportService.nameCatalog(List.of(record), top);
        assertTrue(catalog.stream().anyMatch(t -> "RHM".equals(t.ticker())
                && "Rheinmetall AG".equals(t.name())));
        assertTrue(catalog.stream().anyMatch(t -> "KOSPI.KS".equals(t.ticker())
                && "KOSPI Composite".equals(t.name())));
    }

    @Test
    void inspectSectionAcceptsFiguresFromGermanFormattedMaterial() {
        String material = "DATE: 2026-07-13 (MONDAY)\n\n"
                + "MARKETS (verified): DAX 25.106 Pkt (+0,2 %) · Gold 4.069,00 USD (-1,1 %)";
        String body = "Der **DAX** hielt sich bei 25.106 Punkten (+0,2 %), während Gold "
                + "auf 4.069,00 US-Dollar nachgab und der Raum die Lage gelassen las.";
        assertTrue(WeatherReportService.inspectSection(body, material, true).isEmpty());
    }

    @Test
    void inspectSectionFlagsASplicedFigureAsHard() {
        String material = "DATE: 2026-07-13 (MONDAY)\n\n"
                + "MARKETS (verified): DAX 25.106 Pkt (+0,2 %)";
        String body = "Der KOSPI brach um -8,9 % ein, was den Raum nervös machte und die "
                + "Stimmung über die Sitzung hinweg sichtbar drückte.";
        var hard = WeatherReportService.inspectSection(body, material, true).stream()
                .filter(DeepDiveFactCheck.Objection::hard).toList();
        assertFalse(hard.isEmpty());
        String cut = DeepDiveFactCheck.removeOffendingSentences(body, hard);
        assertFalse(cut.contains("KOSPI"), cut);
    }

    @Test
    void inspectSectionReplacesTheDdLengthContractWithTheForecastOne() {
        String material = "DATE: 2026-07-13 (MONDAY)\n\nROOM PULSE: 12 lines from 5 subjects";
        // 130 chars of figure-free prose: below the DD's 180 floor, fine here.
        String body = "Der Raum blieb den ganzen Morgen über ruhig und wartete sichtbar "
                + "auf einen Impuls, der in diesem Fenster nicht mehr kam heute.";
        assertTrue(WeatherReportService.inspectSection(body, material, true).stream()
                .noneMatch(o -> o.kind() == DeepDiveFactCheck.Objection.Kind.LENGTH));
        String tooLong = (body + " ").repeat(12);
        assertTrue(WeatherReportService.inspectSection(tooLong, material, true).stream()
                .anyMatch(o -> o.kind() == DeepDiveFactCheck.Objection.Kind.LENGTH));
    }
}
