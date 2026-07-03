package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The related-budget distribution: a shared pool of related-instrument lookups
 * spread evenly across all subjects (round-robin), capped per subject.
 */
class EditorialAgentTest {

    @Test
    void sixSubjectsEachGetFour() { // 6×4 = 24, the old behaviour
        assertArrayEquals(new int[]{4, 4, 4, 4, 4, 4},
                EditorialAgent.distributeRelated(6, 24, 4));
    }

    @Test
    void twentyFourSubjectsEachGetOne() {
        int[] a = EditorialAgent.distributeRelated(24, 24, 4);
        assertEquals(24, a.length);
        assertEquals(24, Arrays.stream(a).sum());
        for (int v : a) assertEquals(1, v);
    }

    @Test
    void twentyFifthSubjectGetsNone() { // user's exact example
        int[] a = EditorialAgent.distributeRelated(25, 24, 4);
        assertEquals(24, Arrays.stream(a).sum());
        for (int i = 0; i < 24; i++) assertEquals(1, a[i]);
        assertEquals(0, a[24]);
    }

    @Test
    void fewSubjectsAreCappedPerSubject() { // 3 subjects can't soak up all 24
        assertArrayEquals(new int[]{4, 4, 4}, EditorialAgent.distributeRelated(3, 24, 4));
    }

    @Test
    void twelveSubjectsEachGetTwo() {
        int[] a = EditorialAgent.distributeRelated(12, 24, 4);
        assertEquals(24, Arrays.stream(a).sum());
        for (int v : a) assertEquals(2, v);
    }

    @Test
    void zeroSubjectsIsEmpty() {
        assertEquals(0, EditorialAgent.distributeRelated(0, 24, 4).length);
    }

    // ---- salvageSubjectNames: a broken/truncated subjects array isn't total loss ----

    @Test
    void salvagesNamesFromTruncatedArray() {
        // Reply cut off mid-array: no closing ] and a dangling, unclosed final name.
        String broken = "{\"subjects\": [\"Alphabet\", \"Apple\", \"Münchener Rück\", \"SAN";
        assertEquals(List.of("Alphabet", "Apple", "Münchener Rück"),
                EditorialAgent.salvageSubjectNames(broken));
    }

    @Test
    void salvagesNamesEvenWithTrailingGarbage() {
        String broken = "ok here you go {\"subjects\": [\"Nvidia\", \"SAP\"] } blah blah";
        assertEquals(List.of("Nvidia", "SAP"), EditorialAgent.salvageSubjectNames(broken));
    }

    @Test
    void salvageReturnsEmptyWhenNoSubjectsKey() {
        assertTrue(EditorialAgent.salvageSubjectNames("totally unrelated prose").isEmpty());
        assertTrue(EditorialAgent.salvageSubjectNames(null).isEmpty());
    }

    // ---- cleanSubjectName: strip a transcribed price tail, keep numeric names ----

    @Test
    void cleanSubjectNameStripsScreenshotPriceTail() {
        assertEquals("Micron Technology", EditorialAgent.cleanSubjectName("Micron Technology 772,30 € ▼ 9,23 %"));
        assertEquals("Oracle", EditorialAgent.cleanSubjectName("Oracle 185,00 € ▼ 8,48 %"));
        assertEquals("Take-Two Interactive", EditorialAgent.cleanSubjectName("Take-Two Interactive 49,57 €"));
    }

    @Test
    void cleanSubjectNameKeepsLegitimateNumericNames() {
        assertEquals("S&P 500", EditorialAgent.cleanSubjectName("S&P 500"));
        assertEquals("3M", EditorialAgent.cleanSubjectName("3M"));
        assertEquals("Nvidia", EditorialAgent.cleanSubjectName("Nvidia"));
        assertEquals("Berkshire Hathaway", EditorialAgent.cleanSubjectName("Berkshire Hathaway"));
    }

