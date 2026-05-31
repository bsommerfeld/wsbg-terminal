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
        return List.of(new ResolvedSubject("ServiceNow", "ServiceNow, Inc.", "NOW", null, List.of(), List.of()));
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
}
