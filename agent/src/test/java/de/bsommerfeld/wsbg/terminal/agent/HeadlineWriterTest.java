package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.Draft;
import de.bsommerfeld.wsbg.terminal.agent.HeadlineWriter.DraftSubject;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA behaviour of {@link HeadlineWriter} — no LLM, no network. */
class HeadlineWriterTest {

    private InvestigationCluster cluster() {
        RedditThread t = new RedditThread("t3_x", "wallstreetbetsGER", "ServiceNow", "u/a",
                "now läuft", 1780000000L, "/r/x/comments/x/", 0, 0.0, 0, 1780000100L,
                List.of(), null);
        return new InvestigationCluster(t, Embedding.from(new float[] {0.1f, 0.2f}));
    }

    private List<ResolvedSubject> resolvedNow() {
        return List.of(new ResolvedSubject("ServiceNow", "ServiceNow, Inc.", "NOW", null, List.of(), List.of(), false));
    }

    @Test
    void strips_html_validates_subject_and_attaches_resolver_ticker() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());

        Draft d = new Draft(
                "ServiceNow <strong>(NOW)</strong> +6,5% — der Käfig FOMO-t rein",
                "FOMO", "IMPORTANT", "NOW",
                List.of(new DraftSubject("ServiceNow", "NOW")),
                6.5, List.of("Software"), "stock",
                List.of("t3_x"), List.of("t1_c1", "garbage"));

        assertTrue(w.publish(cluster(), d, resolvedNow()));
        HeadlineRecord h = repo.getAllHeadlines().get(0);
        assertFalse(h.headline().contains("<"), "HTML must be stripped");
        assertEquals("NOW", h.tickerSymbol());
        assertEquals(1, h.subjects().size());
        assertEquals(6.5, h.priceMovePercent(), 1e-9);
        assertEquals(List.of("t3_x"), h.sourceThreadIds());
        assertEquals(List.of("t1_c1"), h.sourceCommentIds(), "non-t1_ id dropped");
    }

    @Test
    void drops_ticker_the_resolver_did_not_validate() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        // Model claims ticker FAKE, but resolver only validated NOW.
        Draft d = new Draft("Irgendein Hype um FAKE", "BULLISH", "NORMAL", "FAKE",
                List.of(new DraftSubject("FAKE", "FAKE")), null, List.of(), null,
                List.of(), List.of());
        assertTrue(w.publish(cluster(), d, resolvedNow()));
        HeadlineRecord h = repo.getAllHeadlines().get(0);
        assertNull(h.tickerSymbol(), "unvalidated ticker dropped");
        assertTrue(h.subjects().isEmpty(), "unvalidated subject dropped");
    }

    @Test
    void publishes_tickerless_theme_headline() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        // A macro/theme line — no instrument at all. Must still publish.
        Draft d = new Draft("Deutsche Inflation sinkt auf 2,7% — Risk-on im Käfig",
                "BULLISH", "NORMAL", null, List.of(), null, List.of(), null,
                List.of(), List.of());
        assertTrue(w.publish(cluster(), d, List.of()));
        assertEquals(1, repo.getAllHeadlines().size());
        assertNull(repo.getAllHeadlines().get(0).tickerSymbol());
    }

    @Test
    void nulls_pnl_misread_pricemove() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        Draft d = new Draft("Turbo-Schein +1166% auf 29.210 € gerannt", "BULLISH", "IMPORTANT",
                null, List.of(), 1166.0, List.of(), null, List.of(), List.of());
        assertTrue(w.publish(cluster(), d, List.of()));
        assertNull(repo.getAllHeadlines().get(0).priceMovePercent(),
                "huge % + money amount ⇒ P&L misread, nulled");
    }

    @Test
    void sentiment_follows_the_move_sign_not_the_model() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        // Model says BULLISH but the move is −3,2 % — the reader must not see BULLISH.
        Draft down = new Draft("Irgendwas −3,2%", "BULLISH", "NORMAL", null, List.of(),
                -3.2, List.of(), null, List.of(), List.of());
        assertTrue(w.publish(cluster(), down, List.of()));
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.BEARISH,
                repo.getAllHeadlines().get(0).sentiment(), "−% can't read BULLISH");

        // A non-directional read with a move is left as the model set it.
        AgentRepository repo2 = new AgentRepository();
        HeadlineWriter w2 = new HeadlineWriter(repo2, new ApplicationEventBus());
        Draft mixed = new Draft("Seitwärts −0,5%", "MIXED", "NORMAL", null, List.of(),
                -0.5, List.of(), null, List.of(), List.of());
        assertTrue(w2.publish(cluster(), mixed, List.of()));
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment.MIXED,
                repo2.getAllHeadlines().get(0).sentiment(), "non-directional read untouched");
    }

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
        Draft d = new Draft("ServiceNow läuft heiß", "FOMO", "NORMAL", "BOGUS",
                List.of(new DraftSubject("BOGUS", "BOGUS")), null, List.of("Software"),
                "stock", List.of("t3_x"), List.of("t1_c1", "junk"));

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
        Draft d = new Draft("Deutsche Inflation sinkt auf 2,7%", "BULLISH", "NORMAL",
                "FAKE", List.of(new DraftSubject("FAKE", "FAKE")), null, List.of(), null,
                List.of(), List.of());
        assertTrue(w.publishUnit(theme, d));
        assertNull(repo.getHeadlinesByClusterId("name:inflation").get(0).tickerSymbol());
    }

    @Test
    void publishUnit_guards_identical_double_publish_for_the_same_unit() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        SubjectUnit u = instrumentUnit();
        Draft d = new Draft("ServiceNow läuft heiß", "FOMO", "NORMAL", null,
                List.of(), null, List.of(), null, List.of(), List.of());
        assertTrue(w.publishUnit(u, d));
        assertFalse(w.publishUnit(u, d), "identical text within guard window skipped");
        assertEquals(1, repo.getHeadlinesByClusterId("NOW").size());
    }

    @Test
    void guards_against_identical_double_publish() {
        AgentRepository repo = new AgentRepository();
        HeadlineWriter w = new HeadlineWriter(repo, new ApplicationEventBus());
        InvestigationCluster c = cluster();
        Draft d = new Draft("ServiceNow NOW läuft heiß", "FOMO", "NORMAL", "NOW",
                List.of(new DraftSubject("NOW", "NOW")), null, List.of(), null,
                List.of(), List.of());
        assertTrue(w.publish(c, d, resolvedNow()));
        assertFalse(w.publish(c, d, resolvedNow()), "identical text within guard window skipped");
        assertEquals(1, repo.getAllHeadlines().size());
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
        Draft first = new Draft("ServiceNow verlagert Kapital in Software", "MIXED", "NORMAL", null,
                List.of(), null, List.of(), null, List.of(), List.of());
        Draft asUpdate = new Draft("ServiceNow verlagert Kapital in Software -Update:", "MIXED",
                "NORMAL", null, List.of(), null, List.of(), null, List.of(), List.of());
        assertTrue(w.publishUnit(u, first));
        assertFalse(w.publishUnit(u, asUpdate), "same line as an -Update: must be skipped");
        assertEquals(1, repo.getHeadlinesByClusterId("NOW").size());
    }
}