    @Test
    void cleanSubjectNameCollapsesSpacedOutTicker() {
        // OCR'd watchlist row split a ticker into single letters — collapse it.
        assertEquals("OTLK", EditorialAgent.cleanSubjectName("O T L K"));
        assertEquals("NVDA", EditorialAgent.cleanSubjectName("N V D A"));
        // Ordinary multi-word names (multi-letter tokens) stay untouched.
        assertEquals("Take-Two Interactive", EditorialAgent.cleanSubjectName("Take-Two Interactive"));
        assertEquals("S&P 500", EditorialAgent.cleanSubjectName("S&P 500"));
    }

    // ---- salvageDraftByRegex: recover a headline from stray-quote-broken JSON ----

    @Test
    void salvageDraftByRegexRecoversHeadlineFromStrayQuoteJson() {
        // The exact live failure: `"ticker": null"` breaks the object, but the
        // headline + scalars must still be recovered.
        String broken = "{\"headline\": \"NVIDIA-Update: Die Apes sehen einen Rückgang von -4,97% bei NVIDIA\", "
                + "\"mode\": \"UPDATE\", \"sentiment\": \"CAPITULATION\", \"highlight\": \"NORMAL\", "
                + "\"tickerSymbol\": null, \"subjects\": [{\"name\": \"NVIDIA\", \"ticker\": null\"}], "
                + "\"priceMovePercent\": -4.97}";
        var d = EditorialAgent.salvageDraftByRegex(broken);
        assertEquals("NVIDIA-Update: Die Apes sehen einen Rückgang von -4,97% bei NVIDIA", d.headline());
        assertEquals("CAPITULATION", d.sentiment());
        assertEquals("NORMAL", d.highlight());
        assertEquals(-4.97, d.priceMovePercent());
    }

    // ---- headlineHasPriceNumber: detect a user-posted price for the "unverified" flag ----

    @Test
    void detectsPriceShapedNumbersForUnverifiedFlag() {
        assertTrue(EditorialAgent.headlineHasPriceNumber("NVIDIA −4,97% im Depot"));
        assertTrue(EditorialAgent.headlineHasPriceNumber("Ceres Power fällt auf 13,11%"));
        assertTrue(EditorialAgent.headlineHasPriceNumber("TSLA 175.00 $ im Screenshot"));
    }

    @Test
    void ignoresNonPriceNumbersAndPlainText() {
        assertFalse(EditorialAgent.headlineHasPriceNumber("S&P 500 im Fokus der Apes"));
        assertFalse(EditorialAgent.headlineHasPriceNumber("SAP taucht als Wette auf"));
        assertFalse(EditorialAgent.headlineHasPriceNumber(null));
    }

    @Test
    void salvageDraftByRegexFieldsAndNumber() {
        assertEquals("Oracle (ORCL) +2%", EditorialAgent.regexStringField(
                "{\"headline\": \"Oracle (ORCL) +2%\", \"x\": 1}", "headline"));
        assertEquals(6.5, EditorialAgent.regexNumberField("{\"priceMovePercent\": 6.5}", "priceMovePercent"));
        assertNull(EditorialAgent.regexStringField("no json here", "headline"));
    }

    // ---- unitBrief: the per-unit compose context, incl. the story memory that
    // ---- survives the evidence prune ----

    private static MarketSnapshot snap(double price) {
        return new MarketSnapshot("NVDA", price, Double.NaN, 1.5, Double.NaN, Double.NaN,
                -1, Double.NaN, Double.NaN, "USD", "", 0, List.of());
    }

    private static SubjectUnit.EvidenceRef ev(String comment, String snippet) {
        return new SubjectUnit.EvidenceRef("t3_x", comment, snippet, "reddit",
                Instant.now().getEpochSecond());
    }

