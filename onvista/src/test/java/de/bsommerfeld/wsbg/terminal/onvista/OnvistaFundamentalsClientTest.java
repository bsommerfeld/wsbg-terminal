package de.bsommerfeld.wsbg.terminal.onvista;

import org.junit.jupiter.api.Test;

import java.util.List;

import static de.bsommerfeld.wsbg.terminal.onvista.OnvistaFixtures.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free parse tests against trimmed live captures (2026-08-02, SAP
 * {@code STOCK/82849}). The behaviours that matter: estimate years must stay
 * marked as estimates, the shareholder table must not confuse free float with
 * a named holder, and the EQS report PDFs must survive intact — they are the
 * primary sources a research pass reads.
 */
class OnvistaFundamentalsClientTest {

    // ------------------------------------------------------------ consensus

    @Test
    void analystConsensusCountsRatingsRevisionsAndTargets() {
        var c = OnvistaFundamentalsClient.parseAnalystConsensus(
                json("analyzer-recommendations-sap.json")).orElseThrow();
        assertEquals(9, c.numTotal());
        assertEquals(7, c.numBuy());
        assertEquals(1, c.numHold());
        assertEquals(1, c.numSell());
        assertEquals(0, c.numStrongBuy());
        assertEquals(9, c.numUnchanged());
        assertEquals(0, c.netRevisions());
        assertEquals(194.571428571, c.avgTargetPrice(), 1e-9);
        assertEquals(164.0, c.minTargetPrice(), 1e-9);
        assertEquals(230.0, c.maxTargetPrice(), 1e-9);
        assertEquals("EUR", c.targetCurrency());
        // Upside over a live price is the caller's question, answered here.
        assertEquals(25.0, c.upsidePct(155.657142857), 1e-6);
        assertTrue(Double.isNaN(c.upsidePct(0)));
        assertTrue(OnvistaFundamentalsClient.parseAnalystConsensus(
                OnvistaApi.JSON.createObjectNode()).isEmpty());
    }

    // -------------------------------------------------------------- figures

    @Test
    void fundamentalYearsKeepEstimatesMarkedAsEstimates() {
        var f = OnvistaFundamentalsClient.parseFigures(json("figures-sap.json")).orElseThrow();
        var labels = f.fundamentals().stream().map(y -> y.label()).toList();
        assertEquals(List.of("2016", "2025", "2026e", "2028e"), labels);

        assertEquals(2, f.actuals().size());
        assertEquals(2, f.estimates().size());
        assertEquals("2026e", f.estimates().get(0).label());
        assertTrue(f.estimates().get(0).estimate());
        // The last REPORTED year — never an estimate presented as fact.
        assertEquals("2025", f.lastActual().orElseThrow().label());
        assertFalse(f.lastActual().orElseThrow().estimate());
        assertEquals(2016, f.fundamentals().get(0).year());
    }

    @Test
    void fiscalYearFiguresAreReachableByOnvistasOwnFieldNames() {
        var f = OnvistaFundamentalsClient.parseFigures(json("figures-sap.json")).orElseThrow();
        var y2016 = f.fundamentals().get(0);
        assertEquals(27.2401315789474, y2016.get("cnPer"), 1e-9);
        assertEquals(84183, y2016.get("employees"), 1e-9);
        assertEquals("EUR", y2016.isoCurrency());
        assertTrue(Double.isNaN(y2016.get("noSuchFigure")));
        // The balance sheet, growth and financial legs come along too.
        assertEquals(2, f.balanceSheet().size());
        assertEquals(22062000000.0, f.balanceSheet().get(0).get("turnover"), 1e-3);
        assertFalse(f.growth().isEmpty());
        assertFalse(f.financials().isEmpty());
        assertEquals("EUR", f.financials().get(0).isoCurrency());
    }

