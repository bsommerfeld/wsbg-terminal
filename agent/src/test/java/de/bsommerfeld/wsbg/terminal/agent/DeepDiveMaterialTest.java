package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.price.AnalystView;
import de.bsommerfeld.wsbg.terminal.core.price.FundFacts;
import de.bsommerfeld.wsbg.terminal.briefing.CentralBankCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.EconCalendarClient;
import de.bsommerfeld.wsbg.terminal.briefing.TradingViewCalendarClient;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.briefing.GlobalHazardsClient;
import de.bsommerfeld.wsbg.terminal.core.price.AnalystActions;
import de.bsommerfeld.wsbg.terminal.core.price.PressTimeline;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.core.price.HedgeFundPopularity;
import de.bsommerfeld.wsbg.terminal.core.price.InstrumentFacts;
import de.bsommerfeld.wsbg.terminal.core.price.UsListingStats;
import de.bsommerfeld.wsbg.terminal.core.price.VenueStats;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KI-DD's completeness guarantee: EVERY data leg the collect step gathers
 * must actually reach the model — this test fills a {@link DeepDiveService.Material}
 * with every block and asserts each one's label AND a distinctive value in the
 * material (user mandate 2026-07-13: "validiere, ob die KI wirklich alle Daten
 * sieht"). Since the section-workspace rebuild it also pins the SHELF MAPPING
 * (which leg feeds which section) and the deterministic assembly.
 */
class DeepDiveMaterialTest {

    private static DeepDiveService.Material fullMaterial() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Rheinmetall AG";
        m.ticker = "RHM";
        m.isin = "DE0007030009";
        m.snapshot = DeepDiveChartsTest.snapshot();
        m.deepDive = DeepDiveChartsTest.deepDive();
        m.analystView = new AnalystView(19, 3, 5, 0, 0, 27, 16, 3, 6, 0, 0, 4, 3,
                1720.0, "EUR", 73.4, 1_700_000_000L,
                List.of(new AnalystView.CorporateEvent(
                        Instant.now().getEpochSecond() + 30L * 86400, "RESULTS",
                        "Rheinmetall AG: Bericht 2. Quartal 2026")), 0);
        m.shortInterest = DeepDiveChartsTest.shorts();
        m.insiderDealings = DeepDiveChartsTest.insider();
        m.venueStats = new VenueStats("Tradegate", 991.9, 992.5, 300, 250, 992.1,
                -1.77, 1019.8, 985.0, 1010.0, 154_000, 152_000_000, 4_200,
                Instant.now().getEpochSecond());
        m.facts = new InstrumentFacts("RHEINMETALL AG", "Deutschland", "Industrie",
                "Maschinenbau", 4.6e10, 28_000, "2024", 41.4, "2025", 1.2, "2025",
                310_000, Instant.now().getEpochSecond());
        m.news = List.of(new RawNewsItem("uuid-1", "Rheinmetall gewinnt Großauftrag",
                "WELT", "https://example.org/a", Instant.now(), List.of(),
                null, null, false, null));
        SubjectUnit unit = new SubjectUnit("RHM", "Rheinmetall AG");
        unit.updateResolved("Rheinmetall AG", "RHM", DeepDiveChartsTest.snapshot(), List.of());
        unit.addEvidence(new SubjectUnit.EvidenceRef("t1", "c1",
                "Rheiner läuft, bin all-in", "reddit", Instant.now().getEpochSecond()));
        unit.addHeadline("Rheinmetall: Käfig feiert den Auftrag", "BULLISH");
        m.unit = unit;
        m.evidenceCount = unit.evidenceCount();
        return m;
    }

    @Test
    void everyDataLegReachesTheModel() {
        String brief = DeepDiveService.buildMaterial("Rheinmetall", fullMaterial());

        // Identity: name, ticker, ISIN, index membership.
        assertContains(brief, "SUBJECT: Rheinmetall AG");
        assertContains(brief, "Ticker RHM");
        assertContains(brief, "ISIN DE0007030009");
        assertContains(brief, "member of DAX PERFORMANCE INDEX");

        // Market: price, day change, day range.
        assertContains(brief, "MARKET (verified) [1]:");
        assertContains(brief, "992.10 EUR");
        assertContains(brief, "day range 985.00-");

        // Profile: website, HQ, shares, sector, employees + the portrait prose.
        assertContains(brief, "official website https://www.rheinmetall.com");
        assertContains(brief, "HQ Düsseldorf, Deutschland");
        assertContains(brief, "sector Industrie / Maschinenbau");
        assertContains(brief, "COMPANY PORTRAIT (verified) [4]: Portrait");

        // Fundamentals: key-figure years incl. the estimate, balance sheet, boards.
        // Every year label carries its reported/estimate status as a WORD the
        // model can copy — the bare 'e' suffix provably lost against the 4B's
        // training prior (reported years narrated as "geschätzt", live SAP
        // 2026-07-15).
        assertContains(brief, "KEY FIGURES BY FISCAL YEAR");
        assertContains(brief, "2024 (reported):");
        assertContains(brief, "2026e (consensus estimate):");
        // Per-share values spelled out — the bare "EPS" acronym let the 4B
        // narrate 6.95 per share as "6,95 Millionen Euro Gewinn" (live smoke
        // 2026-07-15); the material now carries the meaning as a literal.
        assertContains(brief, "earnings per share 55.00");
        assertContains(brief, "dividend per share 12.00");
        assertContains(brief, "PEG 0.90");
        // Human units, never the upstream thousands-EUR raw values (a copied
        // "30 871 000" misstated SAP's revenue by three orders of magnitude).
        assertContains(brief, "BALANCE SHEET (verified");
        // Balance years join their status via the key-figure flags; a year
        // with no key-figure twin (2023) stays bare — honesty over a guess.
        assertContains(brief, "2024 (reported): turnover 9.75B EUR");
        assertContains(brief, "2023: turnover");
        assertContains(brief, "R&D 380.0M EUR");
        assertContains(brief, "BOARDS (verified) [4]: Armin Papperger (Vorstand)");

        // Street: analyst distribution, trend, target, revisions, events, estimate path.
        assertContains(brief, "ANALYSTS (verified) [4]: 27 covering");
        assertContains(brief, "19 buy / 3 overweight / 5 hold");
        assertContains(brief, "(3 months ago: 16/3/6/0/0)");
        assertContains(brief, "consensus target 1720.00 EUR (+73.4% vs current)");
        assertContains(brief, "recent revisions 4 up / 3 down");
        assertContains(brief, "UPCOMING EVENTS (verified) [4]:");
        assertContains(brief, "Bericht 2. Quartal 2026");
        assertContains(brief, "CONSENSUS ESTIMATE PATH");

        // Insiders and shorts, with names and figures.
        assertContains(brief, "INSIDER DEALINGS (verified, BaFin");
        assertContains(brief, "ATP Holding GmbH (in enger Beziehung): Kauf 3043315 EUR @ 954.62 EUR");
        assertContains(brief, "SHORT POSITIONS (verified, Bundesanzeiger register, total disclosed 1.15%)");
        assertContains(brief, "D. E. Shaw & Co., L.P.: 0.60%");

        // Chart-technical read (attributed) + peers + performance.
        assertContains(brief, "CHART-TECHNICAL READ (TradingCentral");
        assertContains(brief, "pivot 919.27");
        assertContains(brief, "Its comment: Kommentar");
        assertContains(brief, "PEERS (verified) [4]: KSB Vz (mcap 1.5B EUR, P/E 11.9)");
        assertContains(brief, "PERFORMANCE (verified) [4]:");
        assertContains(brief, "52w -46.1%");
        assertContains(brief, "52w high 2008.50 (2025-10-02)");

        // Trading depth incl. the 30d-average yardstick from the onvista facts.
        assertContains(brief, "TRADING (Tradegate, verified) [2]: 154000 shares (30d average 310000)");
        assertContains(brief, "152.0M EUR turnover");
        assertContains(brief, "bid/ask 991.90/992.50");

        // News with title and publisher.
        assertContains(brief, "PRESS FACTS (last 30 days");
        assertContains(brief, "[8] ");
        assertContains(brief, "Rheinmetall gewinnt Großauftrag · WELT");

        // The room: anchor, evidence snippet, wire line.
        assertContains(brief, "ROOM EVIDENCE (r/wallstreetbetsGER, unverified) [9]:");
        assertContains(brief, "First mentioned");
        assertContains(brief, "Rheiner läuft, bin all-in");
        assertContains(brief, "WIRE LINES ALREADY PUBLISHED FOR THIS SUBJECT:");
        assertContains(brief, "Rheinmetall: Käfig feiert den Auftrag");
    }

    /**
     * The ETF branch: prod fills EITHER the stock profile (facts) OR the fund
     * profile (fundFacts), never both — the fund leg is asserted against a
     * fund-only material, exactly the shape collect() produces for an ETF ISIN.
     */
    @Test
    void fundLegReachesTheModelOnAFundMaterial() {
        DeepDiveService.Material m = fullMaterial();
        m.facts = null; // mutually exclusive with fundFacts in prod
        m.fundFacts = new FundFacts("iShares Core MSCI World", 0.20, 1.28e11,
                "MSCI World NR USD", 4, "Beschreibung",
                List.of(new FundFacts.Holding("Nvidia", 5.42)), Instant.now().getEpochSecond());
        String brief = DeepDiveService.buildMaterial("MSCI World", m);
        assertContains(brief, "FUND [3]: iShares Core MSCI World");
        assertContains(brief, "TER 0.20%");
        assertContains(brief, "benchmark MSCI World NR USD");
        assertContains(brief, "Nvidia 5.4%");
    }

    @Test
    void onvistaValuationSurvivesAConsorsbankMiss() {
        DeepDiveService.Material m = fullMaterial();
        m.deepDive = null; // Consorsbank outage — the onvista headline figures must step in
        String brief = DeepDiveService.buildMaterial("Rheinmetall", m);
        assertContains(brief, "market cap 46.0B EUR");
        assertContains(brief, "P/E 41.4 (2025)");
        assertContains(brief, "dividend yield 1.20% (2025)");
    }

    /**
     * The shelf mapping (the workspace rebuild 2026-07-13): every leg lands on
     * ITS section's shelf — profile on "Worum es geht", market/trading on
     * "Lage", figures on "Fundamentale Entwicklung", street on "Bewertung",
     * insiders/shorts/technicals on "Katalysatoren", the dated events and the
     * estimate path on "Ausblick", the room last — and no shelf approaches the
     * context window.
     */
    @Test
    void everyLegLandsOnItsSectionShelf() {
        String[] shelves = DeepDiveService.sectionMaterials(fullMaterial());
        assertEquals(DeepDiveService.SECTION_COUNT, shelves.length);

        assertTrue(shelves[DeepDiveService.SEC_ABOUT].contains("PROFILE (verified)"));
        assertTrue(shelves[DeepDiveService.SEC_ABOUT].contains("COMPANY PORTRAIT"));
        assertTrue(shelves[DeepDiveService.SEC_ABOUT].contains("BOARDS (verified)"));

        assertNull(shelves[DeepDiveService.SEC_THESIS],
                "the thesis shelf is filled LAST, from the standing sections");

        assertTrue(shelves[DeepDiveService.SEC_SITUATION].contains("MARKET (verified)"));
        assertTrue(shelves[DeepDiveService.SEC_SITUATION].contains("TRADING (Tradegate"));
        assertTrue(shelves[DeepDiveService.SEC_SITUATION].contains("PERFORMANCE (verified)"));
        assertTrue(shelves[DeepDiveService.SEC_SITUATION].contains("Rheinmetall gewinnt Großauftrag"),
                "untriaged news defaults to the LAGE shelf");

        assertTrue(shelves[DeepDiveService.SEC_FUNDAMENTALS].contains("KEY FIGURES BY FISCAL YEAR"));
        assertTrue(shelves[DeepDiveService.SEC_FUNDAMENTALS].contains("BALANCE SHEET"));

        assertTrue(shelves[DeepDiveService.SEC_VALUATION].contains("ANALYSTS (verified)"));
        assertTrue(shelves[DeepDiveService.SEC_VALUATION].contains("PEERS (verified)"));
        // House arithmetic: the target as a multiple of the estimate path plus
        // the price located in its 52w range — 1720/55 EPS 2026e = 31.3x,
        // 992.10 vs high 2008.50 / low 845.00.
        assertTrue(shelves[DeepDiveService.SEC_VALUATION].contains(
                "VALUATION CONTEXT (house arithmetic on the verified figures)"));
        assertTrue(shelves[DeepDiveService.SEC_VALUATION]
                .contains("31.3x the 2026e consensus earnings per share"));
        assertTrue(shelves[DeepDiveService.SEC_VALUATION].contains("50.6% below its 52w high"));

        assertTrue(shelves[DeepDiveService.SEC_CATALYSTS].contains("INSIDER DEALINGS"));
        assertTrue(shelves[DeepDiveService.SEC_CATALYSTS].contains("SHORT POSITIONS"));
        assertTrue(shelves[DeepDiveService.SEC_CATALYSTS].contains("CHART-TECHNICAL READ"));

        assertTrue(shelves[DeepDiveService.SEC_OUTLOOK].contains("UPCOMING EVENTS"));
        assertTrue(shelves[DeepDiveService.SEC_OUTLOOK].contains("CONSENSUS ESTIMATE PATH"));
        assertTrue(shelves[DeepDiveService.SEC_OUTLOOK].contains("consensus target 1720.00"));

        assertTrue(shelves[DeepDiveService.SEC_ROOM].contains("ROOM EVIDENCE"));
        assertTrue(shelves[DeepDiveService.SEC_ROOM].contains("WIRE LINES ALREADY PUBLISHED"));

        for (String shelf : shelves) {
            assertTrue(shelf == null || shelf.length() <= 6400,
                    "shelf too large: " + (shelf == null ? 0 : shelf.length()));
        }
    }

    /** Triage routing: a KATALYSATOR-judged item leaves the LAGE shelf. */
    @Test
    void triagedNewsLandsOnItsRoutedShelf() {
        DeepDiveService.Material m = fullMaterial();
        m.newsTargets = Map.of("uuid-1", "KATALYSATOR");
        String[] shelves = DeepDiveService.sectionMaterials(m);
        assertFalse(shelves[DeepDiveService.SEC_SITUATION].contains("Großauftrag"));
        assertTrue(shelves[DeepDiveService.SEC_CATALYSTS].contains("Großauftrag"));
    }

    /**
     * Multi-label routing (2026-07-16): one article may serve SEVERAL shelves
     * - the comma-joined route reaches every named section, and only those.
     */
    @Test
    void multiRoutedNewsReachesEveryNamedShelf() {
        DeepDiveService.Material m = fullMaterial();
        m.newsTargets = Map.of("uuid-1", "LAGE,AUSBLICK");
        String[] shelves = DeepDiveService.sectionMaterials(m);
        assertTrue(shelves[DeepDiveService.SEC_SITUATION].contains("Großauftrag"));
        assertTrue(shelves[DeepDiveService.SEC_OUTLOOK].contains("Großauftrag"));
        assertFalse(shelves[DeepDiveService.SEC_CATALYSTS].contains("Großauftrag"));
    }

    /** BEWERTUNG is the fourth triage route - analyst reasoning reaches the valuation shelf. */
    @Test
    void bewertungRoutedNewsReachesTheValuationShelf() {
        DeepDiveService.Material m = fullMaterial();
        m.newsTargets = Map.of("uuid-1", "BEWERTUNG");
        String[] shelves = DeepDiveService.sectionMaterials(m);
        assertTrue(shelves[DeepDiveService.SEC_VALUATION].contains("Großauftrag"));
        assertFalse(shelves[DeepDiveService.SEC_SITUATION].contains("Großauftrag"));
    }

    /**
     * An empty room yields NO shelf — the section then gets its honest literal
     * without any model call, so an empty room can never be hallucinated into
     * a dated discussion (live-observed SAP 2026-07-13: evidenceCount 0, yet
     * the report claimed a debate "begann am 12. Juli").
     */
    @Test
    void emptyRoomYieldsNoShelfAndAnHonestLiteral() {
        DeepDiveService.Material m = fullMaterial();
        m.unit = new SubjectUnit("RHM", "Rheinmetall AG"); // exists, but silent
        m.roomUnits = List.of(m.unit);
        m.evidenceCount = 0;
        assertNull(DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ROOM]);

        String[] bodies = new String[DeepDiveService.SECTION_COUNT];
        String report = DeepDiveService.assemble(DeepDiveService.SECTIONS_DE, bodies, true);
        assertContains(report, "## Der Raum");
        assertContains(report, "Der Käfig hat dieses Subjekt bisher nicht aufgegriffen.");
        assertContains(report, "Hierzu liegen keine verifizierten Daten vor.");
    }

    /**
     * The deterministic typesetter: all eight canonical headings in order,
     * bodies under their headings, cross-section duplicate paragraphs dropped
     * (first occurrence wins) — assembly is the one place that sees every
     * section at once.
     */
    @Test
    void assemblySetsHeadingsAndDropsCrossSectionDuplicates() {
        String[] bodies = new String[DeepDiveService.SECTION_COUNT];
        bodies[DeepDiveService.SEC_ABOUT] = "Der Konzern baut Panzer [4].";
        String dup = "Der Bericht zum 2. Quartal steht am 24. Juli 2026 an und ist die"
                + " nächste harte Messlatte für das Papier [4].";
        bodies[DeepDiveService.SEC_CATALYSTS] = dup;
        bodies[DeepDiveService.SEC_OUTLOOK] = dup + "\n\nDie Street ruft 1720 EUR auf [4].";
        String report = DeepDiveService.assemble(DeepDiveService.SECTIONS_DE, bodies, true);

        int at = -1;
        for (String heading : DeepDiveService.SECTIONS_DE) {
            int next = report.indexOf("## " + heading);
            assertTrue(next > at, "heading out of order or missing: " + heading);
            at = next;
        }
        assertEquals(1, occurrences(report, "24. Juli 2026"),
                "the duplicated paragraph must survive exactly once:\n" + report);
        // Markers live on the heading now, not in the prose.
        assertContains(report, "Die Street ruft 1720 EUR auf.");
        assertContains(report, "## Ausblick [4]");
        assertTrue(DeepDiveService.looksLikeReport(report, DeepDiveService.SECTIONS_DE));
    }

    /**
     * The typesetter's polish belts (live-observed smoke findings): a long
     * verbatim sentence repeating across sections drops (first wins), leftover
     * ISO dates render as German dotted dates, and pseudo-citation brackets
     * vanish while their sentence stays.
     */
    @Test
    void assemblyPolishesSentencesDatesAndBrackets() {
        String longSentence = "Die Entwicklung wird anhand der kommenden Berichte gemessen,"
                + " allen voran des Berichts zum zweiten Quartal 2026 am 24. Juli 2026 [4].";
        String[] bodies = new String[DeepDiveService.SECTION_COUNT];
        // The thesis reads the FULL sections (2026-07-16): a sentence it
        // copied from the body dies in the THESIS - the body keeps its own.
        bodies[DeepDiveService.SEC_THESIS] = "Das Bild lehnt bullish [4]. " + longSentence;
        // Two BODY sections carrying the identical sentence: first wins.
        bodies[DeepDiveService.SEC_CATALYSTS] = longSentence + " Das Risiko bleibt benannt [7].";
        bodies[DeepDiveService.SEC_OUTLOOK] = longSentence
                + " Der nächste Bericht folgt am 2026-10-22 [4].";
        bodies[DeepDiveService.SEC_ROOM] = "Der Käfig stritt am Nachmittag [2026-07-13 18:51]."
                + " Ein Beitrag hielt dagegen [19].";
        String report = DeepDiveService.assemble(DeepDiveService.SECTIONS_DE, bodies, true);

        assertEquals(1, occurrences(report, "am 24. Juli 2026"),
                "the copied thesis sentence dies, one body occurrence stays:\n" + report);
        int thesisAt = report.indexOf("## These");
        int catalystsAt = report.indexOf("## Katalysatoren");
        int outlookAt = report.indexOf("## Ausblick");
        int roomAt = report.indexOf("## Der Raum");
        String catalystsSec = report.substring(catalystsAt, outlookAt);
        String outlookSec = report.substring(outlookAt, roomAt);
        assertTrue(catalystsSec.contains("am 24. Juli 2026"), "first body occurrence wins");
        assertTrue(!outlookSec.contains("am 24. Juli 2026"), "the later body duplicate drops");
        assertTrue(!report.substring(thesisAt, catalystsAt).contains("am 24. Juli 2026"),
                "the thesis loses its copied sentence to the body");
        assertContains(report, "am 22.10.2026");
        assertTrue(!report.contains("2026-10-22"), report);
        assertContains(report, "Der Käfig stritt am Nachmittag.");
        assertTrue(!report.contains("[2026-07-13"), report);
        assertContains(report, "[19]");
    }

    /** Wikipedia discipline: an uncited NEWS article stays out of the register; legs stay. */
    @Test
    void registerListsOnlyCitedNews() {
        DeepDiveService.Material m = fullMaterial();
        // news:0 carries number 8 (see the numbering test) — report never cites it.
        String register = DeepDiveService.sourcesSection(m, true, java.util.Set.of(1, 4, 9));
        assertTrue(register.contains("- [1] LSX"), register);
        assertTrue(register.contains("- [4] Consorsbank"), register);
        assertTrue(register.contains("- [2] Tradegate"), "uncited LEGS stay listed");
        assertTrue(!register.contains("- [8]"), "uncited news must leave the register:\n" + register);
        assertTrue(register.contains("- [9] r/wallstreetbetsGER"), register);
        // The citation-less overload lists everything (test/material view).
        assertTrue(DeepDiveService.sourcesSection(m, true).contains("- [8]"));
    }

    /**
     * The editor's thesis shelf carries the STANDING SECTIONS IN FULL plus the
     * key data (2026-07-16 zoom-out: the old claim-sentence proxy bred layered
     * fixes - price-led claims, primacy reordering, red-thread echoes; the
     * full report fits the window-scaled editor pass).
     */
    @Test
    void thesisMaterialCarriesKeyDataAndFullSections() {
        DeepDiveService.Material m = fullMaterial();
        String[] bodies = new String[DeepDiveService.SECTION_COUNT];
        bodies[DeepDiveService.SEC_ABOUT] = "Der Konzern ist der größte deutsche Rüstungsbauer [4]."
                + " Ein zweiter Satz mit Details.";
        bodies[DeepDiveService.SEC_SITUATION] = "Der Kurs notiert bei 992,10 EUR [1].";
        String shelf = DeepDiveService.thesisMaterial(DeepDiveService.SECTIONS_DE, bodies, m);
        assertContains(shelf, "KEY DATA (verified):");
        assertContains(shelf, "consensus target 1720.00 EUR");
        assertContains(shelf, "## Worum es geht");
        assertContains(shelf, "Ein zweiter Satz mit Details.");
        assertContains(shelf, "Der Kurs notiert bei 992,10 EUR [1].");
    }

    @Test
    void roomlessSubjectIsSaidHonestly() {
        DeepDiveService.Material m = fullMaterial();
        m.unit = null;
        m.evidenceCount = 0;
        assertNull(DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ROOM]);
    }

    /**
     * The Wikipedia-style source register (user mandate 2026-07-13): numbers are
     * assigned deterministically from what delivered, the register text is house
     * output (never the model), and a marker the model invented is scrubbed.
     */
    @Test
    void sourceRegisterIsDeterministicAndModelMarkersAreScrubbed() {
        DeepDiveService.Material m = fullMaterial();
        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.get("price") == 1 && nums.get("venue") == 2
                && nums.get("profile") == 3 && nums.get("consors") == 4
                && nums.get("tc") == 5 && nums.get("shorts") == 6
                && nums.get("insider") == 7 && nums.get("news:0") == 8
                && nums.get("room") == 9, "numbering drifted: " + nums);

        String register = DeepDiveService.sourcesSection(m, true);
        assertTrue(register.startsWith("## Quellen\n"), register);
        assertTrue(register.contains("- [1] LSX - Kurs- und Handelsdaten"), register);
        assertTrue(register.contains("- [6] Bundesanzeiger"), register);
        assertTrue(register.contains("- [8] WELT - „Rheinmetall gewinnt Großauftrag“"), register);
        assertTrue(register.contains("- [9] r/wallstreetbetsGER"), register);

        String scrubbed = DeepDiveService.scrubUnknownSourceMarkers(
                "Der Umsatz wuchs [4]. Reine Fantasie [12], aber der Raum feiert [9].",
                new java.util.HashSet<>(nums.values()));
        assertTrue(scrubbed.contains("wuchs [4]."), scrubbed);
        assertTrue(scrubbed.contains("feiert [9]."), scrubbed);
        assertTrue(!scrubbed.contains("[12]"), scrubbed);
        assertTrue(scrubbed.contains("Fantasie, aber"), scrubbed);
    }

    /**
     * The room speaks name AND ticker (user mandate 2026-07-13): roomBlock
     * draws from the UNION of every matching unit — a "name:outlook" unit's
     * chatter belongs to the OTLK DD — with shared mentions riding only once.
     */
    @Test
    void roomUnionMergesNameAndTickerUnits() {
        SubjectUnit tickerUnit = new SubjectUnit("OTLK", "Outlook Therapeutics, Inc.");
        SubjectUnit nameUnit = new SubjectUnit("name:outlook", "Outlook");
        tickerUnit.addEvidence(new SubjectUnit.EvidenceRef("t1", "c1",
                "Outlook läuft heiß, FDA kommt", "reddit", 1_700_000_000L));
        nameUnit.addEvidence(new SubjectUnit.EvidenceRef("t1", "c1",
                "Outlook läuft heiß, FDA kommt", "reddit", 1_700_000_000L)); // shared mention
        nameUnit.addEvidence(new SubjectUnit.EvidenceRef("t1", "c2",
                "bin long Outlook", "reddit", 1_700_000_100L));
        tickerUnit.addHeadline("OTLK: Käfig wettet auf die FDA", "BULLISH");

        String joined = DeepDiveService.roomBlock(
                List.of(tickerUnit, nameUnit), java.util.Map.of("room", 9), true);
        assertTrue(joined.contains("bin long Outlook"), "the name unit's own mention is missing");
        assertTrue(joined.indexOf("Outlook läuft heiß") == joined.lastIndexOf("Outlook läuft heiß"),
                "a shared mention must ride exactly once");
        assertTrue(joined.contains("OTLK: Käfig wettet auf die FDA"), "wire lines from the union");
        assertTrue(joined.contains("[9]"), "the room's source marker");
    }

    /**
     * The OUTSIDE ROOMS shelf (2026-07-16): forum/social voices from the
     * sentiment fan ride the ROOM shelf as ONE capped aggregate block —
     * venue-labeled lines, an own source marker, loom bypass by design.
     */
    @Test
    void outsideRoomsRideTheRoomShelfAsOneCappedBlock() {
        DeepDiveService.Material m = fullMaterial();
        m.socialSentiment = List.of(
                new RawNewsItem("s1", "RHM zieht an", "Ariva-Forum (St2023)", "https://x/1",
                        Instant.parse("2026-07-16T12:00:00Z"), List.of(), null,
                        "die Vorzüge ziehen an, bin long", false),
                new RawNewsItem("s2", "/smg/ - stock market general", "4chan /biz/",
                        "https://x/2", Instant.parse("2026-07-16T11:00:00Z"),
                        List.of(), null, null, false));

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("sentiment"), "the outside rooms earn a source number");

        String block = DeepDiveService.sentimentBlock(m, nums);
        assertTrue(block.startsWith("OUTSIDE ROOMS"), block);
        assertTrue(block.contains("[Ariva-Forum (St2023)] die Vorzüge ziehen an"),
                "each voice carries its venue label: " + block);
        assertTrue(block.contains("[4chan /biz/] /smg/ - stock market general"),
                "a summary-less voice falls back to its title");
        assertTrue(block.contains("[" + nums.get("sentiment") + "]"),
                "the block carries its own source marker");
        assertTrue(block.contains("[2026-07-16]"), "date-only stamps, aggregate discipline");

        String roomShelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ROOM];
        assertTrue(roomShelf.contains("OUTSIDE ROOMS"),
                "the voices ride the ROOM shelf: " + roomShelf);
        assertTrue(roomShelf.contains("ROOM EVIDENCE"),
                "the cage stays first on the shelf");

        String register = DeepDiveService.sourcesSection(m, true);
        assertTrue(register.contains("Foren & Social Media"), register);

        // An empty fan leaves NO scaffolding behind.
        DeepDiveService.Material empty = fullMaterial();
        assertTrue(DeepDiveService.sentimentBlock(empty,
                DeepDiveService.sourceNumbers(empty)) == null);
    }

    /**
     * Fair share across venues (2026-07-16): a loud venue must not crowd the
     * quiet ones out of the budget — the block interleaves round-robin, each
     * venue's freshest first, and the author suffix never splits a venue.
     */
    @Test
    void outsideRoomsInterleaveVenuesFreshestFirst() {
        java.util.function.BiFunction<String, Integer, RawNewsItem> voice =
                (publisher, minute) -> new RawNewsItem(
                        publisher + "#" + minute, "t", publisher, "https://x/" + minute,
                        Instant.parse(String.format("2026-07-16T12:%02d:00Z", minute)),
                        List.of(), null, "post " + publisher + " " + minute, false);
        // 4chan floods (four voices, all freshest), Ariva speaks twice with
        // two different authors, Lemmy once — recency order alone would put
        // all four 4chan lines first.
        List<RawNewsItem> mixed = List.of(
                voice.apply("4chan /biz/", 59), voice.apply("4chan /biz/", 58),
                voice.apply("4chan /biz/", 57), voice.apply("4chan /biz/", 56),
                voice.apply("Ariva-Forum (St2023)", 30),
                voice.apply("Ariva-Forum (azulon)", 20),
                voice.apply("Lemmy (!finanzen@feddit.org)", 10));

        List<RawNewsItem> out = DeepDiveService.venueInterleaved(mixed);
        assertEquals("4chan /biz/#59", out.get(0).uuid());
        assertEquals("Ariva-Forum (St2023)#30", out.get(1).uuid(),
                "round one carries every venue's freshest — Ariva before 4chan's backlog");
        assertEquals("Lemmy (!finanzen@feddit.org)#10", out.get(2).uuid());
        assertEquals("4chan /biz/#58", out.get(3).uuid(), "round two starts");
        assertEquals("Ariva-Forum (azulon)#20", out.get(4).uuid(),
                "two authors are ONE Ariva room — the author suffix never splits a venue");
        assertEquals(7, out.size(), "interleaving loses nothing");
    }

    /**
     * The skeleton gate: all eight canonical headings must appear line-leading
     * and in order — the final belt before the archive.
     */
    @Test
    void looksLikeReportEnforcesTheCanonicalHeadings() {
        String pad = "x".repeat(80) + "\n";
        StringBuilder good = new StringBuilder();
        for (String h : DeepDiveService.SECTIONS_DE) good.append("## ").append(h).append('\n').append(pad);
        assertTrue(DeepDiveService.looksLikeReport(good.toString(), DeepDiveService.SECTIONS_DE));

        StringBuilder truncated = new StringBuilder();
        for (String h : DeepDiveService.SECTIONS_DE.subList(0, 7)) {
            truncated.append("## ").append(h).append('\n').append(pad);
        }
        assertTrue(!DeepDiveService.looksLikeReport(truncated.toString(), DeepDiveService.SECTIONS_DE));

        String renamed = good.toString().replace("## Der Raum", "## Fazit");
        assertTrue(!DeepDiveService.looksLikeReport(renamed, DeepDiveService.SECTIONS_DE));

        // Inline "## " occurrences do not count as headings (count fallback).
        String inline = ("prose ## not-a-heading " + pad).repeat(12);
        assertTrue(!DeepDiveService.looksLikeReport(inline, null));
    }

    /**
     * The typesetter's locale belt: a ROOT-formatted material money token the
     * model copied verbatim renders German; values stay untouched.
     */
    @Test
    void germanizeMoneyUnitsRendersCopiedTokens() {
        assertEquals("einem Marktwert von 160,6 Mrd. EUR und",
                DeepDiveService.germanizeMoneyUnits("einem Marktwert von 160.6B EUR und"));
        assertEquals("Umsatz von 17,3 Mio. EUR im",
                DeepDiveService.germanizeMoneyUnits("Umsatz von 17.3M EUR im"));
        // Already-German prose passes untouched.
        assertEquals("bei 36,8 Mrd. EUR.",
                DeepDiveService.germanizeMoneyUnits("bei 36,8 Mrd. EUR."));
    }

    /**
     * The desk journal's sentence diff, GitHub-hunk-shaped: del/add lines
     * carry the change with 1-based old/new sentence numbers, the ONE
     * unchanged neighbour sentence rides as ctx, longer unchanged runs elide
     * to a gap marker, an unchanged text yields nothing.
     */
    @Test
    void sentenceDiffBuildsAHunkWithContextAndLineNumbers() {
        String before = "Der Kurs steigt auf 140,31 EUR. Die UBS senkt ihr Ziel auf 164 Euro. "
                + "Der Konzern bleibt profitabel. Die Marge hält. Der Vorstand kauft zu.";
        String after = "Der Kurs steigt auf 140,31 EUR. Die UBS senkt ihr Ziel auf 164 Euro "
                + "und bleibt bei Kaufen. Der Konzern bleibt profitabel. Die Marge hält. "
                + "Der Vorstand kauft zu.";
        var lines = DeepDiveService.sentenceDiff(before, after);
        assertEquals("ctx", lines.get(0).kind());
        assertEquals(1, lines.get(0).oldLine());
        assertEquals(1, lines.get(0).newLine());
        assertEquals("del", lines.get(1).kind());
        assertEquals(2, lines.get(1).oldLine());
        assertEquals(0, lines.get(1).newLine());
        assertEquals("add", lines.get(2).kind());
        assertEquals(2, lines.get(2).newLine());
        assertTrue(lines.get(2).text().contains("bleibt bei Kaufen"));
        assertEquals("ctx", lines.get(3).kind());
        // The far tail is elided to one gap marker.
        assertEquals("gap", lines.get(4).kind());
        assertEquals(5, lines.size());
        // A fresh draft is pure adds; identical texts are silent.
        assertTrue(DeepDiveService.sentenceDiff(null, before).stream()
                .allMatch(l -> l.kind().equals("add")));
        assertEquals(0, DeepDiveService.sentenceDiff(before, before).size());
    }

    /**
     * The cross-section review routes an objection to the section that
     * carries the quoted claim — whitespace-tolerant, short quotes rejected.
     */
    @Test
    void consistencyObjectionsMapToTheirOwningSection() {
        String[] bodies = new String[DeepDiveService.SECTION_COUNT];
        bodies[4] = "Die jüngsten Revisionen zeigen weder Anhebungen noch Senkungen. "
                + "Der Konsens bleibt bei Kaufen.";
        bodies[6] = "Die jüngsten Analystenrevisionen zeigen 0 Aufwärtsbewegungen und "
                + "1 Herabstufung.";
        String line = "E: \"Die jüngsten Revisionen zeigen weder Anhebungen noch "
                + "Senkungen.\" — Ausblick trägt 0/1";
        assertEquals("Die jüngsten Revisionen zeigen weder Anhebungen noch Senkungen.",
                DeepDiveService.quotedSpan(line));
        assertEquals(4, DeepDiveService.owningSection(bodies,
                DeepDiveService.quotedSpan(line)));
        assertEquals(-1, DeepDiveService.owningSection(bodies, "zu kurz"));
    }

    /**
     * The dilution series is house arithmetic on legs already delivered:
     * equity (thousands EUR) ÷ book value per share = shares outstanding per
     * fiscal year, YoY change attached (user finding 2026-07-14).
     */
    @Test
    void shareCountSeriesComesFromEquityAndBookValue() {
        StringBuilder sb = new StringBuilder();
        DeepDiveService.appendShareCount(sb, DeepDiveChartsTest.deepDive(),
                new java.util.HashMap<>(java.util.Map.of("consors", 4)));
        String line = sb.toString();
        // 2024: equity 3 800 000 thousand EUR / book value 80.0 = 47.5M shares.
        assertTrue(line.contains("SHARES OUTSTANDING"), line);
        assertTrue(line.contains("2024: 47 500 000 shares"), line);
        assertTrue(line.contains("dilution"), line);
    }

    /** Venue codes read as names in prose; bare cryptic codes stay out. */
    @Test
    void venueNamesAreReadable() {
        assertEquals("NASDAQ", DeepDiveService.venueName("NMS"));
        assertEquals("XETRA", DeepDiveService.venueName("GER"));
        assertEquals("L&S Exchange", DeepDiveService.venueName("LSX"));
        assertEquals(null, DeepDiveService.venueName("XX"));
    }

    /** Weave splice residue ",." is tidied at assembly; decimals survive. */
    @Test
    void nasdaqLegReachesItsShelvesAndTheRegister() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Outlook Therapeutics, Inc.";
        m.ticker = "OTLK";
        m.usStats = new UsListingStats("OTLK", "Outlook Therapeutics, Inc. Common Stock",
                "NASDAQ-CM", "Health Care", "Biotechnology", 2.4e8, 16_625_884, Double.NaN,
                List.of(new UsListingStats.ShortInterestPoint("2026-06-30", 12_345_678,
                        9_000_000, 1.4)),
                new UsListingStats.InsiderActivity(3, 1, 5, 4, 250_000, -1_200_000),
                List.of(new UsListingStats.InsiderTrade("2026-06-25", "SMITH JOHN",
                        "Director", "Buy", "buy", 100_000, 0.83, 450_000)),
                new UsListingStats.InstitutionalOwnership(7.63, 24, 12_000_000, 19.0,
                        List.of(new UsListingStats.InstitutionalOwnership.Holder(
                                "Vanguard Group Inc", 1_234_567, 6544.0, "2026-03-31"))),
                new UsListingStats.AnalystRatings("Buy", 8, 1, 1, 0, 5.50, 12.00, 4.50),
                List.of(new UsListingStats.EarningsSurprise("Mar 2026", "2026-05-15",
                        -0.19, -0.25, 24.0)),
                Instant.now().getEpochSecond());

        String[] shelves = DeepDiveService.sectionMaterials(m);
        assertContains(shelves[DeepDiveService.SEC_ABOUT],
                "US LISTING (NASDAQ data) [1]: NASDAQ-CM, sector Health Care / Biotechnology");
        assertContains(shelves[DeepDiveService.SEC_FUNDAMENTALS],
                "US EARNINGS SURPRISES");
        assertContains(shelves[DeepDiveService.SEC_FUNDAMENTALS],
                "Mar 2026 [2026-05-15]: actual -0.19 USD vs consensus -0.25 USD (surprise +24.0%)");
        assertContains(shelves[DeepDiveService.SEC_VALUATION],
                "US STREET VIEW (NASDAQ consensus) [1]: mean rating 'Buy' (8 analysts); "
                        + "price target mean 5.50 USD (high 12.00, low 4.50) "
                        + "from a target panel of buy 1 / hold 1 / sell 0");
        assertContains(shelves[DeepDiveService.SEC_VALUATION],
                "US INSTITUTIONAL OWNERSHIP (13F filings, NASDAQ) [1]: "
                        + "7.63% of shares held institutionally by 24 holders (value 19.0M USD)");
        assertContains(shelves[DeepDiveService.SEC_VALUATION], "Vanguard Group Inc");
        assertContains(shelves[DeepDiveService.SEC_VALUATION], "(6.5M USD), as of 2026-03-31");
        assertContains(shelves[DeepDiveService.SEC_CATALYSTS], "US INSIDER TRADES (SEC Form 4");
        assertContains(shelves[DeepDiveService.SEC_CATALYSTS],
                "last 3 months: 3 buys / 1 sells (net 250 000 shares)");
        assertContains(shelves[DeepDiveService.SEC_CATALYSTS],
                "[2026-06-25] SMITH JOHN (Director): Buy 100 000 shares @ 0.83 USD");
        assertContains(shelves[DeepDiveService.SEC_CATALYSTS],
                "[2026-06-30] 12 345 678 shares short, days to cover 1.4");
        // Outlook reuses the street view like the Consorsbank consensus.
        assertContains(shelves[DeepDiveService.SEC_OUTLOOK], "US STREET VIEW");

        String register = DeepDiveService.sourcesSection(m, true);
        assertContains(register, "- [1] NASDAQ - US-Listing: Insider-Trades (Form 4)");
    }

    @Test
    void hedgeFundLegReachesTheValuationShelfAndTheRegister() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Outlook Therapeutics, Inc.";
        m.ticker = "OTLK";
        m.hedgeFunds = new HedgeFundPopularity("OTLK", 1649989L,
                List.of(new HedgeFundPopularity.QuarterPoint("2026-03-31", "Q1 2026",
                                9, 30845L, 3, 1, 1.22, false),
                        new HedgeFundPopularity.QuarterPoint("2026-06-30", "Q2 2026",
                                12, 41000L, 4, 1, 1.37, true)),
                List.of());

        String[] shelves = DeepDiveService.sectionMaterials(m);
        assertContains(shelves[DeepDiveService.SEC_VALUATION],
                "HEDGE-FUND POSITIONING (13F filings, Insider Monkey, quarterly) [1]:");
        assertContains(shelves[DeepDiveService.SEC_VALUATION],
                "Q1 2026: 9 funds (3 new / 1 closed), quarter-end price 1.22 USD");
        assertContains(shelves[DeepDiveService.SEC_VALUATION],
                "Q2 2026: 12 funds (4 new / 1 closed), quarter-end price 1.37 USD [quarter still filing]");

        String register = DeepDiveService.sourcesSection(m, true);
        assertContains(register, "- [1] Insider Monkey - Hedgefonds-Positionierung (13F-Quartalskurve)");
    }

    @Test
    void marketBeatLegFeedsShelvesRegisterAndActionsTable() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Outlook Therapeutics, Inc.";
        m.ticker = "OTLK";
        m.analystActions = new AnalystActions("Moderate Buy", 5.50, "USD",
                List.of(new AnalystActions.Action(null, null, "2026-07-10", "Oppenheimer",
                                "T. Analyst", "Initiated Coverage", null, "Outperform",
                                Double.NaN, 12.00, "USD"),
                        new AnalystActions.Action(null, null, "2026-06-20", "HC Wainwright",
                                null, "Boost Target", "Buy", "Buy", 4.00, 8.00, "USD")),
                new AnalystActions.UsShortStats(3_500_000, 3_100_000, 4_800_000,
                        1.4, 9.60, "2026-06-30"),
                0);

        String[] shelves = DeepDiveService.sectionMaterials(m);
        assertContains(shelves[DeepDiveService.SEC_VALUATION],
                "ANALYST ACTIONS (dated history, MarketBeat, newest first) [1]:");
        assertContains(shelves[DeepDiveService.SEC_VALUATION],
                "[2026-07-10] Oppenheimer: Initiated Coverage, rating Outperform, target 12.00 USD");
        assertContains(shelves[DeepDiveService.SEC_VALUATION],
                "[2026-06-20] HC Wainwright: Boost Target, rating Buy, target 4.00→8.00 USD");
        assertContains(shelves[DeepDiveService.SEC_CATALYSTS],
                "US SHORT STATS (MarketBeat) [1]: 9.60% of float short, 3 500 000 shares short "
                        + "(prior 3 100 000), days to cover 1.4, record date 2026-06-30");

        String table = DeepDiveService.actionsTable(m, DeepDiveService.sourceNumbers(m), true);
        assertContains(table, "| Datum | Haus | Aktion | Rating | Kursziel [1] |");
        assertContains(table, "| 2026-07-10 | Oppenheimer | Initiated Coverage | Outperform | 12,00 USD |");
        assertContains(table, "| 2026-06-20 | HC Wainwright | Boost Target | Buy | 4,00→8,00 USD |");

        String register = DeepDiveService.sourcesSection(m, true);
        assertContains(register,
                "- [1] MarketBeat - Analysten-Aktionshistorie, US-Short-Quote und Presse-Zeitleiste");
    }

    /**
     * The press timeline (the "Was war" leg): months of dated coverage reach
     * the Lage shelf SAMPLED — the oldest four verbatim, at most one entry per
     * month in between, the newest six verbatim, the skipped rest said
     * honestly — and the timeline ALONE earns MarketBeat its source number.
     */
    @Test
    void pressTimelineSamplesOldestMonthlyAndNewestWithElision() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "SAP SE";
        m.ticker = "SAP.DE";
        m.pressTimeline = new PressTimeline("SAP.DE", List.of(
                // newest-first as the provider delivers
                new PressTimeline.Entry("2026-07-14", "N1", "finance.yahoo.com"),
                new PressTimeline.Entry("2026-07-14", "N2", null),
                new PressTimeline.Entry("2026-07-13", "N3", "msn.com"),
                new PressTimeline.Entry("2026-07-10", "N4", "msn.com"),
                new PressTimeline.Entry("2026-07-08", "N5", "msn.com"),
                new PressTimeline.Entry("2026-07-05", "N6", "msn.com"),
                new PressTimeline.Entry("2026-06-28", "M1", "msn.com"),
                new PressTimeline.Entry("2026-06-20", "M2", "msn.com"),
                new PressTimeline.Entry("2026-06-05", "M3", "msn.com"),
                new PressTimeline.Entry("2026-05-18", "M4", "msn.com"),
                new PressTimeline.Entry("2026-05-02", "M5", "msn.com"),
                new PressTimeline.Entry("2026-04-20", "O1", "msn.com"),
                new PressTimeline.Entry("2026-04-10", "O2", "msn.com"),
                new PressTimeline.Entry("2026-03-15", "O3", "msn.com"),
                new PressTimeline.Entry("2026-03-01", "O4", "welt.de")));

        String lage = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
        assertContains(lage,
                "PRESS TIMELINE (dated coverage, MarketBeat — how the name got here) [1]:");
        // Oldest four verbatim, chronological, publisher in parens.
        assertContains(lage, "  - [2026-03-01] O4 (welt.de)");
        assertContains(lage, "  - [2026-03-15] O3");
        assertContains(lage, "  - [2026-04-10] O2");
        assertContains(lage, "  - [2026-04-20] O1");
        // Middle: one entry per month, the rest elided honestly.
        assertContains(lage, "  - [2026-05-02] M5");
        assertFalse(lage.contains("M4"), "second May entry is sampled away");
        assertContains(lage, "  - [2026-06-05] M3");
        assertFalse(lage.contains("M2"), "further June entries are sampled away");
        assertFalse(lage.contains("M1"), "further June entries are sampled away");
        assertContains(lage, "  - (3 further headline(s) elided — one per month kept)");
        // Newest six verbatim; a null publisher renders without parens.
        assertContains(lage, "  - [2026-07-05] N6 (msn.com)");
        assertContains(lage, "  - [2026-07-14] N2\n");
        assertContains(lage, "  - [2026-07-14] N1 (finance.yahoo.com)");
        // The timeline alone makes MarketBeat a source.
        assertContains(DeepDiveService.sourcesSection(m, true),
                "- [1] MarketBeat - Analysten-Aktionshistorie, US-Short-Quote und Presse-Zeitleiste");
    }

    /**
     * The judge design (2026-07-15, user mandate "alles rein, die KI sortiert
     * aus"): EVERY hazard is a candidate for every subject — the old exposure
     * gate survives only as a HINT on the lines the map knows; the judge's
     * survivors reach the Lage shelf under the "world" register entry.
     */
    @Test
    void hazardsBecomeJudgeCandidatesWithExposureHints() {
        var storm = new GlobalHazardsClient.Hazard(
                "STORM", "Hurrikan Delta, 120 kt (Atlantik)", "HIGH");
        var quake = new GlobalHazardsClient.Hazard(
                "QUAKE", "M6.2 near Tokyo — PAGER orange", "HIGH");
        var aviation = new GlobalHazardsClient.Hazard(
                "AVIATION", "EWR: Ground Stop bis 21:00 (Wetter)", "HIGH");

        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Exxon Mobil";
        m.ticker = "XOM";
        m.sectorEtfSymbol = "XLE";
        m.allHazards = List.of(storm, quake, aviation);
        List<String> candidates = DeepDiveService.worldSignalCandidateLines(m);
        // ALL three are candidates — nothing is pre-filtered away …
        assertEquals(3, candidates.size());
        // … but only the storm (XLE's mapped class) carries the hint.
        assertTrue(candidates.get(0).contains("Hurrikan Delta"), candidates.get(0));
        assertTrue(candidates.get(0).contains("[hint:"), candidates.get(0));
        assertFalse(candidates.get(1).contains("[hint:"), candidates.get(1));
        assertFalse(candidates.get(2).contains("[hint:"), candidates.get(2));

        // Judge survivors reach the shelf verbatim under the world register.
        m.worldSignalKeep = List.of(
                "World hazard [STORM, HIGH]: Hurrikan Delta, 120 kt (Atlantik)");
        String lage = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
        assertContains(lage, "WORLD SIGNALS");
        assertContains(lage, "Hurrikan Delta, 120 kt (Atlantik)");
        assertContains(DeepDiveService.sourcesSection(m, true), "Weltsignale");
    }

    /** No judged survivors = no world block and no register claim. */
    @Test
    void noSurvivorsMeansNoWorldBlock() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "SAP SE";
        m.ticker = "SAP.DE";
        m.sectorEtf = new MarketSnapshot("XLK", 231.10, 233.90, -1.20, 233.0, 229.0,
                0, Double.NaN, Double.NaN, "USD", "NYSEArca",
                Instant.now().getEpochSecond(), List.of());
        m.sectorEtfSymbol = "XLK";
        m.sectorDisplayName = "Tech";
        for (String shelf : DeepDiveService.sectionMaterials(m)) {
            assertFalse(shelf != null && shelf.contains("WORLD SIGNALS"),
                    "no survivors must mean no world block");
        }
        assertFalse(DeepDiveService.sourcesSection(m, true).contains("Weltsignale"));
    }

    @Test
    void sectorEtfMappingIsPriorityOrderedAndConservative() {
        // Health outranks chemicals: onvista's combined label is a health label.
        assertEquals("XLV", DeepDiveService.sectorEtfFor("Chemie / Pharma / Gesundheit").symbol());
        assertEquals("XLK", DeepDiveService.sectorEtfFor("Informationstechnologie", "Software").symbol());
        assertEquals("XLI", DeepDiveService.sectorEtfFor("Industrie / Maschinenbau").symbol());
        assertEquals("XLK", DeepDiveService.sectorEtfFor(null, null, "Technology", "Semiconductors").symbol());
        // No mapping = no sector block, never a wrong proxy.
        assertNull(DeepDiveService.sectorEtfFor("Konglomerat"));
        assertNull(DeepDiveService.sectorEtfFor((String) null));
    }

    @Test
    void sectorMacroContextExplainsEveryNumberAndFeedsBothShelves() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "SAP SE";
        m.ticker = "SAP.DE";
        m.snapshot = DeepDiveChartsTest.snapshot(); // day -1.77%
        m.sectorEtf = new MarketSnapshot("XLK", 231.10, 233.90, -1.20, 233.0, 229.0,
                0, Double.NaN, Double.NaN, "USD", "NYSEArca",
                java.time.Instant.now().getEpochSecond(), List.of());
        m.sectorEtfSymbol = "XLK";
        m.sectorDisplayName = "Tech";
        m.macroActualsToday = List.of(new TradingViewCalendarClient.TvEvent(
                "Core CPI", "CPI", "US", "USD", java.time.Instant.now(), 1,
                3.50, 3.30, 3.40, "%", "Jun", "tv"));
        m.macroDocket = List.of(new EconCalendarClient.EconEvent(
                "Core PPI", "US", java.time.Instant.now().getEpochSecond() + 3 * 86400,
                "High", "2.4%", "2.6%"));
        m.cbDecisions = List.of(new CentralBankCalendarClient.CbMeeting(
                "EZB", "Zinsentscheid", java.time.LocalDate.of(2026, 7, 23)));

        String[] shelves = DeepDiveService.sectionMaterials(m);
        String lage = shelves[DeepDiveService.SEC_SITUATION];
        assertContains(lage, "SECTOR & MACRO CONTEXT (verified) [2]:");
        // The sector move never stands naked: instrument move + gap + house arithmetic.
        assertContains(lage, "US sector proxy Tech (XLK): -1.20% today — the instrument moved "
                + "-1.77%, 0.6 points behind its sector (house arithmetic)");
        // The actual never stands naked: forecast + prior + house comparison.
        assertContains(lage, "Core CPI (US): actual 3.50% vs forecast 3.30 (prior 3.40) "
                + "— above forecast (house comparison)");
        String outlook = shelves[DeepDiveService.SEC_OUTLOOK];
        assertContains(outlook, "UPCOMING MACRO DOCKET");
        assertContains(outlook, "[2026-07-23] EZB: Zinsentscheid");
        assertContains(outlook, "Core PPI (US), high impact, forecast 2.4%");
        assertContains(DeepDiveService.sourcesSection(m, true),
                "Sektor- und Makro-Kontext - Yahoo Sektor-ETFs");
    }

    @Test
    void wireArchiveCarriesWasWarOntoTheRoomShelf() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "SAP SE";
        m.ticker = "SAP.DE";
        java.util.List<HeadlineRecord> history = new java.util.ArrayList<>();
        long day = 86400L;
        long start = java.time.Instant.now().getEpochSecond() - 120 * day;
        for (int i = 0; i < 12; i++) {
            history.add(new HeadlineRecord("c" + i, "Zeile " + i, null, start + i * 10 * day,
                    List.of(), List.of(), null, "SAP.DE", List.of(), null, List.of(),
                    null, null, null, false, List.of()));
        }
        m.wireHistory = history;

        String room = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ROOM];
        assertContains(room, "WIRE ARCHIVE (the house's own published lines about this subject, dated) [1]:");
        assertContains(room, "Zeile 0");
        assertContains(room, "Zeile 1");
        assertContains(room, "(4 further line(s) elided)");
        assertContains(room, "Zeile 11");
        assertFalse(room.contains("Zeile 3"), "elided middle stays out");
        // The archive alone earns the room its source number (no honest literal).
        assertContains(DeepDiveService.sourcesSection(m, true), "r/wallstreetbetsGER");
    }

    @Test
    void typesetterTablesBuildFromVerifiedLegsOnly() {
        DeepDiveService.Material m = fullMaterial();
        var nums = DeepDiveService.sourceNumbers(m);

        String peers = DeepDiveService.peerTable(m, nums, true);
        assertContains(peers, "| Unternehmen | Marktkap. | KGV | Dividendenrendite [4] |");
        assertContains(peers, "| KSB Vz | 1,5 Mrd. EUR | 11,9 |");

        // No US street band → no scenario table (never invented anchors).
        assertNull(DeepDiveService.scenarioTable(m, nums, true));

        m.usStats = new UsListingStats("RHM", null, null, null, null, Double.NaN, -1,
                Double.NaN, List.of(), null, List.of(), null,
                new UsListingStats.AnalystRatings("Buy", 8, 1, 1, 0, 5.50, 12.00, 4.50),
                List.of(), 0);
        var nums2 = DeepDiveService.sourceNumbers(m);
        String scenario = DeepDiveService.scenarioTable(m, nums2, true);
        assertContains(scenario, "| Szenario | Anker | Kursziel [8] |");
        assertContains(scenario, "| Bull | Street-Hoch | 12,00 USD |");
        assertContains(scenario, "| Basis | Konsens | 5,50 USD |");
        assertContains(scenario, "| Bear | Street-Tief | 4,50 USD |");
        // EUR price vs USD targets: the distance column must NOT appear.
        assertFalse(scenario.contains("Abstand"));
    }

    @Test
    void assemblyKeepsTableBlocksAtomicAndLiftsTheirMarkers() {
        String[] bodies = new String[DeepDiveService.SECTION_COUNT];
        bodies[4] = "Die Peers zahlen weniger für denselben Umsatz [4].\n\n"
                + "| Unternehmen | KGV [4] |\n"
                + "|---|---|\n"
                + "| KSB Vz | 11,9 |";
        String report = DeepDiveService.assemble(DeepDiveService.SECTIONS_DE, bodies, true);
        // Rows survive as their own lines, cell content intact.
        assertContains(report, "| Unternehmen | KGV |");
        assertContains(report, "|---|---|");
        assertContains(report, "| KSB Vz | 11,9 |");
        // The marker left the table and sits on the heading.
        assertContains(report, "## Bewertung und Wettbewerb [4]");
        assertFalse(report.contains("KGV [4]"));
    }

    @Test
    void assemblyTidiesPunctuationSplices() {
        String[] bodies = new String[DeepDiveService.SECTION_COUNT];
        bodies[2] = "Die UBS behielt die Einstufung bei,. Der Kurs steigt um 2,5 %.";
        String report = DeepDiveService.assemble(DeepDiveService.SECTIONS_DE, bodies, true);
        assertTrue(report.contains("bei. Der Kurs steigt um 2,5 %."));
        assertTrue(!report.contains(",."));
    }

    @Test
    void materialBudgetsScaleWithTheResolvedWindow() {
        // The char budgets are the 8k arithmetic times the window factor —
        // an 8k machine keeps exactly the historical values, bigger windows
        // buy proportionally fuller shelves (capped at 3x), and an odd
        // window between tiers never scales partially.
        try {
            DeepDiveService.windowTokens = 8192;
            assertEquals(6200, DeepDiveService.scaled(6200));
            DeepDiveService.windowTokens = 12288; // between tiers → still 1x
            assertEquals(6200, DeepDiveService.scaled(6200));
            DeepDiveService.windowTokens = 16384;
            assertEquals(12400, DeepDiveService.scaled(6200));
            assertEquals(1000, DeepDiveService.scaled(500));
            DeepDiveService.windowTokens = 24576;
            assertEquals(18600, DeepDiveService.scaled(6200));
            DeepDiveService.windowTokens = 1 << 20; // absurd window → 3x cap
            assertEquals(18600, DeepDiveService.scaled(6200));
        } finally {
            DeepDiveService.windowTokens = 8192;
        }
    }

    /** The multi-year press history rides the Lage shelf with its own register leg. */
    @Test
    void pressHistoryRidesTheSituationShelf() {
        DeepDiveService.Material m = fullMaterial();
        m.pressHistory = List.of(new RawNewsItem("h1",
                "Rheinmetall verdoppelt Munitionskapazität", "Handelsblatt", null,
                java.time.Instant.parse("2024-03-14T09:00:00Z"), List.of()));
        String[] shelves = DeepDiveService.sectionMaterials(m);
        assertTrue(shelves[DeepDiveService.SEC_SITUATION].contains("PRESS HISTORY (multi-year"));
        assertTrue(shelves[DeepDiveService.SEC_SITUATION]
                .contains("[2024-03-14] Rheinmetall verdoppelt Munitionskapazität · Handelsblatt"));
    }

    /** Archive intake: a history entry's verified fact lines ride indented under it. */
    @Test
    void pressHistoryCarriesItsVerifiedFactLines() {
        DeepDiveService.Material m = fullMaterial();
        m.pressHistory = List.of(new RawNewsItem("h1",
                "Rheinmetall verdoppelt Munitionskapazität", "Handelsblatt",
                "https://example.org/rhm-munition",
                java.time.Instant.parse("2024-03-14T09:00:00Z"), List.of()));
        m.factNotes = Map.of("https://example.org/rhm-munition",
                "- 2024-03-14 Munitionskapazität wird verdoppelt");
        String[] shelves = DeepDiveService.sectionMaterials(m);
        assertTrue(shelves[DeepDiveService.SEC_SITUATION]
                .contains("      - 2024-03-14 Munitionskapazität wird verdoppelt"));
    }

    /** The first-party IR archive rides fundamentals in full, the outlook future-only. */
    @Test
    void irArchiveRidesFundamentalsAndOutlookShelves() {
        DeepDiveService.Material m = fullMaterial();
        m.irEntries = List.of(
                new CompanyPressScout.IrEntry("Quartalsmitteilung Q1 2026", "2026-04-30", "u1"),
                new CompanyPressScout.IrEntry("Hauptversammlung", "2099-05-14", "u2"));
        String[] shelves = DeepDiveService.sectionMaterials(m);
        assertTrue(shelves[DeepDiveService.SEC_FUNDAMENTALS].contains("IR ARCHIVE (first-party"));
        assertTrue(shelves[DeepDiveService.SEC_FUNDAMENTALS].contains("[2026-04-30] Quartalsmitteilung"));
        assertTrue(shelves[DeepDiveService.SEC_OUTLOOK].contains("IR CALENDAR (first-party"));
        assertTrue(shelves[DeepDiveService.SEC_OUTLOOK].contains("[2099-05-14] Hauptversammlung"));
        assertFalse(shelves[DeepDiveService.SEC_OUTLOOK].contains("Quartalsmitteilung Q1 2026"));
    }

    // -- the four-phase intake's pure helpers (Einarbeiten/Aufarbeiten) --

    /** Chunking is logistics: sentence-friendly cuts, nothing lost, order kept. */
    @Test
    void chunkTextSplitsAtSentenceBoundariesAndLosesNothing() {
        String text = ("Der Umsatz stieg deutlich. " .repeat(40)).strip();
        List<String> parts = DeepDiveService.chunkText(text, 300);
        assertTrue(parts.size() > 1, "long text must split");
        for (String part : parts) {
            assertTrue(part.length() <= 300, "part over the chunk budget: " + part.length());
        }
        String rejoined = String.join(" ", parts).replaceAll("\\s+", " ");
        assertEquals(text.replaceAll("\\s+", " "), rejoined, "chunking must not lose text");
    }

    @Test
    void chunkTextKeepsAShortTextWhole() {
        assertEquals(List.of("Kurzer Text."), DeepDiveService.chunkText("Kurzer Text.", 300));
        assertTrue(DeepDiveService.chunkText("   ", 300).isEmpty());
    }

    /** Only "- " lines are facts; NONE and prose stay out. */
    @Test
    void parseFactLinesKeepsOnlyDashLines() {
        List<String> lines = DeepDiveService.parseFactLines(
                "- 2026-07-15 Umsatz 1,2 Mrd. EUR\nNONE\nDazu eine Erklärung.\n- Vorstand kauft Aktien\n- x");
        assertEquals(List.of("- 2026-07-15 Umsatz 1,2 Mrd. EUR", "- Vorstand kauft Aktien"), lines);
        assertTrue(DeepDiveService.parseFactLines("NONE").isEmpty());
        assertTrue(DeepDiveService.parseFactLines(null).isEmpty());
    }

    /**
     * The ledger dedupe merges only NORMALIZED-EQUAL fact lines: the kept
     * line collects the confirming block's marker, the duplicate drops out of
     * its block - and a conflicting figure is NOT a duplicate, it stands.
     */
    @Test
    void dedupeFactLinesMergesDuplicatesAndKeepsConflicts() {
        String a = "  -[7] [2026-07-15 09:00] Umsatzmeldung · Reuters\n"
                + "      - Umsatz stieg auf 1,2 Mrd. EUR\n"
                + "      - Marge bei 8 %\n";
        String b = "  -[9] [2026-07-15 10:00] Umsatz bestätigt · dpa\n"
                + "      - Umsatz stieg auf 1,2 Mrd. EUR\n"
                + "      - Marge bei 9 %\n";
        List<String> out = DeepDiveService.dedupeFactLines(List.of(a, b));
        assertEquals(2, out.size());
        assertTrue(out.get(0).contains("- Umsatz stieg auf 1,2 Mrd. EUR [9]"),
                "kept line must collect the confirming marker:\n" + out.get(0));
        assertFalse(out.get(1).contains("Umsatz stieg"), "duplicate must drop out of its block");
        assertTrue(out.get(1).contains("- Marge bei 9 %"), "a conflicting figure is no duplicate");
        assertTrue(out.get(0).contains("- Marge bei 8 %"));
    }

    private static int occurrences(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    /**
     * The live feed shows diff sentences VERBATIM — the 240-char journal clip
     * must never reach the sentence diff itself (the journal channel clips
     * its own copy at emission).
     */
    @Test
    void sentenceDiffNeverClipsLongSentences() {
        String longSentence = "Die Gesellschaft meldet für das abgelaufene Quartal "
                + "einen Umsatz von 4,82 Milliarden Euro bei einer bereinigten "
                + "operativen Marge von 11,3 Prozent und hebt zugleich die "
                + "Jahresprognose für den freien Mittelzufluss auf mindestens "
                + "1,1 Milliarden Euro an, während der Auftragsbestand mit "
                + "9,7 Milliarden Euro einen neuen Höchststand erreicht und die "
                + "Nettoverschuldung auf das 1,4-Fache des EBITDA sinkt.";
        var lines = DeepDiveService.sentenceDiff("", longSentence);
        assertEquals(1, lines.size());
        assertEquals("add", lines.get(0).kind());
        assertEquals(longSentence.replaceAll("\\s+", " "), lines.get(0).text());
        assertTrue(lines.get(0).text().length() > 300);
    }

    /**
     * The live view's margin-note anchor: the 1-based paragraph carrying the
     * quote, whitespace-tolerant; short or absent quotes stay unanchored (0)
     * rather than pinned to the wrong spot.
     */
    @Test
    void paragraphOfQuoteAnchorsWhitespaceTolerantOrNotAtAll() {
        String body = "Der Konzern wächst zweistellig. Die Marge hält bei 11 Prozent.\n\n"
                + "Die UBS senkt ihr Kursziel auf 164 Euro und bleibt bei Kaufen.\n\n"
                + "Der Vorstand kauft eigene Aktien zu.";
        assertEquals(2, DeepDiveService.paragraphOfQuote(body,
                "senkt ihr  Kursziel auf\n164 Euro"));
        assertEquals(3, DeepDiveService.paragraphOfQuote(body,
                "kauft eigene Aktien zu"));
        // Too short to be unique, or simply not standing: unanchored.
        assertEquals(0, DeepDiveService.paragraphOfQuote(body, "Die Marge"));
        assertEquals(0, DeepDiveService.paragraphOfQuote(body,
                "Dieser Satz steht nirgendwo im Text."));
        assertEquals(0, DeepDiveService.paragraphOfQuote(null, "kauft eigene Aktien zu"));
        assertEquals(0, DeepDiveService.paragraphOfQuote(body, null));
    }

    private static void assertContains(String brief, String needle) {
        assertTrue(brief.contains(needle),
                "missing: \"" + needle + "\"\n---\n" + brief);
    }
}
