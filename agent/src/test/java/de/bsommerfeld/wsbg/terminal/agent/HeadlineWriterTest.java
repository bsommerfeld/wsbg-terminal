package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.Draft;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA behaviour of {@link HeadlineWriter} — no LLM, no network. */
class HeadlineWriterTest {








    @Test
    void reconcileSentiment_uses_the_lines_own_number_user_or_yahoo() {
        // The number is the line's own priceMovePercent — source-agnostic.
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.BEARISH,
                HeadlineWriter.reconcileSentiment(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.BULLISH, -3.2),
                "a −% line can't read BULLISH, whoever posted the number");
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.BULLISH,
                HeadlineWriter.reconcileSentiment(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.CAPITULATION, 4.0));
        // Loss porn: BEARISH/CAPITULATION with NO move of its own stays bearish —
        // we never override it with the instrument's (possibly green) day move.
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.CAPITULATION,
                HeadlineWriter.reconcileSentiment(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.CAPITULATION, null),
                "sharing losses is bearish even when the stock is up today");
        // Non-directional reads are left alone.
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.MIXED,
                HeadlineWriter.reconcileSentiment(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.MIXED, -2.0));
        // A tiny day-move (−0,3%) is NOT prominent enough to flip a bullish narrative
        // (the Smoke-5 "Micron steigt … feiern +20% seit Tief, BEARISH −0,3%" case).
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.BULLISH,
                HeadlineWriter.reconcileSentiment(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.BULLISH, -0.3),
                "sub-1.5% move must not flip the label");
    }

    // ---- publishUnit: the prod path after the #2 cutover ----

    private SubjectUnit instrumentUnit() {
        SubjectUnit u = new SubjectUnit("NOW", "ServiceNow");
        u.updateResolved("ServiceNow", "NOW", null, List.of());
        return u;
    }

    @Test
    void publishUnit_keys_on_unit_id_and_takes_ticker_from_the_unit() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        // The model's draft ticker is deliberately bogus — publishUnit must ignore
        // it and use the unit's resolver-validated ticker.
        Draft d = new Draft("ServiceNow läuft heiß", "FOMO", "NORMAL", "");

        assertTrue(w.publishUnit(instrumentUnit(), d));
        List<HeadlineRecord> byUnit = repo.getHeadlinesByClusterId("NOW");
        assertEquals(1, byUnit.size(), "saved under the unit id, not a cluster id");
        HeadlineRecord h = byUnit.get(0);
        assertEquals("NOW", h.tickerSymbol(), "ticker comes from the unit, never the model");
        assertEquals(1, h.subjects().size(), "name appears in line → glow subject kept");
        assertEquals("NOW", h.subjects().get(0).ticker());
        assertTrue(h.sourceCommentIds().isEmpty(),
                "slim compose output no longer carries model source ids — the unit is the evidence");
    }

    @Test
    void publishUnit_themeUnit_has_no_ticker_even_if_model_claims_one() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        SubjectUnit theme = new SubjectUnit("name:inflation", "Inflation"); // no ticker
        Draft d = new Draft("Deutsche Inflation sinkt auf 2,7%", "BULLISH", "NORMAL", "");
        assertTrue(w.publishUnit(theme, d));
        assertNull(repo.getHeadlinesByClusterId("name:inflation").get(0).tickerSymbol());
    }

    @Test
    void publishUnit_guards_identical_double_publish_for_the_same_unit() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        SubjectUnit u = instrumentUnit();
        Draft d = new Draft("ServiceNow läuft heiß", "FOMO", "NORMAL", "");
        assertTrue(w.publishUnit(u, d));
        assertFalse(w.publishUnit(u, d), "identical text within guard window skipped");
        assertEquals(1, repo.getHeadlinesByClusterId("NOW").size());
    }


    @Test
    void nearDuplicate_catchesUpdateSuffixAndLightRewords() {
        String base = "Microsoft Corporation: Die Affen verlagern Spielgeld von Rüstung in Software";
        // Same line re-emitted as an "-Update:" — the live duplicate pattern.
        assertTrue(HeadlineWriter.isNearDuplicate(base, base + " -Update:"));
        // One-word reword ("hat"→"hält").
        assertTrue(HeadlineWriter.isNearDuplicate(
                "Alphabet hat trotz Tech-Rout weiterhin Potenzial für die kommenden Jahre",
                "Alphabet hält trotz Tech-Rout weiterhin Potenzial für die kommenden Jahre"));
        // Genuinely different angles on the same subject must NOT be flagged.
        assertFalse(HeadlineWriter.isNearDuplicate(
                "Microsoft: Die Affen spekulieren mit Short-Positionen wegen Software-Unsicherheit",
                "Microsoft: Die Affen verlagern Spielgeld von Rüstung in Pharma und SAP"));
    }

    @Test
    void publishUnit_skipsNearDuplicateUpdate() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        SubjectUnit u = instrumentUnit();
        Draft first = new Draft("ServiceNow verlagert Kapital in Software", "MIXED", "NORMAL", "");
        Draft asUpdate = new Draft("ServiceNow verlagert Kapital in Software -Update:", "MIXED", "NORMAL", "");
        assertTrue(w.publishUnit(u, first));
        assertFalse(w.publishUnit(u, asUpdate), "same line as an -Update: must be skipped");
        assertEquals(1, repo.getHeadlinesByClusterId("NOW").size());
    }

    // ---- reconcileHighlight: red must name a trigger that holds up ----

    private static de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot pricedSnapshot() {
        return new de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot(
                "NOW", 100.0, 98.0, 2.04, Double.NaN, Double.NaN, -1,
                Double.NaN, Double.NaN, "EUR", "LS", 0, List.of());
    }

    @Test
    void reconcileHighlight_importantWithoutATriggerDemotes() {
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, "NONE", pricedSnapshot()),
                "IMPORTANT with trigger NONE is the contradiction case → NORMAL");
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, "", pricedSnapshot()),
                "a salvage-path/legacy draft (blank trigger) is doubt → NORMAL");
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, "VIBES", pricedSnapshot()),
                "an unknown trigger value never justifies red");
    }

    @Test
    void reconcileHighlight_catalystTriggersStandWithoutAPrice() {
        // The quiet pennystock pooled call / hard news catalyst must stay red-capable
        // even when L&S has no listing (SpaceX-style price-less subjects).
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, "HARD_CATALYST", null));
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, "POOLED_CALL", null));
    }

    @Test
    void reconcileHighlight_priceShapedTriggersNeedAVerifiedPrice() {
        // A "runner" whose move exists only in the room's screenshot is the rubric's
        // "an unverified price never earns red on its own".
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, "RUNNER", null));
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, "EXTREME_DIRECTION", null));
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, "RUNNER", pricedSnapshot()));
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, "extreme_direction",
                        pricedSnapshot()),
                "trigger match is case-insensitive");
    }

    @Test
    void reconcileHighlight_neverPromotesNormal() {
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL,
                HeadlineWriter.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL, "HARD_CATALYST", pricedSnapshot()),
                "the gate only demotes — a NORMAL with a trigger stays NORMAL");
    }

    @Test
    void publishUnit_demotesImportantWhoseTriggerDoesNotHoldUp() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        // Model flags IMPORTANT but names no trigger — the classic "feels important".
        Draft d = new Draft("ServiceNow läuft heiß", "FOMO", "IMPORTANT", "NONE");
        assertTrue(w.publishUnit(instrumentUnit(), d));
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL,
                repo.getHeadlinesByClusterId("NOW").get(0).highlight(),
                "IMPORTANT without a real trigger publishes as NORMAL");
    }

    @Test
    void trimsNoNewsFillerButKeepsTheHead() {
        // The forbidden "absence of news as news" clause — deterministic backstop.
        assertEquals("Die Affen halten an der Long-Position für Silber fest.",
                HeadlineWriter.trimInterpretiveTail(
                        "Die Affen halten an der Long-Position für Silber fest, "
                        + "während die Nachrichtenlage keine neuen Katalysatoren liefert."));
        assertEquals("Die Affen spekulieren, Polen könnte von Russland durch einen False Flag Angriff angegriffen werden.",
                HeadlineWriter.trimInterpretiveTail(
                        "Die Affen spekulieren, Polen könnte von Russland durch einen False Flag Angriff "
                        + "angegriffen werden, während die Nachrichtenlage keine neuen Katalysatoren für Polenergia S.A. liefert."));
        // A "während"-clause with real content is NOT filler — never cut.
        String real = "Die Affen kaufen Nvidia, während der Gesamtmarkt die Chip-Aktien fallen lässt";
        assertEquals(real, HeadlineWriter.trimInterpretiveTail(real));
    }

    // ---- displayFormIn: the gilded name is the form the LINE wrote, not Yahoo's legal one ----

    @Test
    void displayFormInIsCaseInsensitiveAndReturnsTheLinesSpelling() {
        // The line writes "Nvidia", Yahoo's legal name shouts "NVIDIA Corporation" —
        // the gild must find it and return the LINE's spelling so the UI regex matches.
        assertEquals("Nvidia", HeadlineWriter.displayFormIn(
                "Nvidia dominiert das KI-Rennen weiter", "NVIDIA Corporation"));
        // Word-boundary guard: "Aris" must not gild inside "Paris".
        org.junit.jupiter.api.Assertions.assertNull(HeadlineWriter.displayFormIn(
                "Paris feiert den Deal", "Aris Water Solutions"));
    }

    @Test
    void displayFormInStripsALeadingArticle() {
        // "The Wendy's Company" could never gild via pure prefixes — every prefix
        // starts with "The". The article is stripped before the prefix descent.
        assertEquals("Wendy's", HeadlineWriter.displayFormIn(
                "Institutionelle Käufe bei Wendy's gelten als Kaufgelegenheit", "The Wendy's Company"));
    }

    @Test
    void displayFormInFindsTheShortFormOfALegalName() {
        assertEquals("Salesforce", HeadlineWriter.displayFormIn(
                "Salesforce zieht nach starken Zahlen an", "Salesforce, Inc."));
        assertEquals("D-Wave Quantum", HeadlineWriter.displayFormIn(
                "D-Wave Quantum sichert sich die NSF-Förderung", "D-Wave Quantum Inc."));
        assertEquals("D-Wave", HeadlineWriter.displayFormIn(
                "D-Wave sichert sich die NSF-Förderung", "D-Wave Quantum Inc."));
    }

    @Test
    void displayFormInNeverGildsALoneGenericWordAndNullsWhenAbsent() {
        org.junit.jupiter.api.Assertions.assertNull(HeadlineWriter.displayFormIn(
                "The Raum feiert den Deal", "The Metals Company"), // only "The" would match
                "a lone generic word never gilds");
        org.junit.jupiter.api.Assertions.assertNull(HeadlineWriter.displayFormIn(
                "Der Raum diskutiert Quantencomputer", "D-Wave Quantum Inc."),
                "no form in the line → no gild");
    }

    @Test
    void displayFormInHandlesDomainFirstWordsAndDroppedBrandWords() {
        // "Amazon.com, Inc." — the line writes "Amazon"; the domain suffix never
        // appears in prose (live false drop 2026-07-02).
        assertEquals("Amazon", HeadlineWriter.displayFormIn(
                "Amazon könnte durch eigene KI-Chips einen Schub bekommen", "Amazon.com, Inc."));
        // "iShares Core MSCI EM IMI …" — the line drops the brand word entirely
        // (live false drop 2026-07-02); one retry without the first word.
        assertEquals("Core MSCI EM IMI", HeadlineWriter.displayFormIn(
                "Ein Rückgang beim Core MSCI EM IMI lässt den Raum reagieren",
                "iShares Core MSCI EM IMI UCITS ETF USD (Acc)"));
        // The retry must not create false KEEPS: a line about other companies still
        // yields null for an unrelated unit (live: SK hynix unit, Lam-Research line).
        org.junit.jupiter.api.Assertions.assertNull(HeadlineWriter.displayFormIn(
                "Europoors spekulieren auf eine Rakete für Lam Research und Applied Materials",
                "SK hynix Inc."));
        // …and a lone legal word after the drop never gilds ("Corporation").
        org.junit.jupiter.api.Assertions.assertNull(HeadlineWriter.displayFormIn(
                "Microsoft Corporation bündelt Copilot-Bots", "Bio-Techne Corporation"));
    }

    @Test
    void displayFormInBindsGermanCompoundHeads() {
        // "Goldpreis" NAMES gold — the room writes compounds, not phrases (live: a
        // priced GC=F unit lost its quote because the line said "Goldpreis").
        // The gild wraps the whole compound.
        assertEquals("Goldpreis", HeadlineWriter.displayFormIn(
                "Goldpreis klettert über 4.200 Dollar, Asien kauft weiter", "Gold"));
        // A hyphenated compound hits the exact word boundary first — the gild stays
        // on the name itself.
        assertEquals("Rheinmetall", HeadlineWriter.displayFormIn(
                "Die Rheinmetall-Aktie zieht nach dem Großauftrag an", "Rheinmetall AG"));
        // Short candidates never bind as compound heads ("Bay" must not match "Bayern").
        org.junit.jupiter.api.Assertions.assertNull(HeadlineWriter.displayFormIn(
                "Bayern feiert die Börsenwoche", "Bay"));
        // A capitalized continuation is a DIFFERENT name, not a compound of ours.
        org.junit.jupiter.api.Assertions.assertNull(HeadlineWriter.displayFormIn(
                "MicroStrategy kauft weiter Bitcoin nach", "Micron Technology, Inc."));
        // An exact word-boundary hit anywhere in the line beats an earlier compound
        // hit — "Goldman" must not shadow the actual "Gold".
        assertEquals("Gold", HeadlineWriter.displayFormIn(
                "Goldman warnt, doch Gold klettert weiter", "Gold"));
    }

    @Test
    void displayFormInAcceptsTheGermanGenitive() {
        // "Rheinmetalls Auftrag" — the name IS in the line, inflected. A lone
        // trailing "s" is a boundary, a longer suffix is not.
        assertEquals("Rheinmetall", HeadlineWriter.displayFormIn(
                "Rheinmetalls Großauftrag treibt den Kurs", "Rheinmetall AG"));
        // The room's wordplay compound ("Rheinmetallitis") NAMES the subject — since
        // the compound-head rule it binds (whole word, so the gild wraps the joke).
        assertEquals("Rheinmetallitis", HeadlineWriter.displayFormIn(
                "Rheinmetallitis grassiert im Käfig", "Rheinmetall AG"));
    }

    // ---- near-dup patterns lifted verbatim from the live archive (2026-07-02) ----

    @Test
    void nearDuplicate_catchesAPriorLineWithAnAppendedClause() {
        // The MU wire published the same GM-Chip line three times, the later two with a
        // tacked-on subclause. Jaccard alone dropped below the threshold (the union grew);
        // the containment side must catch it.
        String base = "General Motors sichert sich Chip-Lieferung bei Micron, "
                + "was die allgemeine Chip-Nachfrage stützt.";
        String extended = "General Motors sichert sich Chip-Lieferung bei Micron, "
                + "was die allgemeine Chip-Nachfrage stützt, während der Raum die Aktie hält.";
        assertTrue(HeadlineWriter.isNearDuplicate(base, extended));
    }

    @Test
    void nearDuplicate_catchesANumberOnlyTick() {
        // The ^GDAXI wire published the same "wartet auf Katalysator" line at +1,66 %,
        // +1,78 % and +2,01 % — the ticking day-move is on the quote strip, not a development.
        assertTrue(HeadlineWriter.isNearDuplicate(
                "Marktverlauf zeigt, dass der DAX trotz der jüngsten Kursgewinne von +1,66% "
                        + "weiterhin auf einen klaren, neuen Katalysator wartet",
                "Marktverlauf zeigt, dass der DAX trotz des heutigen Anstiegs von +2,01% "
                        + "weiterhin auf einen klaren, neuen Katalysator wartet"));
    }

    @Test
    void nearDuplicate_aGenuinelyNewNumberStoryIsNotFlagged() {
        // Numbers are stripped for the comparison, so a NEW story must differ in its
        // words, not merely its figures — and it does: a price-target line and a
        // short-squeeze line share the subject but nothing else.
        assertFalse(HeadlineWriter.isNearDuplicate(
                "Analysten heben das Kursziel für Rheinmetall auf 2.100 Euro an",
                "Shortseller kapitulieren bei Rheinmetall, der Käfig feiert den Squeeze"));
    }

    @Test
    void publishUnit_skipsACrossUnitNearDuplicate() {
        // The merz/friedrich-merz twin units once wrote the same Reformpaket line 5 min
        // apart — invisible to the per-unit guard, caught by the wire-level window.
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        SubjectUnit merz = new SubjectUnit("name:merz", "Merz");
        SubjectUnit friedrich = new SubjectUnit("name:friedrich merz", "Friedrich Merz");
        Draft line = new Draft("Merz präsentiert umfassendes Reformpaket mit Steuerkürzungen "
                + "und Rentenüberarbeitung", "NEUTRAL", "NORMAL", "");
        assertTrue(w.publishUnit(merz, line));
        assertFalse(w.publishUnit(friedrich, line),
                "the same sentence from a twin unit within the window must be skipped");
        assertEquals(1, repo.getRecentHeadlines().size());
    }

    @Test
    void publishUnit_dropsTickerWhenTheLineNeverNamesTheUnit() {
        // A unit-line disagreement (the SPCX Daiwa-Kursziel case): the line publishes,
        // but it carries no claim to an instrument it never names — no ticker, no snapshot.
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        SubjectUnit u = instrumentUnit(); // NOW / ServiceNow
        Draft d = new Draft("Analysten von Daiwa Securities initiieren die Position mit "
                + "einem Kursziel von 175 Euro", "BULLISH", "NORMAL", "");
        assertTrue(w.publishUnit(u, d), "the line itself still publishes — the mirror stays 1:1");
        HeadlineRecord h = repo.getHeadlinesByClusterId("NOW").get(0);
        assertNull(h.tickerSymbol(), "a line that never names the unit carries no ticker");
        assertNull(h.snapshot(), "…and no price snapshot");
        assertTrue(h.subjects().isEmpty(), "…and no glow subject");
    }

    @Test
    void newsTagAndRefsAreLineScopedNotUnitScoped() {
        // The tag promises "the articles this line leans on": a room-sentiment line
        // on a news-rich subject carries NO tag and NO refs (live: a Microsoft
        // chart-waiting line once shipped the subject's whole article pool); a line
        // that wove an item in carries exactly that item.
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        SubjectUnit u = instrumentUnit();
        de.bsommerfeld.wsbg.terminal.source.RawNewsItem article =
                new de.bsommerfeld.wsbg.terminal.source.RawNewsItem("uuid-1",
                        "ServiceNow hebt Prognose nach Großauftrag an", "Reuters",
                        "https://example.com/now", null, List.of("NOW"));

        Draft sentimentLine = new Draft("ServiceNow bleibt die Lieblingswette des Raums",
                "BULLISH", "NORMAL", "");
        assertTrue(w.publishUnit(u, sentimentLine, List.of()));
        HeadlineRecord plain = repo.getHeadlinesByClusterId("NOW").get(0);
        assertFalse(plain.newsEnriched(), "no woven-in item → no News tag");
        assertTrue(plain.newsRefs().isEmpty(), "…and no source list");

        Draft newsLine = new Draft("ServiceNow hebt nach Großauftrag die Prognose an",
                "BULLISH", "NORMAL", "");
        assertTrue(w.publishUnit(u, newsLine, List.of(article)));
        HeadlineRecord enriched = repo.getHeadlinesByClusterId("NOW").get(1);
        assertTrue(enriched.newsEnriched());
        assertEquals(1, enriched.newsRefs().size(), "refs are exactly the woven-in items");
        assertEquals("https://example.com/now", enriched.newsRefs().get(0).url());
    }

    // ---- trimInterpretiveTail: the mechanical gate for the ~20% interpretation-clause class ----

    @Test
    void trimsAnAbstractTrailingWasClause() {
        // The stable live pattern: concrete head, interpretive tail.
        assertEquals("Direxion lanciert einen 2X-ETF auf SK hynix.",
                HeadlineWriter.trimInterpretiveTail(
                        "Direxion lanciert einen 2X-ETF auf SK hynix, "
                                + "was die Diskussion um den Halbleitersektor weiter anheizt"));
        assertEquals("Trump-Aktivitäten treiben den Dow Jones nach unten.",
                HeadlineWriter.trimInterpretiveTail(
                        "Trump-Aktivitäten treiben den Dow Jones nach unten, "
                                + "wodurch die anhaltende Einflussnahme sichtbar wird"));
    }

    @Test
    void keepsAFigureBearingWasClause() {
        // A clause with a number/currency is detail, not interpretation — never cut.
        String line = "EU-Gericht bestätigt Anti-Trust-Strafen gegen Alphabet, "
                + "was die Anleger mit -1,7% quittieren";
        assertEquals(line, HeadlineWriter.trimInterpretiveTail(line));
    }

    @Test
    void keepsLinesWithoutATailAndTooShortHeads() {
        String plain = "Rheinmetall erhält Großauftrag über Artilleriemunition";
        assertEquals(plain, HeadlineWriter.trimInterpretiveTail(plain));
        // Head below the minimum stays untouched — cutting would leave a stump.
        String shortHead = "Kurs fällt, was den Raum beunruhigt";
        assertEquals(shortHead, HeadlineWriter.trimInterpretiveTail(shortHead));
    }
}
