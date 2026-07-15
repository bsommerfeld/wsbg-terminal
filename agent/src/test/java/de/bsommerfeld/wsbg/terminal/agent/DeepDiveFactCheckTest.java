package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic examiner — the mechanical half of the supervisory review.
 * These tests pin the exact failure classes of the SAP run dd-e6f0d98e
 * (2026-07-13): a spliced figure ("33,93 P/E" from another year), a leaked
 * {@code <<<WITH} sentinel, a torn sentence, verbatim repeats, and off-section
 * source markers — every one of them must now be a concrete objection.
 */
class DeepDiveFactCheckTest {

    @Test
    void unitAbbreviationPeriodsAreNotSentenceEnds() {
        // Live SAP run 2026-07-14: the split after "Mrd." tore "3,15 Mrd. EUR"
        // (and its bold span) across the deterministic paragraph break.
        List<String> s = DeepDiveFactCheck.sentences(
                "Der Nettogewinn stieg von **3,15 Mrd. EUR (2024) auf 7,33 Mrd. EUR** (2025e). "
                        + "Die F&E-Ausgaben erreichten ca. 6,63 Mrd. EUR. Das trägt die These.");
        assertEquals(3, s.size(), s.toString());
        assertTrue(s.get(0).contains("3,15 Mrd. EUR (2024) auf 7,33 Mrd. EUR"), s.toString());
        // A word merely ENDING in an abbreviation's letters stays a sentence end.
        List<String> t = DeepDiveFactCheck.sentences(
                "Das zeigt der Bericht des Reviews. Danach folgt mehr.");
        assertEquals(2, t.size(), t.toString());
    }

    /** ROOT-formatted material, the way our block renderers emit it. */
    private static final String MATERIAL = """
            MARKET (verified) [1]: 138.28 EUR, day +0.50%, day range 137.10-139.00
            KEY FIGURES BY FISCAL YEAR (verified, 'e' = consensus estimate) [4]:
              2024: EPS 2.68, dividend 2.20, P/E 33.93, EBIT margin 22.10%
              2025e: EPS 6.14, dividend 2.35, P/E 19.80, EBIT margin 28.79%
            BALANCE SHEET (verified, thousands EUR) [4]:
              2025: turnover 36 800 000, net income 7 330 000
            ANALYSTS (verified) [4]: 29 covering; consensus target 202.50 EUR (+45.9% vs current)
            UPCOMING EVENTS (verified) [4]: 2026-07-24 SAP SE: Bericht 2. Quartal 2026
            """;

    private static List<DeepDiveFactCheck.Objection> inspect(String draft) {
        return DeepDiveFactCheck.inspect(draft, MATERIAL, Set.of(1, 4), true);
    }

    private static boolean has(List<DeepDiveFactCheck.Objection> objections,
            DeepDiveFactCheck.Objection.Kind kind) {
        return objections.stream().anyMatch(o -> o.kind() == kind);
    }

    private static final String PAD = " Der Konzern bleibt nach den vorliegenden Zahlen"
            + " der maßgebliche Anbieter seines Segments und wird von 29 Analysten"
            + " begleitet [4].";

    @Test
    void cleanDraftPasses() {
        String draft = "Der Kurs notiert bei 138,28 EUR und damit 0,5 % über dem Vortag [1]. "
                + "Die Analysten rufen im Konsens ein Kursziel von 202,50 EUR auf, was "
                + "+45,9 % Potenzial entspricht [4]." + PAD;
        assertEquals(List.of(), inspect(draft));
    }