    @Test
    void unitBriefTagsOldNewsAsStaleButStillShowsIt() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        Instant now = Instant.now();
        u.updateResolved("NVIDIA", "NVDA", null, List.of(
                new RawNewsItem("fresh", "Nvidia beats", "Reuters", "http://x/f",
                        now.minus(2, ChronoUnit.HOURS), List.of()),
                new RawNewsItem("old", "Nvidia capex worries", "WSJ", "http://x/o",
                        now.minus(3, ChronoUnit.DAYS), List.of())));
        u.addEvidence(ev("t1_a", "NVDA yolo"));

        String brief = EditorialAgent.unitBrief(u, false);
        assertTrue(brief.contains("[news:old] 3d ago [STALE] — Nvidia capex worries · WSJ"),
                "old news stays visible but tagged:\n" + brief);
        assertTrue(brief.contains("[news:fresh] 2h ago — Nvidia beats · Reuters"),
                "fresh news untagged:\n" + brief);
    }

    @Test
    void unitBriefOmitsEvidenceAlreadyCoveredByAPriorHeadline() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        long now = Instant.now().getEpochSecond();
        long headlineAt = now - 3600;            // the unit's last headline, 1h ago
        // Evidence from BEFORE that headline → already reflected → must be OMITTED.
        u.addEvidence(new SubjectUnit.EvidenceRef("t3_x", "t1_old", "old yolo call",
                "reddit", now - 7200));
        // Evidence from AFTER that headline → genuinely new → must be shown.
        u.addEvidence(new SubjectUnit.EvidenceRef("t3_x", "t1_new", "fresh DD drop",
                "reddit", now - 600));
        u.seedHeadline("NVIDIA läuft", "BULLISH", headlineAt);

        String brief = EditorialAgent.unitBrief(u, false);
        assertFalse(brief.contains("old yolo call"),
                "evidence older than the last headline must be OMITTED (the headline is its context):\n" + brief);
        assertTrue(brief.contains("fresh DD drop"),
                "evidence newer than the last headline must be shown in full:\n" + brief);
        assertTrue(brief.contains("already reflected in the prior headlines"),
                "the brief must note that covered material was omitted:\n" + brief);
        assertTrue(brief.contains("NVIDIA läuft"),
                "the prior headline must be present as the context for the omitted evidence:\n" + brief);
    }

    @Test
    void unitBriefShowsAllEvidenceWhenNoPriorHeadline() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        u.addEvidence(ev("t1_a", "first NVDA mention"));
        String brief = EditorialAgent.unitBrief(u, false);
        assertTrue(brief.contains("first NVDA mention"),
                "with no prior headline nothing is covered — every mention is shown:\n" + brief);
        assertFalse(brief.contains("already reflected in the prior headlines"),
                "no omission note when there is no prior headline:\n" + brief);
    }

    @Test
    void unitBriefRendersStoryDigestAndSentimentArc() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        u.addEvidence(ev("t1_a", "NVDA"));
        for (int i = 1; i <= 5; i++) {
            u.addHeadline("Headline Nummer " + i, i > 1, i <= 2 ? "BULLISH" : "BEARISH");
        }
        String brief = EditorialAgent.unitBrief(u, false);
        assertTrue(brief.contains("(+2 earlier headline(s)"), "older lines collapse to a digest:\n" + brief);
        assertTrue(brief.contains("Headline Nummer 5") && brief.contains("Headline Nummer 3"),
                "last 3 shown in full");
        assertFalse(brief.contains("Headline Nummer 2"), "older lines not shown verbatim");
        assertTrue(brief.contains("Sentiment arc so far: BULLISH → BEARISH"),
                "arc collapses consecutive duplicates:\n" + brief);
    }

    @Test
    void unitBriefRendersPriceAnchorSinceFirstMention() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        u.updateResolved("NVIDIA", "NVDA", snap(100.0), null);
        u.updateResolved("NVIDIA", "NVDA", snap(112.0), null);
        u.addEvidence(ev("t1_a", "NVDA"));

        String brief = EditorialAgent.unitBrief(u, false);
        assertTrue(brief.contains("since first mention"), brief);
        assertTrue(brief.contains("+12.00% (100.00 → 112.00)"), brief);
    }

    @Test
    void unitBriefBudgetsEvidenceDroppingOldestWithAnExplicitOmissionLine() {
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        String big = "x".repeat(600);
        for (int i = 0; i < 15; i++) {
            u.addEvidence(ev("t1_" + i, big + i)); // ~9k chars total > budget
        }
        String brief = EditorialAgent.unitBrief(u, false);
        assertTrue(brief.contains("omitted to fit the context budget"), "budget omission is explicit:\n"
                + brief.substring(0, Math.min(400, brief.length())));
        assertTrue(brief.contains(big + "14"), "newest evidence kept");
        assertFalse(brief.contains(big + "0]") || brief.contains("[t1_0,"), "oldest dropped");
        assertTrue(brief.length() < EditorialAgent.EVIDENCE_CHAR_BUDGET + 2000,
                "brief stays near the budget, got " + brief.length());
    }

    @Test
    void sentimentArcNeedsTwoDistinctSteps() {
        assertEquals("", EditorialAgent.sentimentArc(List.of(
                new SubjectUnit.UnitHeadline("a", false, 0, "BULLISH", null),
                new SubjectUnit.UnitHeadline("b", false, 0, "bullish", null))),
                "one collapsed step carries no information");
        assertEquals("BULLISH → MIXED → BULLISH", EditorialAgent.sentimentArc(List.of(
                new SubjectUnit.UnitHeadline("a", false, 0, "BULLISH", null),
                new SubjectUnit.UnitHeadline("b", false, 0, "MIXED", null),
                new SubjectUnit.UnitHeadline("c", false, 0, "", null),
                new SubjectUnit.UnitHeadline("d", false, 0, "BULLISH", null))),
                "blank sentiments are skipped, real flips kept");
    }

    @Test
    void ageFormatsCompactly() {
        Instant now = Instant.now();
        assertEquals("5m", EditorialAgent.age(now.minus(5, ChronoUnit.MINUTES), now));
        assertEquals("3h", EditorialAgent.age(now.minus(3, ChronoUnit.HOURS), now));
        assertEquals("2d", EditorialAgent.age(now.minus(2, ChronoUnit.DAYS), now));
        assertEquals("0m", EditorialAgent.age(now.plus(1, ChronoUnit.MINUTES), now), "clock skew clamps");
    }

    // ---- parseDraft: the shared compose-reply parser (used by composeUnit + composeTheme) ----

    @Test
    void parseDraftReadsATickerlessThemeObject() {
        // A typical theme reply: thread narrative, no instrument, co-occurrence framing.
        String reply = "{\"headline\": \"Waffenstillstand-Thread: Raum jubelt, dazu Rheinmetall-Chart −8% gepostet\","
                + " \"sentiment\": \"MIXED\", \"highlight\": \"NORMAL\", \"tickerSymbol\": null,"
                + " \"subjects\": [], \"priceMovePercent\": null, \"sectors\": [], \"assetClass\": null,"
                + " \"sourceThreadIds\": [\"t3_abc\"], \"sourceCommentIds\": []}";
        HeadlineWriter.Draft d = EditorialAgent.parseDraft(reply);
        assertNotNull(d);
        assertTrue(d.headline().contains("Waffenstillstand-Thread"), "headline kept verbatim");
        assertNull(d.tickerSymbol(), "a theme line carries no ticker");
        assertTrue(d.subjects().isEmpty());
        assertNull(d.priceMovePercent());
        assertEquals(List.of("t3_abc"), d.sourceThreadIds(), "source ids drive coverage");
        assertEquals("MIXED", d.sentiment());
    }

    @Test
    void parseDraftReturnsNullForEmptyHeadline() {
        // The "nothing fresh / nothing market-relevant" contract.
        assertNull(EditorialAgent.parseDraft("{\"headline\": \"\"}"));
    }

    @Test
    void parseDraftSalvagesAHeadlineFromCodeFencedReply() {
        // 4B models sometimes wrap the object in a ```json fence — the balanced-brace
        // salvage still recovers the line.
        String fenced = "```json\n{\"headline\": \"NVIDIA-Daily: Raum FOMO-t den Bounce\","
                + " \"sentiment\": \"FOMO\"}\n```";
        HeadlineWriter.Draft d = EditorialAgent.parseDraft(fenced);
        assertNotNull(d, "the object inside the fence is recovered");
        assertTrue(d.headline().contains("NVIDIA-Daily"));
    }

    @Test
    void parseDraftReturnsNullForGarbage() {
        assertNull(EditorialAgent.parseDraft("ich kann das leider nicht beantworten"));
    }

    // ---- news coverage is earned by USE: token overlap = "konkret eingewoben" ----

    @org.junit.jupiter.api.Test
    void newsIsCoveredOnlyWhenTheLineActuallyWoveItIn() {
        var n = new de.bsommerfeld.wsbg.terminal.source.RawNewsItem("u1",
                "Meta Wolf AG: Wandlung zu CERAM TECH abgeschlossen",
                "wso", "https://x", null, java.util.List.of());
        org.junit.jupiter.api.Assertions.assertTrue(EditorialAgent.headlineReflectsNews(
                "Meta Wolf AG springt +25,8 % nach der abgeschlossenen Wandlung zu CERAM TECH", n),
                "the line names the event's players → woven in");
        org.junit.jupiter.api.Assertions.assertFalse(EditorialAgent.headlineReflectsNews(
                "Affen feiern den kleinen Keramik-Laden, alle wollen rein", n),
                "a sentiment-only line leaves the item fresh for the next compose");
    }

    @org.junit.jupiter.api.Test
    void inheritedRefsMapOrdinalsInsideTheShownWindowAndDedupeByUrl() {
        // 5 priors, shown window = last 3 (PRIOR_HEADLINES_SHOWN): #1=c, #2=d, #3=e.
        var priors = java.util.List.of(
                new SubjectUnit.UnitHeadline("a", false, 0, "", null),
                new SubjectUnit.UnitHeadline("b", false, 0, "", null),
                new SubjectUnit.UnitHeadline("c", false, 0, "", null),
                new SubjectUnit.UnitHeadline("d", false, 0, "", null),
                new SubjectUnit.UnitHeadline("e", false, 0, "", null));
        var r1 = new de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef("T1", "p", "https://1", null);
        var r2 = new de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef("T2", "p", "https://2", null);
        var r3 = new de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef("T3", "p", "https://3", null);
        var records = java.util.List.of(
                record("b", java.util.List.of(r3)),                    // outside the window
                record("c", java.util.List.of(r1)),
                record("d", java.util.List.of(r1, r2)));               // r1 duplicated across lines

        var out = EditorialAgent.inheritedRefs(priors,
                java.util.List.of(1, 2, 7), records);                  // 7 = model mis-count → skipped
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("https://1", "https://2"),
                out.stream().map(de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef::url).toList(),
                "cited lines' refs carry over, deduped by url; out-of-range ordinals never throw");
        org.junit.jupiter.api.Assertions.assertTrue(
                EditorialAgent.inheritedRefs(priors, java.util.List.of(), records).isEmpty(),
                "no citation → no inheritance");
    }

    private static de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord record(
            String headline, java.util.List<de.bsommerfeld.wsbg.terminal.db.HeadlineNewsRef> refs) {
        return new de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord(
                "U", headline, "", 0, java.util.List.of(), java.util.List.of(),
                de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL, null,
                java.util.List.of(), null, java.util.List.of(), null,
                de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.NEUTRAL, null, true, refs);
    }
}
