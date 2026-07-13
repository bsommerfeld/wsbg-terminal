package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.price.AnalystView;
import de.bsommerfeld.wsbg.terminal.core.price.FundFacts;
import de.bsommerfeld.wsbg.terminal.core.price.InstrumentFacts;
import de.bsommerfeld.wsbg.terminal.core.price.VenueStats;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KI-DD's completeness guarantee: EVERY data leg the collect step gathers
 * must actually reach the model — this test fills a {@link DeepDiveService.Material}
 * with every block and asserts each one's label AND a distinctive value in the
 * brief. A leg that silently falls out of {@code buildMaterial} fails here
 * (user mandate 2026-07-13: "validiere, ob die KI wirklich alle Daten sieht").
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
        m.fundFacts = new FundFacts("iShares Core MSCI World", 0.20, 1.28e11,
                "MSCI World NR USD", 4, "Beschreibung",
                List.of(new FundFacts.Holding("Nvidia", 5.42)), Instant.now().getEpochSecond());
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

        // Fund profile (ETF branch).
        assertContains(brief, "FUND [3]: iShares Core MSCI World");
        assertContains(brief, "TER 0.20%");
        assertContains(brief, "benchmark MSCI World NR USD");
        assertContains(brief, "Nvidia 5.4%");

        // Fundamentals: key-figure years incl. the estimate, balance sheet, boards.
        assertContains(brief, "KEY FIGURES BY FISCAL YEAR");
        assertContains(brief, "2026e:");
        assertContains(brief, "EPS 55.00");
        assertContains(brief, "PEG 0.90");
        assertContains(brief, "BALANCE SHEET (verified");
        assertContains(brief, "turnover 9 751 000");
        assertContains(brief, "R&D 380 000");
        assertContains(brief, "BOARDS (verified) [4]: Armin Papperger (Vorstand)");

        // Street: analyst distribution, trend, target, revisions, events.
        assertContains(brief, "ANALYSTS (verified) [4]: 27 covering");
        assertContains(brief, "19 buy / 3 overweight / 5 hold");
        assertContains(brief, "(3 months ago: 16/3/6/0/0)");
        assertContains(brief, "consensus target 1720.00 EUR (+73.4% vs current)");
        assertContains(brief, "recent revisions 4 up / 3 down");
        assertContains(brief, "UPCOMING EVENTS (verified) [4]:");
        assertContains(brief, "Bericht 2. Quartal 2026");

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
        assertContains(brief, "NEWS (verified, last 30 days):");
        assertContains(brief, "[8] ");
        assertContains(brief, "Rheinmetall gewinnt Großauftrag · WELT");

        // The room: anchor, evidence snippet, wire line.
        assertContains(brief, "ROOM EVIDENCE (r/wallstreetbetsGER, unverified) [9]:");
        assertContains(brief, "First mentioned");
        assertContains(brief, "Rheiner läuft, bin all-in");
        assertContains(brief, "WIRE LINES ALREADY PUBLISHED FOR THIS SUBJECT:");
        assertContains(brief, "Rheinmetall: Käfig feiert den Auftrag");
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
     * The sequential feed (user mandate 2026-07-13 "nacheinander reinreichen,
     * statt cappen"): the material splits into thematic packets along the
     * report skeleton, each block lands in ITS packet, and no single packet
     * approaches the context window.
     */
    @Test
    void materialSplitsIntoThreadOrderedPackets() {
        List<DeepDiveService.Packet> packets =
                DeepDiveService.buildPackets("Rheinmetall", fullMaterial(), false);
        assertTrue(packets.size() == 6, "expected 6 packets, got " + packets.size());

        assertTrue(packets.get(0).briefLabel().equals("Company and market"));
        assertTrue(packets.get(0).text().contains("MARKET (verified) [1]:"));
        assertTrue(packets.get(0).text().contains("official website https://www.rheinmetall.com"));
        assertTrue(packets.get(0).text().contains("Armin Papperger"));

        assertTrue(packets.get(1).briefLabel().equals("Fundamentals"));
        assertTrue(packets.get(1).text().contains("KEY FIGURES BY FISCAL YEAR"));
        assertTrue(packets.get(1).text().contains("BALANCE SHEET"));
        assertTrue(packets.get(1).text().contains("PEERS (verified)"));

        assertTrue(packets.get(2).briefLabel().equals("Street and insiders"));
        assertTrue(packets.get(2).text().contains("ANALYSTS (verified)"));
        assertTrue(packets.get(2).text().contains("INSIDER DEALINGS"));
        assertTrue(packets.get(2).text().contains("SHORT POSITIONS"));

        assertTrue(packets.get(3).briefLabel().equals("Chart and trading"));
        assertTrue(packets.get(3).text().contains("CHART-TECHNICAL READ"));
        assertTrue(packets.get(3).text().contains("PERFORMANCE (verified)"));
        assertTrue(packets.get(3).text().contains("TRADING (Tradegate"));

        assertTrue(packets.get(4).briefLabel().equals("News"));
        assertTrue(packets.get(4).text().contains("Rheinmetall gewinnt Großauftrag"));

        // The room comes LAST — the divergence read needs the fact layers first.
        assertTrue(packets.get(5).briefLabel().equals("The room"));
        assertTrue(packets.get(5).text().contains("ROOM EVIDENCE"));
        assertTrue(packets.get(5).text().contains("WIRE LINES ALREADY PUBLISHED"));

        for (DeepDiveService.Packet p : packets) {
            assertTrue(p.text().length() < 7100,
                    "packet '" + p.briefLabel() + "' too large: " + p.text().length());
        }
    }

    /** An unresolvable subject still grounds the draft on an honest identity stub. */
    @Test
    void emptyMaterialStillYieldsADraftPacket() {
        List<DeepDiveService.Packet> packets =
                DeepDiveService.buildPackets("Nirvana", new DeepDiveService.Material(), false);
        assertTrue(!packets.isEmpty());
        assertTrue(packets.get(0).briefLabel().equals("Company and market"));
        assertTrue(packets.get(0).text().contains("no verified company/market material"));
    }

    @Test
    void roomlessSubjectIsSaidHonestly() {
        DeepDiveService.Material m = fullMaterial();
        m.unit = null;
        m.evidenceCount = 0;
        String brief = DeepDiveService.buildMaterial("Rheinmetall", m);
        assertContains(brief, "(the room has not taken this subject up)");
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
        assertTrue(register.contains("- [8] WELT - \u201ERheinmetall gewinnt Großauftrag\u201C"), register);
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
     * The author's one-article-at-a-time read (user mandate 2026-07-13): articles
     * carry their full-text digests, and a digest-fat pool splits into SEVERAL
     * small news packets — each its own integrate pass, none anywhere near the
     * context window.
     */
    @Test
    void digestFatNewsSplitIntoSeveralSmallPackets() {
        DeepDiveService.Material m = fullMaterial();
        java.util.List<RawNewsItem> news = new java.util.ArrayList<>();
        java.util.Map<String, String> digests = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            String link = "https://example.org/artikel-" + i;
            news.add(new RawNewsItem("uuid-" + i, "Meldung Nummer " + i, "WELT",
                    link, Instant.now(), List.of(), null, null, false, null));
            digests.put(link, ("Kernfakt " + i + ": ").repeat(40)); // ~500 chars each
        }
        m.news = List.copyOf(news);
        m.digests = digests;

        List<DeepDiveService.Packet> packets = DeepDiveService.buildPackets("Rheinmetall", m, false);
        long newsPackets = packets.stream().filter(p -> p.briefLabel().startsWith("News")).count();
        assertTrue(newsPackets >= 2, "expected several news packets, got " + newsPackets);
        assertTrue(packets.stream().anyMatch(p -> p.briefLabel().equals("News (1/" + newsPackets + ")")),
                "part labels missing");
        // Every article (with its digest) still lands somewhere, in order.
        String joined = packets.stream().filter(p -> p.briefLabel().startsWith("News"))
                .map(DeepDiveService.Packet::text).reduce("", String::concat);
        for (int i = 0; i < 6; i++) {
            assertTrue(joined.contains("Meldung Nummer " + i), "article " + i + " missing");
            assertTrue(joined.contains("Kernfakt " + i + ":"), "digest " + i + " missing");
        }
        for (DeepDiveService.Packet p : packets) {
            assertTrue(p.text().length() < 4000 || !p.briefLabel().startsWith("News"),
                    "news packet too large: " + p.text().length());
        }
        // The room still comes last, after every news packet.
        assertTrue(packets.get(packets.size() - 1).briefLabel().equals("The room"));
    }

    /**
     * The routing hints must literally match the report's headings (a 4B must
     * not translate to route): German report -> German hints, and every hint
     * names only canonical section literals.
     */
    @Test
    void sectionHintsMatchTheReportHeadingsLiterally() {
        List<DeepDiveService.Packet> de =
                DeepDiveService.buildPackets("Rheinmetall", fullMaterial(), true);
        for (DeepDiveService.Packet p : de) {
            for (String hint : p.sectionsHint().split(", ")) {
                assertTrue(DeepDiveService.SECTIONS_DE.contains(hint),
                        "hint '" + hint + "' is not a German heading literal");
            }
        }
        List<DeepDiveService.Packet> en =
                DeepDiveService.buildPackets("Rheinmetall", fullMaterial(), false);
        for (DeepDiveService.Packet p : en) {
            for (String hint : p.sectionsHint().split(", ")) {
                assertTrue(DeepDiveService.SECTIONS_EN.contains(hint),
                        "hint '" + hint + "' is not an English heading literal");
            }
        }
    }

    /**
     * The structural gate (loss guard 2026-07-13): all seven canonical headings
     * must appear line-leading and in order — a renamed, reordered or truncated
     * pass is rejected and must never replace the standing report.
     */
    @Test
    void looksLikeReportEnforcesTheCanonicalHeadings() {
        String pad = "x".repeat(80) + "\n";
        StringBuilder good = new StringBuilder();
        for (String h : DeepDiveService.SECTIONS_DE) good.append("## ").append(h).append('\n').append(pad);
        assertTrue(DeepDiveService.looksLikeReport(good.toString(), DeepDiveService.SECTIONS_DE));

        // A missing tail section (the live-observed truncation) fails.
        StringBuilder truncated = new StringBuilder();
        for (String h : DeepDiveService.SECTIONS_DE.subList(0, 6)) {
            truncated.append("## ").append(h).append('\n').append(pad);
        }
        assertTrue(!DeepDiveService.looksLikeReport(truncated.toString(), DeepDiveService.SECTIONS_DE));

        // A renamed section fails even though the count is right.
        String renamed = good.toString().replace("## Der Raum", "## Fazit");
        assertTrue(!DeepDiveService.looksLikeReport(renamed, DeepDiveService.SECTIONS_DE));

        // Inline "## " occurrences do not count as headings (count fallback).
        String inline = ("prose ## not-a-heading " + pad).repeat(12);
        assertTrue(!DeepDiveService.looksLikeReport(inline, null));
    }

    /**
     * The marker gates (loss guards 2026-07-13): retention = every prior [n]
     * survives the revision; arrival = a marker-carrying packet leaves at least
     * one of its markers in the revised report.
     */
    @Test
    void markerGatesCatchDroppedMaterial() {
        assertTrue(DeepDiveService.retainsMarkers("a [1] b [2]", "b [2] c [1] d [3]"));
        assertTrue(!DeepDiveService.retainsMarkers("a [1] b [2]", "only [1] left"));

        DeepDiveService.Packet p = new DeepDiveService.Packet("News", "News", "Lage",
                "  - [8] Meldung\n");
        java.util.Set<Integer> valid = java.util.Set.of(1, 8);
        assertTrue(DeepDiveService.packetArrived(p, "der Bericht zitiert [8].", valid));
        assertTrue(!DeepDiveService.packetArrived(p, "der Bericht vergisst die Quelle.", valid));
        // A markerless packet (identity stub) has nothing to verify.
        DeepDiveService.Packet stub = new DeepDiveService.Packet("Company and market",
                "Profil & Markt", "Worum es geht", "(no verified company/market material)\n");
        assertTrue(DeepDiveService.packetArrived(stub, "irgendein Text", valid));

        // Anti-compression: a clearly shorter revision is a compressed pass.
        String prior = "y".repeat(4000);
        assertTrue(DeepDiveService.notShrunk(prior, "y".repeat(3900)));
        assertTrue(!DeepDiveService.notShrunk(prior, "y".repeat(3000)));
    }

    /** An over-budget packet splits at line boundaries, parts labeled and ordered. */
    @Test
    void oversizedPacketSplitsAtLineBoundaries() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 60; i++) text.append("Zeile ").append(i).append(" ").append("z".repeat(90)).append('\n');
        DeepDiveService.Packet p = new DeepDiveService.Packet("News", "News", "Lage", text.toString());
        List<DeepDiveService.Packet> parts = DeepDiveService.splitPacket(p, 2000);
        assertTrue(parts.size() >= 3, "expected several parts, got " + parts.size());
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            DeepDiveService.Packet part = parts.get(i);
            assertTrue(part.text().length() <= 2100, "part too large: " + part.text().length());
            assertTrue(part.briefLabel().equals("News (" + (i + 1) + "/" + parts.size() + ")"));
            joined.append(part.text());
        }
        assertTrue(joined.toString().equals(text.toString()), "split lost content");
        // A packet under budget returns untouched.
        assertTrue(DeepDiveService.splitPacket(p, 20_000).get(0) == p);
    }

    /**
     * The QA fact sheet carries every verifiable figure but none of the prose
     * legs — it exists so the adversarial pass can check the report's numbers.
     */
    @Test
    void factSheetCarriesFiguresButNoProse() {
        String sheet = DeepDiveService.factSheet(fullMaterial());
        assertTrue(sheet.contains("MARKET (verified) [1]:"), sheet);
        assertTrue(sheet.contains("KEY FIGURES BY FISCAL YEAR"), sheet);
        assertTrue(sheet.contains("consensus target 1720.00 EUR"), sheet);
        assertTrue(sheet.contains("SHORT POSITIONS"), sheet);
        assertTrue(sheet.contains("Rheinmetall gewinnt Großauftrag"), sheet);
        assertTrue(!sheet.contains("COMPANY PORTRAIT"), "portrait prose leaked into the sheet");
        assertTrue(!sheet.contains("Its comment:"), "TradingCentral prose leaked into the sheet");
        assertTrue(!sheet.contains("ROOM EVIDENCE"), "room prose leaked into the sheet");
        assertTrue(sheet.length() <= 3600, "sheet over its cap: " + sheet.length());
    }

    /** A talkative room yields SEVERAL chronological packets — the retelling needs the history. */
    @Test
    void talkativeRoomSplitsIntoSeveralPackets() {
        DeepDiveService.Material m = fullMaterial();
        SubjectUnit unit = new SubjectUnit("RHM", "Rheinmetall AG");
        for (int i = 0; i < 60; i++) {
            unit.addEvidence(new SubjectUnit.EvidenceRef("t" + i, "c" + i,
                    "Kommentar Nummer " + i + " " + "w".repeat(80), "reddit",
                    1_700_000_000L + i));
        }
        unit.addHeadline("Rheinmetall: Käfig feiert", "BULLISH");
        m.unit = unit;
        List<DeepDiveService.Packet> packets =
                DeepDiveService.buildPackets("Rheinmetall", m, false);
        List<DeepDiveService.Packet> room = packets.stream()
                .filter(p -> p.briefLabel().startsWith("The room")).toList();
        assertTrue(room.size() >= 2, "expected several room packets, got " + room.size());
        assertTrue(room.get(0).briefLabel().equals("The room (1/" + room.size() + ")"));
        // Chronology: an early comment sits in an earlier packet than a late one.
        String first = room.get(0).text();
        String last = room.get(room.size() - 1).text();
        assertTrue(first.contains("(continued)") == false && last.contains("(continued)"));
        assertTrue(last.contains("WIRE LINES ALREADY PUBLISHED"), "wire lines belong to the last chunk");
        for (DeepDiveService.Packet p : room) {
            assertTrue(p.text().length() < 2800, "room packet too large: " + p.text().length());
        }
        // The room still comes last overall.
        assertTrue(packets.get(packets.size() - 1).briefLabel().startsWith("The room"));
    }

    /**
     * The room speaks name AND ticker (user mandate 2026-07-13): roomBlocks
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

        String joined = String.join("", DeepDiveService.roomBlocks(
                List.of(tickerUnit, nameUnit), java.util.Map.of("room", 9)));
        assertTrue(joined.contains("bin long Outlook"), "the name unit's own mention is missing");
        assertTrue(joined.indexOf("Outlook läuft heiß") == joined.lastIndexOf("Outlook läuft heiß"),
                "a shared mention must ride exactly once");
        assertTrue(joined.contains("OTLK: Käfig wettet auf die FDA"), "wire lines from the union");
        assertTrue(joined.contains("[9]"), "the room's source marker");
    }

    /** The material plan marks done / THIS PASS / pending — the model's map of the work. */
    @Test
    void materialPlanNamesEveryPacketWithItsStatus() {
        List<DeepDiveService.Packet> packets =
                DeepDiveService.buildPackets("Rheinmetall", fullMaterial(), false);
        String plan = DeepDiveService.materialPlan(packets, 2, java.util.Set.of(0, 1));
        assertTrue(plan.startsWith("MATERIAL PLAN"), plan);
        assertTrue(plan.contains("1. Company and market [done]"), plan);
        assertTrue(plan.contains("2. Fundamentals [done]"), plan);
        assertTrue(plan.contains("3. Street and insiders [THIS PASS]"), plan);
        assertTrue(plan.contains("[pending]"), plan);
    }

    /**
     * The deterministic repetition scrub (live-observed with SAP 2026-07-13:
     * the edit protocol's INSERT drift planted the same sentence FOUR times and
     * the model's own cleanup DELETEs missed their anchors): exact repeats are
     * removed by the terminal — first occurrence wins, headings and the section
     * literals survive, short sentences are never touched.
     */
    @Test
    void dedupeRemovesRepeatedSentencesAndParagraphs() {
        String longSentence = "Die Unternehmensstruktur wird durch einen Vorstand unter Vorsitz"
                + " von Christian Klein und einen Aufsichtsrat unter Vorsitz von Pekka Juhani"
                + " Ala-Pietilä repräsentiert [4].";
        String report = "## Lage\n"
                + "Der Kurs stieg. " + longSentence + " " + longSentence + " " + longSentence + "\n\n"
                + "## Katalysatoren und Risiken\n"
                + longSentence + "\n\n"
                + "Der Bericht zum zweiten Quartal 2026 steht am 24. Juli 2026 an, und die"
                + " Zahlen entscheiden über die weitere Richtung des Papiers [4].\n\n"
                + "Der Bericht zum zweiten Quartal 2026 steht am 24. Juli 2026 an, und die"
                + " Zahlen entscheiden über die weitere Richtung des Papiers [4].\n\n"
                + "## Der Raum\n"
                + "(Dieser Abschnitt folgt mit dem nächsten Materialpaket.)";
        String deduped = DeepDiveService.dedupeRepeats(report);
        assertTrue(occurrences(deduped, "Unternehmensstruktur") == 1,
                "the quadrupled sentence must survive exactly once:\n" + deduped);
        assertTrue(occurrences(deduped, "24. Juli 2026 an") == 1,
                "the duplicated paragraph must survive exactly once:\n" + deduped);
        assertTrue(deduped.contains("## Lage") && deduped.contains("## Katalysatoren und Risiken")
                && deduped.contains("## Der Raum"), "headings must survive:\n" + deduped);
        assertTrue(deduped.contains("Der Kurs stieg."), "short distinct sentences survive");
        assertTrue(deduped.contains("(Dieser Abschnitt folgt mit dem nächsten Materialpaket.)"));

        // The placeholder may legitimately stand in SEVERAL sections at once.
        String twoPlaceholders = "## These\n(Dieser Abschnitt folgt mit dem nächsten Materialpaket.)\n\n"
                + "## Lage\n(Dieser Abschnitt folgt mit dem nächsten Materialpaket.)";
        assertTrue(occurrences(DeepDiveService.dedupeRepeats(twoPlaceholders),
                "(Dieser Abschnitt folgt") == 2, "placeholder literal is exempt from dedupe");
    }

    private static int occurrences(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static void assertContains(String brief, String needle) {
        assertTrue(brief.contains(needle),
                "brief is missing: \"" + needle + "\"\n---\n" + brief);
    }
}