    /**
     * Run 8's reward hack: values spelled out as German number words are
     * invisible to the figure check — the model systematically evaded
     * objections that way ("vierundsiebzig Komma fünf Prozent" for 47,5 %,
     * a 2027 date written out as 2026). Spelled figures, spelled decimals and
     * spelled date days are HARD findings now.
     */
    @Test
    void spelledOutNumbersAreHardFindings() {
        assertTrue(has(inspect("Der Kurs liegt vierundsiebzig Komma fünf Prozent unter dem "
                        + "Hoch, sagt das Material." + PAD),
                DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
        assertTrue(has(inspect("Der Bericht erscheint am vierundzwanzig. Juli "
                        + "zweitausendsechsundzwanzig, so der Kalender." + PAD),
                DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
        assertTrue(has(inspect("Ein Beitrag vom dreizehnten Juli nennt ein Kursziel." + PAD),
                DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
        assertTrue(has(inspect("Die Perioden von Zwanzig und Fünfzig zeigen Schwäche." + PAD),
                DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
        assertTrue(inspect("Der Kurs liegt vierundsiebzig Komma fünf Prozent unter dem Hoch."
                + PAD).stream().anyMatch(DeepDiveFactCheck.Objection::hard));
    }

    /**
     * TWIN sentences spelling out the same words must EACH be a finding —
     * needle-anchored sentence lookup flagged only the first occurrence, and
     * the surviving twin sailed through the surgery (live run 9: two
     * "dreizehnten Juli zweitausendsechsundzwanzig" sentences, one cut).
     */
    @Test
    void twinSpelledNumberSentencesAreBothFindings() {
        String draft = "Weiterhin wurde am dreizehnten Juli zweitausendsechsundzwanzig die "
                + "positive Entwicklung des Kurses hervorgehoben, da die Stimmung drehte. "
                + "Die Affen signalisierten am dreizehnten Juli zweitausendsechsundzwanzig, "
                + "dass der Sektor grün wird und die Stimmung positiv bleibt." + PAD;
        long findings = inspect(draft).stream()
                .filter(o -> o.kind() == DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS)
                .count();
        assertEquals(2, findings);
        // Surgery on those findings leaves NO spelled date behind.
        String cut = DeepDiveFactCheck.removeOffendingSentences(draft,
                inspect(draft).stream().filter(DeepDiveFactCheck.Objection::hard).toList());
        assertFalse(cut.contains("dreizehnten"), cut);
    }

    /**
     * German dot-grouped integers PARSE and get checked — the old blanket
     * "multiple dots = date fragment" skip let an unparseable "1.243.743"
     * sail past the figure check entirely (live run 10: Tradegate volume
     * misstated by 10x, unflagged). Dotted dates stay exempt.
     */
    @Test
    void dotGroupedGermanIntegersAreCheckedAgainstTheMaterial() {
        String draft = "Es wurden 1.243.743 Aktien gehandelt, ein deutlich erhöhter Umsatz."
                + PAD;
        List<DeepDiveFactCheck.Objection> objections = DeepDiveFactCheck.inspect(
                draft, "TRADING [4]: volume 124 374 shares", Set.of(4), true);
        assertTrue(has(objections, DeepDiveFactCheck.Objection.Kind.FIGURE));
        // The true value passes; a dotted date stays a date, not a figure.
        assertFalse(has(DeepDiveFactCheck.inspect(
                        "Es wurden 124.374 Aktien gehandelt, ein solider Umsatz." + PAD,
                        "TRADING [4]: volume 124 374 shares, as of 2026-07-14", Set.of(4), true),
                DeepDiveFactCheck.Objection.Kind.FIGURE));
    }

    /**
     * The wall-of-text repair is DETERMINISTIC typesetting now (the retired
     * STRUCTURE objection provably never converged — every weave re-emission
     * flattened the breaks again, 10+ rounds on one live section): paragraphs
     * beyond four sentences split mechanically at sentence boundaries into
     * ~3-sentence paragraphs, order and text kept verbatim.
     */
    @Test
    void splitLongParagraphsReflowsWallsOfText() {
        String wall = "Der Kurs steigt deutlich an. Die Analysten bleiben gespalten. "
                + "Der Konzern investiert weiter kräftig. Die Presse nennt keinen Anlass dafür. "
                + "Die Marge verbessert sich strukturell. Der Vorstand verkauft unterdessen Anteile.";
        String out = DeepDiveFactCheck.splitLongParagraphs(wall);
        String[] paras = out.split("\n\\s*\n");
        assertTrue(paras.length >= 2, out);
        for (String para : paras) {
            assertTrue(DeepDiveFactCheck.sentences(para).size() <= 4, para);
        }
        // Every sentence survives, in order (whitespace-normalized).
        assertEquals(wall.replaceAll("\\s+", " "), out.replaceAll("\\s+", " "));
        // Shaped text passes untouched, and the split is idempotent.
        String shaped = "Der Kurs steigt deutlich an. Die Analysten bleiben gespalten. "
                + "Der Konzern investiert weiter.\n\nDie Marge verbessert sich. "
                + "Der Vorstand verkauft unterdessen Anteile.";
        assertEquals(shaped, DeepDiveFactCheck.splitLongParagraphs(shaped));
        assertEquals(out, DeepDiveFactCheck.splitLongParagraphs(out));
    }

    /**
     * Torn source-list residue is scrubbed MECHANICALLY (digest-less sources
     * made the weave model paste raw list lines into the body — live OTLK run;
     * each used to burn an INTEGRITY objection+revision round): list-shaped
     * lines go entirely, sentence-LEADING markers are dropped (never
     * relocated), real prose survives.
     */
    @Test
    void tornSourceListLinesAreScrubbedMechanically() {
        String text = "Der Konzern wartet auf die FDA-Entscheidung [17].\n"
                + "[17] [17] Direktoren kauften vor FDA-Entscheidung.\n"
                + "- [19] [2026-07-12 09:26] Outlook Therapeutics Aktie: 29. Juli für "
                + "ONS-5010 · Börse Express\n"
                + "[2026-07-13 14:24] Outlook Therapeutics Aktie: 16 Prozent ohne "
                + "Volumen-Rückendeckung · Börse Express [16]\n"
                + "[22] [23] Trotz dieser starken Entwicklung bleibt das Volumen dünn.\n"
                + "\n"
                + "Der Handel blieb zuletzt ruhig [19].";
        String out = DeepDiveFactCheck.scrubSourceListLines(text);
        assertFalse(out.contains("Direktoren kauften"), out);
        assertFalse(out.contains("29. Juli für ONS-5010"), out);
        assertFalse(out.contains("Volumen-Rückendeckung"), out);
        // The sentence behind leading markers survives — markers dropped, not moved.
        assertTrue(out.contains("Trotz dieser starken Entwicklung bleibt das Volumen dünn."), out);
        assertFalse(out.contains("[22]"), out);
        assertFalse(out.contains("[23]"), out);
        // Real prose with its trailing markers stays verbatim.
        assertTrue(out.contains("Der Konzern wartet auf die FDA-Entscheidung [17]."), out);
        assertTrue(out.contains("Der Handel blieb zuletzt ruhig [19]."), out);
        // Clean text passes through untouched (same instance semantics).
        String clean = "Ein sauberer Satz steht hier [1].\n\nNoch einer [4].";
        assertEquals(clean, DeepDiveFactCheck.scrubSourceListLines(clean));
    }

    /** Small count words, ordinals and lookalike nouns stay legitimate prose. */
    @Test
    void countWordsAndLookalikesAreNotNumberFindings() {
        String draft = "Es gab vier Transaktionen von zwei Vorständen, und im zweiten "
                + "Quartal blieb die Achtung vor dem Elfmeter ein Bild aus einem anderen "
                + "Jahrhundert." + PAD;
        assertFalse(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
    }

    /**
     * The comma-splice escape (live report: "8,5" verbalized as "acht, fünf"):
     * a spelled decimal of two numeral words joined by a comma is a HARD
     * NUMBER_WORDS finding when a scale/currency word follows directly — that
     * scale word is the gate that keeps plain enumerations legitimate.
     */
    @Test
    void commaSplicedSpelledDecimalWithScaleWordIsAFinding() {
        String draft = "GMS Ventures verkaufte am 02. Juli 2026 acht, fünf Millionen "
                + "Aktien [45]." + PAD;
        List<DeepDiveFactCheck.Objection> objections = inspect(draft);
        assertTrue(has(objections, DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS),
                String.valueOf(objections));
        assertTrue(objections.stream()
                .filter(o -> o.kind() == DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS)
                .allMatch(DeepDiveFactCheck.Objection::hard));
        assertTrue(has(inspect("Der Erlös lag bei acht, fünf Prozent des Umsatzes." + PAD),
                DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
    }

    /** An enumeration of count words WITHOUT a scale word right after stays prose. */
    @Test
    void plainCountWordEnumerationsAreNotCommaDecimalFindings() {
        String draft = "Am Ende erzielten sie acht, fünf und drei Punkte in den "
                + "jeweiligen Testreihen des Vorstands." + PAD;
        assertFalse(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
    }

    /**
     * The last spelled-number escape hatch: a SINGLE small count word directly
     * before a scale/currency word ("acht Millionen") used to slip through the
     * small-word exemption — with the scale word it IS a value and a HARD
     * finding. Teens ("dreizehn Prozent") are single atoms too and must close
     * with the same gate.
     */
    @Test
    void smallCountWordBeforeScaleWordIsAFinding() {
        for (String draft : List.of(
                "Der Konzern verkaufte acht Millionen Aktien im Quartal." + PAD,
                "Die Marge sank um dreizehn Prozent gegenüber dem Vorjahr." + PAD,
                "Das Programm kostet zwölf Milliarden Euro über die Laufzeit." + PAD)) {
            List<DeepDiveFactCheck.Objection> objections = inspect(draft);
            assertTrue(has(objections, DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS), draft);
            assertTrue(objections.stream()
                    .filter(o -> o.kind() == DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS)
                    .allMatch(DeepDiveFactCheck.Objection::hard), draft);
        }
    }

    /**
     * The scale-word gate must not over-reach: enumerations without a scale
     * word stay prose, and the connective/article lookalikes ("und", "ein")
     * before a currency word are ordinary German, not counts.
     */
    @Test
    void countWordsWithoutScaleWordAndConnectivesStayProse() {
        assertFalse(has(inspect("Es meldeten sich acht Analysten und drei Vorstände "
                        + "zu den Aussichten des Konzerns." + PAD),
                DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
        assertFalse(has(inspect("Die Debatte um Forschung und Euro bleibt für den "
                        + "Konzern ohne Folgen." + PAD),
                DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
        assertFalse(has(inspect("Der Bericht nennt ein Prozent-Ziel, aber keine Frist "
                        + "für dessen Umsetzung." + PAD),
                DeepDiveFactCheck.Objection.Kind.NUMBER_WORDS));
    }

    /** A word truncated into an ellipsis is a torn source snippet (run 8: "ändern mu…."). */
    @Test
    void truncatedWordEllipsisIsAFinding() {
        String draft = "SAP muss Regeln für Wartung und Support weltweit ändern mu…. "
                + "Der Konzern bleibt gelassen." + PAD;
        assertTrue(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.INTEGRITY));
    }

    @Test
    void scaledFiguresMatchAcrossUnits() {
        // 36,8 Mrd. EUR vs material "36 800 000" (thousands): scale 1e6 bridge.
        String draft = "Der Umsatz erreichte zuletzt 36,8 Mrd. EUR bei einem Nettogewinn "
                + "von 7,33 Mrd. EUR, womit das Geschäft profitabel skaliert [4]." + PAD;
        assertEquals(List.of(), inspect(draft));
    }

    @Test
    void splicedFigureIsAFinding() {
        // The SAP chimera: 28,79 % is the 2025e margin, but "33,93" beside a
        // "%"-free P/E claim for 2025 is 2024's P/E — the VALUE exists in the
        // material, so the examiner accepts it; what must fail is a figure the
        // material never carried at all:
        String draft = "Die EBIT-Marge liegt bei 28,79 % und verbessert sich auf "
                + "31,47 % im Folgejahr [4]." + PAD;
        List<DeepDiveFactCheck.Objection> objections = inspect(draft);
        assertTrue(has(objections, DeepDiveFactCheck.Objection.Kind.FIGURE),
                "invented 31,47 must be a figure objection: " + objections);
        assertTrue(objections.stream().anyMatch(o -> o.quote().contains("31,47")),
                "objection must quote the offending sentence");
    }

    @Test
    void unknownDateIsAFinding() {
        String draft = "Die Zahlen zum zweiten Quartal kommen am 23. Juli 2026 und "
                + "entscheiden über die weitere Richtung [4]." + PAD;
        List<DeepDiveFactCheck.Objection> objections = inspect(draft);
        assertTrue(has(objections, DeepDiveFactCheck.Objection.Kind.DATE),
                "23. Juli 2026 is not in the material (the 24th is): " + objections);
    }

    @Test
    void knownDateInGermanFormMatchesIsoMaterial() {
        String draft = "Der Bericht zum zweiten Quartal steht am 24. Juli 2026 an und "
                + "bildet die nächste harte Messlatte für das Papier [4]." + PAD;
        assertEquals(List.of(), inspect(draft));
    }

    @Test
    void sentinelResidueIsAFinding() {
        String draft = "Die Diskussion begann früh. <<<WITH\nDie Diskussion begann früh."
                + PAD;
        assertTrue(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.RESIDUE));
    }

    @Test
    void metaVocabularyIsAFinding() {
        String draft = "Das Faktenblatt listet für 2025 einen deutlichen Anstieg [4]." + PAD;
        assertTrue(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.RESIDUE));
    }

    @Test
    void tornParagraphIsAFinding() {
        String draft = "Der Konzern wächst stabil über die Jahre [4].\n\n"
                + "Die Prognose listet für 2025\n\n"
                + "Ein sauber beendeter Absatz steht hier [1]." + PAD;
        assertTrue(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.INTEGRITY));
    }

    @Test
    void trailingMarkersAfterPunctuationAreFine() {
        String draft = "Der Konzern wächst nach den vorliegenden Geschäftsjahren stabil"
                + " und hält seine Marge über der eigenen Historie. [1][4]" + PAD;
        assertFalse(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.INTEGRITY));
    }

    @Test
    void verbatimRepeatIsAFinding() {
        String s = "Die Diskussion auf r/wallstreetbetsGER begann mit einem ersten"
                + " Beitrag bei einem Kursniveau von 138,28 EUR [1].";
        assertTrue(has(inspect(s + " " + s + PAD), DeepDiveFactCheck.Objection.Kind.REPEAT));
    }

    @Test
    void offSectionMarkerIsAFinding() {
        String draft = "Die Shortquote bleibt niedrig und stützt das Bild [6]." + PAD;
        assertTrue(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.MARKER));
    }

    @Test
    void ordinalsYearsAndSmallCountsAreExemptFromTheFigureCheck() {
        String draft = "Im 2. Quartal 2026 stehen die Zahlen an, und 3 der Argumente"
                + " hängen an der Marge des Jahres 2025 [4]." + PAD;
        assertFalse(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.FIGURE));
    }

    @Test
    void surgeryRemovesOnlyTheOffendingSentence() {
        String keep = "Der Kurs notiert bei 138,28 EUR [1].";
        String bad = "Die Marge verbessert sich auf 31,47 % im Folgejahr [4].";
        String draft = keep + " " + bad + PAD;
        List<DeepDiveFactCheck.Objection> hard = inspect(draft).stream()
                .filter(DeepDiveFactCheck.Objection::hard).toList();
        assertFalse(hard.isEmpty());
        String cut = DeepDiveFactCheck.removeOffendingSentences(draft, hard);
        assertTrue(cut.contains("138,28 EUR"), cut);
        assertFalse(cut.contains("31,47"), cut);
    }

    @Test
    void residueScrubDropsTheLineNotTheSection() {
        String text = "Ein sauberer Satz steht hier [1].\n<<<WITH\nNoch ein sauberer Satz [4].";
        String scrubbed = DeepDiveFactCheck.scrubResidue(text);
        assertFalse(scrubbed.contains("<<<"), scrubbed);
        assertTrue(scrubbed.contains("Ein sauberer Satz"), scrubbed);
        assertTrue(scrubbed.contains("Noch ein sauberer Satz"), scrubbed);
    }

    @Test
    void adjacentNumbersNeverGlueIntoOneToken() {
        // Live-observed smoke run: "132.63, 128.17" (two true chart marks) was
        // read as ONE unparseable figure and cost a true sentence.
        String draft = "Die Marken liegen bei 138,28 EUR und 137,10 EUR, beide aus dem"
                + " Tagesbereich des Papiers, und stützen die aktuelle Einordnung [1]." + PAD;
        assertFalse(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.FIGURE));
    }

    @Test
    void pseudoCitationBracketsAreAFinding() {
        // Live-observed smoke run: the room section minted "[2026-07-13 138.28]"
        // from material timestamps instead of citing the room's real marker.
        String draft = "Der Kurs wurde am Nachmittag verortet [2026-07-13 138.28]." + PAD;
        assertTrue(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.RESIDUE));
    }

    @Test
    void sentenceSplitterSurvivesGermanDateOrdinals() {
        List<String> sentences = DeepDiveFactCheck.sentences(
                "Der Bericht zum 2. Quartal steht am 24. Juli 2026 an. Danach folgt der 22. Oktober 2026.");
        assertEquals(2, sentences.size(), String.valueOf(sentences));
        assertTrue(sentences.get(0).contains("24. Juli 2026"), sentences.get(0));
    }

    /* ---- markdown tables (licensed report content since 2026-07-14) ---- */

    private static final String TABLE_INTRO = "Die kommenden Termine bündeln, woran der "
            + "Markt das Papier als Nächstes misst [4].";
    private static final String TABLE = "| Termin | Ereignis |\n"
            + "|---|---|\n"
            + "| 24. Juli 2026 | Bericht 2. Quartal [4] |\n"
            + "| Konsensziel | 202,50 EUR mit +45,9 % Potenzial [4] |";

    /**
     * (a) A pipe row legitimately ends on '|' — a table block is typesetting,
     * never torn text; a material-faithful table draws no objection at all.
     */
    @Test
    void tableBlocksAreNotTornText() {
        String draft = TABLE_INTRO + "\n\n" + TABLE + "\n\nDanach folgt die Einordnung "
                + "der Termine im Gesamtbild." + PAD;
        assertEquals(List.of(), inspect(draft));
    }

    /** (b) splitLongParagraphs never splits inside a table and counts no row as a sentence. */
    @Test
    void splitLongParagraphsKeepsTableBlocksAtomic() {
        String table = "| Reihe | Wert |\n|---|---|\n| a | eins |\n| b | zwei |\n"
                + "| c | drei |\n| d | vier |\n| e | fünf |";
        String wall = "Der Kurs steigt deutlich an. Die Analysten bleiben gespalten. "
                + "Der Konzern investiert weiter kräftig. Die Presse nennt keinen Anlass. "
                + "Die Marge verbessert sich strukturell.";
        String out = DeepDiveFactCheck.splitLongParagraphs(wall + "\n\n" + table);
        assertTrue(out.contains(table), out); // table verbatim, all seven lines intact
        assertTrue(out.split("\n\\s*\n").length >= 3, out); // the prose wall still reflows
    }

    /**
     * (c) scrubSourceListLines never removes a table row — a row legitimately
     * carries markers and "·"-joined cell text without sentence-final
     * punctuation (exactly the shape the list scrub hunts in prose).
     */
    @Test
    void scrubSourceListLinesKeepsTableRows() {
        String text = "Der Konzern wartet auf die Zahlen [4].\n"
                + "| 2026-07-24 | Bericht Q2 · Consorsbank [4] |\n"
                + "[17] [17] Direktoren kauften vor der Entscheidung.";
        String out = DeepDiveFactCheck.scrubSourceListLines(text);
        assertTrue(out.contains("| 2026-07-24 | Bericht Q2 · Consorsbank [4] |"), out);
        assertFalse(out.contains("Direktoren kauften"), out);
    }

    /**
     * (d) The fidelity checks still SEE table cells: an off-material figure
     * inside a cell is a HARD finding, and surgery removes only that ROW.
     */
    @Test
    void offMaterialFigureInsideTableCellIsAFinding() {
        String draft = TABLE_INTRO + "\n\n"
                + "| Jahr | KGV |\n|---|---|\n| 2024 | 33,93 [4] |\n| 2025e | 21,55 [4] |"
                + "\n\n" + PAD.strip();
        List<DeepDiveFactCheck.Objection> objections = inspect(draft);
        assertTrue(has(objections, DeepDiveFactCheck.Objection.Kind.FIGURE),
                String.valueOf(objections));
        assertTrue(objections.stream().anyMatch(o -> o.quote().contains("21,55")),
                "objection must quote the offending row: " + objections);
        String cut = DeepDiveFactCheck.removeOffendingSentences(draft,
                objections.stream().filter(DeepDiveFactCheck.Objection::hard).toList());
        assertFalse(cut.contains("21,55"), cut);
        assertTrue(cut.contains("| 2024 | 33,93 [4] |"), cut); // innocent row survives
        // The true value passes cleanly.
        assertFalse(has(inspect(TABLE_INTRO + "\n\n"
                        + "| Jahr | KGV |\n|---|---|\n| 2024 | 33,93 [4] |\n| 2025e | 19,80 [4] |"
                        + "\n\n" + PAD.strip()),
                DeepDiveFactCheck.Objection.Kind.FIGURE));
    }

    /** (d, cont.) Off-material dates and off-section markers in cells are findings too. */
    @Test
    void offMaterialDateAndMarkerInsideTableCellsAreFindings() {
        String badDate = TABLE_INTRO + "\n\n| Termin | Ereignis |\n|---|---|\n"
                + "| 23. Juli 2026 | Bericht 2. Quartal [4] |\n\n" + PAD.strip();
        assertTrue(has(inspect(badDate), DeepDiveFactCheck.Objection.Kind.DATE));
        String badMarker = TABLE_INTRO + "\n\n| Termin | Ereignis |\n|---|---|\n"
                + "| 24. Juli 2026 | Bericht 2. Quartal [6] |\n\n" + PAD.strip();
        assertTrue(has(inspect(badMarker), DeepDiveFactCheck.Objection.Kind.MARKER));
    }

    /**
     * (e) A table row never pairs with a prose sentence in the repeat check —
     * a licensed table legitimately shares its tokens with the introducing
     * prose around it.
     */
    @Test
    void tableRowsAreExemptFromRepeatPairing() {
        String s = "Die Analysten rufen im Konsens ein Kursziel von 202,50 EUR mit "
                + "+45,9 % Potenzial auf [4].";
        String row = "| Analysten | Die Analysten rufen im Konsens ein Kursziel von "
                + "202,50 EUR mit +45,9 % Potenzial auf [4] |";
        String draft = s + "\n\n| Quelle | Aussage |\n|---|---|\n" + row + "\n\n" + PAD.strip();
        assertFalse(has(inspect(draft), DeepDiveFactCheck.Objection.Kind.REPEAT),
                String.valueOf(inspect(draft)));
    }

    /**
     * The house scissors (2026-07-15): over-max sections are cut at block/
     * sentence boundaries — a model LENGTH round provably grew the section
     * instead (live smoke: 4241 → 4481 chars).
     */
    @org.junit.jupiter.api.Test
    void cutToLengthCutsAtBoundariesAndKeepsTablesAtomic() {
        String s80 = "Dieser Satz traegt genau achtzig Zeichen Substanz fuer den "
                + "Schnitt am Rand hier."; // 80 chars
        String body = s80 + "\n\n" + s80 + "\n\n" + s80;
        // Budget for two blocks + separator: the third block falls off whole.
        String cut = DeepDiveFactCheck.cutToLength(body, 2 * 80 + 2);
        org.junit.jupiter.api.Assertions.assertEquals(s80 + "\n\n" + s80, cut);
        // Under the limit: untouched.
        org.junit.jupiter.api.Assertions.assertEquals(body,
                DeepDiveFactCheck.cutToLength(body, 10_000));
        // One giant prose block cuts at a sentence boundary, never empty.
        String giant = (s80 + " ").repeat(10).strip();
        String giantCut = DeepDiveFactCheck.cutToLength(giant, 200);
        org.junit.jupiter.api.Assertions.assertTrue(giantCut.length() <= 200);
        org.junit.jupiter.api.Assertions.assertTrue(giantCut.endsWith("hier."));
        // A table block stays atomic: it is dropped whole, never torn.
        String table = "| a | b |\n| --- | --- |\n| 1 | 2 |";
        String withTable = s80 + "\n\n" + table;
        String tableCut = DeepDiveFactCheck.cutToLength(withTable, 90);
        org.junit.jupiter.api.Assertions.assertEquals(s80, tableCut);
    }
}