    @Test
    void technicalPanelAndBenchmarkFitsSurvive() {
        var f = OnvistaFundamentalsClient.parseFigures(json("figures-sap.json")).orElseThrow();
        var t = f.technical();
        assertNotNull(t);
        assertEquals(157.232, t.movingAverage(5), 1e-9);
        assertEquals(174.20595, t.movingAverage(200), 1e-9);
        assertEquals(185.74256, t.movingAverage(250), 1e-9);
        assertEquals(50.1023861343, t.volatility(30), 1e-9);
        assertTrue(t.rsi(20) > 0 && t.rsi(20) < 100);
        assertNotNull(t.calculatedAt());

        assertEquals(2, f.benchmarks().size());
        var fit = f.defaultBenchmark().orElseThrow();
        assertEquals("INDEX", fit.index().entityType());
        assertTrue(fit.beta250() > 0);
        assertTrue(fit.correlation250() > 0 && fit.correlation250() <= 1);
        assertTrue(OnvistaFundamentalsClient.parseFigures(
                OnvistaApi.JSON.createObjectNode()).isEmpty());
    }

    // ------------------------------------------------------ company snapshot

    @Test
    void shareholderStructureSeparatesFreeFloatFromNamedHolders() {
        var s = OnvistaFundamentalsClient.parseCompanySnapshot(
                json("company-snapshot-sap.json")).orElseThrow();
        assertEquals("SAP SE", s.officialName());
        assertEquals("Deutschland", s.country());
        assertEquals("Software", s.sector());
        assertEquals(2025, s.lastBalanceYear());
        assertTrue(s.marketCap() > 1e11);

        assertEquals(3, s.shareholders().size());
        assertEquals(68.78974422613899, s.freeFloat().orElseThrow().percentage(), 1e-9);
        assertEquals(68.78974422613899, s.freeFloatPct(), 1e-9);
        var named = s.namedHolders();
        assertEquals(2, named.size());
        assertEquals("Hopp Dietmar Family", named.get(0).name());
        assertEquals(5.0914000433, named.get(0).percentage(), 1e-9);
        assertNotNull(s.profileText());
    }

    @Test
    void eqsReportPdfsSurviveDatedAndSortable() {
        var s = OnvistaFundamentalsClient.parseCompanySnapshot(
                json("company-snapshot-sap.json")).orElseThrow();
        assertEquals(4, s.documents().size());
        var newest = s.documentsNewestFirst().get(0);
        assertTrue(newest.url().startsWith("https://ir-api.eqs.com/"),
                "the PDF must stay a direct EQS link: " + newest.url());
        assertTrue(newest.url().endsWith(".pdf"));
        assertNotNull(newest.date());
        // German and English editions of the same report ride as separate rows.
        assertTrue(s.documents().stream().anyMatch(d -> d.title().contains("Halbjahres")));
        assertTrue(s.documents().stream().anyMatch(d -> d.title().contains("Half-yearly")));
        // Newest first, oldest last.
        assertTrue(!s.documentsNewestFirst().get(0).date()
                .isBefore(s.documentsNewestFirst().get(3).date()));
    }

    @Test
    void boardMembersJoinTheirEqsBiographies() {
        var s = OnvistaFundamentalsClient.parseCompanySnapshot(
                json("company-snapshot-sap.json")).orElseThrow();
        // onvista serves the board TWICE and the two lists do not fully
        // overlap: the union must survive, the join must not drop either side.
        assertEquals(3, s.managingBoard().size());
        var ceo = s.managingBoard().get(0);
        assertEquals("Christian Klein", ceo.fullName());
        assertEquals("Vorstandsvorsitzender", ceo.function());
        assertNotNull(ceo.bio(), "the EQS biography must be joined onto the plain row");
        assertTrue(ceo.bio().contains("SAP"));
        assertNotNull(ceo.photoUrl());
        // Present in the plain list only — no bio, but still a board member.
        assertEquals("Dominik Asam", s.managingBoard().get(1).fullName());
        assertNull(s.managingBoard().get(1).bio());
        // Present in the EQS list only — appended rather than lost.
        var eqsOnly = s.managingBoard().get(2);
        assertEquals("Muhammad Alam", eqsOnly.fullName());
        assertNotNull(eqsOnly.bio());
        // The supervisory board comes from the plain list alone (no EQS bios here).
        assertEquals(1, s.supervisoryBoard().size());
        assertEquals("Pekka Juhani Ala-Pietilä", s.supervisoryBoard().get(0).fullName());
        assertNull(s.supervisoryBoard().get(0).bio());
    }

    @Test
    void participationsCarryTheirOwnAddressableInstrument() {
        var s = OnvistaFundamentalsClient.parseCompanySnapshot(
                json("company-snapshot-sap.json")).orElseThrow();
        assertFalse(s.participations().isEmpty());
        var p = s.participations().get(0);
        assertEquals("SAP SE", p.companyName());
        assertTrue(p.sharePercentage() > 0);
        assertNotNull(p.instrument());
        assertEquals("STOCK/82849", p.instrument().pathPair());
        assertTrue(OnvistaFundamentalsClient.parseCompanySnapshot(
                OnvistaApi.JSON.createObjectNode()).isEmpty());
    }

    // ------------------------------------------------------ theScreener text

    @Test
    void screenerRatingKeepsThePlainGermanVerdicts() {
        var r = OnvistaFundamentalsClient.parseScreenerRating(
                json("screener-rating-sap.json")).orElseThrow();
        assertEquals("Fundamentalanalyse", r.fundamentalTitle());
        assertEquals(2, r.fundamental().size());
        assertEquals(1, r.risk().size());
        var valuation = r.item("VALUATION_RATING").orElseThrow();
        assertEquals("Bewertung", valuation.typeDescription());
        assertTrue(valuation.shortText().contains("unterbewertet"));
        assertTrue(valuation.longText().length() > 40, "the reasoning must survive intact");
        assertEquals(1, valuation.stars());
        assertTrue(r.item("NO_SUCH_ITEM").isEmpty());
    }

    // ------------------------------------------------------------ the board

    @Test
    void forumThreadsCarryTodaysTraffic() {
        var board = OnvistaFundamentalsClient.parseForum(json("forum-sap.json")).orElseThrow();
        assertTrue(board.forumUrl().startsWith("https://forum.onvista.de/"));
        assertEquals(3, board.threads().size());
        var top = board.threads().get(0);
        assertEquals(8217, top.answersTotal());
        assertEquals(3785, top.hitsToday());
        assertNotNull(top.lastPost());
        // Today's attention is the sum across the listed threads.
        assertEquals(3785 + 37 + 17, board.hitsToday());
        assertTrue(OnvistaFundamentalsClient.parseForum(
                OnvistaApi.JSON.createObjectNode()).isEmpty());
    }

    // --------------------------------------------------------- fund holders

    @Test
    void fundHoldersShowWhoIsForcedToOwnTheShare() {
        var holders = OnvistaFundamentalsClient.parseFundHolders(json("fund-holders-sap.json"));
        assertEquals(3, holders.size());
        var first = holders.get(0);
        assertEquals("FUND", first.fund().entityType());
        assertNotNull(first.fund().isin());
        assertEquals(9.62, first.investmentPct(), 1e-9);
        assertEquals(0.7, first.ongoingCharges(), 1e-9);
        assertTrue(first.volumeFundEuro() > 0);
        assertNotNull(first.issuer());
    }

    // ------------------------------------------------------- article text

    @Test
    void articleFullTextKeepsProseAndSkipsChartItems() {
        var a = OnvistaFundamentalsClient.parseArticle(json("article-dpa.json")).orElseThrow();
        assertEquals("26536937", a.articleId());
        assertTrue(a.headline().startsWith("AKTIE IM FOKUS"));
        assertEquals("dpa-AFX", a.publisher());
        assertEquals("de", a.language());
        assertFalse(a.premium());
        assertNotNull(a.published());
        assertTrue(a.url().startsWith("https://www.onvista.de/news/"));
        // Four content items, one of which is a chart — only the three prose
        // items may become body text.
        assertEquals(3, a.paragraphs().size());
        assertTrue(a.paragraphs().get(0).contains("dpa-AFX"));
        assertTrue(a.body().contains("\n\n"));
        // Every instrument onvista linked rides along, addressable.
        assertEquals(2, a.instruments().size());
        assertEquals("DE0007164600", a.instruments().get(0).isin());
        assertEquals("STOCK/82849", a.instruments().get(0).pathPair());
        assertTrue(OnvistaFundamentalsClient.parseArticle(
                OnvistaApi.JSON.createObjectNode()).isEmpty());
    }
}
